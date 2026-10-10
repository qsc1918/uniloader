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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.impl.compat.quilt.QuiltCompat;
import net.fabricmc.loader.impl.compat.quilt.QuiltLoadType;
import net.fabricmc.loader.impl.compat.quilt.QuiltModMetadata;
import net.fabricmc.loader.impl.compat.quilt.QuiltModMetadataReader;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;

/**
 * T2.2: the Quilt semantics Fabric cannot express (versioned provides / unless / load_type).
 *
 * <p>测试数据照抄真实 QSL 的形状：提供者自己 11.0.0-alpha.3，
 * 声明「我提供 fabric-api-base 0.4.42」——这正是让「只装 QSL 不装 fabric-api」
 * 能成立的关键，也是不做这一步就一定会误判失败的地方。
 */
class QuiltCompatTest {
	// ------------------------------------------------- provides 带版本

	@Test
	void dependencyOnProvidedVersionIsJudgedByTheProvidedVersion() throws Exception {
		// QSL：提供 fabric-api-base 0.4.42，自己却是 11.0.0-alpha.3
		ModCandidateImpl provider = candidate(mod("qsl-base-mod", "11.0.0-alpha.3", "",
				"{\"id\": \"fabric-api-base\", \"version\": \"0.4.42\"}"));

		// 一个依赖 fabric-api-base >=0.4.0 <1.0 的 Fabric 模组
		ModCandidateImpl consumer = candidate(mod("consumer", "1.0.0", "", ""));
		setDependency(consumer, "fabric-api-base", Arrays.asList(">=0.4.0 <1.0"));

		List<ModCandidateImpl> candidates = new ArrayList<>(Arrays.asList(provider, consumer));
		ModDependency before = firstDependency(consumer, "fabric-api-base");

		// 未处理前：拿提供者的版本（11.0.0）去比，区间不匹配 → false
		assertFalse(before.matches(provider.getVersion()), "前置条件：直接比提供者版本应当不匹配");

		QuiltCompat.prepare(candidates);

		ModDependency after = firstDependency(consumer, "fabric-api-base");
		assertTrue(after.matches(provider.getVersion()),
				"声明提供的版本是 0.4.42，落在 >=0.4.0 <1.0 里，所以这条依赖应当判定为满足");
	}

	@Test
	void providedVersionOutsideRangeStillFails() throws Exception {
		ModCandidateImpl provider = candidate(mod("provider", "11.0.0", "",
				"{\"id\": \"fabric-api-base\", \"version\": \"3.0.0\"}"));
		ModCandidateImpl consumer = candidate(mod("consumer", "1.0.0", "", ""));
		setDependency(consumer, "fabric-api-base", Arrays.asList(">=0.4.0 <1.0"));

		QuiltCompat.prepare(new ArrayList<>(Arrays.asList(provider, consumer)));

		assertFalse(firstDependency(consumer, "fabric-api-base").matches(provider.getVersion()),
				"提供的版本 3.0.0 不在区间里，不能因为「有模组提供了它」就算满足");
	}

	@Test
	void realModIdIsLeftAlone() throws Exception {
		// 真有一个就叫 fabric-api-base 的模组 → 走 Fabric 原有判定，一条都不动
		ModCandidateImpl real = candidate(mod("fabric-api-base", "0.4.42", "", ""));
		ModCandidateImpl consumer = candidate(mod("consumer", "1.0.0", "", ""));
		setDependency(consumer, "fabric-api-base", Arrays.asList(">=0.4.0 <1.0"));

		QuiltCompat.prepare(new ArrayList<>(Arrays.asList(real, consumer)));

		ModDependency dep = firstDependency(consumer, "fabric-api-base");
		assertFalse(dep instanceof QuiltCompat.ProvidedVersionDependency, "真实模组在场时不该改写依赖");
		assertTrue(dep.matches(real.getVersion()));
	}

	// ---------------------------------------------------------- unless

	@Test
	void unlessDependencyIsDroppedWhenTargetIsPresent() throws Exception {
		ModCandidateImpl other = candidate(mod("other-mod", "1.0.0", "", ""));
		ModCandidateImpl dependent = candidate(modWithUnless("needy", "legacy-mod", "other-mod"));

		List<ModCandidateImpl> candidates = new ArrayList<>(Arrays.asList(other, dependent));
		assertEquals(1, dependent.getMetadata().getDependencies().size(), "前置条件：原本有一条依赖");

		QuiltCompat.prepare(candidates);

		assertTrue(dependent.getMetadata().getDependencies().isEmpty(),
				"other-mod 在场 → 「除非 other-mod 否则依赖 legacy-mod」这条不适用");
	}

	@Test
	void unlessDependencyIsKeptWhenTargetIsAbsent() throws Exception {
		ModCandidateImpl dependent = candidate(modWithUnless("needy", "legacy-mod", "other-mod"));

		QuiltCompat.prepare(new ArrayList<>(Collections.singletonList(dependent)));

		assertEquals(1, dependent.getMetadata().getDependencies().size(),
				"other-mod 不在场 → 这条依赖仍然要成立");
	}

	// ------------------------------------------------------- load_type

	@Test
	void optionalQuiltModsCanBeDropped() throws Exception {
		ModCandidateImpl always = candidate(mod("always-mod", "1.0.0", "\"load_type\": \"always\"", ""));
		ModCandidateImpl possible = candidate(mod("possible-mod", "1.0.0", "\"load_type\": \"if_possible\"", ""));
		ModCandidateImpl required = candidate(mod("required-mod", "1.0.0", "\"load_type\": \"if_required\"", ""));

		List<ModCandidateImpl> candidates = new ArrayList<>(Arrays.asList(always, possible, required));
		List<ModCandidateImpl> reduced = QuiltCompat.withoutOptionalQuiltMods(candidates);

		assertNotNull(reduced);
		assertEquals(1, reduced.size());
		assertEquals("always-mod", reduced.get(0).getId());
	}

	@Test
	void nothingToDropReturnsNull() throws Exception {
		List<ModCandidateImpl> candidates = new ArrayList<>();
		candidates.add(candidate(mod("a", "1.0.0", "\"load_type\": \"always\"", "")));

		assertNull(QuiltCompat.withoutOptionalQuiltMods(candidates), "没有可选模组时别让调用方白重试一次");
	}

	@Test
	void missingLoadTypeDefaultsToAlways() throws Exception {
		QuiltModMetadata m = (QuiltModMetadata) mod("no-load-type", "1.0.0", "", "");
		assertEquals(QuiltLoadType.ALWAYS, m.getLoadType());
	}

	// ---------------------------------------------------------- 工具

	private static ModCandidateImpl candidate(LoaderModMetadata metadata) {
		return ModCandidateImpl.createPlain(Collections.singletonList(Paths.get(metadata.getId() + ".jar")), metadata, false, Collections.emptyList());
	}

	/** Builds a quilt.mod.json metadata. {@code provided} looks like {"id": "...", "version": "..."}. */
	private static LoaderModMetadata mod(String id, String version, String extraFields, String provided) throws Exception {
		String provides = provided.isEmpty() ? "" : "\"provides\": [" + provided + "],";
		String json = "{\"schema_version\": 1, \"quilt_loader\": {"
				+ "\"id\": \"" + id + "\", \"group\": \"test\", \"version\": \"" + version + "\","
				+ provides
				+ (extraFields.isEmpty() ? "" : extraFields + ",")
				+ "\"entrypoints\": {}}}";

		return parse(json);
	}

	private static LoaderModMetadata modWithUnless(String id, String dependsOn, String unlessId) throws Exception {
		String json = "{\"schema_version\": 1, \"quilt_loader\": {"
				+ "\"id\": \"" + id + "\", \"group\": \"test\", \"version\": \"1.0.0\","
				+ "\"depends\": [{\"id\": \"" + dependsOn + "\", \"versions\": \"*\", \"unless\": {\"id\": \"" + unlessId + "\"}}]}}";

		return parse(json);
	}

	private static LoaderModMetadata parse(String json) throws Exception {
		try (InputStream is = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))) {
			return QuiltModMetadataReader.read(is, "<test>");
		}
	}

	private static void setDependency(ModCandidateImpl candidate, String id, List<String> versions) throws Exception {
		net.fabricmc.loader.impl.metadata.ModDependencyImpl dep =
				new net.fabricmc.loader.impl.metadata.ModDependencyImpl(ModDependency.Kind.DEPENDS, id, versions);
		candidate.getMetadata().setDependencies(Collections.<ModDependency>singletonList(dep));
	}

	private static ModDependency firstDependency(ModCandidateImpl candidate, String id) {
		Collection<ModDependency> deps = candidate.getMetadata().getDependencies();

		for (ModDependency dep : deps) {
			if (dep.getModId().equals(id)) return dep;
		}

		throw new AssertionError("没有对 " + id + " 的依赖，现有: " + deps);
	}
}
