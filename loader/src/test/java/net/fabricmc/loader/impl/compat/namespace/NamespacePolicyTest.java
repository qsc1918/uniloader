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

package net.fabricmc.loader.impl.compat.namespace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.game.GameProvider;
import net.fabricmc.loader.impl.launch.MappingConfiguration;
import net.fabricmc.loader.impl.util.SystemProperties;

/**
 * UniLoader fork patch (T1.1)。
 *
 * <p>两类断言：
 * <ol>
 *   <li><b>纯函数表驱动</b>：给定「有没有 mappings × mappings 里有什么命名空间 × 有没有覆盖」，
 *       判定必须落在预期那一格；</li>
 *   <li><b>与 {@link MappingConfiguration} 对拍</b>：内核真正用起来的那个值，必须与策略算出来的
 *       一致。这是防漂移的关键——两处逻辑一旦各改一半，这里就会红。</li>
 * </ol>
 */
class NamespacePolicyTest {
	private static final List<String> FULL_NS = Arrays.asList("official", "intermediary", "named");
	private static final List<String> NO_NAMED_NS = Arrays.asList("official", "intermediary");

	@TempDir
	Path tmp;

	/** 被测试改过的系统属性，跑完必须还原，否则会串到别的测试上。 */
	private static final String[] TOUCHED = {
		SystemProperties.MAPPING_PATH,
		SystemProperties.RUNTIME_MAPPING_NAMESPACE,
		SystemProperties.DEFAULT_MOD_DISTRIBUTION_NAMESPACE,
		NamespacePolicy.PROPERTY_NAMESPACE,
		NamespacePolicy.PROPERTY_STRICT,
		NamespacePolicy.PROPERTY_REMAP_FABRIC_MODS
	};

	private String gameProviderNsAdjust = "identity";

	@BeforeEach
	void setUp() {
		NamespacePolicy.resetCache();

		for (String p : TOUCHED) {
			System.clearProperty(p);
		}

		gameProviderNsAdjust = "identity";
		installGameProvider();
	}

	@AfterEach
	void tearDown() {
		NamespacePolicy.resetCache();

		for (String p : TOUCHED) {
			System.clearProperty(p);
		}
	}

	/**
	 * 装一个「原样透传」的游戏提供者。
	 *
	 * <p>真实的 MinecraftGameProvider 也不改写命名空间（GameProvider 的默认实现就是 return defaultNs），
	 * 所以这里用透传是等价的；providerAdjust 的生效另有一个专门的用例。
	 */
	private void installGameProvider() {
		GameProvider provider = mock(GameProvider.class);
		when(provider.getLaunchDirectory()).thenReturn(tmp);
		when(provider.getRuntimeNamespace(anyString())).thenAnswer(invocation -> {
			String ns = invocation.getArgument(0);
			return "identity".equals(gameProviderNsAdjust) ? ns : gameProviderNsAdjust;
		});
		when(provider.getDefaultModDistributionNamespace(anyString())).thenAnswer(invocation -> invocation.getArgument(0));

		FabricLoaderImpl.INSTANCE.setGameProvider(provider);
	}

	// ---------------------------------------------------------------- 纯函数：自动判定

	@Test
	void unobfuscated_usesOfficialWithZeroRemap() {
		NamespacePolicy.Decision d = NamespacePolicy.decide(null, false, Collections.<String>emptyList(), null);

		assertEquals("official", d.runtimeNamespace());
		assertEquals("official", d.modDistributionNamespace());
		assertFalse(d.remapRequired(), "未混淆版本必须零重映射");
		assertTrue(d.zeroRemap());
		assertTrue(d.isMojmapRuntime(), "未混淆版本上的 official 就是 Mojang 真实名");
		assertFalse(d.explicit());
		assertFalse(d.reason().isEmpty(), "判定必须带原因");
	}

	@Test
	void obfuscated_appliesD1_namedRuntimeWithIntermediaryMods() {
		NamespacePolicy.Decision d = NamespacePolicy.decide(null, true, FULL_NS, null);

		assertEquals("named", d.runtimeNamespace(), "决策 D1：运行时统一到 mojmap");
		assertEquals("intermediary", d.modDistributionNamespace());
		assertTrue(d.remapRequired(), "named ≠ intermediary，必须重映射");
		assertTrue(d.isMojmapRuntime());
	}

	@Test
	void mappingsWithoutNamed_fallsBackToIntermediaryWithoutRemap() {
		NamespacePolicy.Decision d = NamespacePolicy.decide(null, true, NO_NAMED_NS, null);

		assertEquals("intermediary", d.runtimeNamespace());
		assertEquals("intermediary", d.modDistributionNamespace());
		assertFalse(d.remapRequired());
		assertFalse(d.isMojmapRuntime(), "intermediary 不是 Mojang 真实名，NeoForge 侧无法工作");
	}

	// ---------------------------------------------------------------- 纯函数：显式覆盖

	@Test
	void explicitOverride_winsOverAuto() {
		assertEquals("named", NamespacePolicy.decide("named", false, Collections.<String>emptyList(), null).runtimeNamespace());
		assertEquals("official", NamespacePolicy.decide("official", true, FULL_NS, null).runtimeNamespace());

		NamespacePolicy.Decision official = NamespacePolicy.decide("official", true, FULL_NS, null);
		assertFalse(official.remapRequired(), "显式 official → 两侧同名，不重映射");
		assertTrue(official.explicit());
	}

	@Test
	void explicitOverride_autoIsNotAnOverride() {
		assertFalse(NamespacePolicy.decide("auto", false, Collections.<String>emptyList(), null).explicit());
		assertFalse(NamespacePolicy.decide("", false, Collections.<String>emptyList(), null).explicit());
		assertFalse(NamespacePolicy.decide(null, false, Collections.<String>emptyList(), null).explicit());
	}

	@Test
	void illegalOverride_isRejectedInsteadOfGuessed() {
		assertThrows(IllegalArgumentException.class, () -> NamespacePolicy.requireValidNamespace("mojmap"));
		assertThrows(IllegalArgumentException.class, () -> NamespacePolicy.requireValidNamespace("named2"));

		System.setProperty(NamespacePolicy.PROPERTY_NAMESPACE, "srg");
		assertThrows(IllegalArgumentException.class, NamespacePolicy::readOverride);
	}

	@Test
	void validOverride_isNormalizedToLowerCase() {
		assertEquals("named", NamespacePolicy.requireValidNamespace(" NAMED "));
	}

	// ---------------------------------------------------------------- 游戏命名空间（门闸 G4）

	@Test
	@DisplayName("未混淆版本：游戏 jar 就是 official，与运行时同名 → G4 不触发反混淆")
	void gameNamespace_isOfficialOnUnobfuscatedGame() {
		// 26.3 走的就是这一支。它必须与 runtimeNamespace() 相等，否则 G4 会把游戏
		// 莫名其妙地重映射一遍——那是灾难性的，而且日志里看不出是谁改的。
		assertEquals("official", NamespacePolicy.gameNamespace(null, false, null, false, false));
		assertEquals("official", NamespacePolicy.gameNamespace(null, false, Collections.<String>emptyList(), true, true));

		NamespacePolicy.Decision runtime = NamespacePolicy.decide(null, false, Collections.<String>emptyList(), null);
		assertEquals(runtime.runtimeNamespace(),
				NamespacePolicy.gameNamespace(null, false, null, false, false),
				"26.3 上 gameNs 必须等于 runtimeNs");
	}

	@Test
	@DisplayName("显式 fabric.gameMappingNamespace 优先于一切")
	void gameNamespace_overrideWins() {
		assertEquals("named", NamespacePolicy.gameNamespace("named", false, null, false, false));
		assertEquals("intermediary", NamespacePolicy.gameNamespace("intermediary", true, FULL_NS, true, false));
	}

	@Test
	@DisplayName("有 mappings：开发环境且 mappings 里有 named → named")
	void gameNamespace_developmentPrefersNamed() {
		assertEquals("named", NamespacePolicy.gameNamespace(null, true, FULL_NS, true, false));
		assertEquals("named", NamespacePolicy.gameNamespace(null, true, FULL_NS, true, true));
	}

	@Test
	@DisplayName("有 mappings：非开发环境 → official（不跟 runtime 的 named 走）")
	void gameNamespace_productionStaysOfficial() {
		assertEquals("official", NamespacePolicy.gameNamespace(null, true, FULL_NS, false, false));
	}

	@Test
	@DisplayName("老 MC：mappings 里没有 official 时按 client/server 分开")
	void gameNamespace_splitsClientAndServerForOldMc() {
		List<String> noOfficial = Arrays.asList("intermediary", "named");

		assertEquals("clientOfficial", NamespacePolicy.gameNamespace(null, true, noOfficial, false, true));
		assertEquals("serverOfficial", NamespacePolicy.gameNamespace(null, true, noOfficial, false, false));
	}

	@Test
	@DisplayName("namespaces 读不到时不做任何判断，留在 official（照抄上游的保守行为）")
	void gameNamespace_nullNamespacesStayOfficial() {
		assertEquals("official", NamespacePolicy.gameNamespace(null, true, null, false, false));
		assertEquals("official", NamespacePolicy.gameNamespace(null, true, null, true, true));
	}

	// ---------------------------------------------------------------- providerAdjust

	@Test
	void providerAdjust_isAppliedLast() {
		NamespacePolicy.Decision d = NamespacePolicy.decide(null, false, Collections.<String>emptyList(), ns -> "clientOfficial");

		assertEquals("clientOfficial", d.runtimeNamespace());
		assertEquals("intermediary", d.modDistributionNamespace(), "非 official 的运行时 → mod 分发为 intermediary");
	}

	// ---------------------------------------------------------------- 与 MappingConfiguration 对拍

	@Test
	void mappingConfiguration_matchesPolicy_whenNoMappings() {
		System.setProperty(SystemProperties.MAPPING_PATH, tmp.resolve("does-not-exist.tiny").toString());

		MappingConfiguration config = new MappingConfiguration();
		NamespacePolicy.Decision policy = NamespacePolicy.current(config);

		assertFalse(config.hasAnyMappings(), "前置条件：这个用例模拟的是未混淆版本");
		assertEquals("official", config.getRuntimeNamespace());
		assertEquals(policy.runtimeNamespace(), config.getRuntimeNamespace(), "内核与策略必须对拍一致");
		assertEquals("official", config.getDefaultModDistributionNamespace());
		assertEquals(policy.modDistributionNamespace(), config.getDefaultModDistributionNamespace());
		assertTrue(policy.zeroRemap());
	}

	@Test
	void mappingConfiguration_matchesPolicy_whenMappingsPresent() throws IOException {
		System.setProperty(SystemProperties.MAPPING_PATH, writeTinyMappings().toString());

		MappingConfiguration config = new MappingConfiguration();
		NamespacePolicy.Decision policy = NamespacePolicy.current(config);

		assertTrue(config.hasAnyMappings(), "前置条件：这个用例模拟的是混淆版本");
		// 决策 D1 与上游的唯一分歧点：上游是 dev ? named : intermediary，UL 一律 named。
		assertEquals("named", config.getRuntimeNamespace());
		assertEquals(policy.runtimeNamespace(), config.getRuntimeNamespace());
		assertEquals("intermediary", config.getDefaultModDistributionNamespace());
		assertEquals(policy.modDistributionNamespace(), config.getDefaultModDistributionNamespace());
	}

	@Test
	void mappingConfiguration_runtimeNamespacePropertyIsAnEscapeHatchAbovePolicy() throws IOException {
		System.setProperty(SystemProperties.MAPPING_PATH, writeTinyMappings().toString());
		System.setProperty(SystemProperties.RUNTIME_MAPPING_NAMESPACE, "intermediary");

		MappingConfiguration config = new MappingConfiguration();

		assertEquals("intermediary", config.getRuntimeNamespace(),
				"fabric.runtimeMappingNamespace 优先于一切，这是调试用的逃生舱，不能被策略覆盖掉");

		// 策略的判定必须与内核实际用的值一致：否则日志会打出 runtime=named、remap=true，
		// 而内核其实跑在 intermediary 上、一次重映射都没做——诊断说假话比没有诊断更糟。
		NamespacePolicy.Decision policy = NamespacePolicy.current(config);

		assertEquals("intermediary", policy.runtimeNamespace(), "策略必须把逃生舱算进来");
		assertEquals("intermediary", policy.modDistributionNamespace());
		assertFalse(policy.remapRequired(), "两侧同名时不该声称需要重映射");
		assertTrue(policy.explicit(), "逃生舱属于「显式指定」，reason 里要说清楚是谁指定的");
	}

	@Test
	void mappingConfiguration_modDistributionPropertyAlsoWins() throws IOException {
		System.setProperty(SystemProperties.MAPPING_PATH, writeTinyMappings().toString());
		System.setProperty(SystemProperties.DEFAULT_MOD_DISTRIBUTION_NAMESPACE, "srg");

		MappingConfiguration config = new MappingConfiguration();

		assertEquals("srg", config.getDefaultModDistributionNamespace());
	}

	// ---------------------------------------------------------------- remap 开关（G1）

	@Test
	void remapFabricMods_followsTheDecisionAndCanBeSwitchedOff() {
		NamespacePolicy.Decision needsRemap = NamespacePolicy.decide(null, true, FULL_NS, null);
		NamespacePolicy.Decision zeroRemap = NamespacePolicy.decide(null, false, Collections.<String>emptyList(), null);

		assertTrue(NamespacePolicy.remapFabricMods(needsRemap), "G1：混淆版本上生产也要 remap");
		assertFalse(NamespacePolicy.remapFabricMods(zeroRemap), "26.3 上零重映射，不能凭空打开重映射通道");

		System.setProperty(NamespacePolicy.PROPERTY_REMAP_FABRIC_MODS, "false");
		assertFalse(NamespacePolicy.remapFabricMods(needsRemap), "总开关必须能关掉");
	}

	// ---------------------------------------------------------------- 严格模式与生态兼容

	@Test
	void strictIsOnByDefaultAndLenientIsOptIn() {
		assertTrue(NamespacePolicy.strict());

		System.setProperty(NamespacePolicy.PROPERTY_STRICT, "lenient");
		assertFalse(NamespacePolicy.strict());
	}

	@Test
	void neoforgeNeedsMojmapRuntime() {
		NamespacePolicy.Decision unobfuscated = NamespacePolicy.decide(null, false, Collections.<String>emptyList(), null);
		NamespacePolicy.Decision obfMojmap = NamespacePolicy.decide(null, true, FULL_NS, null);
		NamespacePolicy.Decision obfIntermediary = NamespacePolicy.decide(null, true, NO_NAMED_NS, null);

		assertTrue(NamespacePolicy.isCompatible("neoforge", unobfuscated));
		assertTrue(NamespacePolicy.isCompatible("forge", unobfuscated));
		assertTrue(NamespacePolicy.isCompatible("neoforge", obfMojmap));
		assertFalse(NamespacePolicy.isCompatible("neoforge", obfIntermediary));
		assertTrue(NamespacePolicy.isCompatible("fabric", obfIntermediary), "Fabric 侧恒兼容：分发命名空间就是为它算的");
	}

	@Test
	void mismatchIsReportedAndNeverSilent() {
		NamespacePolicy.Decision obfIntermediary = NamespacePolicy.decide(null, true, NO_NAMED_NS, null);

		assertEquals(NamespacePolicy.MismatchAction.DEGRADE,
				NamespacePolicy.reportMismatch("examplemod", "neoforge", obfIntermediary),
				"strict 下必须落成 DEGRADED，绝不静默跳过");

		System.setProperty(NamespacePolicy.PROPERTY_STRICT, "lenient");
		assertEquals(NamespacePolicy.MismatchAction.WARN,
				NamespacePolicy.reportMismatch("examplemod", "neoforge", obfIntermediary));
	}

	@Test
	void describeIsNeverEmpty() {
		NamespacePolicy.Decision d = NamespacePolicy.decide(null, false, Collections.<String>emptyList(), null);

		assertNotNull(d.describe());
		assertTrue(d.describe().contains("runtime=official"));
	}

	/** 写一份最小可用的 tiny v2 映射文件，让 MappingConfiguration 真的读到东西。 */
	private Path writeTinyMappings() throws IOException {
		Path file = tmp.resolve("mappings.tiny");
		// tiny v2：头行给出 <源命名空间> <目标命名空间...>，其后每行一个类。
		// 这一份只为了证明「有 mappings」与「mappings 里有哪些命名空间」，不需要成员。
		String content = "tiny\t2\t0\tofficial\tintermediary\tnamed\n"
				+ "c\taaa\tnet/minecraft/class_1\tnet/minecraft/server/level/ServerPlayer\n";
		Files.write(file, content.getBytes(StandardCharsets.UTF_8));
		return file;
	}
}
