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

package org.uniloader.compat.quilt;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.quiltmc.loader.api.ModContainer;
import org.quiltmc.loader.api.ModMetadata;
import org.quiltmc.loader.api.Version;
import org.quiltmc.loader.api.VersionFormatException;
import org.quiltmc.loader.api.entrypoint.EntrypointContainer;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.ModContainerImpl;
import net.fabricmc.loader.impl.compat.quilt.QuiltEntrypointKeys;
import net.fabricmc.loader.impl.compat.quilt.QuiltModMetadata;
import net.fabricmc.loader.impl.compat.quilt.QuiltProvidedMod;

/**
 * T2.3: {@code org.quiltmc.loader.api} 的实现端（Quilt 兼容层，Apache-2.0）。
 *
 * <p>全部数据来自内核现有的 {@link FabricLoaderImpl} / {@link ModContainerImpl}，
 * 不另存一份——两份数据迟早会不一致（坑 G8）。
 *
 * <p>Quilt Loader 是 Apache-2.0，所以这套桥接可以和内核同处一个 jar。
 * 若换成 LGPL 的生态（NeoForge / Forge），就必须独立分发（见 docs/LICENSES.md）。
 */
public final class QuiltApi {
	private QuiltApi() {
	}

	// ------------------------------------------------------------- 版本

	/** 拿不到版本时用的占位。不是「当作没版本」，是报告里的一个可识别值。 */
	public static Version zeroVersion() {
		try {
			return wrap(net.fabricmc.loader.api.Version.parse("0.0.0"));
		} catch (net.fabricmc.loader.api.VersionParsingException e) {
			throw new IllegalStateException("0.0.0 竟然解析不了", e);
		}
	}

	public static Version versionOf(String raw) throws VersionFormatException {
		try {
			return wrap(net.fabricmc.loader.api.Version.parse(raw));
		} catch (net.fabricmc.loader.api.VersionParsingException e) {
			throw new VersionFormatException(e);
		}
	}

	public static Version.Semantic semanticOf(String raw) throws VersionFormatException {
		try {
			net.fabricmc.loader.api.Version v = net.fabricmc.loader.api.Version.parse(raw);

			if (!(v instanceof SemanticVersion)) throw new VersionFormatException("不是语义化版本: " + raw);

			return new SemanticImpl((SemanticVersion) v);
		} catch (net.fabricmc.loader.api.VersionParsingException e) {
			throw new VersionFormatException(e);
		}
	}

	public static Version wrap(net.fabricmc.loader.api.Version version) {
		return version instanceof SemanticVersion ? new SemanticImpl((SemanticVersion) version) : new VersionImpl(version);
	}

	// ------------------------------------------------------------- 环境

	public static EnvType environmentType() {
		return FabricLoaderImpl.INSTANCE.getEnvironmentType();
	}

	public static boolean isDevelopmentEnvironment() {
		return FabricLoaderImpl.INSTANCE.isDevelopmentEnvironment();
	}

	public static Path getGameDir() {
		return FabricLoaderImpl.INSTANCE.getGameDir();
	}

	public static Path getConfigDir() {
		return FabricLoaderImpl.INSTANCE.getConfigDir();
	}

	// ------------------------------------------------------------- 模组

	public static boolean isModLoaded(String id) {
		return FabricLoaderImpl.INSTANCE.isModLoaded(id);
	}

	public static Optional<ModContainer> getModContainer(String id) {
		return FabricLoaderImpl.INSTANCE.getModContainer(id).map(QuiltApi::container);
	}

	public static Collection<ModContainer> getAllMods() {
		List<ModContainer> ret = new ArrayList<>();

		for (net.fabricmc.loader.api.ModContainer mod : FabricLoaderImpl.INSTANCE.getAllMods()) {
			ret.add(container(mod));
		}

		return ret;
	}

	// ------------------------------------------------------------- 入口点

	public static <T> List<T> getEntrypoints(String key, Class<T> type) {
		List<T> ret = new ArrayList<>();

		for (EntrypointContainer<T> container : getEntrypointContainers(key, type)) {
			ret.add(container.getEntrypoint());
		}

		return ret;
	}

	/**
	 * 取某个键下的入口点容器。
	 *
	 * <p>会顺带把 {@link QuiltEntrypointKeys} 里的别名键合并进来：
	 * Quilt 模组问 {@code pre_launch} 时，也应该看到 Fabric 模组写在 {@code preLaunch} 下的东西。
	 * 合并是安全的——{@code getEntrypointContainers} 按 {@code type} 过滤，
	 * 接口不兼容的条目本来就不会被返回。
	 */
	public static <T> List<EntrypointContainer<T>> getEntrypointContainers(String key, Class<T> type) {
		Map<String, EntrypointContainer<T>> merged = new LinkedHashMap<>();

		for (String alias : QuiltEntrypointKeys.aliases(key)) {
			for (net.fabricmc.loader.api.entrypoint.EntrypointContainer<T> container : FabricLoaderImpl.INSTANCE.getEntrypointContainers(alias, type)) {
				String dedupKey = container.getProvider().getMetadata().getId() + "|" + alias;

				if (!merged.containsKey(dedupKey)) merged.put(dedupKey, new ContainerImpl<T>(container));
			}
		}

		return new ArrayList<>(merged.values());
	}

	// ------------------------------------------------------------- 实现

	static ModContainer container(net.fabricmc.loader.api.ModContainer mod) {
		if (mod instanceof ModContainerImpl) return new ContainerAdapter((ModContainerImpl) mod);

		return containerById(mod.getMetadata().getId());
	}

	private static ModContainer containerById(String id) {
		Optional<net.fabricmc.loader.api.ModContainer> mod = FabricLoaderImpl.INSTANCE.getModContainer(id);

		if (mod.isPresent() && mod.get() instanceof ModContainerImpl) {
			return new ContainerAdapter((ModContainerImpl) mod.get());
		}

		return new ContainerAdapter(null);
	}

	private static final class VersionImpl implements Version {
		private final net.fabricmc.loader.api.Version delegate;

		VersionImpl(net.fabricmc.loader.api.Version delegate) {
			this.delegate = delegate;
		}

		@Override
		public String raw() {
			return delegate.getFriendlyString();
		}

		@Override
		public int compareTo(Version o) {
			if (o instanceof VersionImpl) return delegate.compareTo(((VersionImpl) o).delegate);
			if (o instanceof SemanticImpl) return delegate.compareTo(((SemanticImpl) o).delegate);

			return raw().compareTo(o.raw());
		}

		@Override
		public boolean equals(Object obj) {
			return obj != null && raw().equals(obj.toString());
		}

		@Override
		public int hashCode() {
			return raw().hashCode();
		}

		@Override
		public String toString() {
			return raw();
		}
	}

	private static final class SemanticImpl implements Version.Semantic {
		private final SemanticVersion delegate;

		SemanticImpl(SemanticVersion delegate) {
			this.delegate = delegate;
		}

		@Override
		public int versionComponentCount() {
			return delegate.getVersionComponentCount();
		}

		@Override
		public int versionComponent(int index) {
			return delegate.getVersionComponent(index);
		}

		@Override
		public String preRelease() {
			return delegate.getPrereleaseKey().orElse("");
		}

		@Override
		public String buildMetadata() {
			return delegate.getBuildKey().orElse("");
		}

		@Override
		public String raw() {
			return delegate.getFriendlyString();
		}

		@Override
		public int compareTo(Version o) {
			if (o instanceof VersionImpl) return delegate.compareTo(((VersionImpl) o).delegate);
			if (o instanceof SemanticImpl) return delegate.compareTo(((SemanticImpl) o).delegate);

			return raw().compareTo(o.raw());
		}

		@Override
		public boolean equals(Object obj) {
			return obj != null && raw().equals(obj.toString());
		}

		@Override
		public int hashCode() {
			return raw().hashCode();
		}

		@Override
		public String toString() {
			return raw();
		}
	}

	private static final class ContainerImpl<T> implements EntrypointContainer<T> {
		private final net.fabricmc.loader.api.entrypoint.EntrypointContainer<T> delegate;

		ContainerImpl(net.fabricmc.loader.api.entrypoint.EntrypointContainer<T> delegate) {
			this.delegate = delegate;
		}

		@Override
		public T getEntrypoint() {
			return delegate.getEntrypoint();
		}

		@Override
		public ModContainer getProvider() {
			return container(delegate.getProvider());
		}

		@Override
		public String getDefinition() {
			// 内核不暴露声明用的类名，这里用实例化后的类名代替。
			// 只用于日志与去重，不影响行为。
			return delegate.getEntrypoint().getClass().getName();
		}
	}

	private static final class ContainerAdapter implements ModContainer {
		private final ModContainerImpl delegate;

		ContainerAdapter(ModContainerImpl delegate) {
			this.delegate = delegate;
		}

		@Override
		public ModMetadata metadata() {
			if (delegate == null) return EmptyMetadata.INSTANCE;

			return new MetadataAdapter(delegate.getMetadata());
		}

		@Override
		public Path rootPath() {
			return delegate != null ? delegate.getRootPath() : getGameDir();
		}

		@Override
		public List<List<Path>> getSourcePaths() {
			if (delegate == null) return Collections.emptyList();

			List<List<Path>> ret = new ArrayList<>();
			ret.add(new ArrayList<>(delegate.getRootPaths()));
			return ret;
		}

		@Override
		public BasicSourceType getSourceType() {
			if (delegate == null) return BasicSourceType.UNKNOWN;

			String type = delegate.getMetadata().getType();

			if (QuiltModMetadata.TYPE_QUILT.equals(type)) return BasicSourceType.NORMAL_QUILT;
			if ("fabric".equals(type)) return BasicSourceType.NORMAL_FABRIC;
			if ("builtin".equals(type)) return BasicSourceType.BUILTIN;

			return BasicSourceType.UNKNOWN;
		}

		@Override
		public ClassLoader getClassLoader() {
			// 内核没有给每个模组留 ClassLoader 的引用。模组代码跑在 KnotClassLoader 里，
			// 所以调用时刻的上下文类加载器就是它——这是启发式，写在注释里而不是假装精确。
			ClassLoader cl = Thread.currentThread().getContextClassLoader();
			return cl != null ? cl : QuiltApi.class.getClassLoader();
		}

		@Override
		public String toString() {
			return delegate != null ? delegate.toString() : "ModContainer[missing]";
		}
	}

	private static final class EmptyMetadata implements ModMetadata {
		static final EmptyMetadata INSTANCE = new EmptyMetadata();

		@Override
		public String id() {
			return "unknown";
		}

		@Override
		public String group() {
			return "";
		}

		@Override
		public Version version() {
			return QuiltApi.zeroVersion();
		}

		@Override
		public String name() {
			return "unknown";
		}

		@Override
		public Collection<org.quiltmc.loader.api.ModLicense> licenses() {
			return Collections.emptyList();
		}

		@Override
		public Collection<org.quiltmc.loader.api.ModContributor> contributors() {
			return Collections.emptyList();
		}

		@Override
		public Map<String, String> contactInfo() {
			return Collections.emptyMap();
		}
	}

	private static final class MetadataAdapter implements ModMetadata {
		private final net.fabricmc.loader.api.metadata.ModMetadata delegate;

		MetadataAdapter(net.fabricmc.loader.api.metadata.ModMetadata delegate) {
			this.delegate = delegate;
		}

		@Override
		public String id() {
			return delegate.getId();
		}

		@Override
		public String group() {
			// Fabric 的元数据没有 group；Quilt 的元数据有。拿不到就退回空串而不是编一个。
			return delegate instanceof QuiltModMetadata ? ((QuiltModMetadata) delegate).getGroup() : "";
		}

		@Override
		public Version version() {
			return wrap(delegate.getVersion());
		}

		@Override
		public String name() {
			return delegate.getName();
		}

		@Override
		public String description() {
			return delegate.getDescription();
		}

		@Override
		public Collection<org.quiltmc.loader.api.ModLicense> licenses() {
			List<org.quiltmc.loader.api.ModLicense> ret = new ArrayList<>();

			for (final String license : delegate.getLicense()) {
				ret.add(new org.quiltmc.loader.api.ModLicense() {
					@Override
					public String name() {
						return license;
					}
				});
			}

			return ret;
		}

		@Override
		public Collection<org.quiltmc.loader.api.ModContributor> contributors() {
			List<org.quiltmc.loader.api.ModContributor> ret = new ArrayList<>();

			for (final net.fabricmc.loader.api.metadata.Person person : delegate.getContributors()) {
				ret.add(new org.quiltmc.loader.api.ModContributor() {
					@Override
					public String name() {
						return person.getName();
					}
				});
			}

			return ret;
		}

		@Override
		public Map<String, String> contactInfo() {
			return delegate.getContact().asMap();
		}

		@Override
		public Collection<? extends ProvidedMod> provides() {
			List<ProvidedMod> ret = new ArrayList<>();

			if (delegate instanceof QuiltModMetadata) {
				for (final QuiltProvidedMod provided : ((QuiltModMetadata) delegate).getProvidedMods()) {
					ret.add(new ProvidedMod() {
						@Override
						public String group() {
							return ((QuiltModMetadata) delegate).getGroup();
						}

						@Override
						public String id() {
							return provided.id();
						}

						@Override
						public Version version() {
							return provided.version() != null ? wrap(provided.version()) : QuiltApi.wrap(delegate.getVersion());
						}
					});
				}
			} else {
				for (final String id : delegate.getProvides()) {
					ret.add(new ProvidedMod() {
						@Override
						public String group() {
							return "";
						}

						@Override
						public String id() {
							return id;
						}

						@Override
						public Version version() {
							return wrap(delegate.getVersion());
						}
					});
				}
			}

			return ret;
		}
	}
}
