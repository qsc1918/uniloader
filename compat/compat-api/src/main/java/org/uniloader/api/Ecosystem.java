/*
 * Copyright 2026 The UniLoader Project
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

package org.uniloader.api;

/**
 * 一个模组属于哪一家生态。
 *
 * <p>UL 的核心承诺是四家共存，所以「这个 mod 是哪家的」必须是数据而不是推断出来的字符串。
 * 发现层（阶段 3）为每个 {@code UniModCandidate} 打上这个标签，之后所有分支都读它。
 */
public enum Ecosystem {
	FABRIC("fabric"),
	QUILT("quilt"),
	NEOFORGE("neoforge"),
	FORGE("forge"),
	/** 识别不出来：既不是四家的清单文件，也没有任何一家的注解。发现层会给出可读原因。 */
	UNKNOWN("unknown");

	private final String id;

	Ecosystem(String id) {
		this.id = id;
	}

	public String id() {
		return id;
	}

	/** 从清单文件名或元数据键反推；认不出就返回 {@link #UNKNOWN}，绝不抛异常。 */
	public static Ecosystem fromId(String value) {
		if (value == null) return UNKNOWN;

		for (Ecosystem e : values()) {
			if (e.id.equalsIgnoreCase(value.trim())) return e;
		}

		return UNKNOWN;
	}
}
