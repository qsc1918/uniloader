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

import net.fabricmc.loader.impl.transformer.FabricTransformer;

/**
 * UniLoader fork patch (T1.5)：{@link TransformPhase#FABRIC_BUILTIN} —— Fabric 内建转换。
 *
 * <p>包访问放宽、{@code @Environment} 剥离、class-tweaker，三件事都在
 * {@link FabricTransformer#transform} 里面（它自己会按游戏提供者给的
 * {@code BuiltinTransform} 集合决定做哪几件）。这里刻意<b>不把它拆成三个阶段</b>：
 * 拆开只是把同一个方法里的三个 if 搬到外面，多一层没有内容的间接，
 * 却多出「拆的顺序和里面不一致」的新风险。
 *
 * <p>{@code classBytes == null} 时返回 null 是上游的 {@code if (input != null) ... ; return null; }
 * 的等价物：没有字节就没什么可转换的，但也不能把 null 当成「字节」传给下游。
 */
public final class FabricBuiltinTransformer implements ClassTransformer {
	@Override
	public byte[] transform(TransformContext context, byte[] classBytes) {
		if (classBytes == null) return null;

		return FabricTransformer.transform(context.development(), context.envType(),
				context.className(), classBytes);
	}

	@Override
	public String name() {
		return "fabric-builtin";
	}

	@Override
	public String toString() {
		return name();
	}
}
