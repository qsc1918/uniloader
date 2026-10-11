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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;

/**
 * T2.1：quilt.mod.json 的解析。
 *
 * <p>夹具全部是真东西——从真实发布的 jar 里原样抽出来的 quilt.mod.json，
 * 不是照着 schema 编的样本。用编造的样本测「解析器能不能解析」没有意义，
 * 真实 jar 才会告诉你字段到底长什么样（比如 provides 带不带版本、load_type 写没写）。
 *
 * <p>另外：设环境变量 {@code UL_QUILT_METADATA_DIR} 指向一个装满真实
 * quilt.mod.json 的目录，本测试会额外把里面每一份都解析一遍——用来跑
 * 「QSL 一个 jar 里 50 多个模组」那种规模的验证，而不是只测 4 个样本。
 */
class QuiltModMetadataReaderTest {
	private static final String FIXTURE_DIR = "quilt";

	private static LoaderModMetadata read(String fixture) throws Exception {
		try (InputStream is = QuiltModMetadataReaderTest.class.getClassLoader().getResourceAsStream(FIXTURE_DIR + "/" + fixture)) {
			assertNotNull(is, "找不到夹具 " + fixture);
			return QuiltModMetadataReader.read(is, fixture);
		}
	}

	private static QuiltModMetadata readQuilt(String fixture) throws Exception {
		LoaderModMetadata metadata = read(fixture);
		assertTrue(metadata instanceof QuiltModMetadata, fixture + " 应该解析成 QuiltModMetadata");
		return (QuiltModMetadata) metadata;
	}

	// ---------------------------------------------------------- 真实夹具

	@Test
	void parsesRealQslTopLevel() throws Exception {
		QuiltModMetadata m = readQuilt("qsl-top.json");

		assertEquals("quilted_fabric_api", m.getId());
		assertEquals("org.quiltmc.quilted-fabric-api", m.getGroup());
		assertEquals("11.0.0-alpha.3+0.102.0-1.21", m.getVersion().getFriendlyString());
		assertEquals(QuiltLoadType.ALWAYS, m.getLoadType());

		// QSL 的核心承诺：我提供 fabric-api 与 fabric
		assertTrue(m.getProvides().contains("fabric-api"), "应提供 fabric-api");
		assertTrue(m.getProvides().contains("fabric"), "应提供 fabric");

		// 版本号必须留着：QSL 自己 11.0.0，提供的 fabric-api 却是 0.102.0
		assertEquals("0.102.0", providedVersion(m, "fabric-api"));
		assertFalse(m.getJars().isEmpty(), "QSL 应该有嵌套 jar");
	}

	@Test
	void parsesRealQfapiBaseWithProvidedVersion() throws Exception {
		QuiltModMetadata m = readQuilt("qfapi-base.json");

		assertEquals("quilted_fabric_api_base", m.getId());
		assertTrue(m.getProvides().contains("fabric-api-base"));
		// 这就是 T2.2 存在的理由：提供的是 fabric-api-base 0.4.42，自己却是 11.0.0-alpha.3
		assertEquals("0.4.42+16948103d1", providedVersion(m, "fabric-api-base"));
		assertEquals("11.0.0-alpha.3+0.102.0-1.21", m.getVersion().getFriendlyString());
	}

	@Test
	void parsesRealQslBaseLoadTypeIfPossible() throws Exception {
		QuiltModMetadata m = readQuilt("qsl-base.json");

		assertEquals("quilt_base", m.getId());
		assertEquals(QuiltLoadType.IF_POSSIBLE, m.getLoadType());
		assertTrue(m.getEntrypointKeys().contains("init"), "qsl_base 有 init 入口点");
	}

	@Test
	void parsesRealVoicechatWithBreaksAndMixin() throws Exception {
		QuiltModMetadata m = readQuilt("voicechat.json");

		assertEquals("voicechat", m.getId());
		assertEquals("de.maxhenkel.voicechat", m.getGroup());
		assertEquals("2.6.24+26.3", m.getVersion().getFriendlyString());
		assertTrue(m.getProvides().contains("voicechat_api"));

		// mixin 在根节点（不在 quilt_loader 段里）
		assertTrue(m.getMixinConfigs(net.fabricmc.api.EnvType.SERVER).contains("voicechat.mixins.json"));

		// breaks fabric-api <0.160.6+26.3 必须被读成 BREAKS，而不是被丢掉
		boolean hasBreak = false;

		for (ModDependency dep : m.getDependencies()) {
			if (dep.getKind() == ModDependency.Kind.BREAKS && dep.getModId().equals("fabric-api")) hasBreak = true;
		}

		assertTrue(hasBreak, "声明了 breaks fabric-api，解析后必须还在");

		// depends 里有 quilt_loader / minecraft / java
		List<String> depends = new ArrayList<>();

		for (ModDependency dep : m.getDependencies()) {
			if (dep.getKind() == ModDependency.Kind.DEPENDS) depends.add(dep.getModId());
		}

		assertTrue(depends.contains("quilt_loader"), "Quilt 模组必然依赖 quilt_loader：" + depends);
		assertTrue(depends.contains("minecraft"));
		assertTrue(depends.contains("java"));
	}

	@Test
	void parsesEntryointsOfRealMod() throws Exception {
		QuiltModMetadata m = readQuilt("voicechat.json");

		assertTrue(m.getEntrypointKeys().containsAll(Arrays.asList("main", "client", "modmenu")));
		assertEquals("de.maxhenkel.voicechat.QuiltVoicechatMod", m.getEntrypoints("main").get(0).getValue());
	}

	// ---------------------------------------------------------- JSON5

	@Test
	void acceptsJson5CommentsAndTrailingCommas() throws Exception {
		String json5 = "{"
				+ "  // 这是一行注释\n"
				+ "  /* 这是一段注释 */\n"
				+ "  \"schema_version\": 1,\n"
				+ "  \"quilt_loader\": {\n"
				+ "    id: 'json5-mod',\n"           // 无引号键名 + 单引号字符串
				+ "    group: \"test\",\n"
				+ "    version: \"1.0.0\",\n"         // 尾逗号
				+ "  },\n"
				+ "}\n";

		QuiltModMetadata m = parse(json5);
		assertEquals("json5-mod", m.getId());
		assertEquals("test", m.getGroup());
	}

	@Test
	void rejectsMissingSchemaVersion() {
		try {
			parse("{\"quilt_loader\": {\"id\": \"x\", \"group\": \"g\", \"version\": \"1.0\"}}");
			fail("缺 schema_version 必须报错，不能拿默认值糊过去");
		} catch (Exception e) {
			assertTrue(e.getMessage().contains("schema_version"), "错误信息里要指名哪个字段: " + e.getMessage());
		}
	}

	@Test
	void rejectsUnsupportedSchemaVersion() {
		try {
			parse("{\"schema_version\": 99, \"quilt_loader\": {\"id\": \"x\", \"group\": \"g\", \"version\": \"1.0\"}}");
			fail("不认识的 schema_version 必须报错");
		} catch (Exception e) {
			assertTrue(e.getMessage().contains("99"));
		}
	}

	@Test
	void stripsQuiltMixinConfigPrefix() throws Exception {
		String json = "{\"schema_version\": 1,"
				+ "\"quilt_loader\": {\"id\": \"m\", \"group\": \"g\", \"version\": \"1.0\"},"
				+ "\"mixin\": [\"#m:mod.mixins.json\", \"plain.mixins.json\"]}";
		QuiltModMetadata m = parse(json);
		Collection<String> configs = m.getMixinConfigs(net.fabricmc.api.EnvType.SERVER);

		assertTrue(configs.contains("mod.mixins.json"), "Quilt 的 #modid:config 前缀要剥掉: " + configs);
		assertTrue(configs.contains("plain.mixins.json"));
	}

	// ------------------------------------------------- 批量验证真实文件

	/**
	 * 把 {@code UL_QUILT_METADATA_DIR} 指向的目录里每一份 quilt.mod.json 都解析一遍。
	 *
	 * <p>默认不跑（目录不存在就跳过）——单测必须是可复现的，不能在没网的机器上失败。
	 * 要跑批量验证就先执行 {@code run/fetch-quilt-metadata.sh}。
	 */
	@Test
	void parsesEveryRealFileInFixtureDir() throws Exception {
		String dir = System.getenv("UL_QUILT_METADATA_DIR");

		if (dir == null || dir.isEmpty()) return;

		Path root = new File(dir).toPath();
		assertTrue(Files.isDirectory(root), "UL_QUILT_METADATA_DIR 不是目录: " + dir);

		List<Path> files = new ArrayList<>();

		for (Path p : Files.newDirectoryStream(root)) {
			if (p.toString().endsWith(".json")) files.add(p);
		}

		assertFalse(files.isEmpty(), "目录里没有 .json，批量验证等于没跑: " + dir);

		List<String> failures = new ArrayList<>();

		for (Path p : files) {
			try (InputStream is = Files.newInputStream(p)) {
				QuiltModMetadataReader.read(is, p.toString());
			} catch (Exception e) {
				failures.add(p.getFileName() + ": " + e);
			}
		}

		System.out.println("[quilt-metadata] 真实文件 " + files.size() + " 份，解析失败 " + failures.size() + " 份");

		if (!failures.isEmpty()) fail("有 " + failures.size() + " 份真实 quilt.mod.json 解析失败:\n" + String.join("\n", failures));
	}

	// ------------------------------------------------ minecraft 段（环境）

	@Test
	void readsClientOnlyEnvironment() throws Exception {
		QuiltModMetadata m = parse("{\"schema_version\":1,"
				+ "\"quilt_loader\":{\"id\":\"c\",\"group\":\"g\",\"version\":\"1.0\"},"
				+ "\"minecraft\":{\"environment\":\"client\"}}");

		assertEquals(net.fabricmc.loader.api.metadata.ModEnvironment.CLIENT, m.getEnvironment());
		assertFalse(m.loadsInEnvironment(net.fabricmc.api.EnvType.SERVER),
				"声明 client-only 的模组不该在服务端加载");
		assertTrue(m.loadsInEnvironment(net.fabricmc.api.EnvType.CLIENT));
	}

	@Test
	void readsDedicatedServerEnvironment() throws Exception {
		QuiltModMetadata m = parse("{\"schema_version\":1,"
				+ "\"quilt_loader\":{\"id\":\"s\",\"group\":\"g\",\"version\":\"1.0\"},"
				+ "\"minecraft\":{\"environment\":\"dedicated_server\"}}");

		assertFalse(m.loadsInEnvironment(net.fabricmc.api.EnvType.CLIENT));
		assertTrue(m.loadsInEnvironment(net.fabricmc.api.EnvType.SERVER));
	}

	@Test
	void missingMinecraftBlockMeansUniversal() throws Exception {
		QuiltModMetadata m = parse("{\"schema_version\":1,"
				+ "\"quilt_loader\":{\"id\":\"u\",\"group\":\"g\",\"version\":\"1.0\"}}");

		assertEquals(net.fabricmc.loader.api.metadata.ModEnvironment.UNIVERSAL, m.getEnvironment());
		assertTrue(m.loadsInEnvironment(net.fabricmc.api.EnvType.CLIENT));
		assertTrue(m.loadsInEnvironment(net.fabricmc.api.EnvType.SERVER));
	}

	@Test
	void starEnvironmentIsUniversal() throws Exception {
		QuiltModMetadata m = parse("{\"schema_version\":1,"
				+ "\"quilt_loader\":{\"id\":\"a\",\"group\":\"g\",\"version\":\"1.0\"},"
				+ "\"minecraft\":{\"environment\":\"*\"}}");

		assertTrue(m.loadsInEnvironment(net.fabricmc.api.EnvType.SERVER));
	}

	// ------------------------------------------------------ setVersion 可写

	@Test
	void setVersionIsAllowed() throws Exception {
		// VersionOverrides.apply() 会调它。抛异常 = 用户给 Quilt 模组设版本覆盖时启动直接崩。
		QuiltModMetadata m = parse("{\"schema_version\":1,"
				+ "\"quilt_loader\":{\"id\":\"v\",\"group\":\"g\",\"version\":\"1.0\"}}");

		m.setVersion(net.fabricmc.loader.api.Version.parse("2.0.0"));
		assertEquals("2.0.0", m.getVersion().getFriendlyString());
	}

	// ---------------------------------------------------------- 工具

	private static QuiltModMetadata parse(String json) throws Exception {
		try (InputStream is = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))) {
			return (QuiltModMetadata) QuiltModMetadataReader.read(is, "<inline>");
		}
	}

	private static String providedVersion(QuiltModMetadata metadata, String id) {
		for (QuiltProvidedMod provided : metadata.getProvidedMods()) {
			if (provided.id().equals(id) && provided.version() != null) return provided.version().getFriendlyString();
		}

		return null;
	}
}
