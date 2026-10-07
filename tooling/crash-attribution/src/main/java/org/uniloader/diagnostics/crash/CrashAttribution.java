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

package org.uniloader.diagnostics.crash;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.uniloader.api.ModCatalog;
import org.uniloader.api.ModRecord;

/**
 * 崩溃之后，说出「可能是哪些 mod」。
 *
 * <p>移植自 Forbric 的 {@code net.forbric.kernel.boot.CrashAttribution}（Apache-2.0，保留原版权头），
 * 保持了它的三条设计原则，因为它们是被实测数据支撑的：
 *
 * <ol>
 *   <li><b>读报告，不接崩溃。</b> Minecraft 自己捕获致命错误、写报告、然后 {@code System.exit}，
 *       所以未捕获异常处理器永远看不到真正重要的那次崩溃。放在 shutdown hook 里跑，文件已经落盘完整，
 *       而且每一次正常启动零开销。</li>
 *   <li><b>只做连接，绝不从文件名里猜 mod id。</b> jar 文件名不是 mod id —— 名字里可能带着别的家族的 id，
 *       被改成内容哈希的 jar 也真实存在。所以每一个命中的名字都要回 {@link ModCatalog} 查表，
 *       查不到就当没看见。一个 confidently wrong 的模组名比没有名字更糟。</li>
 *   <li><b>顺带的免罪名单。</b> 内核自己、Minecraft 的库都不是模组，不在表里，也就永远不会被误指。</li>
 * </ol>
 *
 * <p>UL 侧的改动：数据来源换成 {@link ModCatalog}；「mixin 重叠」的信号源改为可注入的
 * {@link #setOverlapSource(Function)}（真实的重叠清单要等阶段 7 的 T7.4 冲突检测才有），
 * 未注入时该信号静默不参与，而不是凭空生成嫌疑。
 */
public final class CrashAttribution {
	/** {@code -Dul.crashAnalysis=off}：报告照写，我们不读。 */
	public static final String SWITCH = "ul.crashAnalysis";

	/** 与人读的文件同名的机器产出，供下一次启动直接给出「先关掉这几个」的建议。 */
	public static final String FILE = "crash-analysis.txt";
	static final String JSON = "crash-suspects.json";

	/** 最多点名几个。再多就从「一个答案」变成「第二份清单」了。 */
	static final int MOST = 5;

	/** 关掉某个 mod 的清单文件（放在 mods/ 旁边）。 */
	static final String DISABLED_FILE = "ul-disabled.txt";

	/** 栈帧里的 jar：{@code ~[sodium-26.3.jar:?]}。 */
	private static final Pattern FRAME_JAR = Pattern.compile("\\[([^\\[\\]:]+\\.jar):[^\\[\\]]*\\]");

	/** mixin 处理器跑在别人的类里：{@code handler$chm000$sodium$loadConfig}。 */
	private static final Pattern MIXIN_HANDLER = Pattern.compile("\\$[a-z]{3}\\d{3}\\$([A-Za-z0-9_\\-]+)\\$");

	/** Mixin 自己的话，在异常消息里而不是栈帧里：{@code from mod sodium}。 */
	private static final Pattern FROM_MOD = Pattern.compile("from mod ([A-Za-z0-9_\\-]+)");

	/** 报错直接点名冲突双方：{@code Sources: chloride and cwb}。 */
	private static final Pattern CLASHING = Pattern.compile("Sources?: ([A-Za-z0-9_\\-]+(?:(?:, and |, ?| and )[A-Za-z0-9_\\-]+)+)");

	/** 栈帧的类与方法：{@code \tat net.minecraft.Foo$Bar.tick(Foo.java:1)}。 */
	private static final Pattern FRAME_METHOD = Pattern.compile("^\\s*at (?:[^\\s(]*/)?([\\w$.]+)\\.([\\w$<>]+)\\(");

	static final String CLASH = "the error names it as clashing with another mod";
	static final String OVERLAP = "its mixin and another mod's collide in a method the crash went through";
	static final String MIXIN_RUNNING = "its mixin was running";
	static final String MIXIN_NAMED = "Mixin named it";
	static final String CODE_IN_CRASH = "its code is in the crash";

	private static final System.Logger LOG = System.getLogger("org.uniloader.diagnostics.crash");

	private static volatile Path rundir;
	private static volatile long startedAt;

	/** 真实来源要等阶段 7 的 T7.4 冲突检测；未注入时该信号不参与归因。 */
	private static volatile Function<String, List<Overlap>> overlapSource;

	private CrashAttribution() {
	}

	/** 一次冲突：双方 id，以及冲突所在的 {@code Class.method}。 */
	public record Overlap(String firstId, String secondId, String where) {
	}

	/**
	 * 一个被点名的 mod，以及是什么点名了它。
	 *
	 * @param depth 它第一次出现在栈里的深度；排序依据。抛异常的那帧比调用它的那帧更像凶手。
	 * @param jar   玩家要从 mods/ 里拿出来的那个 jar 的文件名
	 */
	public record Suspect(String modId, String name, String version, String reason, int depth, String jar, Collision collision) {
		Suspect(String modId, String name, String version, String reason, int depth) {
			this(modId, name, version, reason, depth, "", null);
		}
	}

	/** 对 {@link #OVERLAP} 型嫌疑：另一方的展示名，以及冲突位置。 */
	public record Collision(String other, String method) {
	}

	/** 注入 mixin 重叠信号源（{@code Class.method} → 重叠清单）。 */
	public static void setOverlapSource(Function<String, List<Overlap>> source) {
		overlapSource = source;
	}

	/** 由启动流程设置实例目录——只有它知道目录在哪。同时挂上 shutdown hook。 */
	public static void setRunDir(Path dir) {
		setRunDir(dir, System.currentTimeMillis());
		Runtime.getRuntime().addShutdownHook(new Thread(CrashAttribution::run, "uniloader-crash-analysis"));
	}

	/** 同上，但启动时间显式给出，便于测试掌握比较的两侧。 */
	static void setRunDir(Path dir, long bootMillis) {
		rundir = dir;
		startedAt = bootMillis;
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(SWITCH, "on"));
	}

	/**
	 * 读本次启动产生的崩溃报告（如果有），把答案写在它旁边。
	 *
	 * <p>整体包在 try 里。这段代码跑在「已经出过事、JVM 正在退出」的时刻；一个会抛异常的
	 * 诊断会把玩家能读懂的崩溃报告换成一段他们读不懂的 shutdown hook 栈。
	 */
	static void run() {
		Path dir = rundir;
		if (dir == null || !enabled()) return;

		try {
			Path report = crashReportFromThisRun(dir.resolve("crash-reports"), startedAt);
			if (report == null) return;

			List<Suspect> suspects = suspects(Files.readString(report, StandardCharsets.UTF_8));
			String rendered = render(chinese(), report.getFileName().toString(), suspects);

			Path out = dir.resolve(".uniloader").resolve(FILE);
			Files.createDirectories(out.getParent());
			Files.writeString(out, rendered, StandardCharsets.UTF_8);
			Files.writeString(out.resolveSibling(JSON), json(report.getFileName().toString(), suspects), StandardCharsets.UTF_8);

			// 既写文件也打印：启动器在崩溃时会把 stdout 的尾巴给玩家看，那是他们正在看的地方。
			System.out.println();
			System.out.print(rendered);
			LOG.log(System.Logger.Level.WARNING, "[UniLoader/Crash] %s — analysis in .uniloader/%s",
					suspects.isEmpty() ? "no mod could be named from the crash report" : summary(suspects), FILE);
		} catch (Throwable t) {
			LOG.log(System.Logger.Level.DEBUG, "[UniLoader/Crash] could not analyse the crash report: " + t);
		}
	}

	/** 机器形式的答案，见 {@link #JSON}。 */
	static String json(String reportName, List<Suspect> suspects) {
		List<Object> rows = new ArrayList<>();

		for (Suspect s : suspects) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("modId", s.modId());
			row.put("name", s.name());
			row.put("jar", s.jar());
			row.put("reason", s.reason());
			row.put("depth", s.depth());
			rows.add(row);
		}

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("schema", 1);
		out.put("report", reportName);
		out.put("clash", clashing(suspects) >= 2);
		out.put("suspects", rows);
		return encodeJson(out) + "\n";
	}

	/**
	 * 让玩家「先关掉这几个再开一次」的那些 jar 文件名，按嫌疑顺序去重。
	 *
	 * <p>冲突情形下只列出除第一个之外的各方：报错说的是它们不能装在一起，保留第一个即可。
	 */
	static List<String> startWithout(List<Suspect> suspects) {
		boolean clash = clashing(suspects) >= 2;
		LinkedHashSet<String> jars = new LinkedHashSet<>();
		String kept = null;

		for (Suspect s : suspects) {
			if (clash && !CLASH.equals(s.reason())) continue;

			if (clash && kept == null) {
				kept = s.jar();
				continue;
			}

			if (!s.jar().isEmpty() && !s.jar().equals(kept)) jars.add(s.jar());
		}

		return List.copyOf(jars);
	}

	private static String summary(List<Suspect> suspects) {
		List<String> ids = new ArrayList<>();

		for (Suspect s : suspects) ids.add(s.modId());

		return "the crash points at " + String.join(", ", ids);
	}

	/**
	 * 本次启动写的那份报告，没有则 null。
	 *
	 * <p>按修改时间取最新，并且必须晚于启动时刻。后一半是整个功能的正确性所在：
	 * 实例目录会攒下历史上每一次崩溃报告，否则「最新那份」在上一次正常退出时也会给出一个答案。
	 */
	static Path crashReportFromThisRun(Path dir, long bootMillis) throws IOException {
		if (!Files.isDirectory(dir)) return null;

		try (Stream<Path> files = Files.list(dir)) {
			return files.filter(Files::isRegularFile)
					.filter(p -> p.getFileName().toString().endsWith(".txt"))
					.filter(p -> modifiedAt(p).toEpochMilli() >= bootMillis)
					.max(Comparator.comparing(CrashAttribution::modifiedAt))
					.orElse(null);
		}
	}

	private static Instant modifiedAt(Path file) {
		try {
			return Files.getLastModifiedTime(file).toInstant();
		} catch (IOException unreadable) {
			return Instant.EPOCH;
		}
	}

	/**
	 * 崩溃报告指向的 mod，最可能的在前。
	 *
	 * <p>只读顶部的异常链，不读整份文件。分隔符以下全是 Minecraft 自己的系统报告——
	 * 线程 dump、mod 列表、显卡型号——那里出现的 mod 只是「装了」，不是「参与了」。
	 */
	static List<Suspect> suspects(String crashReport) {
		String trace = exceptionChain(crashReport);
		Map<String, Suspect> byId = new LinkedHashMap<>();

		int depth = 0;

		for (String line : trace.split("\n")) {
			depth++;

			// 处理器形式优先：这类帧带着原版的 jar，读它的括号会把 mod 的 mixin 赖到 Minecraft 头上。
			Matcher handler = MIXIN_HANDLER.matcher(line);

			while (handler.find()) {
				remember(byId, handler.group(1), MIXIN_RUNNING, depth);
			}

			Matcher fromMod = FROM_MOD.matcher(line);

			while (fromMod.find()) {
				remember(byId, fromMod.group(1), MIXIN_NAMED, depth);
			}

			if (!line.startsWith("\tat ")) {
				Matcher clashing = CLASHING.matcher(line);

				while (clashing.find()) {
					for (String id : clashing.group(1).split(", and |, ?| and ")) remember(byId, id.strip(), CLASH, depth);
				}
			} else {
				Matcher frame = FRAME_METHOD.matcher(line);

				if (frame.find()) {
					for (Overlap o : overlapsFor(frame.group(1) + "." + frame.group(2))) {
						remember(byId, o.firstId(), OVERLAP, depth, new Collision(displayName(o.secondId()), o.where().replace('$', '.')));
						remember(byId, o.secondId(), OVERLAP, depth, new Collision(displayName(o.firstId()), o.where().replace('$', '.')));
					}
				}
			}

			Matcher jar = FRAME_JAR.matcher(line);

			while (jar.find()) {
				for (ModRecord record : ModCatalog.byJar(jar.group(1))) {
					remember(byId, record.id(), CODE_IN_CRASH, depth);
				}
			}
		}

		List<Suspect> found = new ArrayList<>(byId.values());
		found.sort(Comparator.comparingInt(Suspect::depth));
		return found.size() > MOST ? found.subList(0, MOST) : found;
	}

	private static List<Overlap> overlapsFor(String classDotMethod) {
		Function<String, List<Overlap>> source = overlapSource;
		if (source == null) return List.of();

		List<Overlap> found = source.apply(classDotMethod);
		return found == null ? List.of() : found;
	}

	/** 记下一个嫌疑：表里得有它，且没有更浅的同名记录。 */
	private static void remember(Map<String, Suspect> byId, String modId, String reason, int depth) {
		remember(byId, modId, reason, depth, null);
	}

	private static void remember(Map<String, Suspect> byId, String modId, String reason, int depth, Collision collision) {
		if (modId == null || modId.isBlank() || byId.containsKey(modId)) return;

		ModCatalog.find(modId).ifPresent(record -> byId.put(record.id(), new Suspect(
				record.id(), record.name(), record.version(), reason, depth, record.jar(), collision)));
	}

	private static String displayName(String modId) {
		return ModCatalog.find(modId).map(ModRecord::name).orElse(modId);
	}

	/**
	 * 只取异常链，也就是分隔符以上的部分。
	 *
	 * <p>找不到分隔符（文件被截断、格式变了）就读全文：宁可多报，不可漏报——
	 * 这个文件唯一的职责就是指个方向。
	 */
	static String exceptionChain(String crashReport) {
		int divider = crashReport.indexOf("\n------------");
		return divider < 0 ? crashReport : crashReport.substring(0, divider);
	}

	private static boolean chinese() {
		return "zh".equalsIgnoreCase(Locale.getDefault().getLanguage());
	}

	/** 包内可见，便于两种语言版本都能被断言，而不必去改系统 locale。 */
	static String render(boolean zh, String reportName, List<Suspect> suspects) {
		StringBuilder sb = new StringBuilder();

		if (zh) {
			sb.append("UniLoader 崩溃分析\n");
			sb.append("===================\n\n");

			if (suspects.isEmpty()) {
				sb.append("这次崩溃里没有出现任何一个你装的 mod，所以说不准是哪个 mod 的问题 —— 也可能不是 mod 的问题。\n");
				sb.append("完整的报错在 crash-reports/").append(reportName).append(" 里。\n");
				return sb.toString();
			}

			if (clashing(suspects) >= 2) {
				sb.append("这几个 mod 互相冲突 —— 报错里把它们点名放在了一起：\n\n");
			} else {
				sb.append(suspects.size() == 1 ? "可能是这个 mod 的问题：\n\n" : "可能是这几个 mod 的问题，越靠前越可能：\n\n");
			}

			for (Suspect s : suspects) {
				sb.append("  ").append(s.name());
				if (!s.version().isEmpty()) sb.append(' ').append(s.version());
				sb.append("  (").append(s.modId()).append(")\n");
				sb.append("    ").append(s.collision() == null ? zhReason(s.reason())
						: "它和 " + s.collision().other() + " 的 mixin 都改了 " + s.collision().method()
								+ "，两边不能同时生效，而这次崩溃正好经过这个方法").append("\n\n");
			}

			sb.append("怎么办\n");
			sb.append("------\n");

			if (clashing(suspects) >= 2) {
				sb.append(clashNames(suspects, "、")).append(" 不能装在一起：只留其中一个，把其余的从 mods 文件夹里拿出来再开一次。\n");
			} else {
				sb.append("先把最上面那个 mod 从 mods 文件夹里拿出来再开一次。还是崩就换下一个。\n");
			}

			for (String pair : collisions(suspects, true)) {
				sb.append(pair).append("：先只留其中一个再开一次。\n");
			}

			List<String> lines = startWithout(suspects);

			if (!lines.isEmpty()) {
				sb.append("也可以不挪文件：把下面几行加进 mods 文件夹旁边的 ").append(DISABLED_FILE)
						.append("，游戏就会不加载它们启动（删掉一行就能重新启用）：\n");

				for (String line : lines) sb.append("    ").append(line).append('\n');
			}

			sb.append("这只是个猜测：它说的是这些 mod 出现在了报错里，不是说它们一定有毛病。\n");
			sb.append("完整的报错在 crash-reports/").append(reportName).append(" 里。\n");
			return sb.toString();
		}

		sb.append("UniLoader crash analysis\n");
		sb.append("========================\n\n");

		if (suspects.isEmpty()) {
			sb.append("No mod you installed appears in this crash, so there is nothing to point at — it may not\n");
			sb.append("be a mod at all. The full error is in crash-reports/").append(reportName).append(".\n");
			return sb.toString();
		}

		if (clashing(suspects) >= 2) {
			sb.append("These mods clash with each other — the error names them together:\n\n");
		} else {
			sb.append(suspects.size() == 1 ? "This mod might be the one:\n\n"
					: "It might be one of these, likeliest first:\n\n");
		}

		for (Suspect s : suspects) {
			sb.append("  ").append(s.name());
			if (!s.version().isEmpty()) sb.append(' ').append(s.version());
			sb.append("  (").append(s.modId()).append(")\n");
			sb.append("    ").append(s.collision() == null ? s.reason()
					: "its mixin and one from " + s.collision().other() + " both change " + s.collision().method()
							+ " in ways that cannot both take effect, and the crash went through it").append("\n\n");
		}

		sb.append("What to do\n");
		sb.append("----------\n");

		if (clashing(suspects) >= 2) {
			sb.append(clashNames(suspects, ", ")).append(" cannot be installed together: keep one of them, take the\n");
			sb.append("other").append(clashing(suspects) > 2 ? "s" : "").append(" out of your mods folder and start again.\n");
		} else {
			sb.append("Take the first one out of your mods folder and start again. If it still crashes, try the next.\n");
		}

		for (String pair : collisions(suspects, false)) {
			sb.append(pair).append(": try the game with only one of them.\n");
		}

		List<String> lines = startWithout(suspects);

		if (!lines.isEmpty()) {
			sb.append("Or leave the files where they are: with these lines in ").append(DISABLED_FILE)
					.append(", next to your mods\nfolder, the game starts without them (delete a line to turn that mod back on):\n");

			for (String line : lines) sb.append("    ").append(line).append('\n');
		}

		sb.append("This is a guess: it says these mods were in the error, not that they are at fault.\n");
		sb.append("The full error is in crash-reports/").append(reportName).append(".\n");
		return sb.toString();
	}

	private static List<String> collisions(List<Suspect> suspects, boolean zh) {
		List<String> out = new ArrayList<>();
		java.util.Set<String> seen = new java.util.HashSet<>();

		for (Suspect s : suspects) {
			if (s.collision() == null) continue;

			String a = s.name();
			String b = s.collision().other();
			String key = (a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a) + "|" + s.collision().method();
			if (!seen.add(key)) continue;

			out.add(zh ? a + " 和 " + b + " 都改了 " + s.collision().method()
					: a + " and " + b + " both change " + s.collision().method());
		}

		return out;
	}

	private static String clashNames(List<Suspect> suspects, String separator) {
		List<String> names = new ArrayList<>();

		for (Suspect s : suspects) {
			if (CLASH.equals(s.reason())) names.add(s.name());
		}

		return String.join(separator, names);
	}

	private static long clashing(List<Suspect> suspects) {
		return suspects.stream().filter(s -> CLASH.equals(s.reason())).count();
	}

	private static String zhReason(String reason) {
		return switch (reason) {
			case CLASH -> "报错里把它和另一个 mod 列为冲突的双方";
			case MIXIN_RUNNING -> "它改过的代码正在运行";
			case MIXIN_NAMED -> "报错里直接点了它的名字";
			default -> "报错里有它的代码";
		};
	}

	/** 最小 JSON 编码，避免为一个诊断工具引入 JSON 依赖。 */
	private static String encodeJson(Object value) {
		StringBuilder sb = new StringBuilder();
		writeJson(sb, value);
		return sb.toString();
	}

	private static void writeJson(StringBuilder sb, Object value) {
		if (value == null) {
			sb.append("null");
		} else if (value instanceof Map<?, ?> map) {
			sb.append('{');
			boolean first = true;

			for (Map.Entry<?, ?> e : map.entrySet()) {
				if (!first) sb.append(',');
				first = false;
				writeJson(sb, String.valueOf(e.getKey()));
				sb.append(':');
				writeJson(sb, e.getValue());
			}

			sb.append('}');
		} else if (value instanceof List<?> list) {
			sb.append('[');
			boolean first = true;

			for (Object o : list) {
				if (!first) sb.append(',');
				first = false;
				writeJson(sb, o);
			}

			sb.append(']');
		} else if (value instanceof Number || value instanceof Boolean) {
			sb.append(value);
		} else {
			sb.append('"').append(String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
		}
	}
}
