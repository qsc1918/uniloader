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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.fabricmc.tinyremapper.InputTag;
import net.fabricmc.tinyremapper.NonClassCopyMode;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyUtils;

/**
 * T1.2 的验收夹具：把「跑一次混淆版本（≤1.21.11）的命名空间环境」所需的三样东西准备好。
 *
 * <p>内核在 26.1+ 上零重映射，所以「重映射缓存」这件事在 26.3 上永远命中不了任何东西，
 * 也就无法验收。要真正验收它，必须能跑一次<b>仍有混淆</b>的 MC——这里就是把那条路铺出来：
 *
 * <ol>
 *   <li>{@code mappings.tiny}：三命名空间 {@code official → intermediary → named}。
 *       内核要同时用到两条边——反混淆游戏本体用 {@code official → named}，
 *       重映射模组用 {@code intermediary → named}。两份真实映射按 official 名对齐后合并。</li>
 *   <li>{@code intermediary-game.jar}：把原版（official 名）游戏 jar 重映射到 intermediary。
 *       这是门闸 G2 要求的东西：<b>remap classpath 必须是源命名空间</b>（intermediary），
 *       喂 named 进去时类名照常换、成员名会静默不变。</li>
 *   <li>{@code remap-classpath.txt}：上面那份 jar + 库，拼成 {@code fabric.remapClasspathFile} 的格式。</li>
 * </ol>
 *
 * <p>用法（由 {@code run/prepare-named.sh} 调用）：
 * <pre>
 * ./gradlew :experiments:d1-namespace:prepareLoaderNamespaces --args="\
 *   --intermediary build/d1/mappings/intermediary.tiny \
 *   --mojmap       build/d1/mappings/mojmap.txt \
 *   --game-jar     run/game-1.21.11/server.jar \
 *   --libs         run/game-1.21.11/classpath.txt \
 *   --out          run/game-1.21.11"
 * </pre>
 */
public final class PrepareLoaderNamespaces {
	private PrepareLoaderNamespaces() {
	}

	public static void main(String[] args) throws Exception {
		Path intermediary = null;
		Path mojmap = null;
		Path gameJar = null;
		Path libs = null;
		Path out = null;
		// classpath.txt 里的路径是**相对仓库根**的（fetch-game.sh 在仓库根被调用时写下的），
		// 而 Gradle 跑这个任务的 working dir 是模块目录。给一个基准目录，把相对项补全。
		Path base = Paths.get(System.getProperty("user.dir"));

		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
			case "--intermediary" -> intermediary = Paths.get(args[++i]);
			case "--mojmap" -> mojmap = Paths.get(args[++i]);
			case "--game-jar" -> gameJar = Paths.get(args[++i]);
			case "--libs" -> libs = Paths.get(args[++i]);
			case "--out" -> out = Paths.get(args[++i]);
			case "--base" -> base = Paths.get(args[++i]);
			default -> {
				System.err.println("[prepare] 未知参数: " + args[i]);
				System.exit(2);
			}
			}
		}

		if (intermediary == null || mojmap == null || gameJar == null || out == null) {
			System.err.println("[prepare] 必填：--intermediary --mojmap --game-jar --out");
			System.exit(2);
		}

		Files.createDirectories(out);

		// ---- 1. 三命名空间映射 ---------------------------------------------------
		System.out.println("[prepare] 合并映射：" + intermediary + " + " + mojmap);
		MergedMappings merged = MergedMappings.load(intermediary, mojmap);
		MergedMappings.Coverage coverage = merged.coverage();
		System.out.printf("[prepare] 覆盖率：类 %.2f%%（%d/%d），成员 %.2f%%（%d/%d）%n",
				coverage.classRate(), coverage.classesMapped(), coverage.classesTotal(),
				coverage.memberRate(), coverage.membersMapped(), coverage.membersTotal());
		System.out.println("[prepare] mojmap 未解析行：" + merged.unparsedMojmapLines());

		Path mappingsOut = out.resolve("mappings.tiny");
		merged.writeTiny3(mappingsOut);
		System.out.println("[prepare] 写出三命名空间映射：" + mappingsOut + "（" + Files.size(mappingsOut) + " 字节）");

		// ---- 2. intermediary 版游戏 jar -----------------------------------------
		Path realGameJar = unwrapBundler(gameJar, out);

		List<Path> classpath = new ArrayList<>();

		if (libs != null && Files.isRegularFile(libs)) {
			for (String part : Files.readString(libs, StandardCharsets.UTF_8).split(java.io.File.pathSeparator)) {
				if (part.isBlank()) continue;

				Path p = Paths.get(part);
				p = p.isAbsolute() ? p : base.resolve(part).normalize();

				// classpath.txt 的第一项就是原版（official 名）游戏 jar，必须剔掉：
				// 它在重映射 classpath 里会与 intermediary 版重复定义同一批 MC 类，
				// tiny-remapper 的层级分析会看到两套名字，成员名重映射的结果就不可信了。
				if (Files.exists(p) && Files.isSameFile(p, gameJar)) continue;
				if (p.toAbsolutePath().normalize().equals(gameJar.toAbsolutePath().normalize())) continue;

				classpath.add(p);
			}
		}

		for (Path p : classpath) {
			if (!Files.isRegularFile(p)) {
				System.err.println("[prepare] classpath 里有不存在的文件（相对项按 " + base + " 补全）：" + p);
				System.exit(2);
			}
		}

		Path intermediaryGame = out.resolve("intermediary-game.jar");
		System.out.println("[prepare] 把游戏 jar 重映射到 intermediary：" + realGameJar + " → " + intermediaryGame);
		long start = System.nanoTime();
		remapJar(realGameJar, intermediary, "official", "intermediary", classpath, intermediaryGame);
		System.out.printf("[prepare] 完成，耗时 %.1f s（%d 字节）%n",
				(System.nanoTime() - start) / 1e9, Files.size(intermediaryGame));

		// ---- 3. remap classpath --------------------------------------------------
		List<String> entries = new ArrayList<>();
		entries.add(intermediaryGame.toAbsolutePath().toString());

		for (Path p : classpath) {
			entries.add(p.toAbsolutePath().toString());
		}

		Path classpathOut = out.resolve("remap-classpath.txt");
		Files.write(classpathOut, String.join(java.io.File.pathSeparator, entries).getBytes(StandardCharsets.UTF_8));
		System.out.println("[prepare] 写出 remap classpath：" + classpathOut + "（" + entries.size() + " 项）");
	}

	/**
	 * 服务端 jar 从 1.18 起是 bundler：真正的游戏 jar 在里面 {@code META-INF/versions/<版本>/server-<版本>.jar}。
	 * 这里把它取出来，重映射的对象是「真正装着 MC 类的那份」。
	 */
	private static Path unwrapBundler(Path jar, Path out) throws IOException {
		try (ZipFile zf = new ZipFile(jar.toFile())) {
			ZipEntry inner = null;

			for (java.util.Enumeration<? extends ZipEntry> e = zf.entries(); e.hasMoreElements(); ) {
				ZipEntry entry = e.nextElement();

				if (entry.getName().startsWith("META-INF/versions/") && entry.getName().endsWith(".jar")) {
					inner = entry;
					break;
				}
			}

			if (inner == null) {
				System.out.println("[prepare] 不是 bundler（没有 META-INF/versions/*.jar），直接用原 jar");

				return jar;
			}

			Path target = out.resolve("server-inner.jar");
			System.out.println("[prepare] 从 bundler 里取出真正的游戏 jar：" + inner.getName());

			try (InputStream in = zf.getInputStream(inner); OutputStream os = Files.newOutputStream(target)) {
				in.transferTo(os);
			}

			return target;
		}
	}

	private static void remapJar(Path input, Path mappingsTiny, String from, String to, List<Path> classpath, Path output)
			throws IOException {
		Files.deleteIfExists(output);

		TinyRemapper remapper = TinyRemapper.newRemapper()
				.withMappings(TinyUtils.createTinyMappingProvider(mappingsTiny, from, to))
				.renameInvalidLocals(false)
				.build();

		try {
			remapper.readClassPathAsync(classpath.toArray(new Path[0])).get();

			InputTag tag = remapper.createInputTag();
			remapper.readInputsAsync(tag, input).get();

			try (OutputConsumerPath consumer = new OutputConsumerPath.Builder(output).build()) {
				try (FileSystem fs = FileSystems.newFileSystem(input, (ClassLoader) null)) {
					Path root = fs.getRootDirectories().iterator().next();
					consumer.addNonClassFiles(root, NonClassCopyMode.FIX_META_INF, remapper);
				}

				remapper.apply(consumer, tag);
			}

			remapper.finish();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException(e);
		} catch (ExecutionException e) {
			throw new IOException(e.getCause());
		}
	}
}
