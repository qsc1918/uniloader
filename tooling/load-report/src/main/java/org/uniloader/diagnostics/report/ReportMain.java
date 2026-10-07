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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * T1.6 的命令行入口：读完内核落下的证据，渲染 {@code mods-report.txt}。
 *
 * <pre>
 *   java -cp load-report.jar:compat-api.jar \
 *        org.uniloader.diagnostics.report.ReportMain &lt;游戏目录&gt;
 * </pre>
 *
 * <p>为什么是独立进程而不是内核里的一个调用：内核跑在 KnotClassLoader 下，
 * 而报告要读的是「内核那个进程留下的文件」；把渲染放在启动之外，
 * 既让内核保持干净，也让「上一次崩溃的那次启动」同样能被渲染出来。
 *
 * <p>退出码：0 正常（含「没有问题、不写文件」）；2 用法错误或证据读不出来。
 * 报告永远不因为自己写不出来而让调用方失败——那会把「崩溃」盖成「报告失败」。
 */
public final class ReportMain {
	private static final System.Logger LOG = System.getLogger("org.uniloader.diagnostics.report");

	private ReportMain() {
	}

	public static void main(String[] args) {
		if (args.length < 1) {
			System.err.println("用法: ReportMain <游戏目录> [证据文件]");
			System.exit(2);
			return;
		}

		Path gameDir = Paths.get(args[0]).toAbsolutePath().normalize();
		Path evidence = args.length > 1
				? Paths.get(args[1]).toAbsolutePath().normalize()
				: gameDir.resolve(KernelDiagnosticsReader.DIR_NAME).resolve(KernelDiagnosticsReader.FILE_NAME);

		if (!Files.isRegularFile(evidence)) {
			LOG.log(System.Logger.Level.WARNING, "[UniLoader/Load] 没有找到内核诊断证据：%s（内核可能没跑起来）", evidence);
			System.exit(2);
			return;
		}

		try {
			int mods = KernelDiagnosticsReader.read(evidence);
			LOG.log(System.Logger.Level.INFO, "[UniLoader/Load] 读入了 %d 个模组的诊断证据", mods);

			LoadReport.setRunDir(gameDir);
			LoadReport.write();
		} catch (Throwable t) {
			LOG.log(System.Logger.Level.ERROR, "[UniLoader/Load] 无法渲染加载报告：" + t);
			System.exit(2);
		}
	}
}
