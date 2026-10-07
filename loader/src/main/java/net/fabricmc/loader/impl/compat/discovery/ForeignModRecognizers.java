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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipFile;

import org.jetbrains.annotations.Nullable;

import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

/**
 * UniLoader fork patch (T1.4)：别家生态的识别器注册表。
 *
 * <p>三个内建识别器对应三家清单文件：
 * <ul>
 *   <li>NeoForge：{@code META-INF/neoforge.mods.toml}</li>
 *   <li>Forge：{@code META-INF/mods.toml}</li>
 *   <li>Quilt：{@code quilt.mod.json}</li>
 * </ul>
 *
 * <p>顺序有意义：一个 jar 同时被多家认领时，先注册的那家赢。目前三家清单文件互不重叠，
 * 但阶段 3 可能会往里加「按注解认领」的识别器，那时顺序就是规则的一部分——
 * 所以这里是 {@code List} 而不是 {@code Set}，插入顺序即优先级。
 *
 * <p>识别失败一律返回 null 而不是抛异常：识别是「尽力而为」，
 * 认不出来最坏的结果是退回上游那条「未知文件」的老路，而不是把整个启动搞挂。
 */
public final class ForeignModRecognizers {
	public static final String NEOFORGE = "neoforge";
	public static final String FORGE = "forge";
	public static final String QUILT = "quilt";

	public static final LogCategory LOG_CATEGORY = LogCategory.createCustom("UniLoader", "Discovery");

	private static final List<ForeignModRecognizer> RECOGNIZERS = new ArrayList<>();

	private ForeignModRecognizers() {
	}

	static {
		register(new ManifestRecognizer(QUILT, "quilt.mod.json"));
		register(new ManifestRecognizer(NEOFORGE, "META-INF/neoforge.mods.toml"));
		register(new ManifestRecognizer(FORGE, "META-INF/mods.toml"));
	}

	/** 追加一个识别器。后加的优先级更低；同生态重复注册会被拒绝（不静默覆盖）。 */
	public static synchronized void register(ForeignModRecognizer recognizer) {
		if (recognizer == null) throw new IllegalArgumentException("recognizer is required");

		for (ForeignModRecognizer existing : RECOGNIZERS) {
			if (existing.ecosystemId().equalsIgnoreCase(recognizer.ecosystemId())) {
				throw new IllegalArgumentException("生态 " + recognizer.ecosystemId() + " 的识别器已经注册过了");
			}
		}

		RECOGNIZERS.add(recognizer);
	}

	public static synchronized List<ForeignModRecognizer> all() {
		return Collections.unmodifiableList(new ArrayList<>(RECOGNIZERS));
	}

	/**
	 * 认一个 jar 或目录。
	 *
	 * @return 认出来返回 {@link Match}；认不出来返回 null（调用方负责退回「未知文件」）
	 */
	@Nullable
	public static Match match(Path path) {
		if (path == null) return null;

		for (ForeignModRecognizer recognizer : all()) {
			String detail;

			try {
				detail = recognizer.recognize(path);
			} catch (Throwable t) {
				// 识别器自己炸了不等于「不是这一家」：记下来，继续问下一个。
				Log.warn(LOG_CATEGORY, "识别器 %s 在 %s 上抛异常，已跳过：%s",
						recognizer.ecosystemId(), path.getFileName(), t);
				continue;
			}

			if (detail != null) return new Match(recognizer.ecosystemId(), detail);
		}

		return null;
	}

	/** 日志与报告里给人看的生态名。 */
	public static String displayName(String ecosystemId) {
		if (ecosystemId == null || ecosystemId.isEmpty()) return "未知";

		switch (ecosystemId.toLowerCase(Locale.ROOT)) {
		case NEOFORGE: return "NeoForge";
		case FORGE: return "Forge";
		case QUILT: return "Quilt";
		case "fabric": return "Fabric";
		default: return ecosystemId;
		}
	}

	/** 一次识别的结果。 */
	public static final class Match {
		private final String ecosystem;
		private final String detail;

		Match(String ecosystem, String detail) {
			this.ecosystem = ecosystem;
			this.detail = detail;
		}

		public String ecosystem() {
			return ecosystem;
		}

		public String detail() {
			return detail;
		}

		@Override
		public String toString() {
			return ecosystem + "(" + detail + ")";
		}
	}

	/**
	 * 按「清单文件在不在」认人——本阶段所有识别器都是这个形态。
	 *
	 * <p>刻意<b>不读清单内容</b>：解析 TOML/JSON 是阶段 3 的事。
	 * 现在能给出的最有用的信息就是「我看到了 META-INF/neoforge.mods.toml」，
	 * 这已经足够把「未知文件」变成「识别为 NeoForge mod（尚未支持）」。
	 */
	static final class ManifestRecognizer implements ForeignModRecognizer {
		private final String ecosystemId;
		private final String manifestPath;

		ManifestRecognizer(String ecosystemId, String manifestPath) {
			this.ecosystemId = ecosystemId;
			this.manifestPath = manifestPath;
		}

		@Override
		public String ecosystemId() {
			return ecosystemId;
		}

		@Override
		public String manifestPath() {
			return manifestPath;
		}

		@Override
		@Nullable
		public String recognize(Path path) {
			if (!Files.exists(path)) return null;

			try {
				if (Files.isDirectory(path)) {
					return Files.exists(path.resolve(manifestPath)) ? manifestPath : null;
				}

				try (ZipFile zf = new ZipFile(path.toFile())) {
					return zf.getEntry(manifestPath) != null ? manifestPath : null;
				}
			} catch (IOException e) {
				// 打不开的 jar、损坏的 zip、权限问题——都不是「这一家」的证据，退回老路。
				Log.debug(LOG_CATEGORY, "读取 %s 失败，跳过 %s 识别：%s", path.getFileName(), ecosystemId, e);
				return null;
			}
		}

		@Override
		public String toString() {
			return "ManifestRecognizer[" + ecosystemId + " -> " + manifestPath + "]";
		}
	}
}
