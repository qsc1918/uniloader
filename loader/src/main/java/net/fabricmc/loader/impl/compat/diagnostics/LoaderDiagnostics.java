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

package net.fabricmc.loader.impl.compat.diagnostics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

/**
 * UniLoader fork patch (T1.6)：内核侧的诊断埋点，只负责「把事实记下来并落盘」。
 *
 * <p>为什么不直接调用 {@code org.uniloader.api.Diagnostics}：内核是一个独立构建的组合构建
 * （{@code includeBuild('loader')}），它不能反向依赖宿主构建里的模块；而且内核 jar 会被
 * proguard 处理，再往里塞诊断库只会让许可证边界更难看。所以分工是：
 *
 * <ul>
 *   <li><b>内核（本类）</b>：计时、归因、把证据写成一份纯文本的
 *       {@code .uniloader/kernel-diagnostics.txt}；</li>
 *   <li><b>{@code tooling/load-report}</b>：读这份证据，喂给阶段 0 已经写好的
 *       {@code ModCatalog} / {@code Diagnostics} / {@code LoadReport}，渲染
 *       {@code mods-report.txt}。</li>
 * </ul>
 *
 * <p>三条硬约束：
 * <ol>
 *   <li>本类的任何方法都<b>不抛异常</b>——诊断绝不能破坏它所报告的那次启动；</li>
 *   <li>状态只有 OK / DEGRADED / FAILED（工程纪律第 6 条），且失败必须带 mod id、阶段与原因；</li>
 *   <li>没走到「加载完成」就退出的那次启动恰恰最需要证据，所以出口装在 shutdown hook 上。</li>
 * </ol>
 */
public final class LoaderDiagnostics {
	public static final String DIR_NAME = ".uniloader";
	public static final String FILE_NAME = "kernel-diagnostics.txt";
	public static final String FORMAT_VERSION = "ul-kernel-diagnostics-1";

	public static final String STAGE_DISCOVERY = "discovery";
	public static final String STAGE_RESOLVE = "resolve";
	public static final String STAGE_REMAP = "remap";
	public static final String STAGE_INSTANTIATE = "instantiate";
	public static final String STAGE_LIFECYCLE = "lifecycle";

	public static final String STATUS_OK = "OK";
	public static final String STATUS_DEGRADED = "DEGRADED";
	public static final String STATUS_FAILED = "FAILED";

	public static final LogCategory LOG_CATEGORY = LogCategory.createCustom("UniLoader", "Diagnostics");

	private static final Object LOCK = new Object();
	private static final Map<String, Long> TIMINGS = new LinkedHashMap<>();
	private static final Map<String, Long> ACTIVE = new LinkedHashMap<>();
	private static final Set<String> MODS = new LinkedHashSet<>();
	private static final List<String> STATUSES = new ArrayList<>();
	private static final List<String> NOTES = new ArrayList<>();
	private static final AtomicBoolean HOOKED = new AtomicBoolean();

	private static volatile Path runDir;

	private LoaderDiagnostics() {
	}

	/**
	 * 设置实例目录并挂上 shutdown hook。
	 *
	 * <p>hook 必须在这里挂而不是等加载完成：崩溃时最需要这份证据，而崩溃时没人会来调用导出。
	 */
	public static void setRunDir(Path dir) {
		runDir = dir;

		if (HOOKED.compareAndSet(false, true)) {
			try {
				Runtime.getRuntime().addShutdownHook(new Thread(LoaderDiagnostics::export, "uniloader-loader-diagnostics"));
			} catch (Throwable t) {
				Log.warn(LOG_CATEGORY, "无法挂上诊断的 shutdown hook：%s", t);
			}
		}
	}

	public static void begin(String stage) {
		synchronized (LOCK) {
			ACTIVE.put(stage, System.nanoTime());
		}
	}

	/** @return 该阶段耗时（毫秒）；没 begin 过返回 -1，绝不抛异常。 */
	public static long end(String stage) {
		long millis;

		synchronized (LOCK) {
			Long start = ACTIVE.remove(stage);
			if (start == null) return -1;

			millis = (System.nanoTime() - start) / 1_000_000L;
			TIMINGS.merge(stage, millis, Long::sum);
		}

		Log.debug(LOG_CATEGORY, "阶段 %s 耗时 %d ms", stage, millis);
		return millis;
	}

	/** 记一次「带名字的阶段耗时」，例如某个入口点在某 mod 上花了多久。 */
	public static void record(String label, long millis) {
		synchronized (LOCK) {
			TIMINGS.merge(label, Math.max(0L, millis), Long::sum);
		}
	}

	/** 登记一个模组。先到者为准，与求解器选定的那一份保持一致。 */
	public static void registerMod(String id, String name, String version, String ecosystem, String jar) {
		synchronized (LOCK) {
			MODS.add(join("mod", nullToEmpty(id), nullToEmpty(name), nullToEmpty(version),
					nullToEmpty(ecosystem), nullToEmpty(jar)));
		}
	}

	/**
	 * 记一次失败。
	 *
	 * @param stage  必须是 discovery / resolve / remap / instantiate / lifecycle 之一
	 * @param detail 人能看懂的原因；cause 为 null 时必须自己说清楚
	 */
	public static void fail(String modId, String stage, String detail, Throwable cause) {
		String text = compose(detail, cause, "失败");

		synchronized (LOCK) {
			STATUSES.add(join("status", nullToEmpty(modId), STATUS_FAILED, nullToEmpty(stage), text));
		}

		Log.error(LOG_CATEGORY, "模组 %s 在 %s 阶段失败：%s", modId, stage, text);
	}

	/** 记一次降级：功能部分缺失，但游戏能继续跑。 */
	public static void degrade(String modId, String stage, String detail) {
		degrade(modId, stage, detail, null);
	}

	/** 记一次降级，并附上原始异常。 */
	public static void degrade(String modId, String stage, String detail, Throwable cause) {
		String text = compose(detail, cause, "降级");

		synchronized (LOCK) {
			STATUSES.add(join("status", nullToEmpty(modId), STATUS_DEGRADED, nullToEmpty(stage), text));
		}

		Log.warn(LOG_CATEGORY, "模组 %s 在 %s 阶段降级：%s", modId, stage, text);
	}

	/**
	 * 拼出「人能看懂的原因」。
	 *
	 * <p>失败但无原因比崩溃更糟——它会让后面所有诊断都失去着力点，
	 * 所以 detail 与 cause 都为空时也要写出一句「内核的 bug」而不是留空。
	 */
	private static String compose(String detail, Throwable cause, String kind) {
		String text = detail == null ? "" : detail;

		if (cause != null) {
			String causeText = cause.getClass().getName() + ": " + cause.getMessage();
			text = text.isEmpty() ? causeText : text + "（" + causeText + "）";
		}

		if (text.isEmpty()) text = kind + "但没有给出原因——这是内核的 bug，请把日志发给 UL";

		return text;
	}

	public static void note(String message) {
		if (message == null) return;

		synchronized (LOCK) {
			NOTES.add(join("note", message));
		}
	}

	/**
	 * 入口点属于哪个阶段。
	 *
	 * <p>{@code main} / {@code client} / {@code server} / {@code preLaunch} 是模组自己的初始化，
	 * 抛异常意味着这个模组根本没起来，是 instantiate 阶段的 FAILED；其余自定义入口点（后续阶段的
	 * Forge 生命周期事件会走这里）发生在世界已经能进之后，只算 lifecycle 阶段的 DEGRADED。
	 */
	public static String stageForEntrypoint(String key) {
		if ("main".equals(key) || "client".equals(key) || "server".equals(key) || "preLaunch".equals(key)) {
			return STAGE_INSTANTIATE;
		}

		return STAGE_LIFECYCLE;
	}

	/** 写出证据文件。任何失败都只记日志——诊断不能破坏启动。 */
	public static void export() {
		Path dir = runDir;

		if (dir == null) {
			Log.debug(LOG_CATEGORY, "还没有实例目录，跳过导出");
			return;
		}

		List<String> lines;

		synchronized (LOCK) {
			lines = new ArrayList<>(2 + MODS.size() + TIMINGS.size() + STATUSES.size() + NOTES.size());
			lines.add(FORMAT_VERSION);

			for (Map.Entry<String, Long> e : TIMINGS.entrySet()) {
				lines.add(join("timing", e.getKey(), Long.toString(e.getValue())));
			}

			lines.addAll(MODS);
			lines.addAll(STATUSES);
			lines.addAll(NOTES);
		}

		try {
			Path out = dir.resolve(DIR_NAME).resolve(FILE_NAME);
			Files.createDirectories(out.getParent());
			Files.write(out, lines, StandardCharsets.UTF_8);
			Log.debug(LOG_CATEGORY, "诊断证据已写入 %s（%d 行）", out, lines.size());
		} catch (IOException | RuntimeException t) {
			Log.warn(LOG_CATEGORY, "无法写出诊断证据：%s", t);
		}
	}

	/** 测试接缝。 */
	public static void reset() {
		synchronized (LOCK) {
			TIMINGS.clear();
			ACTIVE.clear();
			MODS.clear();
			STATUSES.clear();
			NOTES.clear();
		}
	}

	private static String join(String kind, String... values) {
		StringBuilder sb = new StringBuilder(kind);

		for (String v : values) {
			sb.append('\t').append(escape(v));
		}

		return sb.toString();
	}

	/** 值里出现制表符或换行会把这份证据的格式打乱，一律转义。 */
	private static String escape(String value) {
		return value.replace("\\", "\\\\")
				.replace("\t", "\\t")
				.replace("\n", "\\n")
				.replace("\r", "\\r");
	}

	private static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}
}
