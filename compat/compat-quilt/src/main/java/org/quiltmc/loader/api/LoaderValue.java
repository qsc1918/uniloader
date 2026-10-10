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

import java.util.Collections;
import java.util.Map;

/**
 * Quilt Loader API: a lazily typed JSON value. Reimplemented by UniLoader (T2.3).
 *
 * <p>UniLoader's {@code quilt.mod.json} parser does not retain arbitrary custom
 * values, so the only implementation offered here is {@link #NULL}. Mods that
 * read custom metadata through this API will see "no value" rather than crash;
 * that gap is recorded in the stage 2 report.
 */
public interface LoaderValue {
	LoaderValue NULL = new LoaderValue() {
		@Override
		public LType type() {
			return LType.NULL;
		}

		@Override
		public String location() {
			return "null";
		}

		@Override
		public LObject asObject() {
			throw new UnsupportedOperationException("This value is null");
		}

		@Override
		public LArray asArray() {
			throw new UnsupportedOperationException("This value is null");
		}

		@Override
		public String asString() {
			throw new UnsupportedOperationException("This value is null");
		}

		@Override
		public Number asNumber() {
			throw new UnsupportedOperationException("This value is null");
		}

		@Override
		public boolean asBoolean() {
			return false;
		}
	};

	LType type();

	String location();

	LObject asObject();

	LArray asArray();

	String asString();

	Number asNumber();

	boolean asBoolean();

	interface LObject extends LoaderValue {
		Map<String, LoaderValue> entries();

		default boolean containsKey(String key) {
			return entries().containsKey(key);
		}

		default LoaderValue get(String key) {
			return entries().get(key);
		}
	}

	interface LArray extends LoaderValue {
		int size();

		LoaderValue get(int index);

		default java.util.List<LoaderValue> values() {
			return Collections.emptyList();
		}
	}

	enum LType {
		OBJECT,
		ARRAY,
		STRING,
		NUMBER,
		BOOLEAN,
		NULL
	}
}
