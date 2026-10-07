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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.loader.impl.compat.discovery.ForeignModRecognizers.Match;

/**
 * UniLoader fork patch (T1.4) 的验收：认得出是哪一家，认不出就老实说认不出。
 */
class ForeignModRecognizersTest {
	@TempDir
	Path tmp;

	@Test
	@DisplayName("三家清单文件各认各的")
	void recognisesEachEcosystem() throws IOException {
		assertMatch("META-INF/neoforge.mods.toml", "neoforge");
		assertMatch("META-INF/mods.toml", "forge");
		assertMatch("quilt.mod.json", "quilt");
	}

	@Test
	@DisplayName("纯 Fabric 模组不会被别家认领")
	void fabricModIsNotClaimed() throws IOException {
		Path jar = writeJar("fabric-mod.jar", "fabric.mod.json");

		assertNull(ForeignModRecognizers.match(jar), "只带 fabric.mod.json 的 jar 不该被别家认领");
	}

	@Test
	@DisplayName("认不出来就返回 null，绝不瞎猜")
	void unknownJarIsNotClaimed() throws IOException {
		assertNull(ForeignModRecognizers.match(writeJar("mystery.jar", "some/other/file.txt")));
		assertNull(ForeignModRecognizers.match(writeJar("empty.jar")));
	}

	@Test
	@DisplayName("不是 zip 的文件不能让识别炸掉——退回「未知文件」是允许的，崩不是")
	void brokenJarIsNotFatal() throws IOException {
		Path broken = tmp.resolve("broken.jar");
		Files.write(broken, "this is definitely not a zip".getBytes(StandardCharsets.UTF_8));

		assertNull(ForeignModRecognizers.match(broken));
	}

	@Test
	@DisplayName("不存在的路径、null 都返回 null")
	void missingPathsAreNotClaimed() {
		assertNull(ForeignModRecognizers.match(null));
		assertNull(ForeignModRecognizers.match(tmp.resolve("nope.jar")));
	}

	@Test
	@DisplayName("目录形态（开发环境）同样认得出")
	void recognisesDirectories() throws IOException {
		Path dir = tmp.resolve("dev-mod");
		Files.createDirectories(dir.resolve("META-INF"));
		Files.write(dir.resolve("META-INF/neoforge.mods.toml"), new byte[0]);

		Match match = ForeignModRecognizers.match(dir);

		assertNotNull(match);
		assertEquals("neoforge", match.ecosystem());
	}

	@Test
	@DisplayName("认领要给得出依据：日志里得说明凭什么")
	void matchCarriesItsEvidence() throws IOException {
		Match match = ForeignModRecognizers.match(writeJar("n.jar", "META-INF/neoforge.mods.toml"));

		assertNotNull(match);
		assertEquals("META-INF/neoforge.mods.toml", match.detail(), "「凭什么认出来的」必须能写进日志");
	}

	@Test
	@DisplayName("同生态重复注册必须报错，不能静默覆盖")
	void duplicateRegistrationIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> ForeignModRecognizers.register(new ForeignModRecognizers.ManifestRecognizer("forge", "whatever.toml")));
	}

	@Test
	@DisplayName("生态名有给人看的写法")
	void displayNamesAreHumanReadable() {
		assertEquals("NeoForge", ForeignModRecognizers.displayName("neoforge"));
		assertEquals("Forge", ForeignModRecognizers.displayName("forge"));
		assertEquals("Quilt", ForeignModRecognizers.displayName("quilt"));
		assertEquals("Fabric", ForeignModRecognizers.displayName("fabric"));
		assertEquals("未知", ForeignModRecognizers.displayName(null));
	}

	private void assertMatch(String manifest, String expectedEcosystem) throws IOException {
		Path jar = writeJar(expectedEcosystem + ".jar", manifest);
		Match match = ForeignModRecognizers.match(jar);

		assertNotNull(match, manifest + " 应当被认出来");
		assertEquals(expectedEcosystem, match.ecosystem());
		assertEquals(manifest, match.detail());
	}

	private Path writeJar(String name, String... entries) throws IOException {
		Path jar = tmp.resolve(name);

		try (OutputStream os = Files.newOutputStream(jar); ZipOutputStream zos = new ZipOutputStream(os)) {
			for (String entry : entries) {
				zos.putNextEntry(new ZipEntry(entry));
				zos.write(new byte[0]);
				zos.closeEntry();
			}
		}

		return jar;
	}
}
