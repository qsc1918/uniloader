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

package org.uniloader.experiment.d1;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把两份真实映射按 official（Mojang 的哈希名）对齐，得到 intermediary → named 的映射。
 *
 * <p>为什么必须合并两份：intermediary 只给 official→intermediary，mojmap 只给 official→named。
 * 二者唯一可靠的连接点就是 official 名（{@code dlt}、{@code class_1799} 这种），
 * 而成员还要加上描述符才能唯一定位。
 *
 * <p>解析失败的行会被计数并跳过后显式暴露在报告里——绝不静默吞掉（工程纪律第 3 条）。
 */
public final class MergedMappings {
	private final Map<String, String> classIntermediaryByOfficial = new HashMap<>();
	private final Map<String, String> classNamedByOfficial = new HashMap<>();
	private final Map<String, String> classOfficialByNamed = new HashMap<>();
	private final Map<String, String> memberIntermediaryByOfficial = new HashMap<>(); // ownerOfficial|name|desc
	private final Map<String, String> memberNamedByOfficial = new HashMap<>();

	private int officialClasses;
	private int officialMembers;
	private int unparsedMojmapLines;

	public record Coverage(long classesTotal, long classesMapped, long membersTotal, long membersMapped) {
		public double classRate() {
			return classesTotal == 0 ? 0 : classesMapped * 100.0 / classesTotal;
		}

		public double memberRate() {
			return membersTotal == 0 ? 0 : membersMapped * 100.0 / membersTotal;
		}
	}

	public record Example(String official, String intermediary, String named) {
	}

	/**
	 * @param intermediaryTiny official → intermediary（tiny v2，intermediary 发行包里的 mappings/mappings.tiny）
	 * @param mojmapProguard   named → official（Mojang 官方映射，proguard 格式）
	 */
	public static MergedMappings load(Path intermediaryTiny, Path mojmapProguard) throws IOException {
		MergedMappings m = new MergedMappings();
		m.readIntermediary(intermediaryTiny);
		m.readMojmap(mojmapProguard);
		return m;
	}

	private void readIntermediary(Path tiny) throws IOException {
		String currentClass = null;

		for (String raw : Files.readAllLines(tiny, StandardCharsets.UTF_8)) {
			if (raw.isEmpty() || raw.startsWith("#")) continue;

			String[] p = raw.split("\t");

			if (raw.startsWith("tiny")) {
				// tiny\t2\t0\tofficial\tintermediary
				continue;
			}

			if (p[0].equals("c")) {
				currentClass = p[1];
				classIntermediaryByOfficial.put(p[1], p[2]);
				officialClasses++;
			} else if (p.length >= 5 && (p[1].equals("m") || p[1].equals("f"))) {
				String desc = p[2];
				String official = p[3];
				String intermediary = p[4];
				memberIntermediaryByOfficial.put(key(currentClass, official, desc), intermediary);
				officialMembers++;
			}
		}
	}

	/**
	 * 两遍解析，顺序不能颠倒。
	 *
	 * <p>原因是一个真实的坑：mojmap 里的成员类型写的是 **named** 名（{@code net.minecraft.network.chat.Component}），
	 * 而 intermediary 的成员描述符写的是 **official** 名（{@code Lnet/minecraft/class_2561;}）。
	 * 成员要按「official 名 + official 描述符」对齐，就必须先把 mojmap 的类型名映射回 official——
	 * 而这需要完整的 named→official 类表，所以第一遍必须先读全部类行。
	 *
	 * <p>（这个坑第一版就踩了：成员覆盖率只有 37.69%，只有基本类型的成员能对上。）
	 */
	private void readMojmap(Path proguard) throws IOException {
		List<String> lines = Files.readAllLines(proguard, StandardCharsets.UTF_8);

		// 第一遍：类行
		for (String raw : lines) {
			if (raw.isEmpty() || raw.startsWith("#")) continue;
			if (Character.isWhitespace(raw.codePointAt(0))) continue;

			String line = raw.trim();
			if (!line.endsWith(":") || !line.contains(" -> ")) continue;

			String[] sides = line.substring(0, line.length() - 1).split(" -> ");
			String official = sides[1].trim().replace('.', '/');
			String named = sides[0].trim().replace('.', '/');
			classNamedByOfficial.put(official, named);
			classOfficialByNamed.put(named, official);
		}

		// 第二遍：成员行
		String currentOfficial = null;

		for (String raw : lines) {
			if (raw.isEmpty() || raw.startsWith("#")) continue;

			boolean indented = Character.isWhitespace(raw.codePointAt(0));
			String line = raw.trim();

			if (!indented) {
				if (line.endsWith(":") && line.contains(" -> ")) {
					currentOfficial = line.substring(0, line.length() - 1).split(" -> ")[1].trim().replace('.', '/');
				}

				continue;
			}

			if (currentOfficial == null || !line.contains(" -> ")) continue;

			String[] sides = line.split(" -> ");
			if (sides.length != 2) continue;

			String left = sides[0].trim();
			String official = sides[1].trim();
			String desc;

			int paren = left.indexOf('(');

			if (paren >= 0) {
				// 9:10:void youJustLostTheGame(java.lang.String) —— 方法，带行号前缀
				desc = methodDescriptor(left.substring(left.lastIndexOf(':') + 1).trim(), classOfficialByNamed);
			} else {
				// net.minecraft.network.chat.Component UNBREAKABLE_TOOLTIP —— 字段
				int space = left.indexOf(' ');
				if (space < 0) {
					unparsedMojmapLines++;
					continue;
				}

				desc = descriptorOf(left.substring(0, space).trim(), classOfficialByNamed);
			}

			if (desc == null) {
				unparsedMojmapLines++;
				continue;
			}

			String namedName = extractName(left, paren);
			memberNamedByOfficial.put(key(currentOfficial, official, desc), namedName);
			mojmapCandidates.computeIfAbsent(nameKey(currentOfficial, official), x -> new ArrayList<>())
					.add(desc + " => " + namedName);

			String nameKey = nameKey(currentOfficial, official);
			String previous = memberNamedByOfficialName.putIfAbsent(nameKey, namedName);
			if (previous != null && !previous.equals(namedName)) ambiguousNameKeys.add(nameKey);
		}
	}

	private static String extractName(String left, int paren) {
		String head = paren >= 0 ? left.substring(left.lastIndexOf(':') + 1).trim() : left;

		if (paren >= 0) {
			// "void youJustLostTheGame" —— 返回类型与方法名之间隔一个空格
			int space = head.indexOf(' ');
			return space < 0 ? head : head.substring(space + 1).trim();
		}

		int space = head.indexOf(' ');
		return space < 0 ? head : head.substring(space + 1).trim();
	}

	/** {@code void youJustLostTheGame(java.lang.String)} → {@code (Ljava/lang/String;)V} */
	static String methodDescriptor(String signature, Map<String, String> namedToOfficial) {
		int open = signature.indexOf('(');
		int close = signature.lastIndexOf(')');
		if (open < 0 || close < open) return null;

		String head = signature.substring(0, open).trim();
		int space = head.lastIndexOf(' ');
		if (space < 0) return null;

		String returnType = head.substring(0, space).trim();
		String args = signature.substring(open + 1, close).trim();

		StringBuilder sb = new StringBuilder("(");
		if (!args.isEmpty()) {
			// mojmap 的参数列表是逗号分隔且**不带空格**（java.util.List,float,float）。
			// 用 ", " 切会把整串参数当成一个类型名——这正是第一版成员覆盖率只有 83% 的原因。
			for (String arg : args.split(",")) {
				String d = descriptorOf(arg.trim(), namedToOfficial);
				if (d == null) return null;
				sb.append(d);
			}
		}
		sb.append(')');

		String ret = descriptorOf(returnType, namedToOfficial);
		if (ret == null) return null;
		sb.append(ret);

		return sb.toString();
	}

	/**
	 * java 类型名 → JVM 描述符，并把 named 类名映射回 official，使其能与 intermediary 的描述符对齐。
	 * 认不出来返回 null（由调用方计数，绝不猜）。
	 */
	static String descriptorOf(String type, Map<String, String> namedToOfficial) {
		String t = type;
		int generic = t.indexOf('<');
		if (generic >= 0) t = t.substring(0, generic).trim();

		StringBuilder array = new StringBuilder();

		while (t.endsWith("[]")) {
			array.append('[');
			t = t.substring(0, t.length() - 2);
		}

		String primitive = switch (t) {
			case "void" -> "V";
			case "boolean" -> "Z";
			case "byte" -> "B";
			case "char" -> "C";
			case "short" -> "S";
			case "int" -> "I";
			case "long" -> "J";
			case "float" -> "F";
			case "double" -> "D";
			default -> null;
		};

		if (primitive != null) {
			if (array.length() > 0 && primitive.equals("V")) return null;
			return array + primitive;
		}

		if (t.isEmpty() || t.contains(" ")) return null;

		String internal = t.replace('.', '/');
		String official = namedToOfficial == null ? null : namedToOfficial.get(internal);

		return array + "L" + (official != null ? official : internal) + ";";
	}

	private static String key(String owner, String name, String desc) {
		return owner + "|" + name + "|" + desc;
	}

	private static String nameKey(String owner, String name) {
		return owner + "|" + name;
	}

	/**
	 * 按 (owner, official 名) 查 named 名，仅在唯一命中时返回。
	 *
	 * <p>为什么需要这条回退：描述符对齐依赖「mojmap 的类型名能映射回 official」，而少数类型
	 * （泛型擦除的边界情况、Mojang 自己写法的差异）映射不回来，于是键对不上——但成员其实是同一个。
	 * 只在唯一命中时回退，命中多个就放弃：宁可少映射一个，也不能把名字安到错的人身上。
	 */
	private String namedFor(String memberKey) {
		String direct = memberNamedByOfficial.get(memberKey);
		if (direct != null) return direct;

		String[] k = memberKey.split("\\|");
		if (k.length < 2) return null;

		String nameKey = nameKey(k[0], k[1]);
		return ambiguousNameKeys.contains(nameKey) ? null : memberNamedByOfficialName.get(nameKey);
	}


	private final Map<String, String> memberNamedByOfficialName = new HashMap<>();
	private final Set<String> ambiguousNameKeys = new HashSet<>();
	private final Map<String, List<String>> mojmapCandidates = new HashMap<>();

	/**
	 * 诊断用：列出若干个「有 intermediary 但按描述符对不上 mojmap」的成员，附上 mojmap 那边的候选。
	 * 这类缺口不该被含糊过去——报告里要能看到它到底是什么。
	 */
	public List<String> descMismatchSamples(int limit) {
		List<String> out = new ArrayList<>();

		for (Map.Entry<String, String> e : memberIntermediaryByOfficial.entrySet()) {
			if (namedFor(e.getKey()) != null) continue;

			String[] k = e.getKey().split("\\|");
			if (k.length < 3) continue;

			String candidates = String.join(" ; ", mojmapCandidates.getOrDefault(nameKey(k[0], k[1]), List.of()));
			out.add(cls(e.getKey()) + " intermediary=" + k[1] + " desc=" + k[2]
					+ " | mojmap 候选: " + (candidates.isEmpty() ? "(无)" : candidates));

			if (out.size() >= limit) break;
		}

		return out;
	}

	/** 诊断：类表规模与一次定点查询，用来确认 named→official 的索引是否真的建起来了。 */
	public String debugCounts() {
		String probe = "net/minecraft/client/renderer/texture/atlas/SpriteResourceLoader";
		return "namedByOfficial=" + classNamedByOfficial.size()
				+ " officialByNamed=" + classOfficialByNamed.size()
				+ " mojmapCandidates=" + mojmapCandidates.size()
				+ " probe[" + probe + "]=" + classOfficialByNamed.get(probe);
	}

	private String cls(String memberKey) {
		String owner = memberKey.split("\\|")[0];
		return classIntermediaryByOfficial.getOrDefault(owner, owner);
	}

	/** 覆盖率：决定了「Fabric mod 重映射到 mojmap」这条路到底有多完整。 */
	public Coverage coverage() {
		long classesMapped = 0;
		long membersMapped = 0;
		long membersTotal = 0;

		for (String official : classIntermediaryByOfficial.keySet()) {
			if (classNamedByOfficial.containsKey(official)) classesMapped++;
		}

		for (Map.Entry<String, String> e : memberIntermediaryByOfficial.entrySet()) {
			membersTotal++;
			if (namedFor(e.getKey()) != null) membersMapped++;
		}

		return new Coverage(classIntermediaryByOfficial.size(), classesMapped, membersTotal, membersMapped);
	}

	public int unparsedMojmapLines() {
		return unparsedMojmapLines;
	}

	/**
	 * 写出 src=intermediary / dst=named 的 tiny v2 文件。
	 *
	 * <p>格式与 {@code TinyUtils.createTinyMappingProvider(Path, from, to)} 期望的一致，
	 * 也就是内核 {@code RuntimeModRemapper} 在运行时喂给 tiny-remapper 的那一类文件。
	 */
	public Path writeTiny(Path out) throws IOException {
		List<String> lines = new ArrayList<>();
		lines.add("tiny\t2\t0\tintermediary\tnamed");

		// 按 official 类聚合成 tiny 的层级结构
		Map<String, List<String[]>> membersByOfficial = new HashMap<>();

		for (Map.Entry<String, String> e : memberIntermediaryByOfficial.entrySet()) {
			String named = namedFor(e.getKey());
			if (named == null) continue;

			String[] k = e.getKey().split("\\|");
			membersByOfficial.computeIfAbsent(k[0], x -> new ArrayList<>()).add(new String[]{ k[2], e.getValue(), named });
		}

		for (Map.Entry<String, String> e : classIntermediaryByOfficial.entrySet()) {
			String official = e.getKey();
			String named = classNamedByOfficial.get(official);
			if (named == null) continue;

			lines.add("c\t" + e.getValue() + "\t" + named);

			for (String[] m : membersByOfficial.getOrDefault(official, List.of())) {
				// m / f 的区分只影响 tiny 的语义，不 tiny-remapper 的重映射结果；
				// 这里按描述符里是否有 '(' 判断，简单可靠。
				String kind = m[1].startsWith("method_") || m[0].contains("(") ? "m" : "f";
				lines.add("\t" + kind + "\t" + m[0] + "\t" + m[1] + "\t" + m[2]);
			}
		}

		Files.createDirectories(out.getParent());
		Files.write(out, lines, StandardCharsets.UTF_8);
		return out;
	}

	/**
	 * 挑若干「三边齐全」的样本，用来构造合成 mod 和展示给读者看。
	 *
	 * @return 每个元素是一个可重映射的成员：{@code ownerIntermediary.ownerMemberIntermediary → named}
	 */
	public List<Sample> samples(int wanted) {
		List<Sample> out = new ArrayList<>();

		for (Map.Entry<String, String> classEntry : classIntermediaryByOfficial.entrySet()) {
			if (out.size() >= wanted) break;

			String officialClass = classEntry.getKey();
			String namedClass = classNamedByOfficial.get(officialClass);
			if (namedClass == null || !namedClass.startsWith("net/minecraft/")) continue;

			// 每个类尽量各取一个字段样本和一个方法样本：
			// 方法样本是必须的——@Inject(method=...) 的目标只能是方法名，
			// 没有方法样本，「mixin 注解字符串会不会被改写」这条就测不到（G3）。
			Sample field = null;
			Sample method = null;

			for (Map.Entry<String, String> memberEntry : memberIntermediaryByOfficial.entrySet()) {
				String[] k = memberEntry.getKey().split("\\|");
				if (!k[0].equals(officialClass)) continue;

				String namedMember = namedFor(memberEntry.getKey());
				if (namedMember == null || namedMember.startsWith("<")) continue;
				if (namedMember.equals(memberEntry.getValue())) continue; // 同名，重映射看不出效果

				boolean isMethod = k[2].contains("(");
				if (isMethod && !k[2].startsWith("()")) continue; // 只取无参方法，便于机械生成
				if (isMethod && method == null) {
					method = new Sample(classEntry.getValue(), namedClass, memberEntry.getValue(), namedMember, k[2]);
				} else if (!isMethod && field == null) {
					field = new Sample(classEntry.getValue(), namedClass, memberEntry.getValue(), namedMember, k[2]);
				}

				if (field != null && method != null) break;
			}

			if (field != null) out.add(field);
			if (method != null) out.add(method);
		}

		return out;
	}

	public record Sample(String ownerIntermediary, String ownerNamed, String memberIntermediary, String memberNamed,
						 String descriptor) {
		public boolean isMethod() {
			return descriptor.contains("(");
		}
	}
}
