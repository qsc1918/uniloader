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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 本次启动里所有模组的登记表，是诊断与崩溃归因的唯一事实来源。
 *
 * <p>崩溃归因只认这里有的 id（移植自 Forbric 的原则：一个 confidently wrong 的模组名比没有
 * 名字更糟），所以「能不能被指认」等价于「在不在表里」。这也顺带提供了免罪名单：内核自己、
 * Minecraft 的库、合并基底都不是模组，不在表里，也就永远不会被误指。
 *
 * <p>线程安全：发现与加载阶段有并发，登记与更新都在锁内完成。
 */
public final class ModCatalog {
	private static final Map<String, ModRecord> BY_ID = new LinkedHashMap<>();

	private ModCatalog() {
	}

	/** 登记一个新模组；同 id 后来者不覆盖先到者（先到者是求解器选定的那一份）。 */
	public static synchronized void register(ModRecord record) {
		BY_ID.putIfAbsent(record.id(), record);
	}

	/**
	 * 更新一个已知模组的状态。未登记的 id 会被忽略——诊断绝不能凭空造出一行记录。
	 *
	 * @return 更新后的记录，若 id 未知则返回 {@link Optional#empty()}
	 */
	public static synchronized Optional<ModRecord> update(String id, ModStatus status, String detail, String stage) {
		ModRecord current = BY_ID.get(id);
		if (current == null) return Optional.empty();

		// 已经失败的不再被降级覆盖：FAILED 是终态。
		if (current.status() == ModStatus.FAILED) return Optional.of(current);

		ModRecord updated = current.withStatus(status, detail, stage);
		BY_ID.put(id, updated);
		return Optional.of(updated);
	}

	public static synchronized void setDependents(String id, List<String> dependents) {
		ModRecord current = BY_ID.get(id);
		if (current != null) BY_ID.put(id, current.withDependents(dependents));
	}

	public static synchronized List<ModRecord> everything() {
		return List.copyOf(BY_ID.values());
	}

	public static synchronized List<ModRecord> problems() {
		List<ModRecord> out = new ArrayList<>();

		for (ModRecord r : BY_ID.values()) {
			if (r.isProblem()) out.add(r);
		}

		return out;
	}

	public static synchronized Optional<ModRecord> find(String id) {
		return Optional.ofNullable(BY_ID.get(id));
	}

	/** 按 jar 文件名反查；栈帧里能拿到的只有文件名，这是崩溃归因的入口。 */
	public static synchronized List<ModRecord> byJar(String jarFileName) {
		if (jarFileName == null || jarFileName.isEmpty()) return List.of();

		List<ModRecord> out = new ArrayList<>();

		for (ModRecord r : BY_ID.values()) {
			if (jarFileName.equals(r.jar())) out.add(r);
		}

		return out;
	}

	public static synchronized int size() {
		return BY_ID.size();
	}

	/** 测试与重新加载用的接缝：清空全部登记。 */
	public static synchronized void reset() {
		BY_ID.clear();
	}
}
