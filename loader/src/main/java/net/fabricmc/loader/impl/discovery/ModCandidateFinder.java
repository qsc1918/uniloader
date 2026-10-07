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

package net.fabricmc.loader.impl.discovery;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * UniLoader fork patch (T1.4)：这个接口原来是包级私有的.
 *
 * <p>它是「模组候选从哪来」的唯一扩展点，包级私有意味着只有
 * {@code net.fabricmc.loader.impl.discovery} 这一个包能加实现，
 * 兼容层（{@code impl.compat.*}）想给 NeoForge/Forge/Quilt 加查找器就必须往内核包里塞代码。
 * 改成 public 是「扩展点插件化」的前提；{@code @FunctionalInterface} 也随之去掉，
 * 因为实现类从「一个 lambda」变成了「有状态的一类查找器」。
 */
public interface ModCandidateFinder {
	void findCandidates(ModCandidateConsumer out);

	interface ModCandidateConsumer {
		default void accept(Path path, boolean requiresRemap) {
			accept(Collections.singletonList(path), requiresRemap);
		}

		void accept(List<Path> paths, boolean requiresRemap);

		/**
		 * UniLoader fork patch (T1.4)：认领一个「别家生态」的模组.
		 *
		 * <p>与 {@link #accept} 的区别是语义而不是签名：accept 说的是
		 * 「这是一个候选，去按 fabric.mod.json 解析吧」，acceptForeign 说的是
		 * 「我认出它是哪一家的了，但本阶段还不能加载」。混用会让
		 * 「它是哪一家的」这个事实在后面丢失——而四家共存全靠这个事实。
		 *
		 * @param ecosystem 生态 id（fabric / quilt / neoforge / forge）
		 * @param detail    凭什么认出来的，进日志给人看
		 */
		default void acceptForeign(List<Path> paths, String ecosystem, String detail) {
			throw new UnsupportedOperationException(
					"这个消费方还不支持别家生态的模组：" + ecosystem + " / " + detail);
		}
	}
}
