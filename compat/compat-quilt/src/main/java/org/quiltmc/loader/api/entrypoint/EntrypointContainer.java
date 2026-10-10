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

package org.quiltmc.loader.api.entrypoint;

import org.quiltmc.loader.api.ModContainer;

/**
 * Quilt Loader API: a lazily created entrypoint instance. Reimplemented by UniLoader (T2.3).
 */
public interface EntrypointContainer<T> {
	/** Creates (or returns the cached) entrypoint instance. */
	T getEntrypoint();

	ModContainer getProvider();

	/** The class name declared in {@code quilt.mod.json}. */
	String getDefinition();
}
