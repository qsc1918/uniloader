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

package net.fabricmc.loader.impl.compat.quilt;

/**
 * UniLoader fork patch (T2.1)：把 JSON5 里 shaded gson 吃不下的那一点点补掉。
 *
 * <p>实测（本机，内核里这份 gson）的结果：
 * <ul>
 *   <li>{@code //}、{@code /*}、{@code #} 注释：<b>支持</b>（lenient 模式）</li>
 *   <li>无引号键名、单引号字符串：<b>支持</b></li>
 *   <li><b>尾逗号：不支持</b>——gson 会报 "Expected name"</li>
 * </ul>
 *
 * <p>只为「尾逗号」这一种情况写一个预处理，而不是引一个 JSON5 依赖：
 * 新增运行时依赖要过一遍许可证审查，而真实 quilt.mod.json 用到的 JSON5 特性
 * 就只有这些。字符串内的逗号、注释里的逗号都不能被误删，所以这里是逐字符扫，
 * 不是正则替换。
 */
public final class QuiltJson5 {
	private QuiltJson5() {
	}

	/**
	 * 去掉「后面紧跟 {@code } 或 {@code ]} 的逗号」。
	 *
	 * @param json 原始内容
	 * @return 可以直接喂给严格 JSON 解析器的内容
	 */
	public static String stripTrailingCommas(String json) {
		StringBuilder out = new StringBuilder(json.length());
		int i = 0;
		int length = json.length();

		while (i < length) {
			char c = json.charAt(i);

			if (c == '"') { // 字符串原样照抄，里面的逗号/括号都不是语法
				int end = skipString(json, i);
				out.append(json, i, end);
				i = end;
				continue;
			}

			if (c == '/' && i + 1 < length) {
				char next = json.charAt(i + 1);

				if (next == '/') { // 行注释：原样保留（gson 自己会跳过），但不能在里面找逗号
					int end = skipLineComment(json, i);
					out.append(json, i, end);
					i = end;
					continue;
				}

				if (next == '*') { // 块注释同理
					int end = skipBlockComment(json, i);
					out.append(json, i, end);
					i = end;
					continue;
				}
			}

			if (c == ',') {
				int next = nextSignificant(json, i + 1);

				if (next < length && (json.charAt(next) == '}' || json.charAt(next) == ']')) {
					i++; // 丢掉这个逗号
					continue;
				}
			}

			out.append(c);
			i++;
		}

		return out.toString();
	}

	/** 跳过一个字符串（含转义），返回结束位置（不含右引号的后一位）。 */
	private static int skipString(String s, int start) {
		int i = start + 1;

		while (i < s.length()) {
			char c = s.charAt(i);

			if (c == '\\') {
				i += 2;
				continue;
			}

			if (c == '"') return i + 1;

			i++;
		}

		return s.length();
	}

	private static int skipLineComment(String s, int start) {
		int i = start + 2;

		while (i < s.length() && s.charAt(i) != '\n') {
			i++;
		}

		return i;
	}

	private static int skipBlockComment(String s, int start) {
		int end = s.indexOf("*/", start + 2);
		return end < 0 ? s.length() : end + 2;
	}

	/** 下一个「有意义」的字符位置：跳过空白与两种注释。 */
	private static int nextSignificant(String s, int from) {
		int i = from;

		while (i < s.length()) {
			char c = s.charAt(i);

			if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
				i++;
				continue;
			}

			if (c == '/' && i + 1 < s.length()) {
				if (s.charAt(i + 1) == '/') {
					i = skipLineComment(s, i);
					continue;
				}

				if (s.charAt(i + 1) == '*') {
					i = skipBlockComment(s, i);
					continue;
				}
			}

			return i;
		}

		return s.length();
	}
}
