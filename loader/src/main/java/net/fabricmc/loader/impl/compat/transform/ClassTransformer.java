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

/**
 * UniLoader fork patch (T1.5)：管线里的一个变换步骤。
 *
 * <p>契约只有两条，但两条都必须精确，因为 T1.5 的验收是「产物逐字节不变」：
 *
 * <ol>
 *   <li><b>返回 {@code null} 表示「我没有意见」</b>，而不是「结果是 null」。
 *       管线保留上一个阶段的字节继续往下走。这条语义是上游那三段短路的等价物：
 *       入口点补丁返回 null → 去读原始字节；原始字节没读到 → 结果是 null。</li>
 *   <li><b>返回非 null 就是新的当前字节</b>，即使它与传入的字节逐字节相同。</li>
 * </ol>
 *
 * <p>实现必须是线程安全的：同一个类的字节可能被多个线程同时变换。
 */
@FunctionalInterface
public interface ClassTransformer {
	/**
	 * @param context    本次调用的上下文（类名、环境、命名空间、是否允许向父加载器取字节）
	 * @param classBytes 当前字节；{@code null} 表示「还没有人产出过字节」
	 * @return 新字节，或 {@code null} 表示不改变
	 */
	byte[] transform(TransformContext context, byte[] classBytes);

	/** 用于日志与排序诊断的稳定名字。 */
	default String name() {
		return getClass().getName();
	}
}
