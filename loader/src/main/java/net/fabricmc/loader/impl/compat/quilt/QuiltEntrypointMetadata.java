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

import net.fabricmc.loader.impl.metadata.EntrypointMetadata;

/**
 * UniLoader fork patch (T2.1): 一条 {@code quilt.mod.json} 的入口点声明。
 *
 * <p>与 Fabric 唯一的差别是 Quilt 允许给单个入口点指定 {@code adapter}
 * （{@code entrypoints: {"main": [{"adapter": "quilt", "value": "..."}]}}），
 * Fabric 的 {@link EntrypointMetadata} 恰好也有这两个字段，所以直接复用接口即可。
 *
 * <p>{@code getAdapter()} 返回 null 表示「用默认适配器」，与上游语义一致
 * （见 {@code EntrypointMetadata} 的调用方）。
 */
public final class QuiltEntrypointMetadata implements EntrypointMetadata {
	/**
	 * 没写 {@code adapter} 时用的名字。
	 *
	 * <p>必须是 {@code "default"}，不能是 null：内核的 {@code EntrypointStorage.add()}
	 * 会拿它去 {@code adapterMap} 里查，查不到就直接抛
	 * {@code Could not find adapter 'null' (mod xxx!)}，整个模组 setup 失败。
	 * Fabric 侧解析 fabric.mod.json 时用的也是这个字面量（V1ModMetadataParser:283）。
	 *
	 * <p>这个坑是冒烟测出来的，不是想出来的——第一版返回 null，
	 * 结果真实 Quilt 模组在 instantiate 阶段就挂了。
	 */
	private static final String DEFAULT_ADAPTER = "default";

	private final String adapter;
	private final String value;

	public QuiltEntrypointMetadata(String adapter, String value) {
		this.adapter = adapter != null ? adapter : DEFAULT_ADAPTER;
		this.value = value;
	}

	@Override
	public String getAdapter() {
		return adapter;
	}

	@Override
	public String getValue() {
		return value;
	}

	@Override
	public String toString() {
		return adapter + "->" + value;
	}
}
