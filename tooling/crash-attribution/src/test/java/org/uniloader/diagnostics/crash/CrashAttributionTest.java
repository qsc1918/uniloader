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

package org.uniloader.diagnostics.crash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.uniloader.api.Ecosystem;
import org.uniloader.api.ModCatalog;
import org.uniloader.api.ModRecord;

/**
 * T0.5 的验收：故意放一个会崩的 mod，报告要能正确指认。
 *
 * <p>这里用真实的崩溃报告文本形态（帧尾的 {@code ~[xxx.jar:?]}、mixin 处理器帧、
 * 冲突消息）来测，因为整个归因逻辑就建立在「Minecraft 打印出来的样子」上。
 */
class CrashAttributionTest {
	@BeforeEach
	void setUp() {
		ModCatalog.reset();
	}

	@AfterEach
	void tearDown() {
		ModCatalog.reset();
		CrashAttribution.setOverlapSource(null);
	}

	private static String crashReport(String... frames) {
		StringBuilder sb = new StringBuilder();
		sb.append("---- Minecraft Crash Report ----\n");
		sb.append("Time: 2026-10-07 12:00:00\n");
		sb.append("Description: Ticking entity\n\n");
		sb.append("java.lang.NullPointerException: Cannot invoke \"Object.toString()\" because \"x\" is null\n");
		sb.append("\tat net.minecraft.world.entity.LivingEntity.tick(LivingEntity.java:1)\n");

		for (String frame : frames) sb.append(frame).append('\n');

		sb.append("\nA detailed walkthrough of the error, its code path and all known details is as follows:\n");
		sb.append("---------------------------------------------------------------------------------------\n");
		sb.append("\t-- System Details --\n");
		sb.append("\t\tMinecraft Version: 26.3\n");
		sb.append("\t\tMods: sodium-26.3.jar, other-1.0.jar\n");
		return sb.toString();
	}

	@Test
	@DisplayName("栈帧里的 jar 名能反查到 mod，并且只认登记表里有的 id")
	void attributesByFrameJar() {
		ModCatalog.register(ModRecord.of("sodium", "Sodium", "0.6.0", Ecosystem.FABRIC, "sodium-26.3.jar"));

		List<CrashAttribution.Suspect> suspects = CrashAttribution.suspects(crashReport(
				"\tat net.caffeinemc.mods.sodium.client.SodiumClientMod.tick(SodiumClientMod.java:42) ~[sodium-26.3.jar:?]"));

		assertEquals(1, suspects.size(), "应该只指认一个 mod");
		assertEquals("sodium", suspects.get(0).modId());
		assertEquals("sodium-26.3.jar", suspects.get(0).jar(), "要给出玩家能直接拿掉的 jar");
		assertEquals(CrashAttribution.CODE_IN_CRASH, suspects.get(0).reason());
	}

	@Test
	@DisplayName("没登记的 jar 不会被指认——绝不从文件名猜 mod id")
	void unknownJarIsNotBlamed() {
		ModCatalog.register(ModRecord.of("sodium", "Sodium", "0.6.0", Ecosystem.FABRIC, "sodium-26.3.jar"));

		List<CrashAttribution.Suspect> suspects = CrashAttribution.suspects(crashReport(
				"\tat some.unknown.Thing.tick(Thing.java:1) ~[xaeroworldmap-forge-26.2-1.46.1.jar:?]"));

		assertTrue(suspects.isEmpty(), "表里没有的 jar 不能变成嫌疑：confidently wrong 比没有答案更糟");
	}

	@Test
	@DisplayName("mixin 处理器帧优先于 jar 括号——否则会把 mod 的 mixin 赖到 Minecraft 头上")
	void mixinHandlerBeatsJarBracket() {
		ModCatalog.register(ModRecord.of("carpet", "Carpet", "1.4.0", Ecosystem.FABRIC, "carpet-26.3.jar"));

		List<CrashAttribution.Suspect> suspects = CrashAttribution.suspects(crashReport(
				"\tat net.minecraft.world.entity.LivingEntity.handler$chm000$carpet$onTick(LivingEntity.java:1) ~[minecraft-26.3.jar:?]"));

		assertEquals(1, suspects.size());
		assertEquals("carpet", suspects.get(0).modId());
		assertEquals(CrashAttribution.MIXIN_RUNNING, suspects.get(0).reason());
	}

	@Test
	@DisplayName("报错点名冲突双方时，两边都要出现在报告里，且给出「先只留一个」的建议")
	void clashNamesBothSides() {
		ModCatalog.register(ModRecord.of("chloride", "Chloride", "1.0", Ecosystem.FABRIC, "chloride.jar"));
		ModCatalog.register(ModRecord.of("cwb", "CWB", "1.0", Ecosystem.FABRIC, "cwb.jar"));

		List<CrashAttribution.Suspect> suspects = CrashAttribution.suspects(crashReport(
				"\tjava.lang.IllegalStateException: Multiple overrides for option 'sodium:general.fullscreen_mode'! Sources: chloride and cwb"));

		assertEquals(2, suspects.size());
		assertTrue(suspects.stream().allMatch(s -> CrashAttribution.CLASH.equals(s.reason())));

		String rendered = CrashAttribution.render(true, "crash-2026-10-07.txt", suspects);
		assertTrue(rendered.contains("Chloride"), rendered);
		assertTrue(rendered.contains("CWB"), rendered);
		assertTrue(rendered.contains("不能装在一起"), rendered);

		List<String> disabled = CrashAttribution.startWithout(suspects);
		assertEquals(1, disabled.size(), "冲突时只建议关掉其中一个，不是两个都关");
	}

	@Test
	@DisplayName("崩溃报告里的分隔符以下（系统信息）不参与归因")
	void ignoresSystemDetailsSection() {
		ModCatalog.register(ModRecord.of("sodium", "Sodium", "0.6.0", Ecosystem.FABRIC, "sodium-26.3.jar"));

		String report = crashReport("\tat java.lang.Object.toString(Object.java:1)");
		String chain = CrashAttribution.exceptionChain(report);

		assertFalse(chain.contains("Mods: sodium-26.3.jar"), "系统信息段不应被读进异常链");
		assertTrue(CrashAttribution.suspects(report).isEmpty());
	}

	@Test
	@DisplayName("没有可指认的 mod 时，报告要明说「可能不是 mod 的问题」而不是沉默")
	void rendersHonestlyWhenNothingFound() {
		String rendered = CrashAttribution.render(true, "crash-x.txt", List.of());

		assertTrue(rendered.contains("说不准是哪个 mod 的问题"), rendered);
		assertTrue(rendered.contains("crash-x.txt"), rendered);
	}
}
