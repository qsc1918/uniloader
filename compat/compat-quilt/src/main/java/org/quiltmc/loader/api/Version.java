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

import org.uniloader.compat.quilt.QuiltApi;

/**
 * Quilt Loader API: version. Reimplemented by UniLoader (T2.3).
 *
 * <p>This is not the original Quilt source; it is a compatible reimplementation
 * backed by the Fabric Loader version model. Quilt Loader is Apache-2.0, and so
 * is this file.
 */
public interface Version extends Comparable<Version> {
	/** Parses a version string. */
	static Version of(String raw) throws VersionFormatException {
		return QuiltApi.versionOf(raw);
	}

	String raw();

	default boolean isSemantic() {
		return this instanceof Semantic;
	}

	default Semantic semantic() {
		if (this instanceof Semantic) return (Semantic) this;

		throw new IllegalStateException("This version is not a semantic version! [" + raw() + "]");
	}

	interface Semantic extends Version {
		static Semantic of(String raw) throws VersionFormatException {
			return QuiltApi.semanticOf(raw);
		}

		int versionComponentCount();

		int versionComponent(int index);

		default int major() {
			return versionComponent(0);
		}

		default int minor() {
			return versionComponent(1);
		}

		default int patch() {
			return versionComponent(2);
		}

		String preRelease();

		String buildMetadata();
	}
}
