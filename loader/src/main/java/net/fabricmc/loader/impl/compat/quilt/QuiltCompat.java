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

package net.fabricmc.loader.impl.compat.quilt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.version.VersionInterval;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import net.fabricmc.loader.impl.discovery.ModCandidateImpl;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

/**
 * UniLoader fork patch (T2.2): Quilt 语义里 Fabric 表达不出来的那几样，在这里补齐。
 *
 * <p>三件事，都在求解<b>之前</b>做，因为此时候选集已经齐了，可以问「谁在场」：
 * <ol>
 *   <li>{@code unless}：Quilt 的「除非 X 在场，否则依赖 Y」。解析期不知道 X 在不在，
 *       所以留到这儿按实际候选集裁剪。</li>
 *   <li>{@code provides} 带版本：Fabric 的 provides 只有 id，依赖判定会拿提供者的版本去比。
 *       QSL 声明「提供 fabric-api-base 0.4.42」而自己版本是 11.0.0-alpha.3，
 *       直接比对会让依赖 {@code fabric-api-base >=0.4.0 <1.0} 的 Fabric 模组误判失败。</li>
 *   <li>{@code load_type}：{@code if_possible} / {@code if_required} 的模组
 *       在求解失败时可以被丢掉（见 {@link #withoutOptionalQuiltMods}）。</li>
 * </ol>
 *
 * <p>刻意不改 SAT 求解器本身：它已经能处理 provides（按 id），
 * 这三样都是在「喂给求解器的输入」上做文章，改输入比改求解器的风险小一个数量级。
 */
public final class QuiltCompat {
	public static final LogCategory LOG_CATEGORY = LogCategory.createCustom("UniLoader", "Quilt");

	private QuiltCompat() {
	}

	/** 求解前的一次性预处理。 */
	public static void prepare(Collection<ModCandidateImpl> candidates) {
		if (candidates == null || candidates.isEmpty()) return;

		applyUnless(candidates);
		applyProvidedVersions(candidates);
	}

	// ------------------------------------------------------------------ unless

	/**
	 * 把「除非 X 在场，否则依赖 Y」翻译成 Fabric 能懂的形式：
	 * X 在场 → 这条依赖整条删掉；X 不在场 → 原样保留。
	 */
	public static void applyUnless(Collection<ModCandidateImpl> candidates) {
		Set<String> present = presentIds(candidates, true);

		for (ModCandidateImpl candidate : candidates) {
			if (!(candidate.getMetadata() instanceof QuiltModMetadata)) continue;

			QuiltModMetadata metadata = (QuiltModMetadata) candidate.getMetadata();
			Map<ModDependency, String> unlessTargets = metadata.getUnlessTargets();
			if (unlessTargets.isEmpty()) continue;

			List<ModDependency> kept = new ArrayList<>();

			for (ModDependency dep : metadata.getDependencies()) {
				String unlessId = unlessTargets.get(dep);

				if (unlessId != null && present.contains(unlessId)) {
					Log.debug(LOG_CATEGORY, "模组 %s 的依赖 %s 带 unless %s，而 %s 在场 → 这条依赖不适用",
							candidate.getId(), dep, unlessId, unlessId);
					continue;
				}

				kept.add(dep);
			}

			int before = metadata.getDependencies().size();
			if (kept.size() != before) metadata.setDependencies(kept);
		}
	}

	// ------------------------------------------------------- provides 带版本

	/**
	 * 把「依赖某个被提供出来的 id」的判定基准，从「提供者的版本」换成「被声明的提供版本」。
	 *
	 * <p>只处理真正的「被提供 id」——如果有个真实模组就叫这个名字，Fabric 原有的判定是对的，
	 * 一条都不动。
	 */
	public static void applyProvidedVersions(Collection<ModCandidateImpl> candidates) {
		Set<String> realIds = presentIds(candidates, false);
		Map<String, List<Version>> providedVersions = new HashMap<>();

		for (ModCandidateImpl candidate : candidates) {
			if (!(candidate.getMetadata() instanceof QuiltModMetadata)) continue;

			for (QuiltProvidedMod provided : ((QuiltModMetadata) candidate.getMetadata()).getProvidedMods()) {
				if (provided.version() == null) continue; // 没写版本 → 退化成用提供者的版本，不动

				List<Version> versions = providedVersions.get(provided.id());

				if (versions == null) providedVersions.put(provided.id(), versions = new ArrayList<>());

				if (!versions.contains(provided.version())) versions.add(provided.version());
			}
		}

		if (providedVersions.isEmpty()) return;

		for (ModCandidateImpl candidate : candidates) {
			Collection<ModDependency> deps = candidate.getMetadata().getDependencies();
			List<ModDependency> result = new ArrayList<>(deps.size());
			boolean changed = false;

			for (ModDependency dep : deps) {
				List<Version> versions = realIds.contains(dep.getModId()) ? null : providedVersions.get(dep.getModId());

				if (versions == null) {
					result.add(dep);
					continue;
				}

				result.add(new ProvidedVersionDependency(dep, versions));
				changed = true;

				Log.debug(LOG_CATEGORY, "模组 %s 依赖 %s，改按提供的版本 %s 判定（提供者的版本是 %s）",
						candidate.getId(), dep.getModId(), versions, candidate.getVersion());
			}

			if (changed) candidate.getMetadata().setDependencies(result);
		}
	}

	// ------------------------------------------------------------- load_type

	/**
	 * 去掉 {@code load_type} 不是 {@code always} 的 Quilt 模组。
	 *
	 * <p>只在首次求解失败后才会用到：Quilt 的 {@code if_possible} 就是「加载不了就算了」，
	 * 所以重试一次把它们摘掉是符合语义的。返回 null 表示「没有可摘的」，调用方别白重试。
	 */
	public static List<ModCandidateImpl> withoutOptionalQuiltMods(Collection<ModCandidateImpl> candidates) {
		List<ModCandidateImpl> dropped = null;
		List<ModCandidateImpl> kept = new ArrayList<>(candidates.size());

		for (ModCandidateImpl candidate : candidates) {
			if (candidate.getMetadata() instanceof QuiltModMetadata
					&& ((QuiltModMetadata) candidate.getMetadata()).getLoadType() != QuiltLoadType.ALWAYS) {
				if (dropped == null) dropped = new ArrayList<>();
				dropped.add(candidate);
			} else {
				kept.add(candidate);
			}
		}

		if (dropped == null) return null;

		StringBuilder names = new StringBuilder();

		for (ModCandidateImpl mod : dropped) {
			if (names.length() > 0) names.append(", ");
			names.append(mod.getId());
		}

		Log.warn(LOG_CATEGORY, "首次求解失败，已按 load_type 摘掉 %d 个「能加载就加载」的 Quilt 模组后重试：%s",
				dropped.size(), names);

		return kept;
	}

	/**
	 * @param includeProvided 是否把 provides 出来的 id 也算「在场」。
	 *        {@code unless} 要算（Quilt 里被 provides 出来的 id 一样是已加载），
	 *        {@code provides} 带版本的判定不能算（算了就永远走不到替换分支）。
	 */
	private static Set<String> presentIds(Collection<ModCandidateImpl> candidates, boolean includeProvided) {
		Set<String> ids = new HashSet<>();

		for (ModCandidateImpl candidate : candidates) {
			ids.add(candidate.getId());

			if (includeProvided) ids.addAll(candidate.getProvides());
		}

		return ids;
	}

	/**
	 * 一条依赖的「判定基准替换」包装：版本号不去比提供者的版本，而是比被声明的提供版本。
	 *
	 * <p>为什么这么做是对的：依赖写的是「我需要 fabric-api-base 至少 0.4.0」，
	 * 提供者声明「我提供的 fabric-api-base 是 0.4.42」——判定的输入就该是 0.4.42。
	 * 只要有一个提供者给出的版本满足，这条依赖就算满足。
	 */
	public static final class ProvidedVersionDependency implements ModDependency {
		private final ModDependency delegate;
		private final List<Version> providedVersions;

		public ProvidedVersionDependency(ModDependency delegate, List<Version> providedVersions) {
			this.delegate = delegate;
			this.providedVersions = Collections.unmodifiableList(new ArrayList<>(providedVersions));
		}

		@Override
		public Kind getKind() {
			return delegate.getKind();
		}

		@Override
		public String getModId() {
			return delegate.getModId();
		}

		@Override
		public boolean matches(Version version) {
			for (Version provided : providedVersions) {
				if (delegate.matches(provided)) return true;
			}

			return false;
		}

		@Override
		public Collection<VersionPredicate> getVersionRequirements() {
			return delegate.getVersionRequirements();
		}

		@Override
		public List<VersionInterval> getVersionIntervals() {
			return delegate.getVersionIntervals();
		}

		@Override
		public boolean equals(Object obj) {
			return delegate.equals(obj);
		}

		@Override
		public int hashCode() {
			return delegate.hashCode();
		}

		@Override
		public String toString() {
			return delegate + "@provided[" + providedVersions + "]";
		}
	}
}
