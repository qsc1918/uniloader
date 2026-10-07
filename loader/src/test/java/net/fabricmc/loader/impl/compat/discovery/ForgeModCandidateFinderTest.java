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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.loader.impl.discovery.ModCandidateFinder;

/**
 * UniLoader fork patch (T1.4) 的验收：查找器只认领别家的，且不产生任何 Fabric 候选。
 */
class ForgeModCandidateFinderTest {
	@TempDir
	Path tmp;

	@Test
	@DisplayName("只认领别家的，Fabric 模组与未知 jar 一概不碰")
	void claimsOnlyForeignMods() throws IOException {
		writeJar("neoforge-mod.jar", "META-INF/neoforge.mods.toml");
		writeJar("forge-mod.jar", "META-INF/mods.toml");
		writeJar("quilt-mod.jar", "quilt.mod.json");
		writeJar("fabric-mod.jar", "fabric.mod.json");
		writeJar("mystery.jar", "some/other/file.txt");

		Recorder recorder = run();

		// 顺序 = 文件名的字典序（forge-mod < neoforge-mod < quilt-mod）
		assertEquals(Arrays.asList("forge:forge-mod.jar", "neoforge:neoforge-mod.jar", "quilt:quilt-mod.jar"),
				recorder.foreign);
		assertTrue(recorder.candidates.isEmpty(),
				"本阶段只识别不解析：一个候选都不该产出，否则会拿别家的 jar 去按 fabric.mod.json 解析");
	}

	@Test
	@DisplayName("非 jar 文件与子目录都不参与")
	void ignoresNonJarsAndSubdirectories() throws IOException {
		writeJar("real.jar", "META-INF/mods.toml");
		Files.write(tmp.resolve("readme.txt"), "not a mod".getBytes(StandardCharsets.UTF_8));
		Path sub = tmp.resolve("nested");
		Files.createDirectories(sub);
		writeJarIn(sub, "inner.jar", "META-INF/mods.toml");

		Recorder recorder = run();

		assertEquals(Arrays.asList("forge:real.jar"), recorder.foreign, "只扫一层，不递归进子目录");
	}

	@Test
	@DisplayName("目录不存在时不报错也不创建：这是识别器，不是安装器")
	void missingDirectoryIsQuiet() {
		Recorder recorder = run(tmp.resolve("does-not-exist"));

		assertTrue(recorder.foreign.isEmpty());
		assertTrue(recorder.candidates.isEmpty());
		assertTrue(Files.notExists(tmp.resolve("does-not-exist")), "不该顺手把目录建出来");
	}

	@Test
	@DisplayName("空目录不产出任何东西")
	void emptyDirectoryProducesNothing() {
		Recorder recorder = run();

		assertTrue(recorder.foreign.isEmpty());
		assertTrue(recorder.candidates.isEmpty());
	}

	@Test
	@DisplayName("结果按文件名排序：诊断输出不稳定的话，diff 就没法看了")
	void resultsAreSorted() throws IOException {
		writeJar("zeta.jar", "META-INF/mods.toml");
		writeJar("alpha.jar", "META-INF/mods.toml");
		writeJar("mid.jar", "META-INF/mods.toml");

		Recorder recorder = run();

		assertEquals(Arrays.asList("forge:alpha.jar", "forge:mid.jar", "forge:zeta.jar"), recorder.foreign);
	}

	@Test
	@DisplayName("构造参数为 null 直接报错，而不是等到运行期空指针")
	void nullDirectoryIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> new ForgeModCandidateFinder(null));
	}

	private Recorder run() {
		return run(tmp);
	}

	private Recorder run(Path dir) {
		Recorder recorder = new Recorder();
		new ForgeModCandidateFinder(dir).findCandidates(recorder);
		return recorder;
	}

	private Path writeJar(String name, String... entries) throws IOException {
		return writeJarIn(tmp, name, entries);
	}

	private Path writeJarIn(Path dir, String name, String... entries) throws IOException {
		Path jar = dir.resolve(name);

		try (OutputStream os = Files.newOutputStream(jar); ZipOutputStream zos = new ZipOutputStream(os)) {
			for (String entry : entries) {
				zos.putNextEntry(new ZipEntry(entry));
				zos.write(new byte[0]);
				zos.closeEntry();
			}
		}

		return jar;
	}

	/** 记下查找器说了什么。 */
	private static final class Recorder implements ModCandidateFinder.ModCandidateConsumer {
		final List<List<Path>> candidates = new ArrayList<>();
		final List<String> foreign = new ArrayList<>();

		@Override
		public void accept(List<Path> paths, boolean requiresRemap) {
			candidates.add(paths);
		}

		@Override
		public void acceptForeign(List<Path> paths, String ecosystem, String detail) {
			for (Path path : paths) {
				foreign.add(ecosystem + ":" + path.getFileName());
			}
		}
	}
}
