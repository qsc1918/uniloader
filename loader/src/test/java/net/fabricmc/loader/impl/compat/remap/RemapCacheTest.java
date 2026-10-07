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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * UniLoader fork patch (T1.2)。
 *
 * <p>这里测的是缓存的两件容易被想当然的事：
 * <ol>
 *   <li><b>键的稳定性与敏感性</b>：同样的输入必须算出同样的键（否则永远命中不了），
 *       任何一项输入变了键必须变（否则会拿到别的批次的产物）；</li>
 *   <li><b>完整性的判据</b>：只看到目录存在不够——写到一半被杀掉留下的残骸必须判为未命中。</li>
 * </ol>
 */
class RemapCacheTest {
	private static final String PROP_ENABLED = RemapCache.PROPERTY_ENABLED;

	@AfterEach
	void clearProperty() {
		System.clearProperty(PROP_ENABLED);
	}

	@Test
	@DisplayName("键：相同输入 → 相同键；任一输入变化 → 键变化")
	void keyIsStableAndSensitive() {
		List<String> base = Arrays.asList("modNs=intermediary", "runtimeNs=named", "mod=a|deadbeef");

		assertEquals(RemapCache.keyFor(base), RemapCache.keyFor(Arrays.asList("modNs=intermediary", "runtimeNs=named", "mod=a|deadbeef")),
				"同样的输入必须算出同样的键，否则缓存永远命中不了");
		assertNotEquals(RemapCache.keyFor(base), RemapCache.keyFor(Arrays.asList("modNs=intermediary", "runtimeNs=named", "mod=a|cafebabe")),
				"jar 内容变了，键必须跟着变，否则会拿到别的批次的产物");
		assertNotEquals(RemapCache.keyFor(base), RemapCache.keyFor(Arrays.asList("modNs=official", "runtimeNs=named", "mod=a|deadbeef")),
				"命名空间这一对变了，键必须跟着变");
	}

	@Test
	@DisplayName("键：对顺序敏感——class-tweaker 的合并顺序会影响产物")
	void keyIsOrderSensitive() {
		assertNotEquals(
				RemapCache.keyFor(Arrays.asList("mergeOrder=a,b", "mod=a|1", "mod=b|2")),
				RemapCache.keyFor(Arrays.asList("mergeOrder=b,a", "mod=a|1", "mod=b|2")),
				"合并顺序进了键，顺序不同就不能复用");
	}

	@Test
	@DisplayName("完整性：没有标记文件 → 未命中")
	void incompleteWithoutMarker(@TempDir Path root) throws IOException {
		RemapCache cache = RemapCache.of(root, "abc");
		Files.createDirectories(cache.entryDir());
		Files.write(cache.entryDir().resolve("a.jar"), new byte[] { 1 });

		assertFalse(cache.isComplete(Collections.singletonList("a.jar")),
				"只有目录、没有标记，必须判为未命中——那可能是上次写到一半的残骸");
	}

	@Test
	@DisplayName("完整性：标记列出的文件缺一个或为空 → 未命中")
	void incompleteWhenFileMissingOrEmpty(@TempDir Path root) throws IOException {
		RemapCache cache = RemapCache.of(root, "abc");
		Files.createDirectories(cache.entryDir());
		Files.write(cache.entryDir().resolve(RemapCache.MARKER), Arrays.asList("a.jar", "b.jar"), StandardCharsets.UTF_8);
		Files.write(cache.entryDir().resolve("a.jar"), new byte[] { 1 });

		assertFalse(cache.isComplete(Arrays.asList("a.jar", "b.jar")), "b.jar 不在，必须未命中");

		Files.write(cache.entryDir().resolve("b.jar"), new byte[0]);

		assertFalse(cache.isComplete(Arrays.asList("a.jar", "b.jar")), "b.jar 是空文件，必须未命中");
	}

	@Test
	@DisplayName("commit：写入标记并原子改名，随后命中")
	void commitThenHit(@TempDir Path root) throws IOException {
		RemapCache cache = RemapCache.of(root, "abc");
		Path staging = cache.prepareStaging();
		Files.write(staging.resolve("a.jar"), new byte[] { 1, 2, 3 });

		assertFalse(cache.isComplete(Collections.singletonList("a.jar")), "还没 commit，不该命中");

		cache.commit(staging, Collections.singletonList("a.jar"));

		assertTrue(Files.isRegularFile(cache.entryDir().resolve(RemapCache.MARKER)), "commit 必须写出标记文件");
		assertTrue(cache.isComplete(Collections.singletonList("a.jar")), "commit 之后应当命中");
		assertFalse(Files.exists(staging), "staging 应当已经被改名走，不该留下");
	}

	@Test
	@DisplayName("commit：目标已存在（并发）时保留先到的那一份，丢弃自己")
	void commitKeepsExistingEntry(@TempDir Path root) throws IOException {
		RemapCache cache = RemapCache.of(root, "abc");

		Path first = cache.prepareStaging();
		Files.write(first.resolve("a.jar"), new byte[] { 1 });
		cache.commit(first, Collections.singletonList("a.jar"));

		Path second = cache.prepareStaging();
		Files.write(second.resolve("a.jar"), new byte[] { 2 });
		cache.commit(second, Collections.singletonList("a.jar"));

		assertEquals(1, Files.readAllBytes(cache.entryDir().resolve("a.jar"))[0], "应当保留先到的那一份");
		assertFalse(Files.exists(second), "后到的那份临时目录应当被丢弃");
	}

	@Test
	@DisplayName("discard：临时目录被整棵删掉")
	void discardRemovesTree(@TempDir Path root) throws IOException {
		RemapCache cache = RemapCache.of(root, "abc");
		Path staging = cache.prepareStaging();
		Files.createDirectories(staging.resolve("nested"));
		Files.write(staging.resolve("nested").resolve("x.jar"), new byte[] { 1 });

		cache.discard(staging);

		assertFalse(Files.exists(staging));
	}

	@Test
	@DisplayName("sha256：内容相同 → 同值；内容不同 → 异值；且与文件形态无关")
	void sha256OfContent(@TempDir Path root) throws IOException {
		Path a = root.resolve("a.bin");
		Path b = root.resolve("b.bin");
		Files.write(a, "hello".getBytes(StandardCharsets.UTF_8));
		Files.write(b, "hello".getBytes(StandardCharsets.UTF_8));

		assertEquals(RemapCache.sha256(a), RemapCache.sha256(b));
		assertEquals(RemapCache.sha256(a), RemapCache.sha256("hello".getBytes(StandardCharsets.UTF_8)));

		Files.write(b, "hello!".getBytes(StandardCharsets.UTF_8));

		assertNotEquals(RemapCache.sha256(a), RemapCache.sha256(b));
	}

	@Test
	@DisplayName("开关：默认启用，-Dul.remapCache=false 关闭")
	void enabledHonoursProperty() {
		assertTrue(RemapCache.enabled(), "默认必须启用——26.3 上它不命中任何东西，开着也没有代价");

		System.setProperty(PROP_ENABLED, "false");

		assertFalse(RemapCache.enabled(), "显式 false 必须关闭缓存");
	}
}
