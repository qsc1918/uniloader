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

import net.fabricmc.loader.impl.game.patch.GameTransformer;

/**
 * UniLoader fork patch (T1.5)：{@link TransformPhase#ENTRYPOINT_PATCH} —— 游戏入口点补丁。
 *
 * <p>它是链上第一个阶段，也是唯一一个「产出字节」而不是「改字节」的步骤：
 * 游戏提供者在这里把控制权交给加载器（例如改写 {@code net.minecraft.server.Main}）。
 * 它返回 null 表示「这个类不归我管」，链就会走到 {@link TransformPhase#RAW_READ} 去读磁盘。
 *
 * <p>刻意不看 {@code classBytes}：上游就是 {@code input = provider.getEntrypointTransformer().transform(name)}
 * ——直接赋值，不关心之前有没有字节。保持这个语义是「产物逐字节不变」的一部分。
 */
public final class EntrypointPatchTransformer implements ClassTransformer {
	private final GameTransformer transformer;

	public EntrypointPatchTransformer(GameTransformer transformer) {
		if (transformer == null) throw new IllegalArgumentException("transformer is required");

		this.transformer = transformer;
	}

	@Override
	public byte[] transform(TransformContext context, byte[] classBytes) {
		return transformer.transform(context.className());
	}

	@Override
	public String name() {
		return "entrypoint-patch";
	}

	@Override
	public String toString() {
		return name() + "(" + transformer + ")";
	}
}
