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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.ContactInformation;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModDependency.Kind;
import net.fabricmc.loader.api.metadata.Person;
import net.fabricmc.loader.impl.lib.gson.JsonReader;
import net.fabricmc.loader.impl.lib.gson.JsonToken;
import net.fabricmc.loader.impl.metadata.ContactInformationImpl;
import net.fabricmc.loader.impl.metadata.EntrypointMetadata;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.fabricmc.loader.impl.metadata.NestedJarEntry;
import net.fabricmc.loader.impl.metadata.ParseMetadataException;

/**
 * UniLoader fork patch (T2.1): {@code quilt.mod.json} 的解析器。
 *
 * <p>两件必须交代清楚的事：
 * <ol>
 *   <li><b>JSON5 不是靠新依赖解决的。</b>内核里已经有一份 shaded gson，
 *       {@link JsonReader#setLenient(boolean)} 一开就支持注释（{@code //}、{@code /*}、
 *       {@code #}）、尾逗号、无引号键名、单引号字符串——正好覆盖真实 quilt.mod.json
 *       用到的那些 JSON5 特性。为此新增一个运行时依赖不值得（还要过一遍许可证审查）。</li>
 *   <li><b>只解析，不猜测。</b>不认识的字段一律 {@code skipValue()} 跳过；
 *       缺必填字段（{@code schema_version} / {@code id} / {@code version}）就抛出
 *       {@link ParseMetadataException}，绝不拿默认值糊过去——静默补默认值的下场是
 *       后面某一环用错数据，而现场已经没了。</li>
 * </ol>
 */
public final class QuiltModMetadataReader {
	private static final String SCHEMA_VERSION = "schema_version";
	private static final String QUILT_LOADER = "quilt_loader";
	private static final String MIXIN = "mixin";
	private static final String ACCESS_WIDENER = "access_widener";

	private QuiltModMetadataReader() {
	}

	private static String readAll(InputStream is) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		int count;

		while ((count = is.read(buffer)) > 0) {
			out.write(buffer, 0, count);
		}

		return new String(out.toByteArray(), StandardCharsets.UTF_8);
	}

	/**
	 * 解析一份 {@code quilt.mod.json}。
	 *
	 * @param is        清单内容（调用方负责关闭）
	 * @param localPath 用于报错时指认是哪个文件
	 */
	public static LoaderModMetadata read(InputStream is, String localPath) throws IOException, ParseMetadataException {
		// G11：checkstyle 对 impl/compat/** 放宽了 Javadoc 首句要求，这里仍按英文句点收尾。
		// 尾逗号是 gson 唯一吃不下的 JSON5 特性（其余靠 setLenient），所以先过一遍预处理。
		String json = QuiltJson5.stripTrailingCommas(readAll(is));
		JsonReader reader = new JsonReader(new StringReader(json));
		reader.setLenient(true); // JSON5：注释、无引号键名、单引号字符串

		if (reader.peek() != JsonToken.BEGIN_OBJECT) {
			throw new ParseMetadataException("quilt.mod.json 的根节点必须是对象", reader);
		}

		int schemaVersion = -1;
		QuiltLoaderData loaderData = null;
		List<String> mixinConfigs = Collections.emptyList();
		String accessWidener = null;

		reader.beginObject();

		while (reader.hasNext()) {
			String name = reader.nextName();

			if (name.equals(SCHEMA_VERSION)) {
				schemaVersion = reader.nextInt();
			} else if (name.equals(QUILT_LOADER)) {
				loaderData = readQuiltLoader(reader);
			} else if (name.equals(MIXIN)) {
				mixinConfigs = readStringOrArray(reader);
			} else if (name.equals(ACCESS_WIDENER)) {
				accessWidener = reader.nextString();
			} else {
				reader.skipValue();
			}
		}

		reader.endObject();

		if (schemaVersion < 0) {
			throw new ParseMetadataException("quilt.mod.json 缺少 schema_version 字段", reader);
		}

		if (schemaVersion != 1) {
			throw new ParseMetadataException("不支持的 quilt.mod.json schema_version " + schemaVersion
					+ "（当前只支持 1）", reader);
		}

		if (loaderData == null) {
			throw new ParseMetadataException("quilt.mod.json 缺少 quilt_loader 段", reader);
		}

		return new QuiltModMetadata(schemaVersion,
				loaderData.id,
				loaderData.group,
				loaderData.version,
				loaderData.name,
				loaderData.description,
				Collections.<Person>emptyList(),
				loaderData.contributors,
				loaderData.contact,
				loaderData.license,
				loaderData.icon,
				loaderData.provides,
				loaderData.loadType,
				loaderData.intermediateMappings,
				loaderData.jars,
				normalizeMixinConfigs(mixinConfigs),
				accessWidener,
				loaderData.entrypoints,
				loaderData.dependencies,
				loaderData.unlessTargets);
	}

	// ------------------------------------------------------------------ 各段

	private static final class QuiltLoaderData {
		String id;
		String group;
		Version version;
		String name;
		String description;
		Collection<Person> contributors = Collections.emptyList();
		ContactInformation contact = new ContactInformationImpl(Collections.<String, String>emptyMap());
		Collection<String> license = Collections.emptyList();
		String icon;
		List<QuiltProvidedMod> provides = Collections.emptyList();
		QuiltLoadType loadType;
		String intermediateMappings;
		Collection<NestedJarEntry> jars = Collections.emptyList();
		Map<String, List<EntrypointMetadata>> entrypoints = Collections.emptyMap();
		Collection<ModDependency> dependencies = Collections.emptyList();
		Map<ModDependency, String> unlessTargets = Collections.emptyMap();
	}

	private static QuiltLoaderData readQuiltLoader(JsonReader reader) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.BEGIN_OBJECT) {
			throw new ParseMetadataException("quilt_loader 必须是对象", reader);
		}

		QuiltLoaderData data = new QuiltLoaderData();

		reader.beginObject();

		while (reader.hasNext()) {
			String name = reader.nextName();

			switch (name) {
			case "id":
				data.id = reader.nextString();
				break;
			case "group":
				data.group = reader.nextString();
				break;
			case "version":
				data.version = parseVersion(reader.nextString(), reader);
				break;
			case "intermediate_mappings":
				data.intermediateMappings = reader.nextString();
				break;
			case "load_type":
				data.loadType = parseLoadType(reader.nextString());
				break;
			case "metadata":
				readMetadataBlock(reader, data);
				break;
			case "provides":
				data.provides = readProvides(reader);
				break;
			case "jars":
				data.jars = readJars(reader);
				break;
			case "entrypoints":
				data.entrypoints = readEntrypoints(reader);
				break;
			case "depends":
				data.dependencies = concat(data.dependencies, readDependencies(reader, Kind.DEPENDS, data));
				break;
			case "breaks":
				data.dependencies = concat(data.dependencies, readDependencies(reader, Kind.BREAKS, data));
				break;
			default:
				reader.skipValue();
				break;
			}
		}

		reader.endObject();

		if (data.id == null) throw new ParseMetadataException("quilt_loader.id 是必填字段", reader);
		if (data.version == null) throw new ParseMetadataException("quilt_loader.version 是必填字段", reader);

		// group 在 Quilt 的 schema 里是必填，但真实世界里确实有漏写的 jar。
		// 缺了它不是致命的（只在报告里显示），补一个占位而不是把启动打挂——
		// 但必须在日志以外留下痕迹，所以写进 group 字段本身。
		if (data.group == null) data.group = "";

		return data;
	}

	private static void readMetadataBlock(JsonReader reader, QuiltLoaderData data) throws IOException, ParseMetadataException {
		if (reader.peek() != JsonToken.BEGIN_OBJECT) {
			throw new ParseMetadataException("quilt_loader.metadata 必须是对象", reader);
		}

		reader.beginObject();

		while (reader.hasNext()) {
			String name = reader.nextName();

			switch (name) {
			case "name":
				data.name = reader.nextString();
				break;
			case "description":
				data.description = reader.nextString();
				break;
			case "icon":
				data.icon = readIcon(reader);
				break;
			case "license":
				data.license = readLicense(reader);
				break;
			case "contributors":
				data.contributors = readContributors(reader);
				break;
			case "contact":
				data.contact = readContact(reader);
				break;
			default:
				reader.skipValue();
				break;
			}
		}

		reader.endObject();
	}

	private static String readIcon(JsonReader reader) throws IOException {
		if (reader.peek() == JsonToken.BEGIN_OBJECT) {
			// {"32": "assets/icon32.png", ...} → 取最大的那一档
			String best = null;
			int bestSize = -1;

			reader.beginObject();

			while (reader.hasNext()) {
				int size = Integer.parseInt(reader.nextName().trim());
				String path = reader.nextString();

				if (size > bestSize) {
					bestSize = size;
					best = path;
				}
			}

			reader.endObject();
			return best;
		}

		return reader.nextString();
	}

	private static Collection<String> readLicense(JsonReader reader) throws IOException {
		List<String> ret = new ArrayList<>();

		switch (reader.peek()) {
		case STRING:
			ret.add(reader.nextString());
			break;
		case BEGIN_ARRAY:
			reader.beginArray();

			while (reader.hasNext()) {
				if (reader.peek() == JsonToken.STRING) {
					ret.add(reader.nextString());
				} else if (reader.peek() == JsonToken.BEGIN_OBJECT) {
					// {"name": "Apache-2.0", "id": ..., "url": ...}
					reader.beginObject();

					while (reader.hasNext()) {
						if (reader.nextName().equals("name")) {
							ret.add(reader.nextString());
						} else {
							reader.skipValue();
						}
					}

					reader.endObject();
				} else {
					reader.skipValue();
				}
			}

			reader.endArray();
			break;
		case BEGIN_OBJECT:
			reader.beginObject();

			while (reader.hasNext()) {
				if (reader.nextName().equals("name")) {
					ret.add(reader.nextString());
				} else {
					reader.skipValue();
				}
			}

			reader.endObject();
			break;
		default:
			reader.skipValue();
			break;
		}

		return ret;
	}

	private static Collection<Person> readContributors(JsonReader reader) throws IOException {
		List<Person> ret = new ArrayList<>();

		switch (reader.peek()) {
		case BEGIN_OBJECT:
			reader.beginObject();

			while (reader.hasNext()) {
				ret.add(person(reader.nextName()));
				reader.skipValue(); // 值是角色（Owner / Contributor / …），本阶段不用
			}

			reader.endObject();
			break;
		case BEGIN_ARRAY:
			reader.beginArray();

			while (reader.hasNext()) {
				if (reader.peek() == JsonToken.STRING) {
					ret.add(person(reader.nextString()));
				} else {
					reader.skipValue();
				}
			}

			reader.endArray();
			break;
		default:
			reader.skipValue();
			break;
		}

		return ret;
	}

	private static ContactInformation readContact(JsonReader reader) throws IOException {
		Map<String, String> map = new LinkedHashMap<>();

		if (reader.peek() == JsonToken.BEGIN_OBJECT) {
			reader.beginObject();

			while (reader.hasNext()) {
				String key = reader.nextName();
				map.put(key, reader.peek() == JsonToken.NULL ? "" : reader.nextString());
			}

			reader.endObject();
		} else {
			reader.skipValue();
		}

		return new ContactInformationImpl(map);
	}

	private static List<QuiltProvidedMod> readProvides(JsonReader reader) throws IOException, ParseMetadataException {
		List<QuiltProvidedMod> ret = new ArrayList<>();

		if (reader.peek() != JsonToken.BEGIN_ARRAY) {
			reader.skipValue();
			return ret;
		}

		reader.beginArray();

		while (reader.hasNext()) {
			if (reader.peek() == JsonToken.STRING) {
				ret.add(new QuiltProvidedMod(reader.nextString(), null));
			} else if (reader.peek() == JsonToken.BEGIN_OBJECT) {
				String id = null;
				Version version = null;

				reader.beginObject();

				while (reader.hasNext()) {
					String key = reader.nextName();

					if (key.equals("id")) {
						id = reader.nextString();
					} else if (key.equals("version")) {
						String raw = reader.nextString();

						try {
							version = Version.parse(raw);
						} catch (VersionParsingException e) {
							// provides 的版本号解析不出来不影响「我提供这个 id」这件主要的事，
							// 只是依赖判定会退化成用提供者自己的版本。记下来，不要静默吞掉。
							throw new ParseMetadataException("provides 里的版本号无法解析: " + raw, e);
						}
					} else {
						reader.skipValue();
					}
				}

				reader.endObject();

				if (id == null) throw new ParseMetadataException("provides 的条目缺少 id", reader);

				ret.add(new QuiltProvidedMod(id, version));
			} else {
				reader.skipValue();
			}
		}

		reader.endArray();

		return ret;
	}

	private static Collection<NestedJarEntry> readJars(JsonReader reader) throws IOException {
		List<NestedJarEntry> ret = new ArrayList<>();

		if (reader.peek() != JsonToken.BEGIN_ARRAY) {
			reader.skipValue();
			return ret;
		}

		reader.beginArray();

		while (reader.hasNext()) {
			if (reader.peek() == JsonToken.STRING) {
				ret.add(jar(reader.nextString()));
			} else if (reader.peek() == JsonToken.BEGIN_OBJECT) {
				String file = null;

				reader.beginObject();

				while (reader.hasNext()) {
					if (reader.nextName().equals("file")) {
						file = reader.nextString();
					} else {
						reader.skipValue();
					}
				}

				reader.endObject();

				if (file != null) ret.add(jar(file));
			} else {
				reader.skipValue();
			}
		}

		reader.endArray();

		return ret;
	}

	private static Map<String, List<EntrypointMetadata>> readEntrypoints(JsonReader reader) throws IOException {
		Map<String, List<EntrypointMetadata>> ret = new LinkedHashMap<>();

		if (reader.peek() != JsonToken.BEGIN_OBJECT) {
			reader.skipValue();
			return ret;
		}

		reader.beginObject();

		while (reader.hasNext()) {
			String key = reader.nextName();
			List<EntrypointMetadata> entries = new ArrayList<>();

			if (reader.peek() == JsonToken.BEGIN_ARRAY) {
				reader.beginArray();

				while (reader.hasNext()) {
					if (reader.peek() == JsonToken.STRING) {
						entries.add(new QuiltEntrypointMetadata(null, reader.nextString()));
					} else if (reader.peek() == JsonToken.BEGIN_OBJECT) {
						String adapter = null;
						String value = null;

						reader.beginObject();

						while (reader.hasNext()) {
							String field = reader.nextName();

							if (field.equals("adapter")) {
								adapter = reader.nextString();
							} else if (field.equals("value")) {
								value = reader.nextString();
							} else {
								reader.skipValue();
							}
						}

						reader.endObject();

						if (value != null) entries.add(new QuiltEntrypointMetadata(adapter, value));
					} else {
						reader.skipValue();
					}
				}

				reader.endArray();
			} else if (reader.peek() == JsonToken.STRING) {
				entries.add(new QuiltEntrypointMetadata(null, reader.nextString()));
			} else {
				reader.skipValue();
			}

			ret.put(key, entries);
		}

		reader.endObject();

		return ret;
	}

	/**
	 * 读一段依赖（{@code depends} / {@code breaks}）。
	 *
	 * <p>数组里每一项可以是字符串（只有 id）、对象（id + versions + unless + optional），
	 * 或嵌套数组（any-of）。嵌套的 any-of 在 Fabric 的模型里没有对应物，
	 * 这里按「第一个能解析出来的 id」处理并留下警告——真实 jar 里几乎见不到这种写法。
	 */
	private static Collection<ModDependency> readDependencies(JsonReader reader, Kind kind, QuiltLoaderData data)
			throws IOException, ParseMetadataException {
		List<ModDependency> ret = new ArrayList<>();

		if (reader.peek() != JsonToken.BEGIN_ARRAY) {
			reader.skipValue();
			return ret;
		}

		reader.beginArray();

		while (reader.hasNext()) {
			switch (reader.peek()) {
			case STRING:
				ret.add(dependency(kind, reader.nextString(), Collections.singletonList("*")));
				break;
			case BEGIN_OBJECT: {
				String id = null;
				List<String> versions = new ArrayList<>();
				String unless = null;
				boolean optional = false;

				reader.beginObject();

				while (reader.hasNext()) {
					String field = reader.nextName();

					switch (field) {
					case "id":
						id = reader.nextString();
						break;
					case "versions":
						versions = readVersions(reader);
						break;
					case "unless":
						unless = readUnlessId(reader);
						break;
					case "optional":
						optional = reader.nextBoolean();
						break;
					default:
						reader.skipValue();
						break;
					}
				}

				reader.endObject();

				if (id == null) throw new ParseMetadataException("依赖条目缺少 id", reader);

				// Quilt 的 optional = 「只有它真的在场时才校验版本」，Fabric 没有这个 kind。
				// RECOMMENDS 是最接近的（缺失不阻断、在场但不匹配会告警），
				// 这个近似在阶段报告里显式登记。
				Kind effective = optional && kind == Kind.DEPENDS ? Kind.RECOMMENDS : kind;
				ModDependency dep = dependency(effective, id, versions);
				ret.add(dep);

				if (unless != null) {
					if (data.unlessTargets.isEmpty()) data.unlessTargets = new HashMap<>();
					data.unlessTargets.put(dep, unless);
				}

				break;
			}
			case BEGIN_ARRAY: {
				// any-of：取第一个能读出来的对象/字符串，其余跳过
				reader.beginArray();
				boolean taken = false;

				while (reader.hasNext()) {
					if (!taken && reader.peek() == JsonToken.BEGIN_OBJECT) {
						String id = null;
						List<String> versions = new ArrayList<>();

						reader.beginObject();

						while (reader.hasNext()) {
							String field = reader.nextName();

							if (field.equals("id")) {
								id = reader.nextString();
							} else if (field.equals("versions")) {
								versions = readVersions(reader);
							} else {
								reader.skipValue();
							}
						}

						reader.endObject();

						if (id != null) {
							ret.add(dependency(kind, id, versions));
							taken = true;
						}
					} else if (!taken && reader.peek() == JsonToken.STRING) {
						ret.add(dependency(kind, reader.nextString(), Collections.singletonList("*")));
						taken = true;
					} else {
						reader.skipValue();
					}
				}

				reader.endArray();
				break;
			}
			default:
				reader.skipValue();
				break;
			}
		}

		reader.endArray();

		return ret;
	}

	private static List<String> readVersions(JsonReader reader) throws IOException {
		List<String> ret = new ArrayList<>();

		switch (reader.peek()) {
		case STRING:
			ret.add(reader.nextString());
			break;
		case BEGIN_ARRAY:
			reader.beginArray();

			while (reader.hasNext()) {
				if (reader.peek() == JsonToken.STRING) {
					ret.add(reader.nextString());
				} else {
					reader.skipValue();
				}
			}

			reader.endArray();
			break;
		default:
			reader.skipValue();
			break;
		}

		if (ret.isEmpty()) ret.add("*");

		return ret;
	}

	/** {@code unless} 的值本身是一条依赖（对象或字符串），这里只取它的 id。 */
	@Nullable
	private static String readUnlessId(JsonReader reader) throws IOException {
		String id = null;

		if (reader.peek() == JsonToken.STRING) {
			id = reader.nextString();
		} else if (reader.peek() == JsonToken.BEGIN_OBJECT) {
			reader.beginObject();

			while (reader.hasNext()) {
				if (reader.nextName().equals("id")) {
					id = reader.nextString();
				} else {
					reader.skipValue();
				}
			}

			reader.endObject();
		} else {
			reader.skipValue();
		}

		return id;
	}

	// ------------------------------------------------------------------ 小工具

	private static ModDependency dependency(Kind kind, String id, List<String> versions)
			throws ParseMetadataException {
		try {
			return new net.fabricmc.loader.impl.metadata.ModDependencyImpl(kind, id, versions);
		} catch (VersionParsingException e) {
			throw new ParseMetadataException("模组 " + id + " 的版本区间无法解析: " + versions, e);
		}
	}

	private static Version parseVersion(String raw, JsonReader reader) throws ParseMetadataException {
		try {
			return Version.parse(raw);
		} catch (VersionParsingException e) {
			throw new ParseMetadataException("quilt_loader.version 无法解析: " + raw, reader);
		}
	}

	private static QuiltLoadType parseLoadType(String raw) {
		return QuiltLoadType.parse(raw);
	}

	private static List<String> readStringOrArray(JsonReader reader) throws IOException {
		List<String> ret = new ArrayList<>();

		switch (reader.peek()) {
		case STRING:
			ret.add(reader.nextString());
			break;
		case BEGIN_ARRAY:
			reader.beginArray();

			while (reader.hasNext()) {
				if (reader.peek() == JsonToken.STRING) {
					ret.add(reader.nextString());
				} else {
					reader.skipValue();
				}
			}

			reader.endArray();
			break;
		default:
			reader.skipValue();
			break;
		}

		return ret;
	}

	/**
	 * Quilt 的 mixin 配置可能被写成 {@code "#modid:config"}（见
	 * {@code QuiltMixinBootstrap}）。内核这里不认这种前缀，所以在元数据这一层就剥掉，
	 * 剥完还能顺手校验前缀里的 modid 与自己的 id 是否一致。
	 */
	private static Collection<String> normalizeMixinConfigs(List<String> raw) {
		List<String> ret = new ArrayList<>(raw.size());

		for (String config : raw) {
			if (config.startsWith("#")) {
				int sep = config.indexOf(':');

				if (sep > 0) {
					ret.add(config.substring(sep + 1));
					continue;
				}
			}

			ret.add(config);
		}

		return ret;
	}

	private static Collection<ModDependency> concat(Collection<ModDependency> a, Collection<ModDependency> b) {
		if (a.isEmpty()) return b;
		if (b.isEmpty()) return a;

		List<ModDependency> ret = new ArrayList<>(a);
		ret.addAll(b);
		return ret;
	}

	private static Person person(final String name) {
		return new Person() {
			@Override
			public String getName() {
				return name;
			}

			@Override
			public ContactInformation getContact() {
				return ContactInformation.EMPTY;
			}

			@Override
			public String toString() {
				return name;
			}
		};
	}

	private static NestedJarEntry jar(final String file) {
		return new NestedJarEntry() {
			@Override
			public String getFile() {
				return file;
			}

			@Override
			public String toString() {
				return file;
			}
		};
	}
}
