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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;

/**
 * UniLoader fork patch (T1.5)：有序、可插拔的字节码变换管线。
 *
 * <p>排序规则，两层，都是确定性的：
 * <ol>
 *   <li>阶段按 {@link TransformPhase#ordinal()} 顺序执行；</li>
 *   <li>阶段内部按 {@code sortIndex} 升序，相同则按注册顺序。</li>
 * </ol>
 *
 * <p>Forbric 的链还多一层「{@code predepends} 拓扑排序」（对应 Forge 的 coremod 前置依赖）。
 * UL 现在不需要它：链上只有三个内建步骤，谁先谁后由阶段名就能说清，
 * 加一层没有实现者的拓扑排序只是多一段没被测过的代码。等阶段 7 真的有
 * 「A 必须在 B 之前注入」这种需求时再加，那时它会带着真实的测试用例进来。
 *
 * <p>生命周期：<b>注册只在引导期发生</b>，{@link #freeze()} 之后任何注册都是硬错误；
 * {@link #apply} 在链冻结之后是线程安全的（每个阶段的顺序表算一次就缓存）。
 * 这两条都是为了让「注册漏了/晚了」变成启动时就报的错，而不是某个类悄悄没被变换。
 */
public final class TransformChain {
	private static final TransformPhase[] PHASES = TransformPhase.values();

	private static final class Entry {
		final ClassTransformer transformer;
		final int sortIndex;
		final int registrationOrder;

		Entry(ClassTransformer transformer, int sortIndex, int registrationOrder) {
			this.transformer = transformer;
			this.sortIndex = sortIndex;
			this.registrationOrder = registrationOrder;
		}
	}

	private final EnumMap<TransformPhase, List<Entry>> phases = new EnumMap<>(TransformPhase.class);
	private final EnumMap<TransformPhase, List<ClassTransformer>> sorted = new EnumMap<>(TransformPhase.class);
	private int registrationCounter;
	private volatile boolean frozen;

	/** 按阶段默认顺序（sortIndex = 0）注册。 */
	public void register(TransformPhase phase, ClassTransformer transformer) {
		register(phase, transformer, 0);
	}

	/**
	 * 注册一个变换步骤。
	 *
	 * @param phase     所属阶段；不接受 {@link TransformPhase#MIXIN}
	 * @param sortIndex 阶段内的排序键，小的先跑
	 */
	public void register(TransformPhase phase, ClassTransformer transformer, int sortIndex) {
		if (phase == null) throw new IllegalArgumentException("phase is required");
		if (transformer == null) throw new IllegalArgumentException("transformer is required");

		if (phase == TransformPhase.MIXIN) {
			throw new IllegalArgumentException(
					"MIXIN 是终结且独占的阶段，不能注册进链——它由类委托在链之后单独执行");
		}

		if (frozen) {
			throw new IllegalStateException(
					"链已冻结，不能再注册 " + transformer.name() + "；晚注册的步骤会静默地不生效");
		}

		phases.computeIfAbsent(phase, p -> new ArrayList<>(4))
				.add(new Entry(transformer, sortIndex, registrationCounter++));
		sorted.remove(phase); // 该阶段的缓存作废
	}

	/**
	 * 冻结链。冻结之后 {@link #register} 会抛异常，{@link #apply} 才是线程安全的。
	 *
	 * <p>冻结是显式的，不是「第一次 apply 时自动冻」：自动冻结会把
	 * 「本该在引导期注册、结果晚了一步」变成一次竞态，而竞态的表现形式
	 * 恰恰是「某个类没被变换」——最难查的那种。
	 */
	public void freeze() {
		frozen = true;

		for (TransformPhase phase : PHASES) {
			ordered(phase);
		}
	}

	public boolean isFrozen() {
		return frozen;
	}

	/** 跑完整个链（{@link TransformPhase#FIRST_CHAIN_PHASE} 到 {@link TransformPhase#LAST_CHAIN_PHASE}）。 */
	public byte[] apply(String className, byte[] classBytes, TransformContext context) {
		return apply(className, classBytes, context,
				TransformPhase.FIRST_CHAIN_PHASE, TransformPhase.LAST_CHAIN_PHASE);
	}

	/**
	 * 跑指定范围内的阶段，把字节依次穿过每个步骤。
	 *
	 * <p>语义与上游那三段短路逐字等价：某一步返回 null 表示「我没意见」，
	 * 当前字节保持不变；返回非 null 就是新的当前字节。
	 *
	 * @return 变换后的字节；没有任何步骤产出过字节时返回入参（可能是 null）
	 */
	public byte[] apply(String className, byte[] classBytes, TransformContext context,
			TransformPhase fromInclusive, TransformPhase toInclusive) {
		if (fromInclusive == null || toInclusive == null) throw new IllegalArgumentException("phase range is required");

		if (toInclusive == TransformPhase.MIXIN) {
			throw new IllegalArgumentException("链不执行 MIXIN；它由类委托单独执行");
		}

		if (fromInclusive.ordinal() > toInclusive.ordinal()) {
			throw new IllegalArgumentException("阶段范围反了：" + fromInclusive + " -> " + toInclusive);
		}

		byte[] bytes = classBytes;

		for (int i = fromInclusive.ordinal(); i <= toInclusive.ordinal(); i++) {
			for (ClassTransformer transformer : ordered(PHASES[i])) {
				byte[] result = transformer.transform(context, bytes);

				if (result != null) bytes = result;
			}
		}

		return bytes;
	}

	/** 某个阶段里按最终顺序排好的步骤（算一次就缓存）。 */
	public List<ClassTransformer> ordered(TransformPhase phase) {
		if (phase == null) return Collections.emptyList();

		List<ClassTransformer> cached = sorted.get(phase);
		if (cached != null) return cached;

		List<Entry> entries = phases.get(phase);

		if (entries == null || entries.isEmpty()) {
			List<ClassTransformer> empty = Collections.emptyList();
			sorted.put(phase, empty);
			return empty;
		}

		List<Entry> copy = new ArrayList<>(entries);
		copy.sort(Comparator.comparingInt((Entry e) -> e.sortIndex)
				.thenComparingInt(e -> e.registrationOrder));

		List<ClassTransformer> result = new ArrayList<>(copy.size());

		for (Entry entry : copy) {
			result.add(entry.transformer);
		}

		List<ClassTransformer> unmodifiable = Collections.unmodifiableList(result);
		sorted.put(phase, unmodifiable);
		return unmodifiable;
	}

	/** 某个阶段里注册了几个步骤（诊断用）。 */
	public int size(TransformPhase phase) {
		List<Entry> entries = phases.get(phase);
		return entries == null ? 0 : entries.size();
	}

	/** 链上有没有任何步骤（没有的话 apply 就是恒等，可以整个跳过）。 */
	public boolean isEmpty() {
		for (TransformPhase phase : PHASES) {
			if (size(phase) > 0) return false;
		}

		return true;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder("TransformChain[");

		boolean first = true;

		for (TransformPhase phase : PHASES) {
			int n = size(phase);
			if (n == 0) continue;

			if (!first) sb.append(", ");
			sb.append(phase).append('=').append(n);
			first = false;
		}

		return sb.append(']').toString();
	}
}
