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

package org.uniloader.diagnostics.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.uniloader.api.Diagnostics;
import org.uniloader.api.Ecosystem;
import org.uniloader.api.ModCatalog;
import org.uniloader.api.ModRecord;
import org.uniloader.api.ModStatus;

/**
 * T1.6：把内核落下的诊断证据读进阶段 0 已经写好的 {@link ModCatalog} / {@link Diagnostics}。
 *
 * <p>为什么中间要隔一层文件：内核是一个独立构建（{@code includeBuild('loader')}），
 * 它不能反向依赖宿主构建里的诊断模块，而且内核 jar 还要过 proguard。所以内核只负责
 * 「记事实并落盘」（{@code LoaderDiagnostics}），这里负责「读事实并渲染」。
 * 多一层文件当然慢一点，但它换来了内核的干净——这个取舍在阶段 0 就已经定了。
 *
 * <p>格式（制表符分隔，值里的 \t \n \r \\ 一律转义）：
 * <pre>
 * ul-kernel-diagnostics-1
 * timing   &lt;阶段或 entrypoint/&lt;key&gt;/&lt;modId&gt;&gt;   &lt;毫秒&gt;
 * mod      &lt;id&gt;  &lt;name&gt;  &lt;version&gt;  &lt;ecosystem&gt;  &lt;jar&gt;
 * status   &lt;modId&gt;  &lt;OK|DEGRADED|FAILED&gt;  &lt;stage&gt;  &lt;detail&gt;
 * note     &lt;文本&gt;
 * </pre>
 *
 * <p>读不出来的行<b>不会被静默丢掉</b>：它们会以备注的形式出现在报告里——
 * 一份自己都不知道缺了什么的报告，比没有报告更危险。
 */
public final class KernelDiagnosticsReader {
	public static final String FORMAT_VERSION = "ul-kernel-diagnostics-1";
	public static final String DIR_NAME = ".uniloader";
	public static final String FILE_NAME = "kernel-diagnostics.txt";

	private KernelDiagnosticsReader() {
	}

	/**
	 * 读一份证据文件，把内容灌进登记表与耗时表。
	 *
	 * @return 登记进来的模组个数
	 */
	public static int read(Path file) throws IOException {
		List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

		if (lines.isEmpty()) return 0;

		String header = lines.get(0).trim();

		if (!FORMAT_VERSION.equals(header)) {
			throw new IOException("不认识的内核诊断格式：" + header + "（期望 " + FORMAT_VERSION + "）");
		}

		List<String[]> statuses = new ArrayList<>();
		int registered = 0;

		for (int i = 1; i < lines.size(); i++) {
			String line = lines.get(i);
			if (line.isEmpty()) continue;

			String[] parts = split(line);

			// 状态行留到第二轮：必须先有模组记录，才能把状态落到它身上。
			// 「能不能被指认」等价于「在不在表里」，顺序反了就会凭空丢掉失败。
			if ("status".equals(parts[0])) {
				statuses.add(parts);
				continue;
			}

			if (apply(parts)) registered++;
		}

		for (String[] parts : statuses) {
			apply(parts);
		}

		return registered;
	}

	/** @return true 表示这一行登记了一个模组 */
	private static boolean apply(String[] parts) {
		switch (parts[0]) {
		case "mod": {
			if (parts.length < 2 || parts[1].isEmpty()) return false;

			ModCatalog.register(ModRecord.of(
					parts[1],
					parts.length > 2 ? parts[2] : "",
					parts.length > 3 ? parts[3] : "",
					Ecosystem.fromId(parts.length > 4 ? parts[4] : ""),
					parts.length > 5 ? parts[5] : ""));

			return true;
		}
		case "timing": {
			if (parts.length < 3) break;

			try {
				Diagnostics.record(parts[1], Long.parseLong(parts[2]));
			} catch (NumberFormatException e) {
				Diagnostics.note("内核给了一个读不懂的耗时：" + parts[1] + "=" + parts[2]);
			}

			break;
		}
		case "status": {
			if (parts.length < 3) break;

			ModStatus status = parseStatus(parts[2]);
			String detail = parts.length > 4 ? parts[4] : "";
			String stage = parts.length > 3 ? parts[3] : "";

			if (ModCatalog.update(parts[1], status, detail, stage).isEmpty()) {
				// 表上没有这个 id。绝不凭空造一行——但也不能当没发生过。
				Diagnostics.note("内核报告了模组 " + parts[1] + " 的 " + status.name()
						+ "（阶段 " + stage + "），但登记表里没有它：" + detail);
			}

			break;
		}
		case "note": {
			if (parts.length > 1) Diagnostics.note(parts[1]);
			break;
		}
		default:
			Diagnostics.note("内核诊断证据里有一行读不懂，已跳过：" + String.join("\t", parts));
			break;
		}

		return false;
	}

	private static ModStatus parseStatus(String value) {
		for (ModStatus s : ModStatus.values()) {
			if (s.name().equalsIgnoreCase(value)) return s;
		}

		return ModStatus.FAILED;
	}

	/** 按制表符切分并反转义。 */
	static String[] split(String line) {
		List<String> out = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		boolean escaped = false;

		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);

			if (escaped) {
				switch (c) {
				case 't': cur.append('\t'); break;
				case 'n': cur.append('\n'); break;
				case 'r': cur.append('\r'); break;
				case '\\': cur.append('\\'); break;
				default: cur.append(c); break;
				}

				escaped = false;
				continue;
			}

			if (c == '\\') {
				escaped = true;
				continue;
			}

			if (c == '\t') {
				out.add(cur.toString());
				cur.setLength(0);
				continue;
			}

			cur.append(c);
		}

		out.add(cur.toString());
		return out.toArray(new String[0]);
	}
}
