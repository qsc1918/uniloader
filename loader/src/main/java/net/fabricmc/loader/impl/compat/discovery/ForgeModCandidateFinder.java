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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.fabricmc.loader.impl.compat.discovery.ForeignModRecognizers.Match;
import net.fabricmc.loader.impl.discovery.ModCandidateFinder;
import net.fabricmc.loader.impl.util.LoaderUtil;
import net.fabricmc.loader.impl.util.log.Log;

/**
 * UniLoader fork patch (T1.4)：扩展点 H2 —— 别家生态的候选查找器。
 *
 * <p>它做两件事，且只做这两件：
 * <ol>
 *   <li>扫一遍模组目录，把 {@link ForeignModRecognizers} 认得出来的 jar 挑出来；</li>
 *   <li>用 {@link ModCandidateConsumer#acceptForeign} 把它们<b>认领</b>掉，
 *       这样 {@code ModDiscoverer} 就不会再拿它们去按 {@code fabric.mod.json} 解析，
 *       也不会把它们塞进 {@code nonFabricMods} 当「未知文件」。</li>
 * </ol>
 *
 * <p>本阶段<b>不产生任何候选</b>：认出来只是为了在日志里说清「这是什么、为什么没加载」。
 * 阶段 3（统一发现层）会在这里产出真正的 {@code ModCandidate}。
 *
 * <p>为什么它必须排在其它查找器前面：认领发生在 {@code accept} 之前才有意义。
 * 注册顺序由 {@code FabricLoaderImpl.setup()} 保证，这里不假设。
 *
 * <p>目录不存在时它是安静的：这是识别器，不是安装器，
 * 「没有 mods 目录」这件事由 DirectoryModCandidateFinder 负责说。
 */
public final class ForgeModCandidateFinder implements ModCandidateFinder {
	private final Path dir;

	public ForgeModCandidateFinder(Path modsDir) {
		if (modsDir == null) throw new IllegalArgumentException("modsDir is required");

		this.dir = modsDir;
	}

	@Override
	public void findCandidates(ModCandidateConsumer out) {
		if (!Files.isDirectory(dir)) return;

		List<Path> jars = new ArrayList<>();

		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
			for (Path path : stream) {
				if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar")) {
					jars.add(path);
				}
			}
		} catch (IOException e) {
			throw new RuntimeException("扫描别家生态模组时读不了目录 '" + dir + "'！", e);
		}

		// 排序只为了让日志顺序稳定——诊断输出不稳定的时候，diff 就没法看了。
		Collections.sort(jars);

		for (Path jar : jars) {
			Match match = ForeignModRecognizers.match(jar);
			if (match == null) continue;

			Path normalized = LoaderUtil.normalizeExistingPath(jar);
			out.acceptForeign(Collections.singletonList(normalized), match.ecosystem(), match.detail());
			Log.debug(ForeignModRecognizers.LOG_CATEGORY, "认领 %s 为 %s（依据 %s）",
					jar.getFileName(), ForeignModRecognizers.displayName(match.ecosystem()), match.detail());
		}
	}

	@Override
	public String toString() {
		return "ForgeModCandidateFinder[" + dir + "]";
	}
}
