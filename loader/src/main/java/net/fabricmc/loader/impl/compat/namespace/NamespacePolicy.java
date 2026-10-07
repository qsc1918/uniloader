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

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.launch.MappingConfiguration;
import net.fabricmc.loader.impl.util.SystemProperties;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

/**
 * UniLoader fork patch (T1.1)：命名空间的唯一决策点。
 *
 * <p>为什么要有这个类：方案决策 D1 要求「把内核运行时命名空间统一到 Mojang 真实名，Fabric 侧
 * mod 在加载时被重映射过去」。上游 fabric-loader 把这套判定散在
 * {@link MappingConfiguration#getRuntimeNamespace()} 与
 * {@link MappingConfiguration#getDefaultModDistributionNamespace()} 两处，且把「是不是开发环境」
 * 也混进了命名空间判定（dev → named，生产 → intermediary），这正是门闸 G1 的由来。UL 要接
 * NeoForge/Forge，就必须让「运行时用什么名字」只有一个地方说了算，否则兼容层与内核会各算一套、
 * 静默漂移。
 *
 * <h2>三条判定路径</h2>
 * <ol>
 *   <li><b>未混淆版本（MC ≥ 26.1，本机目标 26.3）</b>：{@code mappings/mappings.tiny} 不存在，
 *       MC jar 里就是 Mojang 真实名 → runtime = mod 分发 = {@code official}，<b>零重映射</b>。
 *       注意：这里<b>不比较版本号</b>。工程纪律第 4 条禁止在代码里硬编码 MC 版本号，且
 *       fabric-loom 判定同一件事用的也是「有没有 mappings」这个证据
 *       （{@code MinecraftMetadataProvider.isUnobfuscated()}）。</li>
 *   <li><b>混淆版本（≤ 1.21.11）</b>：有 mappings → 按 D1 把 runtime 定为 {@code named}，
 *       mod 分发命名空间为 {@code intermediary}，需要重映射（G1+G2+G3 全开）。</li>
 *   <li><b>显式覆盖</b>：{@code -Dul.namespace=official|named|intermediary}，
 *       非法值必须抛异常退出，绝不猜。</li>
 * </ol>
 *
 * <p>本类是纯函数 + 一个读环境的入口，不持有可变状态，因此可以直接单测；
 * {@code NamespacePolicyTest} 会把它的判定与 {@link MappingConfiguration} 的实际返回值逐一对拍。
 */
public final class NamespacePolicy {
	/** MC jar 自身的名字；未混淆版本上就是 Mojang 真实名。 */
	public static final String NS_OFFICIAL = "official";
	/** Mojang 官方映射名（mojmap）；NeoForge/Forge 生态自 1.20.5 起全部基于它。 */
	public static final String NS_NAMED = "named";
	/** Fabric 的跨版本稳定名；mod 的分发命名空间。 */
	public static final String NS_INTERMEDIARY = "intermediary";
	/** 老 MC 的客户端 jar 专有名（client/server jar 用了不同映射时，门闸 G4 用得上）。 */
	public static final String NS_CLIENT_OFFICIAL = "clientOfficial";
	/** 老 MC 的服务端 jar 专有名。 */
	public static final String NS_SERVER_OFFICIAL = "serverOfficial";

	/** 走上面第 1/2 条自动判定。 */
	public static final String MODE_AUTO = "auto";

	/** 显式指定运行时命名空间：{@code auto|official|named|intermediary}，默认 auto。 */
	public static final String PROPERTY_NAMESPACE = "ul.namespace";
	/**
	 * 命名空间不匹配时怎么办：{@code strict}（默认）→ 记为 DEGRADED 并写进报告；
	 * {@code lenient} → 只在日志里说一句，不改状态。
	 */
	public static final String PROPERTY_STRICT = "ul.namespace.strict";
	/**
	 * 是否对常规 mod 做运行时重映射。默认跟随策略判定（G1：生产也要 remap）；
	 * 显式设为 false 可整体关闭。
	 */
	public static final String PROPERTY_REMAP_FABRIC_MODS = "ul.remapFabricMods";

	public static final LogCategory LOG_CATEGORY = LogCategory.createCustom("UniLoader", "Namespace");

	private static Decision cached;

	private NamespacePolicy() {
	}

	/**
	 * 读取当前环境并给出判定。
	 *
	 * <p>结果会被缓存：判定链里要读 mappings（可能涉及解压与 IO），而命名空间在进程生命周期内
	 * 不会变。缓存也让「日志里打的」和「实际生效的」必然是同一个值。
	 *
	 * @param config 内核的映射配置，即判定所用的事实来源
	 */
	public static synchronized Decision current(MappingConfiguration config) {
		if (cached == null) {
			// UniLoader fork patch (T1.2)：先把上游的逃生舱算进来。
			//
			// -Dfabric.runtimeMappingNamespace 的优先级高于一切（MappingConfiguration 就是这么写的：
			// 有它就直接返回，压根不问策略）。如果这里不问它，策略就会算出一个「内核其实没用」的
			// 判定——日志打 runtime=named，实际跑在 intermediary 上，remap 也标成 true。
			// 诊断说假话比没有诊断更糟（工程纪律第 6 条：状态只有 OK/DEGRADED/FAILED，没有「看起来」）。
			String upstream = System.getProperty(SystemProperties.RUNTIME_MAPPING_NAMESPACE);

			if (upstream != null && !upstream.trim().isEmpty()) {
				String runtime = upstream.trim();
				String modDist = NS_OFFICIAL.equalsIgnoreCase(runtime) ? NS_OFFICIAL : NS_INTERMEDIARY;

				cached = new Decision(runtime, modDist, !runtime.equals(modDist), config.hasAnyMappings(), true,
						"由 -D" + SystemProperties.RUNTIME_MAPPING_NAMESPACE + "=" + runtime
								+ " 显式指定（上游逃生舱，优先级高于 -D" + PROPERTY_NAMESPACE + "）");
			} else {
				cached = decide(
						readOverride(),
						config.hasAnyMappings(),
						config.getNamespaces(),
						ns -> FabricLoaderImpl.INSTANCE.getGameProvider().getRuntimeNamespace(ns));
			}

			Log.info(LOG_CATEGORY, "%s", cached.describe());
		}

		return cached;
	}

	/** 测试用：清掉 {@link #current(MappingConfiguration)} 的缓存。 */
	static synchronized void resetCache() {
		cached = null;
	}

	/**
	 * 纯函数：给定事实，给出判定。
	 *
	 * <p>与上游 {@code MappingConfiguration.computeRuntimeNamespace()} 有意分歧的地方只有一处，
	 * 见下面 D1 分支的注释——这是 D1 的全部内容，其余分支必须与上游逐一对拍一致。
	 *
	 * @param override          {@code ul.namespace} 的值，null/空/{@code auto} 都表示自动判定
	 * @param mappingsPresent   是否存在非空 mappings（等价于「该 MC 版本仍有混淆」）
	 * @param mappingNamespaces mappings 里可用的命名空间集合，可为 null
	 * @param providerAdjust    游戏提供者对命名空间的最后修正（原样透传即可，null 表示不修正）
	 */
	public static Decision decide(String override, boolean mappingsPresent, List<String> mappingNamespaces,
			UnaryOperator<String> providerAdjust) {
		String runtime;
		String reason;
		boolean explicit;

		if (override != null && !override.isEmpty() && !MODE_AUTO.equalsIgnoreCase(override)) {
			runtime = requireValidNamespace(override);
			explicit = true;
			reason = "由 -D" + PROPERTY_NAMESPACE + "=" + override + " 显式指定";
		} else if (!mappingsPresent) {
			// 路径 1：没有 mappings ⇒ 该 MC 版本未混淆 ⇒ 内核与 mod 天然同名，零重映射。
			runtime = NS_OFFICIAL;
			explicit = false;
			reason = "未检测到 mappings：该 MC 版本未混淆，内核与模组同处官方名，零重映射";
		} else if (containsIgnoreCase(mappingNamespaces, NS_NAMED)) {
			// 路径 2（决策 D1）：与上游唯一的有意分歧。上游是 dev ? named : intermediary，
			// 即用「是不是开发环境」来决定命名空间；UL 一律取 named，因为 NeoForge/Forge 侧
			// （API jar、patch、AT、coremod）全部基于 mojmap，是体量最大、最不可能改动的一方。
			// 「是否真的执行重映射」改由 G1 的 ul.remapFabricMods 开关决定，不再混在命名空间里。
			runtime = NS_NAMED;
			explicit = false;
			reason = "检测到 mappings：按决策 D1 把运行时统一到 mojmap，模组从 intermediary 重映射过来";
		} else {
			runtime = NS_INTERMEDIARY;
			explicit = false;
			reason = "检测到 mappings 但其中没有 named 命名空间：退回 intermediary，两侧同名，不重映射";
		}

		if (providerAdjust != null) {
			String adjusted = providerAdjust.apply(runtime);

			if (adjusted != null && !adjusted.isEmpty() && !adjusted.equals(runtime)) {
				Log.debug(LOG_CATEGORY, "游戏提供者把运行时命名空间从 %s 修正为 %s", runtime, adjusted);
				runtime = adjusted;
			}
		}

		String modDist = NS_OFFICIAL.equals(runtime) ? NS_OFFICIAL : NS_INTERMEDIARY;

		return new Decision(runtime, modDist, !runtime.equals(modDist), mappingsPresent, explicit, reason);
	}

	/**
	 * 游戏 jar 自己处于哪个命名空间（门闸 G4）。
	 *
	 * <p>三个命名空间里最后一个被收进来的：它原本散在
	 * {@code MinecraftGameProvider.initialize()} 里，与 {@link #decide} 各写一套
	 * 「有没有 mappings」的判断。收进来的意义是让 G4 的那句
	 * {@code if (!gameNs.equals(runtimeNs)) → 反混淆} 所依赖的两个值来自同一个决策点，
	 * 否则改了一处忘了另一处，表现是「游戏被莫名其妙地重映射了一遍」。
	 *
	 * <p>判定与上游逐字等价，只是把控制流写成了表：
	 * <ul>
	 *   <li>显式 {@code -Dfabric.gameMappingNamespace} 优先；</li>
	 *   <li>没有 mappings（26.1 起）：{@code official}——jar 里就是 Mojang 真实名；</li>
	 *   <li>有 mappings 且是开发环境、且 mappings 里有 named：{@code named}；</li>
	 *   <li>有 mappings 且 mappings 里没有 official（老 MC 的 client/server jar 用了不同映射）：
	 *       {@code clientOfficial} / {@code serverOfficial}；</li>
	 *   <li>其余：{@code official}。</li>
	 * </ul>
	 *
	 * @param override         {@code fabric.gameMappingNamespace} 的值，null 表示自动判定
	 * @param mappingsPresent  是否存在 mappings
	 * @param mappingNamespaces mappings 里的命名空间集合，可为 null
	 * @param development      是否开发环境
	 * @param client           是否客户端（只影响 clientOfficial / serverOfficial 的取舍）
	 */
	public static String gameNamespace(String override, boolean mappingsPresent, List<String> mappingNamespaces,
			boolean development, boolean client) {
		if (override != null && !override.isEmpty()) return override;

		if (!mappingsPresent) return NS_OFFICIAL;

		// 上游在 namespaces 读不到时**不做任何判断**、直接留在 official。
		// 这个分支现实中几乎走不到（有 mappings 就一定读得出 namespaces），
		// 但「几乎」不是「一定」——把它照抄下来，而不是靠推理省略。
		if (mappingNamespaces == null) return NS_OFFICIAL;

		if (development && containsIgnoreCase(mappingNamespaces, NS_NAMED)) return NS_NAMED;

		if (!containsIgnoreCase(mappingNamespaces, NS_OFFICIAL)) {
			return client ? NS_CLIENT_OFFICIAL : NS_SERVER_OFFICIAL;
		}

		return NS_OFFICIAL;
	}

	/** 校验 {@code ul.namespace} 的取值；非法值直接抛异常，绝不猜一个默认值。 */
	public static String requireValidNamespace(String value) {
		if (value == null) throw new IllegalArgumentException("命名空间不能为 null");

		String v = value.trim().toLowerCase(Locale.ROOT);

		if (NS_OFFICIAL.equals(v) || NS_NAMED.equals(v) || NS_INTERMEDIARY.equals(v) || MODE_AUTO.equals(v)) {
			return v;
		}

		throw new IllegalArgumentException(String.format(
				"-%s=%s 不是合法的命名空间，只接受 %s|%s|%s|%s（默认 %s）",
				PROPERTY_NAMESPACE, value, MODE_AUTO, NS_OFFICIAL, NS_NAMED, NS_INTERMEDIARY, MODE_AUTO));
	}

	/** 读取 {@code ul.namespace}，并在非法时立刻报错退出。 */
	public static String readOverride() {
		String raw = System.getProperty(PROPERTY_NAMESPACE);

		if (raw == null || raw.isEmpty() || MODE_AUTO.equalsIgnoreCase(raw.trim())) return null;

		try {
			return requireValidNamespace(raw);
		} catch (IllegalArgumentException e) {
			// 不静默降级成 auto：输错了就是输错了，猜一个会让后面的诊断全部失真。
			Log.error(LOG_CATEGORY, "%s", e.getMessage());
			throw e;
		}
	}

	/** strict 模式下命名空间不匹配要落到 DEGRADED 上，而不是崩掉整个游戏。 */
	public static boolean strict() {
		return !"lenient".equalsIgnoreCase(System.getProperty(PROPERTY_STRICT, "strict").trim());
	}

	/**
	 * 是否对常规模组执行运行时重映射（门闸 G1）。
	 *
	 * <p>上游是 {@code remapRegularMods = isDevelopmentEnvironment()}，生产恒 false；
	 * UL 改成「策略说需要就做」，并留出 {@code -Dul.remapFabricMods=false} 的总开关。
	 * 在 26.3 上策略本身就是零重映射，因此这条改动对当前目标版本零影响。
	 */
	public static boolean remapFabricMods(Decision decision) {
		if (decision == null) return false;

		String raw = System.getProperty(PROPERTY_REMAP_FABRIC_MODS);
		if (raw != null && "false".equalsIgnoreCase(raw.trim())) return false;

		return decision.remapRequired();
	}

	private static boolean containsIgnoreCase(List<String> values, String needle) {
		if (values == null) return false;

		for (String v : values) {
			if (v != null && v.equalsIgnoreCase(needle)) return true;
		}

		return false;
	}

	/**
	 * 一次判定的结果。不可变，且原因必填——工程纪律第 6 条：不存在「说不清为什么」的状态。
	 */
	public static final class Decision {
		private final String runtimeNamespace;
		private final String modDistributionNamespace;
		private final boolean remapRequired;
		private final boolean mappingsPresent;
		private final boolean explicit;
		private final String reason;

		Decision(String runtimeNamespace, String modDistributionNamespace, boolean remapRequired,
				boolean mappingsPresent, boolean explicit, String reason) {
			this.runtimeNamespace = runtimeNamespace;
			this.modDistributionNamespace = modDistributionNamespace;
			this.remapRequired = remapRequired;
			this.mappingsPresent = mappingsPresent;
			this.explicit = explicit;
			this.reason = reason;
		}

		/** 内核运行时所使用的命名空间。 */
		public String runtimeNamespace() {
			return runtimeNamespace;
		}

		/** 未显式声明的模组被假定使用哪个命名空间。 */
		public String modDistributionNamespace() {
			return modDistributionNamespace;
		}

		/** 是否需要把模组从 {@link #modDistributionNamespace()} 重映射到 {@link #runtimeNamespace()}。 */
		public boolean remapRequired() {
			return remapRequired;
		}

		/** 本次启动是否检测到了 mappings（等价于目标 MC 版本仍有混淆）。 */
		public boolean mappingsPresent() {
			return mappingsPresent;
		}

		/** 是否由 {@code ul.namespace} 显式指定，而不是自动判定。 */
		public boolean explicit() {
			return explicit;
		}

		public String reason() {
			return reason;
		}

		/** 是否「零重映射」：两侧同名，重映射通道完全不启动。 */
		public boolean zeroRemap() {
			return !remapRequired;
		}

		/**
		 * 运行时命名空间是否就是 Mojang 真实名——NeoForge/Forge 生态的全部代码都基于它。
		 *
		 * <p>两个成立条件：显式就是 {@code named}；或者未混淆版本上的 {@code official}
		 * （此时 jar 里的名字就是 Mojang 名）。混淆版本上的 {@code official} 是混淆名，不成立。
		 */
		public boolean isMojmapRuntime() {
			return NS_NAMED.equals(runtimeNamespace)
					|| (NS_OFFICIAL.equals(runtimeNamespace) && !mappingsPresent);
		}

		@Override
		public String toString() {
			return describe();
		}

		/** 一行给人看的判定说明，启动日志与报告都用它，保证两处说法一致。 */
		public String describe() {
			return String.format("命名空间判定：runtime=%s modDist=%s remap=%s mojmap=%s（%s）",
					runtimeNamespace, modDistributionNamespace, remapRequired, isMojmapRuntime(), reason);
		}
	}

	/**
	 * 某个生态的模组能否在当前命名空间下工作。
	 *
	 * <p>Fabric/Quilt：分发命名空间就是为它们算出来的，恒兼容。
	 * NeoForge/Forge：代码基于 mojmap，只有在 {@link Decision#isMojmapRuntime()} 时才兼容。
	 */
	public static boolean isCompatible(String ecosystemId, Decision decision) {
		if (decision == null || ecosystemId == null) return true;

		String id = ecosystemId.trim().toLowerCase(Locale.ROOT);

		if ("neoforge".equals(id) || "forge".equals(id)) return decision.isMojmapRuntime();

		// fabric / quilt / unknown：认不出来的生态不做判断，免得凭空制造失败。
		return true;
	}

	/**
	 * 记一次命名空间不匹配，并返回调用方该怎么做。
	 *
	 * <p>strict → {@link MismatchAction#DEGRADE}（写进报告，游戏继续）；
	 * lenient → {@link MismatchAction#WARN}（只写日志）。
	 * 永远不返回「假装没事」：日志里必须留下模组 id 与两个命名空间。
	 */
	public static MismatchAction reportMismatch(String modId, String ecosystemId, Decision decision) {
		MismatchAction action = strict() ? MismatchAction.DEGRADE : MismatchAction.WARN;
		String message = String.format("模组 %s（生态 %s）在当前命名空间 runtime=%s 下无法工作",
				modId, ecosystemId, decision == null ? "<未判定>" : decision.runtimeNamespace());

		if (action == MismatchAction.DEGRADE) {
			Log.error(LOG_CATEGORY, "%s；按 ul.namespace.strict=strict 记为 DEGRADED", message);
		} else {
			Log.warn(LOG_CATEGORY, "%s；按 ul.namespace.strict=lenient 仅记录，不改动状态", message);
		}

		return action;
	}

	/** 命名空间不匹配时调用方该采取的行动。 */
	public enum MismatchAction {
		/** 记为 DEGRADED 并写进报告，游戏继续跑。 */
		DEGRADE,
		/** 只在日志里说一句，不改状态。 */
		WARN
	}

	/**
	 * 给 {@link #current(MappingConfiguration)} 之外的使用方读取「当前是不是开发环境」的接缝。
	 *
	 * @return launcher 尚未就绪时返回 false，而不是 NPE——诊断不能自己先崩
	 */
	static boolean isDevelopment() {
		FabricLauncher launcher = FabricLauncherBase.getLauncher();
		return launcher != null && launcher.isDevelopment();
	}

	/** {@link #decide} 的便捷重载：没有 mappings 时的最简形态，供单测与文档示例使用。 */
	public static Decision decideWithoutMappings() {
		return decide(null, false, Collections.<String>emptyList(), null);
	}
}
