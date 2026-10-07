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

import java.io.IOException;

/**
 * UniLoader fork patch (T1.5)：{@link TransformPhase#RAW_READ} —— 读原始 class 字节。
 *
 * <p>它是链上唯一一个「读」而不是「改」的步骤，位置由语义决定：必须在所有修改之前，
 * 又必须在入口点补丁之后（入口点补丁要是给了字节，就不用读磁盘了）。
 *
 * <p>「已经有了字节就不读」这条短路原本是上游的 {@code if (input == null) input = getRawClassByteArray(...)}。
 * 在这里它变成了「返回 null 表示我没意见」——同一条短路，只是换了个说法。
 *
 * <p>异常消息与上游逐字相同：这条消息会出现在玩家看到的崩溃里，
 * 改一个字都算行为变化。
 */
public final class RawReadTransformer implements ClassTransformer {
	/** 原始字节的来源。由类委托提供，因为它才知道自己的类加载器与父加载器。 */
	@FunctionalInterface
	public interface RawClassSource {
		byte[] read(String className, boolean allowFromParent) throws IOException;
	}

	private final RawClassSource source;

	public RawReadTransformer(RawClassSource source) {
		if (source == null) throw new IllegalArgumentException("source is required");

		this.source = source;
	}

	@Override
	public byte[] transform(TransformContext context, byte[] classBytes) {
		if (classBytes != null) return null;

		try {
			return source.read(context.className(), context.allowFromParent());
		} catch (IOException e) {
			throw new RuntimeException("Failed to load class file for '" + context.className() + "'!", e);
		}
	}

	@Override
	public String name() {
		return "raw-read";
	}

	@Override
	public String toString() {
		return name();
	}
}
