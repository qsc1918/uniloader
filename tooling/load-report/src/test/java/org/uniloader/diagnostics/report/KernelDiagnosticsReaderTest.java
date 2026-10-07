/*
 * Copyright 2026 The UniLoader Project
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

package org.uniloader.diagnostics.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.uniloader.api.Diagnostics;
import org.uniloader.api.Ecosystem;
import org.uniloader.api.ModCatalog;
import org.uniloader.api.ModRecord;
import org.uniloader.api.ModStatus;

/**
 * T1.6 的验收：内核落下的证据，能被读成「每个 mod 的三态 + 各阶段耗时 + 原因」。
 */
class KernelDiagnosticsReaderTest {
	@TempDir
	Path dir;

	@BeforeEach
	void setUp() {
		ModCatalog.reset();
		Diagnostics.reset();
		LoadReport.reset();
	}

	@AfterEach
	void tearDown() {
		ModCatalog.reset();
		Diagnostics.reset();
		LoadReport.reset();
	}

	@Test
	@DisplayName("一份完整的证据能被读成登记表里的三态，并带阶段与原因")
	void readsAllThreeStates() throws IOException {
		Path file = write(
				KernelDiagnosticsReader.FORMAT_VERSION,
				"timing\tdiscovery\t12",
				"timing\tresolve\t7",
				"timing\tentrypoint/main/sodium\t3",
				"mod\tfabric-api\tFabric API\t0.162.0\tfabric\tfabric-api.jar",
				"mod\tsodium\tSodium\t0.9.3\tfabric\tsodium.jar",
				"mod\tjei\tJEI\t19.0\tneoforge\tjei.jar",
				"status\tsodium\tDEGRADED\tinstantiate\t入口点 'main' 的 'a.b.C' 抛异常",
				"status\tjei\tFAILED\tremap\tmixin target not found",
				"note\t本次启动运行在 official 命名空间");

		assertEquals(3, KernelDiagnosticsReader.read(file));

		ModRecord api = ModCatalog.find("fabric-api").orElseThrow();
		assertEquals(ModStatus.OK, api.status());
		assertEquals(Ecosystem.FABRIC, api.ecosystem());
		assertEquals("fabric-api.jar", api.jar());

		ModRecord sodium = ModCatalog.find("sodium").orElseThrow();
		assertEquals(ModStatus.DEGRADED, sodium.status());
		assertEquals("instantiate", sodium.failedStage());
		assertTrue(sodium.detail().contains("抛异常"), sodium.detail());

		ModRecord jei = ModCatalog.find("jei").orElseThrow();
		assertEquals(ModStatus.FAILED, jei.status());
		assertEquals("remap", jei.failedStage());
		assertEquals(Ecosystem.NEOFORGE, jei.ecosystem());

		assertEquals(12L, Diagnostics.timings().get("discovery"));
		assertEquals(3L, Diagnostics.timings().get("entrypoint/main/sodium"));
		assertTrue(Diagnostics.notes().contains("本次启动运行在 official 命名空间"));

		String rendered = LoadReport.render(true, ModCatalog.everything(), Diagnostics.timings(), Diagnostics.notes());
		assertTrue(rendered.contains("[OK] Fabric API"), rendered);
		assertTrue(rendered.contains("[DEGRADED] Sodium"), rendered);
		assertTrue(rendered.contains("[FAILED] JEI"), rendered);
		assertTrue(rendered.contains("discovery"), "报告要列出各阶段耗时（T1.6 的验收）");
	}

	@Test
	@DisplayName("状态和模组谁先出现都不影响结果：顺序不能让失败凭空消失")
	void statusBeforeModIsStillApplied() throws IOException {
		Path file = write(
				KernelDiagnosticsReader.FORMAT_VERSION,
				"status\tghost\tFAILED\tdiscovery\t先到",
				"mod\tghost\tGhost\t1.0\tfabric\tghost.jar");

		KernelDiagnosticsReader.read(file);

		ModRecord ghost = ModCatalog.find("ghost").orElseThrow();
		assertEquals(ModStatus.FAILED, ghost.status());
		assertEquals("先到", ghost.detail());
	}

	@Test
	@DisplayName("表里没有的模组绝不凭空造一行，但要留下备注")
	void unknownModIsNotInvented() throws IOException {
		Path file = write(
				KernelDiagnosticsReader.FORMAT_VERSION,
				"status\tnot-registered\tFAILED\tlifecycle\t没人登记过它");

		KernelDiagnosticsReader.read(file);

		assertTrue(ModCatalog.find("not-registered").isEmpty(), "登记表不能凭空多出一行");
		assertTrue(Diagnostics.notes().stream().anyMatch(n -> n.contains("not-registered")),
				"但也不能当没发生过，必须留备注");
	}

	@Test
	@DisplayName("转义是往返对称的：值里的制表符与换行不能打乱格式")
	void escapingIsSymmetric() throws IOException {
		Path file = write(
				KernelDiagnosticsReader.FORMAT_VERSION,
				"mod\tweird\tWe\\tird\t1.0\tfabric\tweird.jar",
				"status\tweird\tFAILED\tinstantiate\tline1\\nline2\\twith tab");

		KernelDiagnosticsReader.read(file);

		ModRecord weird = ModCatalog.find("weird").orElseThrow();
		assertEquals("We\tird", weird.name());
		assertEquals("line1\nline2\twith tab", weird.detail());
	}

	@Test
	@DisplayName("读不懂的行不静默丢弃")
	void unparseableLinesAreNotSilent() throws IOException {
		Path file = write(
				KernelDiagnosticsReader.FORMAT_VERSION,
				"timing\tdiscovery\tnot-a-number",
				"wat\tsomething");

		KernelDiagnosticsReader.read(file);

		List<String> notes = Diagnostics.notes();
		assertTrue(notes.stream().anyMatch(n -> n.contains("not-a-number")), notes.toString());
		assertTrue(notes.stream().anyMatch(n -> n.contains("wat")), notes.toString());
	}

	@Test
	@DisplayName("格式版本不对必须报错，不能硬读")
	void unknownFormatVersionFails() throws IOException {
		Path file = write("something-else", "mod\ta\tA\t1.0\tfabric\ta.jar");

		assertThrows(IOException.class, () -> KernelDiagnosticsReader.read(file));
	}

	private Path write(String... lines) throws IOException {
		Path file = dir.resolve(KernelDiagnosticsReader.FILE_NAME);
		Files.write(file, String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
		return file;
	}
}
