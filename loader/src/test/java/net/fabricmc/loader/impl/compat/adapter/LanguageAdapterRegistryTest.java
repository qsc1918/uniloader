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

package net.fabricmc.loader.impl.compat.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.LanguageAdapterException;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.impl.util.DefaultLanguageAdapter;

/**
 * UniLoader fork patch (T1.3)。
 *
 * <p>验收的重点是「不破坏现有行为」：{@code default} 必须还是上游那个实例，
 * 三个新键现在必须和它逐字等价（透明转发），替换实现的行为也必须可预期。
 */
class LanguageAdapterRegistryTest {
	@Test
	void installsAllFourEcosystemKeys() {
		Map<String, LanguageAdapter> target = new LinkedHashMap<>();
		Set<String> installed = LanguageAdapterRegistry.installInto(target);

		Set<String> expected = new HashSet<>(Arrays.asList("default", "quilt", "neoforge", "forge"));
		assertEquals(expected, installed);
		assertEquals(expected, target.keySet());
	}

	@Test
	void defaultStaysTheUpstreamInstance() {
		// 这是「不破坏现有行为」最硬的一条：Fabric 模组走的还是同一个实例，
		// 换了它就会让所有既有模组的行为悄悄改变。
		assertSame(DefaultLanguageAdapter.INSTANCE, LanguageAdapterRegistry.get("default"));
	}

	@Test
	void ecosystemKeysAreTransparentDelegatorsForNow() {
		for (String key : new String[] { "quilt", "neoforge", "forge" }) {
			LanguageAdapter adapter = LanguageAdapterRegistry.get(key);
			assertTrue(adapter instanceof DelegatingLanguageAdapter, key + " 现在应当是透明转发");

			DelegatingLanguageAdapter delegating = (DelegatingLanguageAdapter) adapter;
			assertEquals(key, delegating.ecosystem());
			assertSame(DefaultLanguageAdapter.INSTANCE, delegating.delegate(),
					key + " 本阶段必须与 default 逐字等价");
		}
	}

	@Test
	void delegatingAdapterForwardsTheCall() throws LanguageAdapterException {
		Object sentinel = new Object();
		String[] seen = new String[2];

		LanguageAdapter spy = new LanguageAdapter() {
			@SuppressWarnings("unchecked")
			@Override
			public <T> T create(ModContainer mod, String value, Class<T> type) {
				seen[0] = value;
				seen[1] = type.getName();
				return (T) sentinel;
			}
		};

		DelegatingLanguageAdapter adapter = new DelegatingLanguageAdapter("neoforge", spy);
		Object out = adapter.create(null, "some.Value", Object.class);

		assertSame(sentinel, out, "转发必须原样返回委托方的结果");
		assertEquals("some.Value", seen[0]);
		assertEquals("java.lang.Object", seen[1]);
	}

	@Test
	void installDoesNotOverrideAnExistingKey() {
		Map<String, LanguageAdapter> target = new LinkedHashMap<>();
		LanguageAdapter mine = new DelegatingLanguageAdapter("custom", DefaultLanguageAdapter.INSTANCE);
		target.put("neoforge", mine);

		Set<String> installed = LanguageAdapterRegistry.installInto(target);

		assertFalse(installed.contains("neoforge"), "已经存在的键不能被覆盖——那会让「哪个实现生效」变得说不清");
		assertSame(mine, target.get("neoforge"));
	}

	@Test
	void replaceSwapsTheImplementationAndReturnsThePreviousOne() {
		LanguageAdapter replacement = new DelegatingLanguageAdapter("neoforge", DefaultLanguageAdapter.INSTANCE);

		try {
			LanguageAdapter previous = LanguageAdapterRegistry.replace("neoforge", replacement);

			assertNotNull(previous);
			assertSame(replacement, LanguageAdapterRegistry.get("neoforge"));
		} finally {
			// 注册表是进程级的，测试结束必须还原，否则会串到别的用例上。
			LanguageAdapterRegistry.replace("neoforge",
					new DelegatingLanguageAdapter("neoforge", DefaultLanguageAdapter.INSTANCE));
		}
	}

	@Test
	void replacingAnUnknownKeyIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> LanguageAdapterRegistry.replace("bukkit", DefaultLanguageAdapter.INSTANCE),
				"凭空造一个新生态键必须报错：那等于发明一套新契约");
	}

	@Test
	void delegatingAdapterRejectsMissingArguments() {
		assertThrows(IllegalArgumentException.class,
				() -> new DelegatingLanguageAdapter("", DefaultLanguageAdapter.INSTANCE));
		assertThrows(IllegalArgumentException.class,
				() -> new DelegatingLanguageAdapter("neoforge", null));
	}
}
