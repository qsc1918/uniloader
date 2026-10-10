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

import java.util.Locale;

/**
 * UniLoader fork patch (T2.2): {@code quilt.mod.json} 的 {@code load_type} 字段。
 *
 * <p>Quilt 的三种取值：
 * <ul>
 *   <li>{@code always}：必须加载，加载不了就报错（Fabric 模组全部等价于此）。</li>
 *   <li>{@code if_possible}：能加载就加载，加载不了就静默跳过。</li>
 *   <li>{@code if_required}：只有当别的模组真的依赖它时才加载。</li>
 * </ul>
 *
 * <p>Fabric 的求解器没有「模组级可选」这个概念，所以 UniLoader 用「求解失败时重试」来逼近：
 * 见 {@link QuiltCompat}。{@code if_required} 目前按 {@code if_possible} 处理
 * （宁可多加载一个，也不要少加载一个导致运行时 NoClassDefFoundError），这个偏差在
 * 阶段报告里显式登记。
 */
public enum QuiltLoadType {
	ALWAYS("always"),
	IF_POSSIBLE("if_possible"),
	IF_REQUIRED("if_required");

	private final String key;

	QuiltLoadType(String key) {
		this.key = key;
	}

	public String key() {
		return key;
	}

	/** 解析失败返回 null，由调用方决定是报错还是按默认值处理。 */
	public static QuiltLoadType parse(String value) {
		if (value == null) return null;

		String v = value.trim().toLowerCase(Locale.ROOT);

		for (QuiltLoadType type : values()) {
			if (type.key.equals(v)) return type;
		}

		return null;
	}
}
