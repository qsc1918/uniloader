/*
 * Copyright 2016 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.loader.impl.compat.transform;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.fabricmc.api.EnvType;

/**
 * UniLoader fork patch (T1.5) 的验收：管线的顺序、短路与 null 语义。
 *
 * <p>这些正是「逐字节不变」所依赖的性质。真实字节的对拍由
 * {@code ul.transform.hashFile} 在冒烟里完成（见 docs/stage1），
 * 这里守的是那些对拍一旦失败就很难定位的规则。
 */
class TransformChainTest {
	private static final TransformContext CONTEXT =
			new TransformContext("com.example.Target", EnvType.SERVER, false, "official", true);

	private static byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

	@Test
	@DisplayName("阶段按 ordinal 顺序执行，与注册顺序无关")
	void phasesRunInDeclarationOrder() {
		List<String> log = new ArrayList<>();
		TransformChain chain = new TransformChain();

		// 故意倒着注册
		chain.register(TransformPhase.FABRIC_BUILTIN, logging(log, "fabric"));
		chain.register(TransformPhase.RAW_READ, logging(log, "raw"));
		chain.register(TransformPhase.ENTRYPOINT_PATCH, logging(log, "entry"));
		chain.freeze();

		chain.apply(null, CONTEXT);

		assertEquals(Arrays.asList("entry", "raw", "fabric"), log);
	}

	@Test
	@DisplayName("同一阶段内按 sortIndex 升序，再按注册顺序")
	void sortIndexOrdersWithinPhase() {
		List<String> log = new ArrayList<>();
		TransformChain chain = new TransformChain();

		chain.register(TransformPhase.HOOK_INJECTION, logging(log, "c"), 10);
		chain.register(TransformPhase.HOOK_INJECTION, logging(log, "a"), -5);
		chain.register(TransformPhase.HOOK_INJECTION, logging(log, "b1"), 0);
		chain.register(TransformPhase.HOOK_INJECTION, logging(log, "b2"), 0);
		chain.freeze();

		chain.apply(null, CONTEXT);

		assertEquals(Arrays.asList("a", "b1", "b2", "c"), log);
	}

	@Test
	@DisplayName("返回 null 表示「我没意见」，当前字节原样传给下一步")
	void nullMeansNoOpinion() {
		TransformChain chain = new TransformChain();
		chain.register(TransformPhase.RAW_READ, (ctx, bytes) -> bytes("raw"));
		chain.register(TransformPhase.FABRIC_BUILTIN, (ctx, bytes) -> null); // 没意见
		chain.register(TransformPhase.HOOK_INJECTION, (ctx, bytes) -> bytes); // 原样再交出来
		chain.freeze();

		assertArrayEquals(bytes("raw"), chain.apply(null, CONTEXT));
	}

	@Test
	@DisplayName("上游的三段短路在这里逐条成立")
	void reproducesTheUpstreamShortCircuits() {
		// 「已经有字节就不读磁盘」这条短路在 RawReadTransformer 里面，不在链上——
		// 读磁盘是它的事，链只负责「返回 null = 我没意见」。所以这里用真的实现来测。
		RawReadTransformer neverReads = new RawReadTransformer((name, allow) -> {
			throw new AssertionError("已经有字节了就不该去读磁盘");
		});

		// 情形 1：入口点补丁给了字节 → 不去读磁盘 → 内建转换照做
		TransformChain a = new TransformChain();
		a.register(TransformPhase.ENTRYPOINT_PATCH, (ctx, bytes) -> bytes("patched"));
		a.register(TransformPhase.RAW_READ, neverReads);
		a.register(TransformPhase.FABRIC_BUILTIN, (ctx, bytes) -> bytes("builtin:" + new String(bytes, StandardCharsets.UTF_8)));
		a.freeze();
		assertArrayEquals(bytes("builtin:patched"), a.apply(null, CONTEXT));

		// 情形 2：入口点补丁没意见 → 读磁盘 → 内建转换照做
		TransformChain b = new TransformChain();
		b.register(TransformPhase.ENTRYPOINT_PATCH, (ctx, bytes) -> null);
		b.register(TransformPhase.RAW_READ, new RawReadTransformer((name, allow) -> bytes("disk")));
		b.register(TransformPhase.FABRIC_BUILTIN, (ctx, bytes) -> bytes("builtin:" + new String(bytes, StandardCharsets.UTF_8)));
		b.freeze();
		assertArrayEquals(bytes("builtin:disk"), b.apply(null, CONTEXT));

		// 情形 3：两边都没字节 → 结果是 null。
		// 「没有字节就返回 null」这条守卫在 FabricBuiltinTransformer 里面（上游的
		// `if (input != null) return ...; return null;`），不在链上——链保持通用，
		// 每个步骤自己决定「没字节时我干不干」。这里断言链确实把 null 交到了最后一步，
		// 真实现的守卫由 fabricBuiltinTransformerPassesNullThrough 单独守。
		boolean[] sawNull = { false };
		TransformChain c = new TransformChain();
		c.register(TransformPhase.ENTRYPOINT_PATCH, (ctx, bytes) -> null);
		c.register(TransformPhase.RAW_READ, new RawReadTransformer((name, allow) -> null));
		c.register(TransformPhase.FABRIC_BUILTIN, (ctx, bytes) -> {
			sawNull[0] = bytes == null;
			return null;
		});
		c.freeze();
		assertNull(c.apply(null, CONTEXT));
		assertTrue(sawNull[0], "没有字节时，链必须把 null 原样交到最后一步，而不是凭空造一个空数组");

		// 情形 4：读磁盘抛 IOException → 消息与上游逐字相同（它出现在玩家看到的崩溃里）
		TransformChain d = new TransformChain();
		d.register(TransformPhase.RAW_READ, new RawReadTransformer((name, allow) -> {
			throw new java.io.IOException("boom");
		}));
		d.freeze();

		RuntimeException e = assertThrows(RuntimeException.class, () -> d.apply(null, CONTEXT));
		assertEquals("Failed to load class file for 'com.example.Target'!", e.getMessage());
	}

	@Test
	@DisplayName("内建转换对 null 字节的守卫：没有字节就返回 null，不去碰 FabricTransformer")
	void fabricBuiltinTransformerPassesNullThrough() {
		// FabricTransformer.transform 会去问游戏提供者要 BuiltinTransform 集合，
		// 在单测里没有游戏环境。所以「null 直接短路」这条守卫必须发生在它之前——
		// 这个断言同时证明了「守卫在」与「守卫足够靠前」。
		assertNull(new FabricBuiltinTransformer().transform(CONTEXT, null));
	}

	@Test
	@DisplayName("空链是恒等：原样返回入参（包括 null）")
	void emptyChainIsIdentity() {
		TransformChain chain = new TransformChain();
		chain.freeze();

		assertTrue(chain.isEmpty());
		assertArrayEquals(bytes("same"), chain.apply(bytes("same"), CONTEXT));
		assertNull(chain.apply(null, CONTEXT));
	}

	@Test
	@DisplayName("MIXIN 不允许注册：它必须由类委托单独执行，不能被绕过")
	void mixinCannotBeRegistered() {
		TransformChain chain = new TransformChain();

		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> chain.register(TransformPhase.MIXIN, (ctx, bytes) -> bytes));

		assertTrue(e.getMessage().contains("MIXIN"), e.getMessage());
	}

	@Test
	@DisplayName("apply 的范围不能包含 MIXIN")
	void applyRangeCannotIncludeMixin() {
		TransformChain chain = new TransformChain();
		chain.freeze();

		assertThrows(IllegalArgumentException.class,
				() -> chain.apply(null, CONTEXT, TransformPhase.RAW_READ, TransformPhase.MIXIN));
	}

	@Test
	@DisplayName("freeze 之后注册是硬错误——晚注册的步骤会静默地不生效")
	void registrationAfterFreezeIsRejected() {
		TransformChain chain = new TransformChain();
		chain.register(TransformPhase.RAW_READ, (ctx, bytes) -> null);
		chain.freeze();

		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> chain.register(TransformPhase.HOOK_INJECTION, (ctx, bytes) -> null));

		assertTrue(e.getMessage().contains("冻结"), e.getMessage());
	}

	@Test
	@DisplayName("冻结前注册的步骤，冻结后仍然在链上（冻结不是清空）")
	void freezeKeepsExistingRegistrations() {
		TransformChain chain = new TransformChain();
		chain.register(TransformPhase.RAW_READ, (ctx, bytes) -> bytes("raw"));
		chain.freeze();

		assertArrayEquals(bytes("raw"), chain.apply(null, CONTEXT));
		assertEquals(1, chain.size(TransformPhase.RAW_READ));
	}

	@Test
	@DisplayName("顺序表算一次就缓存：ordered 返回同一个实例")
	void orderedIsCached() {
		TransformChain chain = new TransformChain();
		chain.register(TransformPhase.RAW_READ, (ctx, bytes) -> null, 5);
		chain.freeze();

		assertSame(chain.ordered(TransformPhase.RAW_READ), chain.ordered(TransformPhase.RAW_READ));
	}

	@Test
	@DisplayName("上下文携带类名与 allowFromParent——上游的自由变量收在这里")
	void contextCarriesTheUpstreamFreeVariables() {
		TransformChain chain = new TransformChain();
		String[] seen = new String[2];

		chain.register(TransformPhase.RAW_READ, (ctx, bytes) -> {
			seen[0] = ctx.className();
			seen[1] = ctx.className() + "/" + ctx.allowFromParent() + "/" + ctx.envType() + "/" + ctx.development();
			return null;
		});
		chain.freeze();

		chain.apply(null, CONTEXT);

		assertEquals("com.example.Target", seen[0]);
		assertEquals("com.example.Target/true/SERVER/false", seen[1]);
	}

	@Test
	@DisplayName("参数校验：null 一律直接报错，不猜")
	void argumentsAreValidated() {
		TransformChain chain = new TransformChain();

		assertThrows(IllegalArgumentException.class, () -> chain.register(null, (ctx, bytes) -> null));
		assertThrows(IllegalArgumentException.class, () -> chain.register(TransformPhase.RAW_READ, null));
		assertThrows(IllegalArgumentException.class,
				() -> new TransformContext(null, EnvType.SERVER, false, "official", true));
		assertThrows(IllegalArgumentException.class,
				() -> new TransformContext("x", null, false, "official", true));
	}

	/** 记一笔调用，并原样返回入参。 */
	private static ClassTransformer logging(List<String> log, String name) {
		return new ClassTransformer() {
			@Override
			public byte[] transform(TransformContext context, byte[] classBytes) {
				log.add(name);
				return classBytes;
			}

			@Override
			public String name() {
				return name;
			}
		};
	}
}
