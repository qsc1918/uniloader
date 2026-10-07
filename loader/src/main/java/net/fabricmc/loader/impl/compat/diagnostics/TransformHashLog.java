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

package net.fabricmc.loader.impl.compat.diagnostics;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

/**
 * UniLoader fork patch (T1.5)：把「每个类经过 Mixin 之前管线之后是什么字节」记下来。
 *
 * <p>存在的原因是 T1.5 的验收标准是<b>逐字节不变</b>，而不是「看起来没坏」。
 * 把「固定顺序的三步」改写成「有序可插拔的管线」时，任何一处顺序、短路或 null 语义走偏，
 * 产物都会变——而这种变化在冒烟测试里几乎必然看不出来（游戏照样能起）。
 * 所以先把产物本身记成哈希，改完再对一遍。
 *
 * <p>开关是 {@code -Dul.transform.hashFile=&lt;路径&gt;}。没设开关时，
 * 每个类只多做一次静态字段判断，热路径上没有其它开销。
 *
 * <p>写文件用的是「边跑边追加」而不是「退出时一次性写」：冒烟测试是用 TERM 掐掉的，
 * 而 shutdown hook 里的日志会被已经停掉的日志系统吞掉。一次「hook 到底跑没跑」的悬案，
 * 代价是整轮对拍白做。追加式的代价是文件按加载顺序而不是类名顺序——
 * 对拍时排一下序即可，反正逐行比之前本来就要排序。
 *
 * <p>与 {@link LoaderDiagnostics} 一样的纪律：<b>任何方法都不抛异常</b>。
 * 一个把游戏搞挂的度量工具，比没有度量工具更糟。
 */
public final class TransformHashLog {
	public static final String PROPERTY = "ul.transform.hashFile";

	private static final LogCategory LOG = LogCategory.createCustom("UniLoader", "TransformHash");
	private static final Path FILE = resolveFile();
	private static final Map<String, String> HASHES = new ConcurrentHashMap<>();
	private static final AtomicBoolean STARTED = new AtomicBoolean();

	private static volatile Writer writer;
	private static int pending;

	private TransformHashLog() {
	}

	private static Path resolveFile() {
		try {
			String value = System.getProperty(PROPERTY);
			if (value == null || value.isEmpty()) return null;

			return Paths.get(value).toAbsolutePath().normalize();
		} catch (Throwable t) {
			return null;
		}
	}

	public static boolean enabled() {
		return FILE != null;
	}

	/**
	 * 记一个类的产物哈希。
	 *
	 * @param bytes 可能为 null（表示「这个类没有产出字节」），一样要记下来——
	 *              「本该产出却返回 null」正是最需要被看见的那种偏差
	 */
	public static void record(String className, byte[] bytes) {
		if (FILE == null || className == null) return;

		try {
			String hash = bytes == null ? "<null>" : sha256(bytes);
			HASHES.put(className, hash);

			if (STARTED.compareAndSet(false, true)) {
				Log.info(LOG, "变换产物哈希开始记录到 %s", FILE);
				openWriter();
				Runtime.getRuntime().addShutdownHook(new Thread(TransformHashLog::close, "uniloader-transform-hash"));
			}

			append(className, hash);
		} catch (Throwable t) {
			// 度量不许破坏被测对象。
			Log.debug(LOG, "记录 %s 的变换哈希失败：%s", className, t);
		}
	}

	private static synchronized void openWriter() {
		try {
			Path parent = FILE.getParent();

			if (parent != null) Files.createDirectories(parent);

			writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8);
		} catch (IOException e) {
			writer = null;
			Log.warn(LOG, "打不开变换产物哈希文件 %s：%s", FILE, e);
		}
	}

	private static synchronized void append(String className, String hash) {
		if (writer == null) return;

		try {
			writer.write(className);
			writer.write('\t');
			writer.write(hash);
			writer.write('\n');

			if (++pending >= 256) {
				writer.flush();
				pending = 0;
			}
		} catch (IOException e) {
			Log.warn(LOG, "写变换产物哈希失败：%s", e);
		}
	}

	/** 收尾。由 shutdown hook 调用；失败只记日志。 */
	public static synchronized void close() {
		if (writer == null) return;

		try {
			writer.flush();
			writer.close();
			Log.info(LOG, "变换产物哈希已写入 %s（%d 个类）", FILE, HASHES.size());
		} catch (IOException e) {
			Log.warn(LOG, "关闭变换产物哈希文件失败：%s", e);
		} finally {
			writer = null;
		}
	}

	/** 已经记下的哈希（测试用）。 */
	static Map<String, String> hashes() {
		return new java.util.TreeMap<>(HASHES);
	}

	/** 测试接缝。 */
	static void reset() {
		HASHES.clear();
		pending = 0;
	}

	private static String sha256(byte[] bytes) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(bytes);
			StringBuilder sb = new StringBuilder(hash.length * 2);

			for (byte b : hash) {
				sb.append(Character.forDigit((b >> 4) & 0xF, 16));
				sb.append(Character.forDigit(b & 0xF, 16));
			}

			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			// SHA-256 是 JRE 必备算法，真没有也不该在这里炸。
			return "<unavailable>";
		}
	}
}
