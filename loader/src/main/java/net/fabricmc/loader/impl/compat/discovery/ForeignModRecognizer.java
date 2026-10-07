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

package net.fabricmc.loader.impl.compat.discovery;

import java.nio.file.Path;

import org.jetbrains.annotations.Nullable;

/**
 * UniLoader fork patch (T1.4)：认领一个「别家生态」的模组。
 *
 * <p>本阶段只做<b>识别，不做解析</b>。理由很实际：解析 {@code neoforge.mods.toml} /
 * {@code mods.toml} 要引入 TOML 解析器、要定义依赖与版本语义，那是阶段 3（统一发现层）
 * 的事；而「这个 jar 是哪一家的」这件事越早确定越好——否则它会被当成
 * 「未知文件」丢进 {@code nonFabricMods}，日志里只剩一句
 * {@code Found 1 non-fabric mod(s)}，玩家完全看不出该怎么办。
 *
 * <p>实现必须是<b>纯识别</b>：只读清单在不在，不读它的内容，也不抛异常
 * （坏 jar、不是 zip、权限问题都返回 null，让它继续走「未知文件」的老路）。
 */
public interface ForeignModRecognizer {
	/** 生态 id：quilt / neoforge / forge。 */
	String ecosystemId();

	/** 靠哪个清单文件认人；同时它是日志里给玩家看的证据。 */
	String manifestPath();

	/**
	 * @param path jar 或目录
	 * @return 认出来了就返回一条给人看的说明（本阶段就是清单路径）；不是我家的返回 null
	 */
	@Nullable
	String recognize(Path path);
}
