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

package org.uniloader.api;

/**
 * 出问题时，把它翻译成 {@link ModStatus} 的唯一地方。
 *
 * <p>为什么要有这个类：工程纪律第 6 条要求「不存在看起来成功」，但「多严重算失败」如果在
 * 每个 catch 块里各写一遍，就一定会漂移成静默跳过。这里把它收成一张表，任何调用点都只能
 * 拿到三个值之一，且必须带原因。
 *
 * <p>{@code -Dul.degradation=strict|lenient}（默认 strict）：strict 下「明确不支持的能力」
 * 也记为 DEGRADED 并写进报告；lenient 下只在日志里说一句，不改状态。
 */
public final class DegradationPolicy {
	public static final String PROPERTY = "ul.degradation";

	/** 加载阶段。报告里的 {@code failedStage} 用的就是它的名字。 */
	public enum Stage {
		DISCOVERY("discovery"),
		RESOLVE("resolve"),
		REMAP("remap"),
		INSTANTIATE("instantiate"),
		INJECT("inject"),
		LIFECYCLE("lifecycle");

		private final String id;

		Stage(String id) {
			this.id = id;
		}

		public String id() {
			return id;
		}
	}

	private DegradationPolicy() {
	}

	public static boolean strict() {
		return !"lenient".equalsIgnoreCase(System.getProperty(PROPERTY, "strict"));
	}

	/**
	 * 一个阶段上抛了异常，该记成什么状态。
	 *
	 * <p>注入阶段一律 FAILED：半应用的 mixin 比不加载更危险（Forbric 的失败根源），
	 * 宁可拒绝这个 mod，也不能让它带着一半的注入继续跑。
	 * 生命周期事件抛异常只算 DEGRADED：此时世界已经能进，别的 mod 不受影响。
	 */
	public static ModStatus forFailure(Stage stage) {
		return switch (stage) {
			case DISCOVERY, RESOLVE, REMAP, INSTANTIATE, INJECT -> ModStatus.FAILED;
			case LIFECYCLE -> ModStatus.DEGRADED;
		};
	}

	/**
	 * 把一次失败落到登记表上，并补上原因与阶段。
	 *
	 * @param cause 允许为 null，但此时 detail 必须自己说清楚；不允许「失败且无原因」
	 */
	public static void fail(String modId, Stage stage, String detail, Throwable cause) {
		StringBuilder text = new StringBuilder();

		if (detail != null && !detail.isBlank()) text.append(detail);
		if (cause != null) {
			if (!text.isEmpty()) text.append(" — ");
			text.append(cause.getClass().getName());
			String message = cause.getMessage();
			if (message != null && !message.isBlank()) text.append(": ").append(message);
		}
		if (text.isEmpty()) text.append("failed without a reason (this is a UniLoader bug — see 工程纪律第 3 条)");

		ModCatalog.update(modId, forFailure(stage), text.toString(), stage.id());
	}

	/**
	 * 明确不支持的能力（Quilt 插件、ModLauncher ITransformationService、JS coremod 等）。
	 *
	 * <p>这不是失败：mod 自己的注册照常进行，只是那一项能力缺失。必须给原因，
	 * 且要在文档与运行时都点名（见阶段 10 的 T10.6「明确不支持清单」）。
	 */
	public static void degradeForUnsupportedCapability(String modId, String capability) {
		if (!strict()) return;

		ModCatalog.update(modId, ModStatus.DEGRADED,
				"UniLoader does not support " + capability + " — this mod keeps its other functionality",
				Stage.INSTANTIATE.id());
	}
}
