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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.uniloader.api.Diagnostics;
import org.uniloader.api.DegradationPolicy;
import org.uniloader.api.Ecosystem;
import org.uniloader.api.ModCatalog;
import org.uniloader.api.ModRecord;
import org.uniloader.api.ModStatus;

/**
 * T0.5 的验收：{@code mods-report.txt} 要给出每个 mod 的 OK / DEGRADED / FAILED。
 */
class LoadReportTest {
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
	@DisplayName("三态都出现在报告里，且带原因与阶段")
	void rendersAllThreeStates() {
		ModCatalog.register(ModRecord.of("fabric-api", "Fabric API", "0.162.0", Ecosystem.FABRIC, "fabric-api.jar"));
		ModCatalog.register(ModRecord.of("qsl", "Quilt Standard Libraries", "1.0", Ecosystem.QUILT, "qsl.jar"));
		ModCatalog.register(ModRecord.of("jei", "JEI", "19.0", Ecosystem.NEOFORGE, "jei.jar"));

		DegradationPolicy.degradeForUnsupportedCapability("qsl", "the Quilt plugin system");
		DegradationPolicy.fail("jei", DegradationPolicy.Stage.INJECT,
				"mixin target not found", new IllegalStateException("no such method"));

		String rendered = LoadReport.render(true, ModCatalog.everything(), Diagnostics.timings(), Diagnostics.notes());

		assertTrue(rendered.contains("[OK] Fabric API"), rendered);
		assertTrue(rendered.contains("[DEGRADED] Quilt Standard Libraries"), rendered);
		assertTrue(rendered.contains("[FAILED] JEI"), rendered);
		assertTrue(rendered.contains("the Quilt plugin system"), "降级必须给出原因");
		assertTrue(rendered.contains("mixin target not found"), "失败必须给出原因");
		assertTrue(rendered.contains("inject"), "失败必须给出阶段");
		assertTrue(rendered.contains("不存在第四种"), "报告里要写明三态纪律");
	}

	@Test
	@DisplayName("注入失败一律 FAILED：半应用的 mixin 比不加载更危险")
	void injectFailureIsFatal() {
		ModCatalog.register(ModRecord.of("mod", "Mod", "1.0", Ecosystem.FORGE, "mod.jar"));

		DegradationPolicy.fail("mod", DegradationPolicy.Stage.INJECT, "mixin apply failed", new RuntimeException("boom"));

		assertTrue(ModCatalog.find("mod").orElseThrow().status() == ModStatus.FAILED);
	}

	@Test
	@DisplayName("生命周期阶段抛异常只算 DEGRADED：世界已经能进")
	void lifecycleFailureIsDegraded() {
		ModCatalog.register(ModRecord.of("mod", "Mod", "1.0", Ecosystem.FORGE, "mod.jar"));

		DegradationPolicy.fail("mod", DegradationPolicy.Stage.LIFECYCLE, "common setup threw", new RuntimeException("boom"));

		assertTrue(ModCatalog.find("mod").orElseThrow().status() == ModStatus.DEGRADED);
	}

	@Test
	@DisplayName("FAILED 是终态，不会被后来的降级覆盖")
	void failedIsTerminal() {
		ModCatalog.register(ModRecord.of("mod", "Mod", "1.0", Ecosystem.FORGE, "mod.jar"));

		DegradationPolicy.fail("mod", DegradationPolicy.Stage.REMAP, "remap failed", null);
		ModCatalog.update("mod", ModStatus.OK, "", "");

		assertTrue(ModCatalog.find("mod").orElseThrow().status() == ModStatus.FAILED);
	}

	@Test
	@DisplayName("失败但没有原因时，报告要指出这是 UniLoader 的 bug（纪律第 3 条：不许静默）")
	void failureWithoutReasonIsExplicit() {
		ModCatalog.register(ModRecord.of("mod", "Mod", "1.0", Ecosystem.FORGE, "mod.jar"));

		DegradationPolicy.fail("mod", DegradationPolicy.Stage.DISCOVERY, null, null);

		String detail = ModCatalog.find("mod").orElseThrow().detail();
		assertTrue(detail.contains("UniLoader bug"), detail);
	}

	@Test
	@DisplayName("全绿时不留下文件：文件的存在本身就是信号")
	void cleanBootWritesNoFile(@TempDir Path dir) throws IOException {
		ModCatalog.register(ModRecord.of("a", "A", "1.0", Ecosystem.FABRIC, "a.jar"));

		Path file = dir.resolve("mods-report.txt");
		Files.writeString(file, "stale content from a previous boot");

		LoadReport.writeTo(file, true);

		assertFalse(Files.exists(file), "全绿的一次启动必须把上一次的报告删掉");
	}

	@Test
	@DisplayName("有问题时写出文件，并包含耗时与备注")
	void writesFileWithTimingsAndNotes(@TempDir Path dir) throws IOException {
		ModCatalog.register(ModRecord.of("a", "A", "1.0", Ecosystem.FABRIC, "a.jar"));
		ModCatalog.update("a", ModStatus.DEGRADED, "something is missing", "instantiate");

		Diagnostics.begin("discovery");
		Diagnostics.end("discovery");
		Diagnostics.note("Quilt 插件系统不支持");

		Path file = dir.resolve("mods-report.txt");
		LoadReport.writeTo(file, true);

		assertTrue(Files.exists(file));

		String text = Files.readString(file);
		assertTrue(text.contains("[DEGRADED] A"), text);
		assertTrue(text.contains("discovery"), "报告要带阶段耗时（T1.6 的验收）");
		assertTrue(text.contains("Quilt 插件系统不支持"), text);
	}

	@Test
	@DisplayName("英文渲染同样成立（报告跟着系统语言走）")
	void rendersEnglish() {
		ModCatalog.register(ModRecord.of("a", "A", "1.0", Ecosystem.FABRIC, "a.jar"));
		ModCatalog.update("a", ModStatus.FAILED, "did not finish loading", "discovery");

		String rendered = LoadReport.render(false, ModCatalog.everything(), Map.of(), List.of());

		assertTrue(rendered.contains("UniLoader mod load report"), rendered);
		assertTrue(rendered.contains("[FAILED] A"), rendered);
	}
}
