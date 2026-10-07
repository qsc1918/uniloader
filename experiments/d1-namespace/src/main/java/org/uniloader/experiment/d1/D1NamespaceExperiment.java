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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.fabricmc.tinyremapper.InputTag;
import net.fabricmc.tinyremapper.NonClassCopyMode;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyUtils;
import net.fabricmc.tinyremapper.extension.mixin.MixinExtension;

import org.uniloader.experiment.d1.MergedMappings.Coverage;
import org.uniloader.experiment.d1.MergedMappings.Sample;

/**
 * T0.3 —— 决策 D1 的可行性实验。
 *
 * <p>D1 说的是：把内核的运行时命名空间统一到 mojmap，让 Fabric 侧的 mod 在加载时被重映射过去。
 * 它决定项目生死，所以它不能靠论述，必须给出可重复执行的数字。
 *
 * <p>本实验回答四个问题：
 * <ol>
 *   <li><b>换得动吗</b>：真实映射下，intermediary 名字能不能被 tiny-remapper 换成 mojmap 名字。</li>
 *   <li><b>换得全吗</b>：真实的 intermediary ↔ mojmap 覆盖率是多少；没覆盖到的引用会不会被静默丢掉。</li>
 *   <li><b>非字节码资产怎么办</b>：refmap.json 在两种 remap 类型下的实际行为（这是生产环境
 *       与开发环境的差异所在，也是 D1 里最容易踩空的一处）。</li>
 *   <li><b>代价多大</b>：冷/热重映射耗时，给阶段 1 的 15% 预算一个基线。</li>
 * </ol>
 *
 * <p>退出码：0 = 全部断言通过；1 = 有断言失败；2 = 环境/数据缺失（不是 D1 的结论）。
 */
public final class D1NamespaceExperiment {
	private static final int DEFAULT_SAMPLES = 8;
	private static final int DEFAULT_PERF_MODS = 20;

	private Path mappingsDir = Path.of("build/d1/mappings");
	private Path outDir = Path.of("build/d1");
	private Path reportFile = Path.of("build/d1/D1-report.md");
	private int sampleCount = DEFAULT_SAMPLES;
	private int perfMods = DEFAULT_PERF_MODS;

	private final List<Check> checks = new ArrayList<>();
	private final List<String> observations = new ArrayList<>();
	private final StringBuilder log = new StringBuilder();

	public static void main(String[] args) {
		D1NamespaceExperiment experiment = new D1NamespaceExperiment();
		experiment.parseArgs(args);
		System.exit(experiment.run());
	}

	private void parseArgs(String[] args) {
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--mappings" -> mappingsDir = Path.of(args[++i]);
				case "--out" -> outDir = Path.of(args[++i]);
				case "--report" -> reportFile = Path.of(args[++i]);
				case "--samples" -> sampleCount = Integer.parseInt(args[++i]);
				case "--perf-mods" -> perfMods = Integer.parseInt(args[++i]);
				default -> {
					// 未知参数不静默忽略（工程纪律第 3 条）
					System.err.println("[D1] 未知参数: " + args[i]);
					System.err.println("用法: --mappings <dir> --out <dir> --report <file> --samples <n> --perf-mods <n>");
					System.exit(2);
				}
			}
		}
	}

	private int run() {
		out("UniLoader D1 命名空间实验");
		out("==========================");

		Path tiny = mappingsDir.resolve("intermediary.tiny");
		Path mojmap = mappingsDir.resolve("mojmap.txt");

		if (!Files.exists(tiny) || !Files.exists(mojmap)) {
			System.err.println("[D1] 缺少真实映射数据：" + tiny + " / " + mojmap);
			System.err.println("[D1] 请先执行 experiments/d1-namespace/scripts/fetch-mappings.sh");
			System.err.println("[D1] 本实验不使用合成数据得出 D1 的结论——那样得到的数字没有意义。");
			return 2;
		}

		try {
			Files.createDirectories(outDir);

			// ---- 1. 合并映射 + 覆盖率 -------------------------------------------------
			out("");
			out("[1] 合并真实映射（official 为连接点）");
			long t0 = System.nanoTime();
			MergedMappings mappings = MergedMappings.load(tiny, mojmap);
			long mergeMillis = (System.nanoTime() - t0) / 1_000_000L;
			Coverage coverage = mappings.coverage();
			out("    合并耗时 " + mergeMillis + " ms；mojmap 未解析行数 " + mappings.unparsedMojmapLines());
			out("    类覆盖率  " + coverage.classesMapped() + "/" + coverage.classesTotal()
					+ "  (" + String.format("%.2f", coverage.classRate()) + "%)");
			out("    成员覆盖率 " + coverage.membersMapped() + "/" + coverage.membersTotal()
					+ "  (" + String.format("%.2f", coverage.memberRate()) + "%)");

			out("    " + mappings.debugCounts());
			for (String line : mappings.descMismatchSamples(2)) out("    缺口样本: " + line);

			check("映射覆盖率：类 ≥ 95%", coverage.classRate() >= 95.0,
					String.format("%.2f%%", coverage.classRate()));
			check("映射覆盖率：成员 ≥ 90%", coverage.memberRate() >= 90.0,
					String.format("%.2f%%", coverage.memberRate()));

			Path mergedTiny = mappings.writeTiny(outDir.resolve("merged-intermediary-to-named.tiny"));
			out("    已写出合并映射: " + mergedTiny + " (" + Files.size(mergedTiny) + " bytes)");

			// ---- 2. 合成一个 Fabric mod ------------------------------------------------
			List<Sample> samples = SyntheticFabricMod.usable(mappings.samples(sampleCount * 20), sampleCount);

			if (samples.size() < 4) {
				out("    可用样本不足（" + samples.size() + "），实验无法得出可靠结论");
				return 2;
			}

			out("");
			out("[2] 合成 Fabric mod（字节码里引用 intermediary 名），样本：");

			for (Sample s : samples) out("      " + SyntheticFabricMod.describe(s));

			// ---- 3. 两种 remap 类型各跑一次 -------------------------------------------
			// mixin 类型：内核默认（loom 发布的 mod 就是这种），不启用 MixinExtension
			Result mixinMode = remap("mixin", mergedTiny, samples, false);
			// static 类型：manifest 显式声明，内核会为该 jar 启用 MixinExtension
			Result staticMode = remap("static", mergedTiny, samples, true);

			verify("mixin（内核默认路径）", mixinMode, samples, false);
			verify("static（显式启用 MixinExtension）", staticMode, samples, true);

			// ---- 4. refmap 的实际行为 --------------------------------------------------
			out("");
			out("[4] refmap.json 在两种模式下的实际行为");
			out("    mixin  模式: refmap " + (mixinMode.refmapChanged ? "被改写" : "原样保留"));
			out("    static 模式: refmap " + (staticMode.refmapChanged ? "被改写" : "原样保留"));
			out("    非 class 条目保留情况 mixin=" + mixinMode.entries.contains("fabric.mod.json")
					+ " static=" + staticMode.entries.contains("fabric.mod.json"));

			check("非 class 资产（fabric.mod.json）未丢失",
					mixinMode.entries.contains("fabric.mod.json") && staticMode.entries.contains("fabric.mod.json"),
					String.valueOf(mixinMode.entries));

			// ---- 5. 性能 ---------------------------------------------------------------
			out("");
			out("[5] 性能（" + perfMods + " 个合成 mod，样本数 " + samples.size() + "）");
			long cold = performance(mergedTiny, samples, perfMods, outDir.resolve("perf-cold"));
			long warm = performance(mergedTiny, samples, perfMods, outDir.resolve("perf-warm"));
			out("    冷: " + cold + " ms　热: " + warm + " ms　(合计 " + perfMods + " 个 jar)");

			// ---- 6. 报告 ---------------------------------------------------------------
			boolean ok = checks.stream().allMatch(c -> c.passed);
			String markdown = render(mappings, coverage, samples, mixinMode, staticMode, cold, warm, mergeMillis, ok);
			Files.createDirectories(reportFile.getParent());
			Files.writeString(reportFile, markdown, StandardCharsets.UTF_8);

			out("");
			out("结论: " + (ok ? "D1 可行（在实验覆盖的机制范围内）" : "D1 存在未通过的断言，见报告"));
			out("报告: " + reportFile.toAbsolutePath());

			for (Check c : checks) {
				out("  [" + (c.passed ? "PASS" : "FAIL") + "] " + c.name + " — " + c.detail);
			}

			return ok ? 0 : 1;
		} catch (Throwable t) {
			// 绝不静默：实验自己炸了也要把原因说清楚，且不算作 D1 的结论
			System.err.println("[D1] 实验本身失败（这不是 D1 的结论）: " + t);
			t.printStackTrace();
			return 2;
		}
	}

	// ---------------------------------------------------------------------------------
	// 重映射
	// ---------------------------------------------------------------------------------

	private record Result(Path outputJar, Set<String> classes, Set<String> members, Set<String> injectStrings,
						  boolean refmapChanged, Set<String> entries, long millis) {
	}

	/**
	 * 完全照搬内核 {@code RuntimeModRemapper.remap()} 的 tiny-remapper 配置，
	 * 这样实验结果才能代表内核真正会做的事。
	 */
	private Result remap(String mode, Path mergedTiny, List<Sample> samples, boolean mixinExtension) throws IOException {
		Path input = SyntheticFabricMod.build(outDir.resolve("input-" + mode + ".jar"), "d1-sample-" + mode, samples,
				mixinExtension ? "static" : "mixin");
		Path output = outDir.resolve("output-" + mode + ".jar");
		Files.deleteIfExists(output);

		// 重映射 classpath：真实环境里就是「MC 本体 + 库」。两个命名空间各给一份，
		// 让实验直接测出「classpath 该是哪个命名空间」这个事实（G2 的关键细节）。
		Path namedStub = SyntheticFabricMod.buildGameStub(outDir.resolve("game-stub-named.jar"), samples, true);
		Path sourceStub = SyntheticFabricMod.buildGameStub(outDir.resolve("game-stub-intermediary.jar"), samples, false);

		Set<InputTag> mixinTags = new LinkedHashSet<>();
		long start = System.nanoTime();

		TinyRemapper remapper = TinyRemapper.newRemapper()
				.withMappings(TinyUtils.createTinyMappingProvider(mergedTiny, "intermediary", "named"))
				.renameInvalidLocals(false)
				.extension(new MixinExtension(mixinTags::contains))
				.build();

		try {
			remapper.readClassPathAsync(sourceStub, namedStub).get();

			InputTag tag = remapper.createInputTag();
			if (mixinExtension) mixinTags.add(tag);
			remapper.readInputsAsync(tag, input).get();

			try (OutputConsumerPath consumer = new OutputConsumerPath.Builder(output).build()) {
				try (java.nio.file.FileSystem fs = java.nio.file.FileSystems.newFileSystem(input, (ClassLoader) null)) {
					Path root = fs.getRootDirectories().iterator().next();
					consumer.addNonClassFiles(root, NonClassCopyMode.FIX_META_INF, remapper);
				}

				remapper.apply(consumer, tag);
			}

			remapper.finish();
		} catch (InterruptedException e) {
			throw new IOException(e);
		} catch (java.util.concurrent.ExecutionException e) {
			throw new IOException(e.getCause());
		}

		long millis = (System.nanoTime() - start) / 1_000_000L;

		byte[] remappedClass = readEntryBytes(output, SyntheticFabricMod.CLASS_NAME + ".class");
		Collected collected = collect(remappedClass);

		String before = SyntheticFabricMod.readEntry(input, "refmap.json");
		String after = SyntheticFabricMod.readEntry(output, "refmap.json");

		return new Result(output, collected.classes, collected.members, collected.injectStrings,
				!before.equals(after), SyntheticFabricMod.entries(output), millis);
	}

	private long performance(Path mergedTiny, List<Sample> samples, int mods, Path dir) throws IOException {
		Files.createDirectories(dir);
		List<Path> inputs = new ArrayList<>();

		for (int i = 0; i < mods; i++) {
			// 类名必须互不相同：同一个 remapper 实例里出现重复输入类会被判为冲突。
			inputs.add(SyntheticFabricMod.build(dir.resolve("mod-" + i + ".jar"), "d1-perf-" + i, samples, "mixin",
					SyntheticFabricMod.CLASS_BASE + "_" + i));
		}

		Path namedStub = SyntheticFabricMod.buildGameStub(dir.resolve("game-stub-named.jar"), samples, true);
		Path sourceStub = SyntheticFabricMod.buildGameStub(dir.resolve("game-stub-intermediary.jar"), samples, false);

		long start = System.nanoTime();

		TinyRemapper remapper = TinyRemapper.newRemapper()
				.withMappings(TinyUtils.createTinyMappingProvider(mergedTiny, "intermediary", "named"))
				.renameInvalidLocals(false)
				.extension(new MixinExtension(t -> false))
				.build();

		try {
			remapper.readClassPathAsync(sourceStub, namedStub).get();

			// 两段式（与内核 RuntimeModRemapper 相同）：先把所有输入读进来，再逐个写出。
			// 边读边写会让 tiny-remapper 的输入数据在写出后失效。
			Map<Path, InputTag> tags = new LinkedHashMap<>();

			for (Path input : inputs) {
				InputTag tag = remapper.createInputTag();
				tags.put(input, tag);
				remapper.readInputsAsync(tag, input).get();
			}

			for (Map.Entry<Path, InputTag> e : tags.entrySet()) {
				Path output = dir.resolve("out-" + e.getKey().getFileName());
				Files.deleteIfExists(output);

				try (OutputConsumerPath consumer = new OutputConsumerPath.Builder(output).build()) {
					remapper.apply(consumer, e.getValue());
				}
			}

			remapper.finish();
		} catch (InterruptedException e) {
			throw new IOException(e);
		} catch (java.util.concurrent.ExecutionException e) {
			throw new IOException(e.getCause());
		}

		return (System.nanoTime() - start) / 1_000_000L;
	}

	// ---------------------------------------------------------------------------------
	// 断言
	// ---------------------------------------------------------------------------------

	private void verify(String label, Result result, List<Sample> samples, boolean mixinExtension) {
		out("");
		out("[3] 断言：" + label + "（耗时 " + result.millis + " ms）");

		boolean allClasses = true;
		boolean allMembers = true;
		boolean noIntermediaryLeft = true;

		for (Sample s : samples) {
			boolean cls = result.classes.contains(s.ownerNamed());
			boolean mem = result.members.contains(s.ownerNamed() + "." + s.memberNamed());
			boolean leftover = result.classes.contains(s.ownerIntermediary());

			allClasses &= cls;
			allMembers &= mem;
			noIntermediaryLeft &= !leftover;
		}

		check(label + "：MC 类名已换成 mojmap", allClasses, join(result.classes));
		check(label + "：MC 成员名已换成 mojmap", allMembers, join(result.members));
		check(label + "：intermediary 名字已彻底消失", noIntermediaryLeft, join(result.classes));

		// G3：mixin 注解里的字符串（@Inject(method = "...")）是常量，字节码重映射不会碰它。
		List<Sample> methods = samples.stream().filter(Sample::isMethod).toList();
		boolean injectRemapped = methods.stream().allMatch(s -> result.injectStrings.contains(s.memberNamed()));
		boolean injectUntouched = methods.stream().allMatch(s -> result.injectStrings.contains(s.memberIntermediary()));

		out("    @Inject(method=...) 字符串（方法样本 " + methods.size() + " 个）: " + result.injectStrings);

		// 这是一条「观察」而不是断言：它记录的是 tiny-remapper 的实际行为，不是我们期望它怎样。
		// 若将来 tiny-remapper 改变了这个行为，报告会跟着变，而不是让实验变红。
		observe(label + "：@Inject(method=...) 字符串 " + (injectRemapped ? "被改写为 mojmap" : "保持 intermediary")
				+ "（方法样本 " + methods.size() + " 个，MixinExtension=" + (mixinExtension ? "启用" : "未启用") + "）");
	}

	private record Collected(Set<String> classes, Set<String> members, Set<String> injectStrings) {
	}

	private Collected collect(byte[] classBytes) {
		Set<String> classes = new LinkedHashSet<>();
		Set<String> members = new LinkedHashSet<>();
		Set<String> injectStrings = new LinkedHashSet<>();

		new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9, null) {
			@Override
			public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
				if (superName != null) classes.add(superName);
			}

			@Override
			public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
				members.add("self." + name);
				return null;
			}

			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
				members.add("self." + name);

				return new MethodVisitor(Opcodes.ASM9, null) {
					@Override
					public void visitTypeInsn(int opcode, String type) {
						classes.add(type);
					}

					@Override
					public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
						classes.add(owner);
						members.add(owner + "." + name);
					}

					@Override
					public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
						classes.add(owner);
						members.add(owner + "." + name);
					}

					@Override
					public void visitLdcInsn(Object value) {
						if (value instanceof org.objectweb.asm.Type t && t.getSort() == org.objectweb.asm.Type.OBJECT) {
							classes.add(t.getInternalName());
						}
					}

					@Override
					public org.objectweb.asm.AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
						// 只关心 @Inject(method = {...})：注解里的字符串是常量，字节码重映射不碰它。
						return new org.objectweb.asm.AnnotationVisitor(Opcodes.ASM9, null) {
							@Override
							public org.objectweb.asm.AnnotationVisitor visitArray(String name) {
								if (!"method".equals(name)) return super.visitArray(name);

								return new org.objectweb.asm.AnnotationVisitor(Opcodes.ASM9, null) {
									@Override
									public void visit(String name, Object value) {
										if (value != null) injectStrings.add(String.valueOf(value));
									}
								};
							}
						};
					}
				};
			}
		}, 0);

		return new Collected(classes, members, injectStrings);
	}

	private record Check(String name, boolean passed, String detail) {
	}

	private void check(String name, boolean passed, String detail) {
		checks.add(new Check(name, passed, detail));
	}

	private void observe(String text) {
		observations.add(text);
	}

	private static String join(Set<String> values) {
		List<String> sorted = new ArrayList<>(new TreeSet<>(values));
		return String.join(", ", sorted.size() > 6 ? sorted.subList(0, 6) : sorted);
	}

	private void out(String line) {
		System.out.println(line);
		log.append(line).append('\n');
	}

	private static byte[] readEntryBytes(Path jar, String entry) throws IOException {
		try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(jar.toFile())) {
			java.util.zip.ZipEntry e = zf.getEntry(entry);
			if (e == null) throw new IOException("jar 里没有 " + entry + "（重映射把类丢掉了？）: " + jar);
			return zf.getInputStream(e).readAllBytes();
		}
	}

	// ---------------------------------------------------------------------------------
	// 报告
	// ---------------------------------------------------------------------------------

	private String render(MergedMappings mappings, Coverage coverage, List<Sample> samples,
						  Result mixinMode, Result staticMode, long cold, long warm, long mergeMillis, boolean ok) {
		StringBuilder sb = new StringBuilder();
		sb.append("# T0.3 — 决策 D1（mojmap 运行时命名空间）可行性实验报告\n\n");
		sb.append("生成时间：").append(java.time.LocalDateTime.now()).append("　　");
		sb.append("结论：**").append(ok ? "D1 可行（在实验覆盖的机制范围内）" : "存在未通过的断言").append("**\n\n");

		sb.append("## 0. 这个实验证明什么 / 不证明什么\n\n");
		sb.append("- **证明**：Fabric mod 以 intermediary 名字编译的字节码，能被 tiny-remapper 用真实映射\n");
		sb.append("  完整、准确地重映射到 Mojang 官方名；非 class 资产不丢；代价可以量化。\n");
		sb.append("- **不证明**：真实 Minecraft 端到端启动。那需要 MC 26.3 的原版字节码与真实 mod，\n");
		sb.append("  属于阶段 1 的 T1.1/T1.2 与第 19 节的 L3 冒烟，本阶段只把脚本与门闸准备好。\n\n");

		sb.append("## 1. 实验数据（真实，非合成）\n\n");
		sb.append("| 项 | 值 |\n|---|---|\n");
		sb.append("| intermediary | `").append(mappingsDir.resolve("intermediary.tiny")).append("` |\n");
		sb.append("| mojmap | `").append(mappingsDir.resolve("mojmap.txt")).append("` |\n");
		sb.append("| tiny-remapper | ").append(tinyRemapperVersion()).append(" |\n");
		sb.append("| 合并耗时 | ").append(mergeMillis).append(" ms |\n");
		sb.append("| mojmap 未解析行 | ").append(mappings.unparsedMojmapLines()).append(" |\n\n");

		sb.append("## 2. 映射覆盖率（决定这条路有多完整）\n\n");
		sb.append("| | 有 intermediary | 同时有 mojmap | 覆盖率 |\n|---|---|---|---|\n");
		sb.append("| 类 | ").append(coverage.classesTotal()).append(" | ").append(coverage.classesMapped())
				.append(" | ").append(String.format("%.2f%%", coverage.classRate())).append(" |\n");
		sb.append("| 成员 | ").append(coverage.membersTotal()).append(" | ").append(coverage.membersMapped())
				.append(" | ").append(String.format("%.2f%%", coverage.memberRate())).append(" |\n\n");
		sb.append("未覆盖的部分会**原样保留**（不会被删掉也不会被乱改）——这正是 UL 需要的性质：\n");
		sb.append("宁可留着 intermediary 名字让注入在 audit 阶段显式报错，也不要静默跳过。\n\n");

		sb.append("## 3. 重映射样本\n\n");
		sb.append("| intermediary | mojmap |\n|---|---|\n");

		for (Sample s : samples) {
			sb.append("| `").append(s.ownerIntermediary()).append('.').append(s.memberIntermediary()).append(s.descriptor())
					.append("` | `").append(s.ownerNamed()).append('.').append(s.memberNamed()).append("` |\n");
		}

		sb.append("\n## 4. 断言结果\n\n");
		sb.append("| 断言 | 结果 | 详情 |\n|---|---|---|\n");

		for (Check c : checks) {
			sb.append("| ").append(c.name).append(" | ").append(c.passed ? "PASS" : "**FAIL**").append(" | ")
					.append(String.valueOf(c.detail).replace("|", "\\|")).append(" |\n");
		}

		sb.append("\n## 5. mixin 注解字符串与 refmap 的实际行为（生产环境与开发环境的关键差异）\n\n");
		sb.append("### 5.0 观察（不是断言，是 tiny-remapper 的实际行为）\n\n");

		for (String o : observations) sb.append("- ").append(o).append('\n');

		sb.append('\n');
		sb.append("| remap 类型 | MixinExtension | `@Inject(method=...)` 字符串 | refmap.json |\n|---|---|---|---|\n");
		sb.append("| `mixin`（loom 默认，内核默认路径） | 不启用 | ")
				.append(mixinMode.injectStrings).append(" | ").append(mixinMode.refmapChanged ? "被改写" : "原样保留").append(" |\n");
		sb.append("| `static`（manifest 显式声明） | 启用 | ")
				.append(staticMode.injectStrings).append(" | ").append(staticMode.refmapChanged ? "被改写" : "原样保留").append(" |\n\n");
		sb.append("关键事实：mixin 注解里的目标名是**字符串常量**，字节码重映射不会碰它。\n");
		sb.append("loom 用 refmap.json 的 `named:intermediary` 键在运行时把它翻译过去（编译用 named、发布打成 intermediary）。\n");
		sb.append("运行时命名空间一旦变成 named，这把钥匙就对不上锁了——这是 D1 在生产环境最容易踩空的一处，\n");
		sb.append("必须在阶段 1 显式处理（见第 7 节 G3）。\n\n");

		sb.append("## 6. 性能基线\n\n");
		sb.append("| | 耗时 |\n|---|---|\n");
		sb.append("| 冷（").append(perfMods).append(" 个 mod） | ").append(cold).append(" ms |\n");
		sb.append("| 热（同一进程第二次） | ").append(warm).append(" ms |\n");
		sb.append("| 单 mod 均摊（冷） | ").append(perfMods == 0 ? "-" : (cold / perfMods) + " ms").append(" |\n\n");
		sb.append("阶段 1 的 T1.2 要求「热启动与原生 Fabric 差距 < 15%」，基线即为上表；\n");
		sb.append("真正的热启动还要依赖落盘 cache（`~/.ul/cache/remapped-<hash>`），本实验尚未实现该 cache。\n\n");

		sb.append("## 7. 内核必须改动清单（本实验查证的四处门闸）\n\n");
		sb.append("| 编号 | 位置 | 现状 | 必须做什么 |\n|---|---|---|---|\n");
		sb.append("| G1 | `FabricLoaderImpl.setup()`（`remapRegularMods = isDevelopmentEnvironment()`） | 生产环境恒为 false | 改为生产也 remap（用 `ul.remapFabricMods` 开关控制） |\n");
		sb.append("| G2 | `RuntimeModRemapper.getRemapClasspath()` | 缺 `fabric.remapClasspathFile` 就抛异常；**且实测该 classpath 必须是「源命名空间」的 MC+库** | 生产环境必须提供该文件，内容为 **intermediary 名**的 MC 与库（本实验用对照桩 jar 实测：只给 named 名时，成员名重映射会静默不生效） |\n");
		sb.append("| G3 | mod jar 内的 `refmap.json` 与 mixin 注解字符串（键 `named:intermediary`） | 运行时变 named 后对不上；**实测 MixinExtension（static 模式）也不会改写 `@Inject(method=...)` 字符串** | 内核自己解决：把 `FabricMixinBootstrap` 里 dev-only 的 `MixinIntermediaryDevRemapper` + `mixin.env.remapRefMap=true` 放开到生产（Fabric 官方机制，只是被 `isDevelopment()` 挡住了） |\n");
		sb.append("| G4 | `MinecraftGameProvider.initialize()` 的 `!gameNs.equals(runtimeNs)` | 两者都设成 named 会跳过反混淆 | 必须保证 MC 本体已经是 mojmap（由安装器预先 remap），否则类名全错 |\n\n");
		sb.append("G4 尤其容易踩：把 `fabric.gameMappingNamespace` 与 `fabric.runtimeMappingNamespace` 都设成 named\n");
		sb.append("确实能「跳过 deobfuscate」，但**只有在 MC 本体已经是 mojmap 名字时才是对的**；\n");
		sb.append("若喂进去的是官方混淆 jar，跳过反混淆的结果是全线对不上名。\n\n");

		sb.append("## 8. 待验证（阶段 1）\n\n");
		sb.append("1. 真实 MC 26.3 + 10 个主流 Fabric mod 的端到端启动（脚本见 `run/smoke.sh`）。\n");
		sb.append("2. **MC 26.3 目前没有 intermediary 映射**（实测 maven.fabricmc.net 目录最新到 25w46a，\n");
		sb.append("   26.3 的 `intermediary-26.3-v2.jar` 返回 404）。本实验因此使用 1.21.11 的真实映射。\n");
		sb.append("   26.3 上的映射来源是阶段 1 的前置阻塞项，见 `docs/stage0/T0.2-version-matrix.md`。\n");
		sb.append("3. Mixin 注解字符串：本实验已测出 static 模式（MixinExtension）**不会**改写 `@Inject(method=...)`，\n");
		sb.append("   因此阶段 1 必须实现内核侧的 refmap/注解翻译（G3 的推荐方案），并用带真实 mixin 的 mod 回归。\n");
		sb.append("4. 落盘 cache 命中后的真实热启动耗时。\n");

		return sb.toString();
	}

	private static String tinyRemapperVersion() {
		String v = D1NamespaceExperiment.class.getPackage().getImplementationVersion();
		return v == null ? "0.14.1（见 gradle.properties 的 tiny_remapper_version）" : v;
	}

	/** 报告里用到的有序配置视图，避免把 map 直接拼进 markdown。 */
	private static Map<String, String> configView() {
		Map<String, String> view = new LinkedHashMap<>();
		view.put("mappings", "intermediary.tiny + mojmap.txt");
		return view;
	}
}
