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

import java.util.List;

/**
 * 一个模组在本次启动中的全部诊断事实。
 *
 * <p>不可变：状态流转通过 {@link #withStatus} / {@link #withDependents} 产生新实例，
 * 这样从任何时刻拍下的快照都是一致的，日志、报告与崩溃归因不会看到「正在变」的对象。
 *
 * @param id          模组 id，四家统一用小写 id（Forge 侧 mods.toml 的 modId 需保留大小写原样）
 * @param name        展示名
 * @param version     版本字符串（原样保留，不做语义化解析）
 * @param ecosystem   所属生态
 * @param jar         玩家放进 mods/ 的那个 jar 的文件名；内嵌 jar 为空
 * @param status      三态之一
 * @param detail      出问题时给人看的原因；正常时为空
 * @param failedStage 出问题的阶段（discovery / resolve / remap / instantiate / inject / lifecycle）
 * @param dependents  声明了依赖这个模组的其他模组 id；失败时这是玩家真正会感到「没反应」的部分
 */
public record ModRecord(
		String id,
		String name,
		String version,
		Ecosystem ecosystem,
		String jar,
		ModStatus status,
		String detail,
		String failedStage,
		List<String> dependents) {

	public ModRecord {
		if (id == null || id.isBlank()) throw new IllegalArgumentException("mod id is required");
		if (status == null) throw new IllegalArgumentException("mod status is required");
		if (dependents == null) dependents = List.of();
	}

	public static ModRecord of(String id, String name, String version, Ecosystem ecosystem, String jar) {
		return new ModRecord(id, name == null ? id : name, version == null ? "" : version,
				ecosystem == null ? Ecosystem.UNKNOWN : ecosystem, jar == null ? "" : jar,
				ModStatus.OK, "", "", List.of());
	}

	/** 状态流转；原因与阶段一并留下，绝不出现「失败但没有原因」的记录。 */
	public ModRecord withStatus(ModStatus status, String detail, String stage) {
		if (status == null) throw new IllegalArgumentException("mod status is required");
		return new ModRecord(id, name, version, ecosystem, jar, status,
				detail == null ? "" : detail, stage == null ? "" : stage, dependents);
	}

	public ModRecord withDependents(List<String> dependents) {
		return new ModRecord(id, name, version, ecosystem, jar, status, detail, failedStage,
				dependents == null ? List.of() : List.copyOf(dependents));
	}

	public boolean isProblem() {
		return status.isProblem();
	}
}
