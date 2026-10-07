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

package net.fabricmc.loader.impl.launch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.FabricUtil;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.transformer.Config;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModDependency.Kind;
import net.fabricmc.loader.api.metadata.version.VersionInterval;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.ModContainerImpl;
import net.fabricmc.loader.impl.launch.knot.MixinServiceKnot;
import net.fabricmc.loader.impl.launch.knot.MixinServiceKnotBootstrap;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;
import net.fabricmc.loader.impl.util.mappings.MixinIntermediaryDevRemapper;
import net.fabricmc.mappingio.tree.MappingTree;

public final class FabricMixinBootstrap {
	private FabricMixinBootstrap() { }

	private static boolean initialized = false;

	public static void init(EnvType side, FabricLoaderImpl loader) {
		if (initialized) {
			throw new RuntimeException("FabricMixinBootstrap has already been initialized!");
		}

		System.setProperty("mixin.bootstrapService", MixinServiceKnotBootstrap.class.getName());
		System.setProperty("mixin.service", MixinServiceKnot.class.getName());

		MixinBootstrap.init();

		// UniLoader fork patch (T1.2 / 门闸 G3)：上游把这段锁在 isDevelopment() 里，于是
		// 「生产环境、但运行时命名空间与 mod 分发命名空间不同」这种组合（≤1.21.11 上按 D1
		// 把运行时定到 named，而 mod 的 mixin 注解字符串是 intermediary）拿不到翻译：
		// mixin 的 @Inject(method="method_xxx") 是字符串常量，字节码重映射碰不到它，
		// 目标名对不上，注入会静默失效——这正是 G3 描述的那个坑。
		//
		// 判定改成「两侧命名空间不同且 mappings 里都有这两个命名空间」，与上游 dev 分支的
		// 逻辑逐字相同，只是不再看 dev 标志。未混淆版本（26.1 起）两侧同名，条件不成立，
		// 行为零变化。
		MappingConfiguration mappingConfig = FabricLauncherBase.getLauncher().getMappingConfiguration();
		final String modNs = mappingConfig.getDefaultModDistributionNamespace();
		String runtimeNs = mappingConfig.getRuntimeNamespace();

		if (mappingConfig.hasAnyMappings() && !modNs.equals(runtimeNs)) {
			MappingTree mappings = mappingConfig.getMappings();
			List<String> namespaces = new ArrayList<>(mappings.getDstNamespaces());
			namespaces.add(mappings.getSrcNamespace());

			if (namespaces.contains(modNs) && namespaces.contains(runtimeNs)) {
				System.setProperty("mixin.env.remapRefMap", "true");

				try {
					MixinIntermediaryDevRemapper remapper = new MixinIntermediaryDevRemapper(mappings, modNs, runtimeNs);
					MixinEnvironment.getDefaultEnvironment().getRemappers().add(remapper);
					Log.info(LogCategory.MIXIN, "为 mixin 装上命名空间重映射：%s → %s（%s）", modNs, runtimeNs,
							FabricLauncherBase.getLauncher().isDevelopment() ? "开发环境" : "生产环境按命名空间策略");
				} catch (Exception e) {
					// 显式报警后让游戏继续跑：装不上 remapper 意味着带 mixin 的 mod 会注入失败，
					// 日志必须留下证据，而不是静默继续（工程纪律第 1 条）。
					Log.error(LogCategory.MIXIN, "为 mixin 装命名空间重映射失败——带 mixin 的模组会注入失败！", e);
				}
			}
		}

		Map<String, ModContainerImpl> configToModMap = new HashMap<>();

		for (ModContainerImpl mod : loader.getModsInternal()) {
			for (String config : mod.getMetadata().getMixinConfigs(side)) {
				ModContainerImpl prev = configToModMap.putIfAbsent(config, mod);
				if (prev != null) throw new RuntimeException(String.format("Non-unique Mixin config name %s used by the mods %s and %s", config, prev.getMetadata().getId(), mod.getMetadata().getId()));

				try {
					Mixins.addConfiguration(config);
				} catch (Throwable t) {
					throw new RuntimeException(String.format("Error parsing or using Mixin config %s for mod %s", config, mod.getMetadata().getId()), t);
				}
			}
		}

		for (Config config : Mixins.getConfigs()) {
			ModContainerImpl mod = configToModMap.get(config.getName());
			if (mod == null) continue;
		}

		try {
			IMixinConfig.class.getMethod("decorate", String.class, Object.class);
			MixinConfigDecorator.apply(configToModMap);
		} catch (NoSuchMethodException e) {
			Log.info(LogCategory.MIXIN, "Detected old Mixin version without config decoration support");
		}

		initialized = true;
	}

	private static final class MixinConfigDecorator {
		static void apply(Map<String, ModContainerImpl> configToModMap) {
			for (Config rawConfig : Mixins.getConfigs()) {
				ModContainerImpl mod = configToModMap.get(rawConfig.getName());
				if (mod == null) continue;

				IMixinConfig config = rawConfig.getConfig();
				config.decorate(FabricUtil.KEY_MOD_ID, mod.getMetadata().getId());
				config.decorate(FabricUtil.KEY_COMPATIBILITY, getMixinCompat(mod));
			}
		}

		private static int getMixinCompat(ModContainerImpl mod) {
			// infer from loader dependency by determining the least relevant loader version the mod accepts
			// AND any loader deps

			List<VersionInterval> reqIntervals = Collections.singletonList(VersionInterval.INFINITE);

			for (ModDependency dep : mod.getMetadata().getDependencies()) {
				if (dep.getModId().equals("fabricloader") || dep.getModId().equals("fabric-loader")) {
					if (dep.getKind() == Kind.DEPENDS) {
						reqIntervals = VersionInterval.and(reqIntervals, dep.getVersionIntervals());
					} else if (dep.getKind() == Kind.BREAKS) {
						reqIntervals = VersionInterval.and(reqIntervals, VersionInterval.not(dep.getVersionIntervals()));
					}
				}
			}

			if (reqIntervals.isEmpty()) throw new IllegalStateException("mod "+mod+" is incompatible with every loader version?"); // shouldn't get there

			Version minLoaderVersion = reqIntervals.get(0).getMin(); // it is sorted, to 0 has the absolute lower bound

			if (minLoaderVersion != null) { // has a lower bound
				for (FabricMixinVersions.LoaderMixinVersionEntry version : FabricMixinVersions.getVersions()) {
					if (minLoaderVersion.compareTo(version.loaderVersion) >= 0) { // lower bound is >= current version
						return version.mixinVersion;
					}
				}
			}

			return FabricUtil.COMPATIBILITY_0_9_2;
		}
	}
}
