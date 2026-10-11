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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.ContactInformation;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import net.fabricmc.loader.api.metadata.Person;
import net.fabricmc.loader.impl.metadata.AbstractModMetadata;
import net.fabricmc.loader.impl.metadata.ContactInformationImpl;
import net.fabricmc.loader.impl.metadata.EntrypointMetadata;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.fabricmc.loader.impl.metadata.NestedJarEntry;

/**
 * UniLoader fork patch (T2.1): {@code quilt.mod.json} 解析后的产物。
 *
 * <p>刻意实现 Fabric 内部的 {@link LoaderModMetadata} 而不是另起一套「统一元数据」：
 * 求解器、mixin 引导、入口点调用、诊断全都只认这一个接口，让它当 Quilt 模组的载体，
 * 后面所有环节都不用改。{@code getType()} 返回 {@code "quilt"}，
 * 报告里才能区分这是别家生态的模组。
 *
 * <p>Fabric 侧没有对应物的三样东西在这里单独留字段：
 * {@code group}（Quilt 必填）、{@code load_type}、{@code provides} 的版本号。
 */
public final class QuiltModMetadata extends AbstractModMetadata implements LoaderModMetadata {
	public static final String TYPE_QUILT = "quilt";

	private final int schemaVersion;
	private final String id;
	private final String group;
	private Version version;
	private final String name;
	private final String description;
	private final Collection<Person> authors;
	private final Collection<Person> contributors;
	private final ContactInformation contact;
	private final Collection<String> license;
	private final String icon;
	private final List<QuiltProvidedMod> provides;
	private final QuiltLoadType loadType;
	private final ModEnvironment environment;
	private final String intermediateMappings;
	private final Collection<NestedJarEntry> jars;
	private final Collection<String> mixinConfigs;
	private final String classTweaker;
	private final Map<String, List<EntrypointMetadata>> entrypoints;
	private final Map<ModDependency, String> unlessTargets;

	private Collection<ModDependency> dependencies;

	public QuiltModMetadata(int schemaVersion, String id, String group, Version version, String name, String description,
			Collection<Person> authors, Collection<Person> contributors, ContactInformation contact,
			Collection<String> license, String icon, List<QuiltProvidedMod> provides, QuiltLoadType loadType,
			ModEnvironment environment, String intermediateMappings, Collection<NestedJarEntry> jars, Collection<String> mixinConfigs,
			String classTweaker, Map<String, List<EntrypointMetadata>> entrypoints,
			Collection<ModDependency> dependencies, Map<ModDependency, String> unlessTargets) {
		this.schemaVersion = schemaVersion;
		this.id = id;
		this.group = group;
		this.version = version;
		this.name = name;
		this.description = description;
		this.authors = authors;
		this.contributors = contributors;
		this.contact = contact;
		this.license = license;
		this.icon = icon;
		this.provides = provides;
		this.loadType = loadType;
		this.environment = environment != null ? environment : ModEnvironment.UNIVERSAL;
		this.intermediateMappings = intermediateMappings;
		this.jars = jars;
		this.mixinConfigs = mixinConfigs;
		this.classTweaker = classTweaker;
		this.entrypoints = entrypoints;
		this.dependencies = dependencies;
		this.unlessTargets = unlessTargets;
	}

	// ---------------------------------------------------------------- metadata

	@Override
	public String getType() {
		return TYPE_QUILT;
	}

	@Override
	public String getId() {
		return id;
	}

	/** Quilt 的 maven 组，Fabric 侧没有对应物。 */
	public String getGroup() {
		return group;
	}

	@Override
	public Collection<String> getProvides() {
		List<String> ret = new ArrayList<>(provides.size());

		for (QuiltProvidedMod provided : provides) {
			ret.add(provided.id());
		}

		return ret;
	}

	/** 带版本号的 provides，Fabric 的 {@link #getProvides()} 装不下这些。 */
	public List<QuiltProvidedMod> getProvidedMods() {
		return Collections.unmodifiableList(provides);
	}

	/** Quilt 的 {@code intermediate_mappings}，例如 {@code net.fabricmc:intermediary}。 */
	@Nullable
	public String getIntermediateMappings() {
		return intermediateMappings;
	}

	public QuiltLoadType getLoadType() {
		return loadType != null ? loadType : QuiltLoadType.ALWAYS;
	}

	@Override
	public Version getVersion() {
		return version;
	}

	@Override
	public ModEnvironment getEnvironment() {
		// quilt.mod.json 根节点的 minecraft 段可以声明环境（client / dedicated_server / *）。
		// 缺失时是 UNIVERSAL（两侧都加载）。
		return environment;
	}

	@Override
	public Collection<ModDependency> getDependencies() {
		return dependencies;
	}

	@Override
	public String getName() {
		return name != null ? name : id;
	}

	@Override
	public String getDescription() {
		return description != null ? description : "";
	}

	@Override
	public Collection<Person> getAuthors() {
		return authors;
	}

	@Override
	public Collection<Person> getContributors() {
		return contributors;
	}

	@Override
	public ContactInformation getContact() {
		return contact;
	}

	@Override
	public Collection<String> getLicense() {
		return license;
	}

	@Override
	public Optional<String> getIconPath(int size) {
		return Optional.ofNullable(icon);
	}

	@Override
	public Map<String, CustomValue> getCustomValues() {
		// quilt.mod.json 里的自定义段（modmenu / multiconnect / …）本阶段不保留。
		// 那些段是给别的模组读的，不是给加载器读的；真要支持得按段名逐个移植。
		return Collections.emptyMap();
	}

	// ------------------------------------------------------------ loader parts

	@Override
	public int getSchemaVersion() {
		return schemaVersion;
	}

	@Override
	public Map<String, String> getLanguageAdapterDefinitions() {
		return Collections.emptyMap();
	}

	@Override
	public Collection<NestedJarEntry> getJars() {
		return jars;
	}

	@Override
	public Collection<String> getMixinConfigs(EnvType type) {
		return mixinConfigs;
	}

	@Override
	public String getClassTweaker() {
		return classTweaker;
	}

	@Override
	public boolean loadsInEnvironment(EnvType type) {
		// 之前这里恒返回 true，于是声明了 client-only 的 Quilt 模组在服务端也会被加载。
		// 环境是靠 minecraft 段声明的（客户端模组用 client 入口点只是惯例，不是约束）。
		if (environment == ModEnvironment.UNIVERSAL) return true;

		return environment == (type == EnvType.CLIENT ? ModEnvironment.CLIENT : ModEnvironment.SERVER);
	}

	@Override
	public Collection<String> getOldInitializers() {
		return Collections.emptyList();
	}

	@Override
	public List<EntrypointMetadata> getEntrypoints(String type) {
		List<EntrypointMetadata> ret = entrypoints.get(type);
		return ret != null ? ret : Collections.<EntrypointMetadata>emptyList();
	}

	@Override
	public Collection<String> getEntrypointKeys() {
		return Collections.unmodifiableCollection(entrypoints.keySet());
	}

	@Override
	public void emitFormatWarnings() {
		// 解析期的警告已经在 QuiltModMetadataReader 里逐条打出去了，这里不重复。
	}

	@Override
	public void setVersion(Version version) {
		// 不能抛异常。`VersionOverrides.apply()` 是这条路的唯一调用方：
		// 用户一旦给某个 Quilt 模组设了版本覆盖，抛异常就等于启动直接崩。
		// 覆盖的是「内核眼里的版本」（依赖求解用），不是 quilt.mod.json 的内容——
		// 后者保持不变，报告里两边都能看到。
		this.version = version;
	}

	@Override
	public void setDependencies(Collection<ModDependency> dependencies) {
		this.dependencies = dependencies;
	}

	// ------------------------------------------------------------ Quilt 专属

	/**
	 * 返回「依赖 → unless 目标 id」这张表（不可变）。
	 *
	 * <p>{@code unless} 的语义是「除非 X 存在，否则依赖 Y」。解析期不知道谁在场，
	 * 所以只能在求解前按实际候选集裁剪，见 {@link QuiltCompat#applyUnless}。
	 */
	public Map<ModDependency, String> getUnlessTargets() {
		return Collections.unmodifiableMap(unlessTargets);
	}

	@Override
	public String toString() {
		return "QuiltModMetadata{" + id + " " + version.getFriendlyString() + "}";
	}

	/** 给测试与报告用的一条人话摘要。 */
	public String describe() {
		StringBuilder sb = new StringBuilder();
		sb.append(id).append(' ').append(version.getFriendlyString());
		sb.append(" (group=").append(group);

		if (loadType != null) sb.append(", load_type=").append(loadType.key());
		if (!provides.isEmpty()) sb.append(", provides=").append(provides);

		sb.append(')');
		return sb.toString();
	}

	static ContactInformation emptyContact() {
		return new ContactInformationImpl(Collections.<String, String>emptyMap());
	}

	static Map<String, List<EntrypointMetadata>> copyEntrypoints(Map<String, List<EntrypointMetadata>> src) {
		Map<String, List<EntrypointMetadata>> ret = new LinkedHashMap<>();

		for (Map.Entry<String, List<EntrypointMetadata>> entry : src.entrySet()) {
			ret.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
		}

		return ret;
	}
}
