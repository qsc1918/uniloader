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

package net.fabricmc.loader.impl.compat.remap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

/**
 * UniLoader fork patch (T1.2)：运行时模组重映射的整批缓存。
 *
 * <p>为什么要整批缓存、而不是按单个 mod 缓存：{@code RuntimeModRemapper} 用<b>一个</b>
 * {@code TinyRemapper} 处理整批模组，并把各模组的 class-tweaker <b>合并</b>成一个再挂上去。
 * 因此某个模组的重映射产物<b>取决于同批其它模组</b>——按单个 mod 算缓存键是错的。
 * 缓存键必须覆盖整批输入：所有模组 jar 的内容 + 各自的 class-tweaker 内容 +
 * 映射文件 + {@code modNs → runtimeNs} 这一对命名空间 + remap classpath + mixin remap 类型。
 *
 * <p>只在「需要重映射」的启动里才会被用到：目标 MC 未混淆时（26.1 起）两侧命名空间同名，
 * 重映射通道根本不启动，这个缓存自然也不会命中任何东西。
 *
 * <p>目录布局：{@code <root>/remapped-<key>/} 存放该批次的产物，外加一个 {@code .complete}
 * 标记文件列出该批次应有的文件。标记文件是「完整性」的唯一判据——只看到目录存在还不够，
 * 上一次写到一半被杀掉留下的半成品必须被判为未命中。
 */
public final class RemapCache {
	/** 总开关；{@code -Dul.remapCache=false} 时退回「每次重新重映射」。 */
	public static final String PROPERTY_ENABLED = "ul.remapCache";
	/** 缓存根目录；默认 {@code ~/.ul/cache}。 */
	public static final String PROPERTY_DIR = "ul.remapCacheDir";

	/**
	 * 缓存格式版本。
	 *
	 * <p>重映射管线的行为一变（换了 tiny-remapper、改了 class-tweaker 的合并方式等），
	 * 老产物就不再可信。改管线时把这里加一，等价于整体作废缓存——比事后删目录可靠。
	 */
	public static final String FORMAT_VERSION = "ul-remap-cache-1";

	public static final String PREFIX = "remapped-";
	public static final String MARKER = ".complete";
	public static final String STAGING_MARK = ".tmp-";

	public static final LogCategory LOG_CATEGORY = LogCategory.createCustom("UniLoader", "RemapCache");

	private final Path root;
	private final String key;
	private final Path entryDir;

	private RemapCache(Path root, String key) {
		this.root = root;
		this.key = key;
		this.entryDir = root.resolve(PREFIX + key);
	}

	/** 缓存是否启用；默认启用，只有显式 {@code -Dul.remapCache=false} 才关闭。 */
	public static boolean enabled() {
		String raw = System.getProperty(PROPERTY_ENABLED);

		return raw == null || !"false".equalsIgnoreCase(raw.trim());
	}

	/** 缓存根目录：{@code -Dul.remapCacheDir} 优先，否则 {@code ~/.ul/cache}。 */
	public static Path root() {
		String override = System.getProperty(PROPERTY_DIR);

		if (override != null && !override.trim().isEmpty()) {
			return Paths.get(override.trim());
		}

		return Paths.get(System.getProperty("user.home", "."), ".ul", "cache");
	}

	public static RemapCache of(Path root, String key) {
		return new RemapCache(root, key);
	}

	public Path entryDir() {
		return entryDir;
	}

	public String key() {
		return key;
	}

	/**
	 * 纯函数：把一组「名字=值」的输入拼成一个稳定的哈希。
	 *
	 * <p>顺序敏感（调用方按模组 id 排序后再拼），且以 {@link #FORMAT_VERSION} 打头，
	 * 这样管线一改老缓存自动作废。用 {@code '\n'} 分隔：值里出现换行会改变拼接结果，
	 * 但那只会让它变成一个不同的键（漏命中），不会把两个不同的批次算成同一个。
	 */
	public static String keyFor(List<String> parts) {
		StringBuilder sb = new StringBuilder(FORMAT_VERSION);

		for (String part : parts) {
			sb.append('\n').append(part == null ? "" : part);
		}

		return sha256(sb.toString().getBytes(StandardCharsets.UTF_8));
	}

	/** 文件内容的 SHA-256（十六进制）。 */
	public static String sha256(Path file) throws IOException {
		MessageDigest digest = newDigest();

		try (InputStream in = Files.newInputStream(file)) {
			byte[] buf = new byte[8192];
			int n;

			while ((n = in.read(buf)) > 0) {
				digest.update(buf, 0, n);
			}
		}

		return toHex(digest.digest());
	}

	public static String sha256(byte[] data) {
		return toHex(newDigest().digest(data));
	}

	/**
	 * 这个批次是否已经在缓存里、且是完整的。
	 *
	 * <p>判据有三条，缺一不可：标记文件存在、标记里列出的文件都在、每个文件都非空。
	 * 只看「目录在不在」会把上一次写到一半的残骸当成命中，后果是游戏拿着半截 jar 起来。
	 */
	public boolean isComplete(Collection<String> expectedFiles) {
		if (!Files.isRegularFile(entryDir.resolve(MARKER))) return false;

		try {
			List<String> listed = Files.readAllLines(entryDir.resolve(MARKER), StandardCharsets.UTF_8);

			if (!listed.containsAll(expectedFiles)) return false;
		} catch (IOException e) {
			Log.debug(LOG_CATEGORY, "读不了缓存标记 %s：%s", entryDir, e);

			return false;
		}

		for (String name : expectedFiles) {
			Path file = entryDir.resolve(name);

			try {
				if (!Files.isRegularFile(file) || Files.size(file) <= 0) return false;
			} catch (IOException e) {
				return false;
			}
		}

		return true;
	}

	/** 为一次未命中准备一个临时目录；与最终目录同盘，`commit` 才能原子改名。 */
	public Path prepareStaging() throws IOException {
		Files.createDirectories(root);

		return Files.createTempDirectory(root, PREFIX + key + STAGING_MARK);
	}

	/**
	 * 把写好的临时目录变成正式缓存项。
	 *
	 * <p>先写标记再改名：这样「有目录」必然意味着「有标记」。并发下若目标已被别的进程写好，
	 * 直接丢弃自己这份、用别人的——两份内容等价（键一样），不需要合并。
	 */
	public void commit(Path staging, Collection<String> files) throws IOException {
		Files.write(staging.resolve(MARKER), new ArrayList<>(files), StandardCharsets.UTF_8);

		try {
			Files.move(staging, entryDir, StandardCopyOption.ATOMIC_MOVE);

			return;
		} catch (IOException atomicFailure) {
			// 目标已存在（并发）或文件系统不支持原子目录改名，两种都走这里。
			if (Files.isDirectory(entryDir)) {
				discard(staging);

				return;
			}

			try {
				Files.move(staging, entryDir);

				return;
			} catch (IOException e) {
				if (Files.isDirectory(entryDir)) {
					discard(staging);

					return;
				}

				discard(staging);

				throw e;
			}
		}
	}

	/** 丢弃一个临时目录；失败只记日志——缓存出问题不该拖垮启动。 */
	public void discard(Path staging) {
		if (staging == null) return;

		try {
			deleteRecursively(staging);
		} catch (IOException e) {
			Log.warn(LOG_CATEGORY, "无法清理重映射缓存的临时目录 %s：%s", staging, e);
		}
	}

	private static void deleteRecursively(Path dir) throws IOException {
		if (!Files.exists(dir)) return;

		if (Files.isDirectory(dir)) {
			List<Path> children = new ArrayList<>();

			try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
				stream.forEach(children::add);
			}

			for (Path child : children) {
				deleteRecursively(child);
			}
		}

		Files.deleteIfExists(dir);
	}

	private static MessageDigest newDigest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			// 每个 JVM 都必须有 SHA-256，走到这里说明运行环境坏了，不能装作没事。
			throw new IllegalStateException("JVM 不提供 SHA-256，无法计算重映射缓存键", e);
		}
	}

	private static String toHex(byte[] bytes) {
		StringBuilder sb = new StringBuilder(bytes.length * 2);

		for (byte b : bytes) {
			sb.append(Character.forDigit((b >> 4) & 0xF, 16));
			sb.append(Character.forDigit(b & 0xF, 16));
		}

		return sb.toString();
	}
}
