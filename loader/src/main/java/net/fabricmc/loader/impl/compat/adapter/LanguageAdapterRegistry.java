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

package net.fabricmc.loader.impl.compat.adapter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.impl.util.DefaultLanguageAdapter;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

/**
 * UniLoader fork patch (T1.3)：扩展点 H1 —— 按生态注册语言适配器。
 *
 * <p>上游只认 {@code default} 一个键（{@code FabricLoaderImpl.setupLanguageAdapters()}，
 * 硬写 {@code adapterMap.put("default", DefaultLanguageAdapter.INSTANCE)}），
 * 其余键全靠模组在 {@code fabric.mod.json} 里自己声明。UL 要实例化 Forge/NeoForge 的
 * {@code @Mod} 类、扫描 {@code @SubscribeEvent}，就必须让
 * 「哪个生态用哪个适配器」这件事有个集中的地方，而不是等到阶段 4 再去改内核装配。
 *
 * <p>本阶段三个生态键（{@code quilt} / {@code neoforge} / {@code forge}）都是
 * {@link DelegatingLanguageAdapter}，行为与 {@code default} 逐字相同；
 * 阶段 2/4/8 会把它们换成真正的实现，届时<b>内核装配代码一行都不用改</b>。
 *
 * <p>模组自己声明的适配器仍然优先于内建键之前查重：上游那条
 * 「Duplicate language adapter key」的硬错误必须保留——两个实现抢同一个键，
 * 后果是「看起来注册成功了，其实用的是另一个」，正是纪律第 6 条禁止的那种状态。
 */
public final class LanguageAdapterRegistry {
	public static final String DEFAULT = "default";
	public static final String QUILT = "quilt";
	public static final String NEOFORGE = "neoforge";
	public static final String FORGE = "forge";

	public static final LogCategory LOG_CATEGORY = LogCategory.createCustom("UniLoader", "LanguageAdapter");

	private static final Map<String, LanguageAdapter> BUILT_IN = new LinkedHashMap<>();

	private LanguageAdapterRegistry() {
	}

	static {
		// 内建键在这里定死：它们是「生态 → 实例化方式」的契约，不是配置。
		BUILT_IN.put(DEFAULT, DefaultLanguageAdapter.INSTANCE);
		BUILT_IN.put(QUILT, new DelegatingLanguageAdapter(QUILT, DefaultLanguageAdapter.INSTANCE));
		BUILT_IN.put(NEOFORGE, new DelegatingLanguageAdapter(NEOFORGE, DefaultLanguageAdapter.INSTANCE));
		BUILT_IN.put(FORGE, new DelegatingLanguageAdapter(FORGE, DefaultLanguageAdapter.INSTANCE));
	}

	/**
	 * 把内建适配器装进内核的适配器表。
	 *
	 * <p>只填表里还没有的键：模组如果已经在 {@code fabric.mod.json} 里声明了同名键，
	 * 上游会先抛「Duplicate language adapter key」，那条硬错误不能在这里被绕过。
	 *
	 * @param target 内核的 adapterMap
	 * @return 这次真正装进去的键
	 */
	public static Set<String> installInto(Map<String, LanguageAdapter> target) {
		Map<String, LanguageAdapter> installed = new LinkedHashMap<>();

		for (Map.Entry<String, LanguageAdapter> e : BUILT_IN.entrySet()) {
			if (target.containsKey(e.getKey())) {
				Log.debug(LOG_CATEGORY, "适配器键 %s 已存在，保留原有实现", e.getKey());
				continue;
			}

			target.put(e.getKey(), e.getValue());
			installed.put(e.getKey(), e.getValue());
		}

		Log.debug(LOG_CATEGORY, "已装入 %d 个内建语言适配器：%s", installed.size(), installed.keySet());
		return Collections.unmodifiableSet(installed.keySet());
	}

	/** 查一个内建适配器；没有则返回 null（调用方必须自己决定怎么办，这里不猜）。 */
	public static LanguageAdapter get(String key) {
		return BUILT_IN.get(key);
	}

	public static boolean has(String key) {
		return BUILT_IN.containsKey(key);
	}

	public static Set<String> keys() {
		return Collections.unmodifiableSet(BUILT_IN.keySet());
	}

	/**
	 * 供兼容层在阶段 2/4/8 替换某个生态的实现。
	 *
	 * <p>只允许替换已经存在的键：凭空造一个新键等于发明一套新的生态契约，
	 * 那必须先在 {@code Ecosystem} 里落一个名字，不能在这里悄悄加。
	 *
	 * @return 被换下来的旧实现
	 */
	public static LanguageAdapter replace(String key, LanguageAdapter adapter) {
		if (adapter == null) throw new IllegalArgumentException("adapter is required");

		if (!BUILT_IN.containsKey(key)) {
			throw new IllegalArgumentException("未知的语言适配器键：" + key + "；已知的有 " + BUILT_IN.keySet());
		}

		LanguageAdapter previous = BUILT_IN.put(key, adapter);
		Log.info(LOG_CATEGORY, "语言适配器 %s 已替换为 %s", key, adapter);
		return previous;
	}
}
