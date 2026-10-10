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

import java.util.Collection;
import java.util.Map;

/**
 * Quilt Loader API: mod metadata. Reimplemented by UniLoader (T2.3).
 *
 * <p>Methods that UniLoader cannot answer are intentionally absent rather than
 * stubbed to return wrong data; see the stage 2 report for the list.
 */
public interface ModMetadata {
	String id();

	String group();

	Version version();

	String name();

	default String description() {
		return "";
	}

	Collection<ModLicense> licenses();

	Collection<ModContributor> contributors();

	default String getContactInfo(String key) {
		return contactInfo().get(key);
	}

	Map<String, String> contactInfo();

	default Collection<? extends ProvidedMod> provides() {
		return java.util.Collections.emptyList();
	}

	default String icon(int size) {
		return null;
	}

	default boolean containsValue(String key) {
		return false;
	}

	default LoaderValue value(String key) {
		return LoaderValue.NULL;
	}

	default Map<String, LoaderValue> values() {
		return java.util.Collections.emptyMap();
	}

	/** A mod id provided by this mod, optionally at a specific version. */
	interface ProvidedMod {
		String group();

		String id();

		Version version();
	}
}
