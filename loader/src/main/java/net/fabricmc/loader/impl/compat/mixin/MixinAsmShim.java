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

package net.fabricmc.loader.impl.compat.mixin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

/**
 * T2.7: locates the ASM shim that serves Mixin's legacy package.
 *
 * <p><b>Why this exists.</b> Mods from 2019–2020 compiled against the Mixin of that era,
 * which shaded ASM into {@code org.spongepowered.asm.lib.**}. Fabric's current
 * {@code net.fabricmc:sponge-mixin} keeps ASM at {@code org.objectweb.asm} (that is what
 * Mixin itself uses) and leaves only 7 stubs under the legacy package — so an old mod that
 * registers a custom injection point dies with
 * {@code NoClassDefFoundError: org/spongepowered/asm/lib/tree/InsnList}. Mixin catches it,
 * so startup survives, but the injection point is silently not registered: the mod's
 * functionality is missing. Official fabric-loader 0.19.5 ships none of those classes at
 * all, so this is upstream's status quo, not something this repo introduced.
 *
 * <p><b>What we do.</b> {@code tooling/mixin-asm-shim} republishes the very same ASM that is
 * already on the class path under the legacy package name. Putting it on the class path is
 * <b>not enough</b>: Fabric isolates the game from class path entries that are not
 * "system libraries", so the class is found but rejected with
 * {@code "hasn't been exposed to the game"}. This class finds the shim jar by content and
 * lets the game provider add it to the allowed parent class path.
 *
 * <p>Detection is by content, not by file name: the jar is a build artifact, and a name-based
 * rule would silently stop matching the moment the artifact is renamed or repackaged.
 */
public final class MixinAsmShim {
	/** Marker: any jar providing the legacy package has to carry ASM's entry point type. */
	private static final String MARKER_ENTRY = "org/spongepowered/asm/lib/Type.class";

	private MixinAsmShim() {
	}

	/**
	 * Returns the class path entries that provide Mixin's legacy ASM package.
	 *
	 * <p>Never throws: this runs during game provider setup, and failing to find the shim must
	 * degrade to "old mods lose their injection points" — never to "the game won't start".
	 */
	public static List<Path> findShimJars(Collection<Path> classPath) {
		if (classPath == null || classPath.isEmpty()) return Collections.emptyList();

		List<Path> ret = new ArrayList<>(1);

		for (Path path : classPath) {
			if (path == null || !Files.isRegularFile(path)) continue;

			try (ZipFile zf = new ZipFile(path.toFile())) {
				ZipEntry entry = zf.getEntry(MARKER_ENTRY);

				if (entry != null) {
					ret.add(path);
					Log.debug(LogCategory.GAME_PROVIDER,
							"Mixin 老包名 ASM shim：%s（它的类会被暴露给游戏，"
									+ "否则老模组的自定义注入点注册不了）",
							path.getFileName());
				}
			} catch (IOException e) {
				// 不是 zip（例如 classes 目录）或读不了：跳过，不打扰
				Log.debug(LogCategory.GAME_PROVIDER, "跳过（读不了）：%s", path);
			}
		}

		return ret;
	}
}
