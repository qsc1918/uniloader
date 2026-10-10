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

import org.jetbrains.annotations.Nullable;

import net.fabricmc.loader.api.Version;

/**
 * UniLoader fork patch (T2.2): {@code quilt_loader.provides} 里的一条。
 *
 * <p>Quilt 的 {@code provides} 带版本号，Fabric 的 {@code ModMetadata#getProvides()}
 * 只有 id。差别不是小事：QSL 声明「我提供 {@code fabric-api-base} 版本
 * {@code 0.4.42+16948103d1}」，而 QSL 自己的版本是 {@code 11.0.0-alpha.3}——
 * 依赖 {@code fabric-api-base >=0.4.0} 的 Fabric 模组若拿提供者的版本去比对就会误判失败。
 * 所以版本必须单独留着（{@link QuiltCompat} 用它来做依赖判定）。
 */
public final class QuiltProvidedMod {
	private final String id;
	private final Version version;

	public QuiltProvidedMod(String id, @Nullable Version version) {
		this.id = id;
		this.version = version;
	}

	public String id() {
		return id;
	}

	/** 没有显式声明版本时返回 null，此时依赖判定退化为「用提供者自己的版本」。 */
	@Nullable
	public Version version() {
		return version;
	}

	@Override
	public String toString() {
		return version != null ? id + "@" + version.getFriendlyString() : id;
	}
}
