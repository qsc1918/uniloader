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

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.RecursiveTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.FormattedException;
import net.fabricmc.loader.impl.discovery.ModCandidateFinder.ModCandidateConsumer;
import net.fabricmc.loader.impl.game.GameProvider.BuiltinMod;
import net.fabricmc.loader.impl.metadata.BuiltinModMetadata;
import net.fabricmc.loader.impl.metadata.DependencyOverrides;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.fabricmc.loader.impl.metadata.MetadataVerifier;
import net.fabricmc.loader.impl.compat.discovery.ForeignModRecognizers;
import net.fabricmc.loader.impl.compat.namespace.NamespacePolicy;
import net.fabricmc.loader.impl.metadata.ModMetadataParser;
import net.fabricmc.loader.impl.metadata.NestedJarEntry;
import net.fabricmc.loader.impl.metadata.ParseMetadataException;
import net.fabricmc.loader.impl.metadata.VersionOverrides;
import net.fabricmc.loader.impl.util.ExceptionUtil;
import net.fabricmc.loader.impl.util.LoaderUtil;
import net.fabricmc.loader.impl.util.SystemProperties;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

public final class ModDiscoverer {
	private final VersionOverrides versionOverrides;
	private final DependencyOverrides depOverrides;
	private final List<ModCandidateFinder> candidateFinders = new ArrayList<>();
	private final EnvType envType = FabricLoaderImpl.INSTANCE.getEnvironmentType();
	private final Map<Long, ModScanTask> jijDedupMap = new ConcurrentHashMap<>(); // avoids reading the same jar twice
	private final List<NestedModInitData> nestedModInitDatas = Collections.synchronizedList(new ArrayList<>()); // breaks potential cycles from deduplication
	private final List<Path> nonFabricMods = Collections.synchronizedList(new ArrayList<>());
	// UniLoader fork patch (T1.4): 认出来的别家生态模组。与 nonFabricMods 分开存，
	// 因为「认不出是什么」和「认出是 NeoForge 但本阶段还不支持」对玩家的含义完全不同。
	private final List<ForeignMod> foreignMods = Collections.synchronizedList(new ArrayList<>());
	private final Set<Path> claimedForeign = Collections.synchronizedSet(new HashSet<>());

	public ModDiscoverer(VersionOverrides versionOverrides, DependencyOverrides depOverrides) {
		this.versionOverrides = versionOverrides;
		this.depOverrides = depOverrides;
	}

	public void addCandidateFinder(ModCandidateFinder f) {
		candidateFinders.add(f);
	}

	public List<ModCandidateImpl> discoverMods(FabricLoaderImpl loader, Map<String, Set<ModCandidateImpl>> envDisabledModsOut) throws ModResolutionException {
		long startTime = System.nanoTime();
		ForkJoinPool pool = new ForkJoinPool();
		Set<Path> processedPaths = new HashSet<>(); // suppresses duplicate paths
		List<Future<ModCandidateImpl>> futures = new ArrayList<>();

		// UniLoader fork patch (T1.4): 原来是 lambda，这里改成匿名类，
		// 因为要顺带实现 acceptForeign——别家生态的模组被认领之后，
		// 就不能再走「按 fabric.mod.json 解析」这条路了。
		ModCandidateConsumer taskSubmitter = new ModCandidateConsumer() {
			@Override
			public void accept(List<Path> paths, boolean requiresRemap) {
				List<Path> pendingPaths = new ArrayList<>(paths.size());

				for (Path path : paths) {
					assert path.equals(LoaderUtil.normalizeExistingPath(path));

					if (claimedForeign.contains(path)) {
						// 已经被别家生态的查找器认领了，本阶段加载不了它。
						continue;
					}

					if (processedPaths.add(path)) {
						pendingPaths.add(path);
					}
				}

				if (!pendingPaths.isEmpty()) {
					futures.add(pool.submit(new ModScanTask(pendingPaths, requiresRemap)));
				}
			}

			@Override
			public void acceptForeign(List<Path> paths, String ecosystem, String detail) {
				for (Path path : paths) {
					Path normalized = path;

					try {
						normalized = LoaderUtil.normalizeExistingPath(path);
					} catch (Throwable t) {
						Log.debug(ForeignModRecognizers.LOG_CATEGORY, "路径规范化失败，按原样认领：%s", t);
					}

					if (claimedForeign.add(normalized)) {
						foreignMods.add(new ForeignMod(normalized, ecosystem, detail));
					}
				}
			}
		};

		for (ModCandidateFinder finder : candidateFinders) {
			finder.findCandidates(taskSubmitter);
		}

		List<ModCandidateImpl> candidates = new ArrayList<>();

		// add builtin mods
		for (BuiltinMod mod : loader.getGameProvider().getBuiltinMods()) {
			if (!(mod.metadata.getVersion() instanceof SemanticVersion)) {
				String error = String.format("%s uses the non-semantic version %s, which doesn't support range comparisons and may cause mod dependencies against it to fail unexpectedly. Consider updating Fabric Loader or explicitly specifying the game version with the fabric.gameVersion system property.",
						mod.metadata.getId(), mod.metadata.getVersion());

				if (loader.isDevelopmentEnvironment()) { // fail hard in-dev
					throw new FormattedException("Invalid game version", error);
				} else {
					Log.warn(LogCategory.GENERAL, error);
				}
			}

			ModCandidateImpl candidate = ModCandidateImpl.createBuiltin(mod, versionOverrides, depOverrides);
			candidates.add(MetadataVerifier.verifyIndev(candidate, loader.isDevelopmentEnvironment()));
		}

		// Add the current Java version
		candidates.add(MetadataVerifier.verifyIndev(createJavaMod(), loader.isDevelopmentEnvironment()));

		ModResolutionException exception = null;

		int timeout = Integer.getInteger(SystemProperties.DEBUG_DISCOVERY_TIMEOUT, 60);
		if (timeout <= 0) timeout = Integer.MAX_VALUE;

		try {
			pool.shutdown();

			pool.awaitTermination(timeout, TimeUnit.SECONDS);

			for (Future<ModCandidateImpl> future : futures) {
				if (!future.isDone()) {
					throw new TimeoutException();
				}

				try {
					ModCandidateImpl candidate = future.get();
					if (candidate != null) candidates.add(candidate);
				} catch (ExecutionException e) {
					exception = ExceptionUtil.gatherExceptions(e, exception, exc -> new ModResolutionException("Mod discovery failed!", exc));
				}
			}

			for (NestedModInitData data : nestedModInitDatas) {
				for (Future<ModCandidateImpl> future : data.futures) {
					if (!future.isDone()) {
						throw new TimeoutException();
					}

					try {
						ModCandidateImpl candidate = future.get();
						if (candidate != null) data.target.add(candidate);
					} catch (ExecutionException e) {
						exception = ExceptionUtil.gatherExceptions(e, exception, exc -> new ModResolutionException("Mod discovery failed!", exc));
					}
				}
			}
		} catch (TimeoutException e) {
			throw new FormattedException("Mod discovery took too long!",
					"Analyzing the mod folder contents took longer than %d seconds. This may be caused by unusually slow hardware, pathological antivirus interference or other issues. The timeout can be changed with the system property %s (-D%<s=<desired timeout in seconds>).",
					timeout, SystemProperties.DEBUG_DISCOVERY_TIMEOUT);
		} catch (InterruptedException e) {
			throw new FormattedException("Mod discovery interrupted!", e);
		}

		if (exception != null) {
			throw exception;
		}

		// get optional set of disabled mod ids
		Set<String> disabledModIds = findDisabledModIds();

		// gather all mods (root+nested), initialize parent data

		Set<ModCandidateImpl> ret = Collections.newSetFromMap(new IdentityHashMap<>(candidates.size() * 2));
		Queue<ModCandidateImpl> queue = new ArrayDeque<>(candidates);
		ModCandidateImpl mod;

		while ((mod = queue.poll()) != null) {
			if (mod.getMetadata().loadsInEnvironment(envType)) {
				if (disabledModIds.contains(mod.getId())) {
					Log.info(LogCategory.DISCOVERY, "Skipping disabled mod %s", mod.getId());
					continue;
				}

				if (!ret.add(mod)) continue;

				for (ModCandidateImpl child : mod.getNestedMods()) {
					if (child.addParent(mod)) {
						queue.add(child);
					}
				}
			} else {
				envDisabledModsOut.computeIfAbsent(mod.getId(), ignore -> Collections.newSetFromMap(new IdentityHashMap<>())).add(mod);
			}
		}

		long endTime = System.nanoTime();

		Log.debug(LogCategory.DISCOVERY, "Mod discovery time: %.1f ms", (endTime - startTime) * 1e-6);

		return new ArrayList<>(ret);
	}

	public List<Path> getNonFabricMods() {
		return Collections.unmodifiableList(nonFabricMods);
	}

	/** UniLoader fork patch (T1.4)：本次发现里认出来的别家生态模组. */
	public List<ForeignMod> getForeignMods() {
		return Collections.unmodifiableList(foreignMods);
	}

	/**
	 * UniLoader fork patch (T1.4)：把认出来的别家生态模组说清楚.
	 *
	 * <p>上游对这类 jar 只有一句 {@code Found N non-fabric mod(s)}，玩家看不出那是
	 * NeoForge 的、更看不出该怎么办。这里逐条说明「是什么 + 依据 + 现在还不能加载」。
	 *
	 * <p>顺带把命名空间对不上的情况也报出来（决策 D1 的检查点）：在混淆版本上，
	 * 运行时是 intermediary 而 NeoForge/Forge 的代码全基于 mojmap，那种组合下
	 * 即使阶段 4/8 做完了也跑不起来——现在说清楚，比那时再排查便宜得多。
	 */
	public void dumpForeignMods(NamespacePolicy.Decision namespaceDecision) {
		if (foreignMods.isEmpty()) return;

		for (ForeignMod foreign : foreignMods) {
			String display = ForeignModRecognizers.displayName(foreign.ecosystem());

			Log.warn(ForeignModRecognizers.LOG_CATEGORY,
					"识别为 %s mod: %s（依据 %s；本阶段只识别不解析，尚未支持加载）",
					display, foreign.path().getFileName(), foreign.detail());

			if (!NamespacePolicy.isCompatible(foreign.ecosystem(), namespaceDecision)) {
				NamespacePolicy.reportMismatch(foreign.path().getFileName().toString(),
						foreign.ecosystem(), namespaceDecision);
			}
		}
	}

	/** 一个「认出来了但本阶段加载不了」的模组. */
	public static final class ForeignMod {
		private final Path path;
		private final String ecosystem;
		private final String detail;

		ForeignMod(Path path, String ecosystem, String detail) {
			this.path = path;
			this.ecosystem = ecosystem;
			this.detail = detail;
		}

		public Path path() {
			return path;
		}

		public String ecosystem() {
			return ecosystem;
		}

		/** 凭什么认出来的（本阶段就是清单文件路径）. */
		public String detail() {
			return detail;
		}

		@Override
		public String toString() {
			return ecosystem + ":" + path.getFileName();
		}
	}

	// retrieve set of disabled mod ids from system property
	private static Set<String> findDisabledModIds() {
		String modIdList = System.getProperty(SystemProperties.DISABLE_MOD_IDS);

		if (modIdList == null) {
			return Collections.emptySet();
		}

		Set<String> disabledModIds = Arrays.stream(modIdList.split(","))
				.map(String::trim)
				.filter(s -> !s.isEmpty())
				.collect(Collectors.toSet());
		Log.debug(LogCategory.DISCOVERY, "Disabled mod ids: %s", disabledModIds);
		return disabledModIds;
	}

	private ModCandidateImpl createJavaMod() {
		ModMetadata metadata = new BuiltinModMetadata.Builder("java", System.getProperty("java.specification.version").replaceFirst("^1\\.", ""))
				.setName(System.getProperty("java.vm.name"))
				.build();
		BuiltinMod builtinMod = new BuiltinMod(Collections.singletonList(Paths.get(System.getProperty("java.home"))), metadata);

		return ModCandidateImpl.createBuiltin(builtinMod, versionOverrides, depOverrides);
	}

	@SuppressWarnings("serial")
	final class ModScanTask extends RecursiveTask<ModCandidateImpl> {
		private final List<Path> paths;
		private final String localPath;
		private final RewindableInputStream is;
		private final long hash;
		private final boolean requiresRemap;
		private final List<String> parentPaths;

		ModScanTask(List<Path> paths, boolean requiresRemap) {
			this(paths, null, null, -1, requiresRemap, Collections.emptyList());
		}

		private ModScanTask(List<Path> paths, String localPath, RewindableInputStream is, long hash,
				boolean requiresRemap, List<String> parentPaths) {
			this.paths = paths;
			this.localPath = localPath != null ? localPath : paths.get(0).toString();
			this.is = is;
			this.hash = hash;
			this.requiresRemap = requiresRemap;
			this.parentPaths = parentPaths;
		}

		@Override
		protected ModCandidateImpl compute() {
			if (is != null) { // nested jar
				try {
					return computeJarStream();
				} catch (ParseMetadataException e) { // already contains all context
					throw ExceptionUtil.wrap(e);
				} catch (Throwable t) {
					throw new RuntimeException(String.format("Error analyzing nested jar %s from %s: %s", localPath, parentPaths, t), t);
				}
			} else { // regular classes-dir or jar
				try {
					for (Path path : paths) {
						final ModCandidateImpl candidate;

						if (Files.isDirectory(path)) {
							candidate = computeDir(path);
						} else {
							candidate = computeJarFile(path);
						}

						if (candidate != null) {
							return candidate;
						}
					}
				} catch (ParseMetadataException e) { // already contains all context
					throw ExceptionUtil.wrap(e);
				} catch (Throwable t) {
					throw new RuntimeException(String.format("Error analyzing %s: %s", paths, t), t);
				}

				return null;
			}
		}

		private ModCandidateImpl computeDir(Path path) throws IOException, ParseMetadataException {
			Path modJson = path.resolve("fabric.mod.json");
			if (!Files.exists(modJson)) return null;

			LoaderModMetadata metadata;

			try (InputStream is = Files.newInputStream(modJson)) {
				metadata = parseMetadata(is, path.toString());
			}

			return ModCandidateImpl.createPlain(paths, metadata, requiresRemap, Collections.emptyList());
		}

		private ModCandidateImpl computeJarFile(Path path) throws IOException, ParseMetadataException {
			try (ZipFile zf = new ZipFile(path.toFile())) {
				ZipEntry entry = zf.getEntry("fabric.mod.json");

				if (entry == null) {
					// UniLoader fork patch (T1.4): 上游到这就把它丢进 nonFabricMods，
					// 玩家在日志里只能看到一句 "Found 1 non-fabric mod(s): - xxx.jar"，
					// 完全不知道那其实是个 NeoForge 模组、也不知道该怎么办。
					// 这里先按注册的识别器认一遍：认出来就按别家生态记下，
					// 认不出来才退回「未知文件」。
					ForeignModRecognizers.Match match = ForeignModRecognizers.match(path);

					if (match != null) {
						Path normalized = path;

						try {
							normalized = LoaderUtil.normalizeExistingPath(path);
						} catch (Throwable t) {
							Log.debug(ForeignModRecognizers.LOG_CATEGORY, "路径规范化失败，按原样记录：%s", t);
						}

						if (claimedForeign.add(normalized)) {
							foreignMods.add(new ForeignMod(normalized, match.ecosystem(), match.detail()));
						}

						return null;
					}

					nonFabricMods.add(path);
					return null;
				}

				LoaderModMetadata metadata;

				try (InputStream is = zf.getInputStream(entry)) {
					metadata = parseMetadata(is, localPath);
				}

				if (!metadata.loadsInEnvironment(envType)) {
					return ModCandidateImpl.createPlain(paths, metadata, requiresRemap, Collections.emptyList());
				}

				List<ModScanTask> nestedModTasks;

				if (metadata.getJars().isEmpty()) {
					nestedModTasks = Collections.emptyList();
				} else {
					Set<NestedJarEntry> nestedJarPaths = new HashSet<>(metadata.getJars());

					nestedModTasks = computeNestedMods(new ZipEntrySource() {
						@Override
						public ZipEntry getNextEntry() throws IOException {
							while (jarIt.hasNext()) {
								NestedJarEntry jar = jarIt.next();
								ZipEntry ret = zf.getEntry(jar.getFile());

								if (isValidNestedJarEntry(ret)) {
									currentEntry = ret;
									jarIt.remove();
									return ret;
								}
							}

							currentEntry = null;
							return null;
						}

						@Override
						public RewindableInputStream getInputStream() throws IOException {
							try (InputStream is = zf.getInputStream(currentEntry)) {
								return new RewindableInputStream(is);
							}
						}

						private final Iterator<NestedJarEntry> jarIt = nestedJarPaths.iterator();
						private ZipEntry currentEntry;
					});

					if (!nestedJarPaths.isEmpty() && FabricLoaderImpl.INSTANCE.isDevelopmentEnvironment()) {
						Log.warn(LogCategory.METADATA, "Mod %s %s references missing nested jars: %s", metadata.getId(), metadata.getVersion(), nestedJarPaths);
					}
				}

				List<ModCandidateImpl> nestedMods;

				if (nestedModTasks.isEmpty()) {
					nestedMods = Collections.emptyList();
				} else {
					nestedMods = new ArrayList<>();
					nestedModInitDatas.add(new NestedModInitData(nestedModTasks, nestedMods));
				}

				return ModCandidateImpl.createPlain(paths, metadata, requiresRemap, nestedMods);
			}
		}

		private ModCandidateImpl computeJarStream() throws IOException, ParseMetadataException {
			LoaderModMetadata metadata = null;
			ZipEntry entry;

			try (ZipInputStream zis = new ZipInputStream(is)) {
				while ((entry = zis.getNextEntry()) != null) {
					if (entry.getName().equals("fabric.mod.json")) {
						metadata = parseMetadata(zis, localPath);
						break;
					}
				}
			}

			if (metadata == null) return null;

			if (!metadata.loadsInEnvironment(envType)) {
				return ModCandidateImpl.createNested(localPath, hash, metadata, requiresRemap, Collections.emptyList());
			}

			Collection<NestedJarEntry> nestedJars = metadata.getJars();
			List<ModScanTask> nestedModTasks;

			if (nestedJars.isEmpty()) {
				nestedModTasks = Collections.emptyList();
			} else {
				Set<String> nestedJarPaths = new HashSet<>(nestedJars.size());

				for (NestedJarEntry nestedJar : nestedJars) {
					nestedJarPaths.add(nestedJar.getFile());
				}

				is.rewind();

				try (ZipInputStream zis = new ZipInputStream(is)) {
					nestedModTasks = computeNestedMods(new ZipEntrySource() {
						@Override
						public ZipEntry getNextEntry() throws IOException {
							if (nestedJarPaths.isEmpty()) return null;

							ZipEntry ret;

							while ((ret = zis.getNextEntry()) != null) {
								if (isValidNestedJarEntry(ret) && nestedJarPaths.remove(ret.getName())) {
									is = new RewindableInputStream(zis); // reads the entry, which completes the ZipEntry with any trailing header data
									return ret;
								}
							}

							return null;
						}

						@Override
						public RewindableInputStream getInputStream() throws IOException {
							return is;
						}

						private RewindableInputStream is;
					});
				}

				if (!nestedJarPaths.isEmpty() && FabricLoaderImpl.INSTANCE.isDevelopmentEnvironment()) {
					Log.warn(LogCategory.METADATA, "Mod %s %s references missing nested jars: %s", metadata.getId(), metadata.getVersion(), nestedJarPaths);
				}
			}

			List<ModCandidateImpl> nestedMods;

			if (nestedModTasks.isEmpty()) {
				nestedMods = Collections.emptyList();
			} else {
				nestedMods = new ArrayList<>();
				nestedModInitDatas.add(new NestedModInitData(nestedModTasks, nestedMods));
			}

			ModCandidateImpl ret = ModCandidateImpl.createNested(localPath, hash, metadata, requiresRemap, nestedMods);
			ret.setData(is.getBuffer());

			return ret;
		}

		private List<ModScanTask> computeNestedMods(ZipEntrySource entrySource) throws IOException {
			List<String> parentPaths = new ArrayList<>(this.parentPaths.size() + 1);
			parentPaths.addAll(this.parentPaths);
			parentPaths.add(localPath);

			List<ModScanTask> tasks = new ArrayList<>(5);
			ModScanTask localTask = null;
			ZipEntry entry;

			while ((entry = entrySource.getNextEntry()) != null) {
				long hash = ModCandidateImpl.hash(entry);
				ModScanTask task = jijDedupMap.get(hash);

				if (task == null) {
					task = new ModScanTask(null, entry.getName(), entrySource.getInputStream(), hash, requiresRemap, parentPaths);
					ModScanTask prev = jijDedupMap.putIfAbsent(hash, task);

					if (prev != null) {
						task = prev;
					} else if (localTask == null) { // don't fork first task, leave it for this thread
						localTask = task;
					} else {
						task.fork();
					}
				}

				tasks.add(task);
			}

			if (tasks.isEmpty()) return Collections.emptyList();

			if (localTask != null) localTask.invoke();

			return tasks;
		}

		private LoaderModMetadata parseMetadata(InputStream is, String localPath) throws ParseMetadataException {
			return ModMetadataParser.parseMetadata(is, localPath, parentPaths, versionOverrides, depOverrides, FabricLoaderImpl.INSTANCE.isDevelopmentEnvironment());
		}
	}

	private static boolean isValidNestedJarEntry(ZipEntry entry) {
		return entry != null && !entry.isDirectory() && entry.getName().endsWith(".jar");
	}

	private interface ZipEntrySource {
		ZipEntry getNextEntry() throws IOException;
		RewindableInputStream getInputStream() throws IOException;
	}

	private static final class RewindableInputStream extends InputStream {
		private final ByteBuffer buffer;
		private int pos;

		RewindableInputStream(InputStream parent) throws IOException { // no parent.close()
			buffer = readMod(parent);

			assert buffer.hasArray() && buffer.arrayOffset() == 0 && buffer.position() == 0;
		}

		public ByteBuffer getBuffer() {
			return buffer;
		}

		public void rewind() {
			pos = 0;
		}

		@Override
		public int read() throws IOException {
			if (pos >= buffer.limit()) {
				return -1;
			} else {
				return buffer.get(pos++) & 0xff;
			}
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			int rem = buffer.limit() - pos;

			if (rem <= 0) {
				return -1;
			} else {
				len = Math.min(len, rem);
				System.arraycopy(buffer.array(), pos, b, off, len);
				pos += len;

				return len;
			}
		}
	}

	static ByteBuffer readMod(InputStream is) throws IOException {
		int available = is.available();
		boolean availableGood = available > 1;
		byte[] buffer = new byte[availableGood ? available : 30_000];
		int offset = 0;
		int len;

		while ((len = is.read(buffer, offset, buffer.length - offset)) >= 0) {
			offset += len;

			if (offset == buffer.length) {
				if (availableGood) {
					int val = is.read();
					if (val < 0) break;

					availableGood = false;
					buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, 30_000));
					buffer[offset++] = (byte) val;
				} else {
					buffer = Arrays.copyOf(buffer, buffer.length * 2);
				}
			}
		}

		return ByteBuffer.wrap(buffer, 0, offset);
	}

	private static class NestedModInitData {
		final List<? extends Future<ModCandidateImpl>> futures;
		final List<ModCandidateImpl> target;

		NestedModInitData(List<? extends Future<ModCandidateImpl>> futures, List<ModCandidateImpl> target) {
			this.futures = futures;
			this.target = target;
		}
	}
}
