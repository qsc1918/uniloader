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

package org.quiltmc.loader.api;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.quiltmc.loader.api.entrypoint.EntrypointContainer;
import org.quiltmc.loader.api.entrypoint.EntrypointException;

import org.uniloader.compat.quilt.QuiltApi;

/**
 * Quilt Loader API: the main static entry point used by Quilt mods.
 * Reimplemented by UniLoader (T2.3).
 *
 * <p>Scope note: this covers what real Quilt mods were measured to call
 * (see {@code docs/stage2/STAGE2-REPORT.md}); Quilt's plugin system and virtual
 * file system are explicitly out of scope for stage 2 and are not provided.
 */
public final class QuiltLoader {
	private QuiltLoader() {
	}

	public static <T> List<T> getEntrypoints(String key, Class<T> type) throws EntrypointException {
		return QuiltApi.getEntrypoints(key, type);
	}

	public static <T> List<EntrypointContainer<T>> getEntrypointContainers(String key, Class<T> type) throws EntrypointException {
		return QuiltApi.getEntrypointContainers(key, type);
	}

	public static Optional<ModContainer> getModContainer(String id) {
		return QuiltApi.getModContainer(id);
	}

	public static Collection<ModContainer> getAllMods() {
		return QuiltApi.getAllMods();
	}

	public static boolean isModLoaded(String id) {
		return QuiltApi.isModLoaded(id);
	}

	public static boolean isDevelopmentEnvironment() {
		return QuiltApi.isDevelopmentEnvironment();
	}

	public static Path getGameDir() {
		return QuiltApi.getGameDir();
	}

	public static Path getConfigDir() {
		return QuiltApi.getConfigDir();
	}
}
