/*
 * Copyright 2026 The Forbric Project
 * Copyright 2026 The UniLoader Project (modifications)
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

package org.uniloader.diagnostics.report;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.uniloader.api.Diagnostics;
import org.uniloader.api.ModCatalog;
import org.uniloader.api.ModRecord;
import org.uniloader.api.ModStatus;

/**
 * 把「这次启动每个 mod 是什么状态」写成玩家事后能找到的文件：{@code mods-report.txt}。
 *
 * <p>移植自 Forbric 的 {@code net.forbric.kernel.boot.KernelLoadReport}（Apache-2.0，保留原版权头），
 * 保留了它的两条原则：
 *
 * <ol>
 *   <li><b>只在有事的时候才留下文件。</b>全绿的一次启动不写文件，只在日志里说一句。
 *       一个「出现就代表有问题」的文件，其存在本身就是信号。</li>
 *   <li><b>绝不因为报告写不出来而破坏它所报告的那次启动。</b>整体包在 try 里。</li>
 * </ol>
 *
 * <p>UL 侧的改动：Forbric 只列出「没完成加载」的 mod；UL 的工程纪律第 6 条要求三态贯穿全链路，
 * 所以这里输出的是<b>全量普查</b>——每个 mod 一行 OK / DEGRADED / FAILED，并附上各阶段耗时
 * （对应阶段 1 的 T1.6）与已知备注。
 */
public final class LoadReport {
	public static final String FILE = "mods-report.txt";

	private static final System.Logger LOG = System.getLogger("org.uniloader.diagnostics.report");

	private static volatile Path rundir;
	private static volatile String lastRendered;
	private static final AtomicBoolean HOOKED = new AtomicBoolean();
	private static final AtomicBoolean REPORTED = new AtomicBoolean();

	private LoadReport() {
	}

	/** 由启动流程设置实例目录——只有它知道目录在哪。同时挂上 shutdown hook。 */
	public static void setRunDir(Path dir) {
		rundir = dir;

		// 没走到「加载完成」就退出的那次启动，恰恰是玩家最需要这个文件的一次。
		if (HOOKED.compareAndSet(false, true)) {
			Runtime.getRuntime().addShutdownHook(new Thread(LoadReport::writeEvidence, "uniloader-load-report"));
		}
	}

	/** 加载正常收尾时调用。 */
	public static void write() {
		writeTo(target(), true);
	}

	/**
	 * 在「加载尚未完成」的边界上写证据：游戏 main 之前，以及 shutdown hook 里。
	 * 它永远不会说「每个 mod 都加载完了」，也不会消耗那句话的一次性机会。
	 */
	public static void writeEvidence() {
		writeTo(target(), false);
	}

	private static Path target() {
		Path dir = rundir;
		return dir == null ? null : dir.resolve(".uniloader").resolve(FILE);
	}

	/** 目标显式给出（null 表示只写日志），便于测试观察自己拥有的那个文件。 */
	static void writeTo(Path file, boolean loadingFinished) {
		try {
			List<ModRecord> all = ModCatalog.everything();
			List<ModRecord> problems = ModCatalog.problems();
			boolean clean = problems.isEmpty();

			if (clean && loadingFinished && REPORTED.compareAndSet(false, true)) {
				LOG.log(System.Logger.Level.INFO, "[UniLoader/Load] every mod finished loading");
			}

			if (clean) {
				// 全绿不留下文件：文件的存在本身就是信号。
				if (file != null) Files.deleteIfExists(file);
				lastRendered = null;
				return;
			}

			String rendered = render(chinese(), all, Diagnostics.timings(), Diagnostics.notes());

			synchronized (LoadReport.class) {
				if (rendered.equals(lastRendered)) return;

				List<String> ids = new java.util.ArrayList<>();

				for (ModRecord r : problems) ids.add(r.id());

				LOG.log(System.Logger.Level.WARNING, "[UniLoader/Load] %d mod(s) did not load cleanly: %s — details in .uniloader/%s",
						problems.size(), String.join(", ", ids), FILE);

				REPORTED.set(true);
				lastRendered = rendered;
				if (file == null) return;

				Files.createDirectories(file.getParent());
				Files.writeString(file, rendered, StandardCharsets.UTF_8);
			}
		} catch (Throwable t) {
			LOG.log(System.Logger.Level.DEBUG, "[UniLoader/Load] could not write the load report: " + t);
		}
	}

	private static boolean chinese() {
		return "zh".equalsIgnoreCase(Locale.getDefault().getLanguage());
	}

	/** 包内可见，便于两种语言版本都能被断言。 */
	static String render(boolean zh, List<ModRecord> all, Map<String, Long> timings, List<String> notes) {
		StringBuilder sb = new StringBuilder();
		long ok = all.stream().filter(r -> r.status() == ModStatus.OK).count();
		long degraded = all.stream().filter(r -> r.status() == ModStatus.DEGRADED).count();
		long failed = all.stream().filter(r -> r.status() == ModStatus.FAILED).count();

		if (zh) {
			sb.append("UniLoader 模组加载报告\n");
			sb.append("======================\n\n");
			sb.append("一共 ").append(all.size()).append(" 个 mod：")
					.append(ok).append(" 个正常，").append(degraded).append(" 个降级，").append(failed).append(" 个失败。\n\n");
			sb.append("状态只有三种：OK（正常）/ DEGRADED（部分功能不可用，但游戏能跑）/ FAILED（拒绝加载）。\n");
			sb.append("不存在第四种“看起来成功”。\n\n");
		} else {
			sb.append("UniLoader mod load report\n");
			sb.append("=========================\n\n");
			sb.append(all.size()).append(" mod(s): ").append(ok).append(" OK, ")
					.append(degraded).append(" DEGRADED, ").append(failed).append(" FAILED.\n\n");
			sb.append("There are exactly three states: OK / DEGRADED (partly unusable, game still runs) /\n");
			sb.append("FAILED (refused to load). There is no fourth \"seems fine\".\n\n");
		}

		for (ModRecord r : all) {
			sb.append("  [").append(r.status().name()).append("] ").append(r.name());
			if (!r.name().equals(r.id())) sb.append("  (").append(r.id()).append(')');
			sb.append('\n');
			sb.append("      ").append(zh ? "生态：" : "ecosystem: ").append(r.ecosystem().id());
			if (!r.version().isEmpty()) sb.append(zh ? "　版本：" : "  version: ").append(r.version());
			if (!r.jar().isEmpty()) sb.append(zh ? "　文件：" : "  jar: ").append(r.jar());
			sb.append('\n');

			if (r.isProblem()) {
				sb.append("      ").append(zh ? "阶段：" : "stage: ").append(r.failedStage()).append('\n');
				sb.append("      ").append(zh ? "原因：" : "reason: ").append(r.detail()).append('\n');

				if (!r.dependents().isEmpty()) {
					sb.append("      ").append(zh ? "还有 " : "")
							.append(r.dependents().size())
							.append(zh ? " 个 mod 声明依赖它：" : " other mod(s) declare they need this one: ")
							.append(String.join(zh ? "、" : ", ", r.dependents())).append('\n');
				}
			}

			sb.append('\n');
		}

		if (timings != null && !timings.isEmpty()) {
			sb.append(zh ? "各阶段耗时（毫秒）\n------------------\n" : "Stage timings (ms)\n------------------\n");

			for (Map.Entry<String, Long> e : timings.entrySet()) {
				sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
			}

			sb.append('\n');
		}

		if (notes != null && !notes.isEmpty()) {
			sb.append(zh ? "备注\n----\n" : "Notes\n-----\n");

			for (String note : notes) sb.append("  ").append(note).append('\n');

			sb.append('\n');
		}

		if (zh) {
			sb.append("怎么办\n------\n");
			sb.append("在 logs/latest.log 里搜上面的 mod id，那里有具体的报错。\n");
			sb.append("常见原因是这个 mod 是给别的 Minecraft 版本做的，或者它需要的另一个 mod 没装。\n");
			sb.append("UniLoader 不会因为一个 mod 出问题就停下来：把它从 mods 文件夹里拿出来，其余部分照常能玩。\n");
		} else {
			sb.append("What to do\n----------\n");
			sb.append("Search logs/latest.log for the mod ids above; the actual error is there.\n");
			sb.append("The usual reasons are that the mod was built for a different Minecraft version, or that\n");
			sb.append("something it needs is not installed. UniLoader does not stop at the first mod that goes\n");
			sb.append("wrong: taking it out of the mods folder leaves the rest of the game working.\n");
		}

		return sb.toString();
	}

	/** 测试接缝：忘记已经写过的内容，回到一次全新启动的状态。 */
	static void reset() {
		synchronized (LoadReport.class) {
			lastRendered = null;
			REPORTED.set(false);
			rundir = null;
		}
	}
}
