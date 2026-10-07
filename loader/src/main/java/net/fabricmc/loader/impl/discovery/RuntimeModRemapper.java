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

package net.fabricmc.loader.impl.discovery;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

import org.objectweb.asm.commons.Remapper;

import net.fabricmc.classtweaker.api.ClassTweaker;
import net.fabricmc.classtweaker.api.ClassTweakerReader;
import net.fabricmc.classtweaker.api.ClassTweakerWriter;
import net.fabricmc.classtweaker.visitors.ClassTweakerRemapperVisitor;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.FormattedException;
import net.fabricmc.loader.impl.compat.diagnostics.LoaderDiagnostics;
import net.fabricmc.loader.impl.compat.remap.RemapCache;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.launch.MappingConfiguration;
import net.fabricmc.loader.impl.util.FileSystemUtil;
import net.fabricmc.loader.impl.util.ManifestUtil;
import net.fabricmc.loader.impl.util.SystemProperties;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;
import net.fabricmc.loader.impl.util.log.TinyRemapperLoggerAdapter;
import net.fabricmc.tinyremapper.InputTag;
import net.fabricmc.tinyremapper.NonClassCopyMode;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyUtils;
import net.fabricmc.tinyremapper.extension.mixin.MixinExtension;

public final class RuntimeModRemapper {
	private static final String REMAP_TYPE_MANIFEST_KEY = "Fabric-Loom-Mixin-Remap-Type";
	private static final String REMAP_TYPE_MIXIN = "mixin";
	private static final String REMAP_TYPE_STATIC = "static";

	public static void remap(Collection<ModCandidateImpl> modCandidates, Path tmpDir, Path outputDir) {
		List<ModCandidateImpl> modsToRemap = new ArrayList<>();
		Set<InputTag> remapMixins = new HashSet<>();

		for (ModCandidateImpl mod : modCandidates) {
			if (mod.getRequiresRemap()) {
				modsToRemap.add(mod);
			}
		}

		if (modsToRemap.isEmpty()) return;

		MappingConfiguration config = FabricLauncherBase.getLauncher().getMappingConfiguration();
		String modNs = config.getDefaultModDistributionNamespace();
		String runtimeNs = config.getRuntimeNamespace();
		if (modNs.equals(runtimeNs) || !config.hasAnyMappings()) return;

		Map<ModCandidateImpl, RemapInfo> infoMap = new HashMap<>();

		TinyRemapper remapper = null;
		// UniLoader fork patch (T1.2): 整批缓存。null 表示这次不启用缓存（-Dul.remapCache=false）。
		RemapCache cache = null;
		Path staging = null;
		List<String> cacheFiles = new ArrayList<>();

		try {
			FabricLauncher launcher = FabricLauncherBase.getLauncher();

			ClassTweaker mergedClassTweaker = ClassTweaker.newInstance();
			mergedClassTweaker.visitHeader(modNs);

			for (ModCandidateImpl mod : modsToRemap) {
				RemapInfo info = new RemapInfo();
				infoMap.put(mod, info);

				if (mod.hasPath()) {
					List<Path> paths = mod.getPaths();
					if (paths.size() != 1) throw new UnsupportedOperationException("multiple path for "+mod);

					info.inputPath = paths.get(0);
				} else {
					info.inputPath = mod.copyToDir(tmpDir, true);
					info.inputIsTemp = true;
				}

				String classTweaker = mod.getMetadata().getClassTweaker();

				if (classTweaker != null) {
					info.classTweakerPath = classTweaker;

					try (FileSystemUtil.FileSystemDelegate jarFs = FileSystemUtil.getJarFileSystem(info.inputPath, false)) {
						FileSystem fs = jarFs.get();
						info.classTweaker = Files.readAllBytes(fs.getPath(classTweaker));
					} catch (Throwable t) {
						throw new RuntimeException("Error reading class tweaker for mod '" +mod.getId()+ "'!", t);
					}

					ClassTweakerReader.create(mergedClassTweaker).read(info.classTweaker, modNs);
				}
			}

			// 输入路径已经全部就绪（内存里的模组也已落盘），现在才算缓存键：
			// 键里含每个 jar 与 class-tweaker 的内容，所以必须等落盘之后。
			List<Path> remapClasspath = getRemapClasspath();
			String defaultMixinRemapType = System.getProperty(SystemProperties.DEFAULT_MIXIN_REMAP_TYPE, REMAP_TYPE_MIXIN);

			for (ModCandidateImpl mod : modsToRemap) {
				cacheFiles.add(mod.getDefaultFileName());
			}

			if (RemapCache.enabled()) {
				cache = RemapCache.of(RemapCache.root(), RemapCache.keyFor(
						buildCacheKey(modsToRemap, infoMap, modNs, runtimeNs, remapClasspath, defaultMixinRemapType, config)));
			}

			if (cache != null && cache.isComplete(cacheFiles)) {
				for (ModCandidateImpl mod : modsToRemap) {
					mod.setPaths(Collections.singletonList(cache.entryDir().resolve(mod.getDefaultFileName())));
				}

				Log.info(LogCategory.MOD_REMAP, "重映射缓存命中 %s：跳过 %d 个模组的重映射", cache.entryDir(), modsToRemap.size());
				LoaderDiagnostics.note("重映射缓存命中：" + cache.entryDir()
						+ "（" + modsToRemap.size() + " 个模组，跳过重映射；键 " + cache.key() + "）");

				return;
			}

			Path writeDir = outputDir;

			if (cache != null) {
				staging = cache.prepareStaging();
				writeDir = staging;
			}

			for (ModCandidateImpl mod : modsToRemap) {
				RemapInfo info = infoMap.get(mod);
				info.outputPath = writeDir.resolve(mod.getDefaultFileName());
				Files.deleteIfExists(info.outputPath);
			}

			remapper = TinyRemapper.newRemapper(new TinyRemapperLoggerAdapter(LogCategory.MOD_REMAP))
					.withMappings(TinyUtils.createMappingProvider(launcher.getMappingConfiguration().getMappings(), modNs, runtimeNs))
					.renameInvalidLocals(false)
					.extension(new MixinExtension(remapMixins::contains))
					.extraAnalyzeVisitor((mrjVersion, className, next) ->
					mergedClassTweaker.createClassVisitor(FabricLoaderImpl.ASM_VERSION, next, null))
					.build();

			remapper.readClassPathAsync(remapClasspath.toArray(new Path[0]));

			for (ModCandidateImpl mod : modsToRemap) {
				RemapInfo info = infoMap.get(mod);

				InputTag tag = remapper.createInputTag();
				info.tag = tag;

				if (requiresMixinRemap(info.inputPath, defaultMixinRemapType)) {
					remapMixins.add(tag);
				}

				remapper.readInputsAsync(tag, info.inputPath);
			}

			//Done in a 2nd loop as we need to make sure all the inputs are present before remapping
			for (ModCandidateImpl mod : modsToRemap) {
				RemapInfo info = infoMap.get(mod);
				OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(info.outputPath).build();

				FileSystemUtil.FileSystemDelegate delegate = FileSystemUtil.getJarFileSystem(info.inputPath, false);

				if (delegate.get() == null) {
					throw new RuntimeException("Could not open JAR file " + info.inputPath.getFileName() + " for NIO reading!");
				}

				Path inputJar = delegate.get().getRootDirectories().iterator().next();
				outputConsumer.addNonClassFiles(inputJar, NonClassCopyMode.FIX_META_INF, remapper);

				info.outputConsumerPath = outputConsumer;

				remapper.apply(outputConsumer, info.tag);
			}

			//Done in a 3rd loop as this can happen when the remapper is doing its thing.
			for (ModCandidateImpl mod : modsToRemap) {
				RemapInfo info = infoMap.get(mod);

				if (info.classTweaker != null) {
					info.classTweaker = remapClassTweaker(info.classTweaker, remapper.getEnvironment().getRemapper(), modNs, runtimeNs);
				}
			}

			remapper.finish();

			for (ModCandidateImpl mod : modsToRemap) {
				RemapInfo info = infoMap.get(mod);

				info.outputConsumerPath.close();

				if (info.classTweakerPath != null) {
					try (FileSystemUtil.FileSystemDelegate jarFs = FileSystemUtil.getJarFileSystem(info.outputPath, false)) {
						FileSystem fs = jarFs.get();

						Files.delete(fs.getPath(info.classTweakerPath));
						Files.write(fs.getPath(info.classTweakerPath), info.classTweaker);
					}
				}
			}

			// UniLoader fork patch (T1.2): 产物落盘之后再指向它。
			// 缓存路径下产物写在临时目录里，commit 之后才是稳定位置——先把路径指向临时目录，
			// 会让「刚起来的这次」与「下次命中缓存的那次」指向不同的文件，缓存就白做了。
			if (cache != null) {
				cache.commit(staging, cacheFiles);
				staging = null; // 已交给缓存，异常处理里不该再去删它

				for (ModCandidateImpl mod : modsToRemap) {
					mod.setPaths(Collections.singletonList(cache.entryDir().resolve(mod.getDefaultFileName())));
				}

				Log.info(LogCategory.MOD_REMAP, "重映射完成并写入缓存 %s（%d 个模组，键 %s）",
						cache.entryDir(), modsToRemap.size(), cache.key());
				LoaderDiagnostics.note("重映射完成并写入缓存：" + cache.entryDir()
						+ "（" + modsToRemap.size() + " 个模组；键 " + cache.key() + "）");
			} else {
				for (ModCandidateImpl mod : modsToRemap) {
					mod.setPaths(Collections.singletonList(infoMap.get(mod).outputPath));
				}
			}
		} catch (Throwable t) {
			if (remapper != null) {
				remapper.finish();
			}

			for (RemapInfo info : infoMap.values()) {
				if (info.outputPath == null) {
					continue;
				}

				try {
					Files.deleteIfExists(info.outputPath);
				} catch (IOException e) {
					Log.warn(LogCategory.MOD_REMAP, "Error deleting failed output jar %s", info.outputPath, e);
				}
			}

			// 半成品绝不能留在缓存里——下一次启动会把它当成命中。
			if (cache != null && staging != null) {
				cache.discard(staging);
			}

			throw new FormattedException("Failed to remap mods!", t);
		} finally {
			for (RemapInfo info : infoMap.values()) {
				try {
					if (info.inputIsTemp) Files.deleteIfExists(info.inputPath);
				} catch (IOException e) {
					Log.warn(LogCategory.MOD_REMAP, "Error deleting temporary input jar %s", info.inputIsTemp, e);
				}
			}
		}
	}

	private static byte[] remapClassTweaker(byte[] input, Remapper remapper, String modNs, String runtimeNs) {
		ClassTweakerWriter writer = ClassTweakerWriter.create(ClassTweaker.CT_LATEST);
		ClassTweakerRemapperVisitor remappingDecorator = new ClassTweakerRemapperVisitor(writer, remapper, modNs, runtimeNs);
		ClassTweakerReader reader = ClassTweakerReader.create(remappingDecorator);
		reader.read(input, modNs);
		return writer.getOutput();
	}

	/**
	 * UniLoader fork patch (T1.2): the batch-wide remap cache key.
	 *
	 * <p>键必须覆盖整批输入（G9 的约束）：某个模组的产物取决于同批其它模组，所以
	 * 「所有模组 jar 的内容 + 各自的 class-tweaker 内容 + class-tweaker 的合并顺序 +
	 * 映射文件 + modNs→runtimeNs 这一对 + remap classpath + mixin remap 类型」一个都不能少。
	 */
	private static List<String> buildCacheKey(List<ModCandidateImpl> modsToRemap, Map<ModCandidateImpl, RemapInfo> infoMap,
			String modNs, String runtimeNs, List<Path> remapClasspath, String defaultMixinRemapType,
			MappingConfiguration config) throws IOException {
		List<String> parts = new ArrayList<>();

		parts.add("modNs=" + modNs);
		parts.add("runtimeNs=" + runtimeNs);
		parts.add("mixinRemapType=" + defaultMixinRemapType);
		parts.add("mappings=" + mappingIdentity(config));
		parts.add("classpath=" + classpathIdentity(remapClasspath));

		// class-tweaker 的合并顺序会影响最终产物，所以顺序本身也要进键。
		StringBuilder order = new StringBuilder("mergeOrder=");

		for (ModCandidateImpl mod : modsToRemap) {
			if (order.length() > "mergeOrder=".length()) order.append(',');
			order.append(mod.getId());
		}

		parts.add(order.toString());

		// 每个模组的内容：按 id 排序，让「发现顺序变了但集合没变」不至于白跑一次重映射。
		Map<String, String> perMod = new TreeMap<>();

		for (ModCandidateImpl mod : modsToRemap) {
			RemapInfo info = infoMap.get(mod);
			String tweaker = info.classTweaker == null ? "-" : RemapCache.sha256(info.classTweaker);
			perMod.put(mod.getId(), RemapCache.sha256(info.inputPath) + "|" + tweaker);
		}

		for (Map.Entry<String, String> e : perMod.entrySet()) {
			parts.add("mod=" + e.getKey() + "|" + e.getValue());
		}

		return parts;
	}

	/**
	 * The identity of the mapping file.
	 *
	 * <p>优先哈希 {@code fabric.mappingPath} 指向的文件——那是能真正拿到内容的形态。
	 * 映射来自 classpath 上的 jar 时拿不到路径，只能退而求其次用元数据；那种情况下
	 * 「同版本号但换了映射内容」覆盖不到，需要精确性时请显式设 {@code fabric.mappingPath}。
	 */
	private static String mappingIdentity(MappingConfiguration config) {
		String path = System.getProperty(SystemProperties.MAPPING_PATH);

		if (path != null) {
			Path file = Paths.get(path);

			if (Files.isRegularFile(file)) {
				try {
					return "file:" + RemapCache.sha256(file);
				} catch (IOException e) {
					return "file-unreadable:" + file;
				}
			}
		}

		return "meta:" + config.getGameId() + "/" + config.getGameVersion() + "/" + config.getMappingName()
				+ "/" + config.getNamespaces();
	}

	/**
	 * The identity of the remap classpath: path + size + mtime.
	 *
	 * <p>内容全量哈希太贵，这三个足够抓住「换了 jar」。
	 */
	private static String classpathIdentity(List<Path> classpath) {
		List<String> parts = new ArrayList<>();

		for (Path p : classpath) {
			String abs = p.toAbsolutePath().toString();

			try {
				parts.add(abs + "|" + Files.size(p) + "|" + Files.getLastModifiedTime(p).toMillis());
			} catch (IOException e) {
				parts.add(abs + "|?");
			}
		}

		return RemapCache.keyFor(parts);
	}

	private static List<Path> getRemapClasspath() throws IOException {
		String remapClasspathFile = System.getProperty(SystemProperties.REMAP_CLASSPATH_FILE);

		if (remapClasspathFile == null) {
			throw new RuntimeException("No remapClasspathFile provided");
		}

		String content = new String(Files.readAllBytes(Paths.get(remapClasspathFile)), StandardCharsets.UTF_8);

		return Arrays.stream(content.split(File.pathSeparator))
				.map(Paths::get)
				.collect(Collectors.toList());
	}

	/**
	 * Determine whether a jar requires Mixin remapping with tiny remapper.
	 *
	 * <p>This is typically the case when a mod was built without the Mixin annotation processor generating refmaps.
	 */
	private static boolean requiresMixinRemap(Path inputPath, String defaultMixinRemapType) throws IOException, URISyntaxException {
		final Manifest manifest = ManifestUtil.readManifest(inputPath);
		if (manifest == null) return false;

		final Attributes mainAttributes = manifest.getMainAttributes();

		String remapType = mainAttributes.getValue(REMAP_TYPE_MANIFEST_KEY);
		if (remapType == null) remapType = defaultMixinRemapType;

		return REMAP_TYPE_STATIC.equalsIgnoreCase(remapType);
	}

	private static class RemapInfo {
		InputTag tag;
		Path inputPath;
		Path outputPath;
		boolean inputIsTemp;
		OutputConsumerPath outputConsumerPath;
		String classTweakerPath;
		byte[] classTweaker;
	}
}
