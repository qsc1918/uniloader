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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 各阶段耗时与备注的收集点（阶段 1 的 T1.6 会把它接到内核的入口点调用处）。
 *
 * <p>为什么现在就要有：性能预算（T10.4）要求「冷/热启动耗时、Mixin 应用耗时」进 CI 回归，
 * 而预算必须先有基线才有阈值。阶段 0 的 D1 实验也用同一套计时，两个数才对得上。
 */
public final class Diagnostics {
	private static final Map<String, Long> TIMINGS = new LinkedHashMap<>();
	private static final Map<String, Long> ACTIVE = new LinkedHashMap<>();
	private static final List<String> NOTES = new java.util.concurrent.CopyOnWriteArrayList<>();

	private Diagnostics() {
	}

	public static synchronized void begin(String stage) {
		ACTIVE.put(stage, System.nanoTime());
	}

	/** @return 该阶段耗时（毫秒）；未 begin 过则返回 -1，绝不抛异常 */
	public static synchronized long end(String stage) {
		Long start = ACTIVE.remove(stage);
		if (start == null) return -1;

		long millis = (System.nanoTime() - start) / 1_000_000L;
		TIMINGS.merge(stage, millis, Long::sum);
		return millis;
	}

	/**
	 * 直接登记一段已知耗时（T1.6：内核把自己测出来的耗时通过证据文件喂进来时用这条）。
	 *
	 * <p>与 {@link #begin}/{@link #end} 并存的原因：内核与诊断模块是两个构建，
	 * 内核只能把「测完了的数」交过来，不能反向依赖这里。
	 */
	public static synchronized void record(String stage, long millis) {
		if (stage == null) return;

		TIMINGS.merge(stage, Math.max(0L, millis), Long::sum);
	}

	public static synchronized Map<String, Long> timings() {
		return new LinkedHashMap<>(TIMINGS);
	}

	public static void note(String message) {
		if (message != null) NOTES.add(message);
	}

	public static List<String> notes() {
		return List.copyOf(NOTES);
	}

	/** 测试与重新加载用的接缝。 */
	public static synchronized void reset() {
		TIMINGS.clear();
		ACTIVE.clear();
		NOTES.clear();
	}
}
