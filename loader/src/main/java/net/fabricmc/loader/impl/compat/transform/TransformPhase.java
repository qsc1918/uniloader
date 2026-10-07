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
 * UniLoader fork patch (T1.5)：字节码变换管线的固定阶段。
 *
 * <p>上游把「入口点补丁 → 读原始字节 → Fabric 内建转换 → Mixin」这条链硬写在
 * {@code KnotClassDelegate.getPreMixinClassByteArray()} 里，一共 15 行、三个 return，
 * 顺序与短路条件都藏在控制流里。要接 Forge/NeoForge 的 hook 注入（阶段 7 要把 846 个
 * patch 翻译成 Mixin/ASM），就得往这条链里插东西——而往控制流里插东西，
 * 顺序一旦错了没人看得出来（游戏照样能起，只是某个注入悄悄失效）。
 *
 * <p>所以这里把阶段名写下来，顺序即 {@link Enum#ordinal()}，一眼可见、可断言、可 diff。
 * 设计移植自 Forbric 的 {@code net.forbric.kernel.transform.TransformPhase}（Apache-2.0），
 * 但只保留 UL 现在真有的阶段：Forbric 的 {@code DEOBF_REMAP} / {@code ENV_STRIP} /
 * {@code ACCESS} / {@code COREMOD} 在 UL 里都由 {@link #FABRIC_BUILTIN} 内部的
 * {@code FabricTransformer} 负责，拆出来只会多一层没有内容的间接。
 *
 * <h2>为什么顺序是这个顺序</h2>
 * <ol>
 *   <li>{@link #ENTRYPOINT_PATCH} 最先：游戏入口点补丁决定「谁有字节」，
 *       它产出的字节就是后面所有阶段的输入；</li>
 *   <li>{@link #RAW_READ} 其次：只有前面没人给出字节时才去读磁盘上的 class。
 *       它是「读」而不是「改」，所以必须在所有修改之前；</li>
 *   <li>{@link #FABRIC_BUILTIN} 第三：Fabric 的包访问放宽、{@code @Environment} 剥离、
 *       class-tweaker 都在这里，它们是上游既有行为，位置不能动；</li>
 *   <li>{@link #HOOK_INJECTION} 第四：别家生态的 hook 注入（阶段 7）。
 *       <b>它的位置是暂定的</b>——本阶段它是空的，等阶段 7 有真实 patch 要注入时，
 *       位置必须靠实测决定（注入点会不会被前面的阶段改掉、会不会被后面的 Mixin 覆盖），
 *       不靠猜。把它放在最后是因为「越晚注入，看到的字节越接近最终形态」，
 *       但这句话在阶段 7 之前只是假设。</li>
 * </ol>
 */
public enum TransformPhase {
	/** 游戏入口点补丁：由 GameProvider 提供，负责把控制权交给加载器。 */
	ENTRYPOINT_PATCH,
	/** 读原始 class 字节。只有前面没有任何阶段产出字节时才会真的去读。 */
	RAW_READ,
	/** Fabric 内建转换：包访问放宽、{@code @Environment} 剥离、class-tweaker。 */
	FABRIC_BUILTIN,
	/** 别家生态的 hook 注入（阶段 7）。本阶段为空。 */
	HOOK_INJECTION,
	/**
	 * 单一 Mixin 转换器。终结且独占：它由类委托在整条链跑完之后单独执行，
	 * 不允许通过 {@link TransformChain#register} 注册进来——Mixin 只有一个实例，
	 * 放进链里就会变成「可以被绕过的一步」，而它恰恰是绝对不能少的那一步。
	 */
	MIXIN;

	/** 链会执行的第一个阶段。 */
	public static final TransformPhase FIRST_CHAIN_PHASE = ENTRYPOINT_PATCH;
	/** 链会执行的最后一个阶段；{@link #MIXIN} 由类委托单独执行。 */
	public static final TransformPhase LAST_CHAIN_PHASE = HOOK_INJECTION;
}
