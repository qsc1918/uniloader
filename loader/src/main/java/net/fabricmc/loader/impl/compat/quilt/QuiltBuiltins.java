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

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.impl.metadata.BuiltinModMetadata;

/**
 * UniLoader fork patch (T2.2): {@code quilt_loader} 这个内建模组。
 *
 * <p>为什么必须有它：真实的 Quilt 模组清一色声明
 * {@code depends: [{id: "quilt_loader", versions: ">=0.31.0-beta.4"}]}
 * （QSL 是 {@code >=0.25.0}）。没有一个 id 叫 {@code quilt_loader} 的模组在场，
 * 求解器会直接判定「依赖无法满足」——哪怕别的什么都不做，这一步就能挡掉全部 Quilt 模组。
 *
 * <p>版本号从构建期生成的 {@code quilt-loader.properties} 读，值来自
 * {@code gradle.properties} 的 {@code quilt_loader_version}。
 * 工程纪律第 4 条：Java 里不写版本字面量。读不到就抛异常——
 * 那是构建坏了，静默补一个默认版本号会让「依赖明明写着却过不了」变成悬案。
 */
public final class QuiltBuiltins {
	public static final String MOD_ID = "quilt_loader";

	private static final String RESOURCE = "/net/fabricmc/loader/impl/compat/quilt/quilt-loader.properties";
	private static final String VERSION_KEY = "version";

	private static String cachedVersion;

	private QuiltBuiltins() {
	}

	public static String version() {
		if (cachedVersion == null) cachedVersion = loadVersion();

		return cachedVersion;
	}

	private static String loadVersion() {
		Properties props = new Properties();

		try (InputStream is = QuiltBuiltins.class.getResourceAsStream(RESOURCE)) {
			if (is == null) {
				throw new IllegalStateException("缺少 " + RESOURCE
						+ "（构建期由 gradle.properties 的 quilt_loader_version 生成）。"
						+ "没有它就无法向 Quilt 模组声明 quilt_loader 的版本，依赖求解必然失败。");
			}

			props.load(is);
		} catch (IOException e) {
			throw new IllegalStateException("读取 " + RESOURCE + " 失败", e);
		}

		String version = props.getProperty(VERSION_KEY);

		if (version == null || version.trim().isEmpty()) {
			throw new IllegalStateException(RESOURCE + " 里没有 " + VERSION_KEY + " 这一项");
		}

		return version.trim();
	}

	/** 供 {@code ModDiscoverer} 组装内建候选用。 */
	public static ModMetadata metadata() {
		return new BuiltinModMetadata.Builder(MOD_ID, version())
				.setName("Quilt Loader API")
				.setDescription("由 UniLoader 提供的 Quilt Loader API 兼容层，版本号对应它所兼容的 Quilt Loader。")
				.build();
	}
}
