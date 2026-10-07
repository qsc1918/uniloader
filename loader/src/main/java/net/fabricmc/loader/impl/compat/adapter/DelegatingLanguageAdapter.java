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

import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.LanguageAdapterException;
import net.fabricmc.loader.api.ModContainer;

/**
 * UniLoader fork patch (T1.3)：一个「现在什么都不做」的语言适配器。
 *
 * <p>它存在的唯一理由是<b>把挂载点先占住</b>。NeoForge/Forge 的模组类不是
 * {@code ModInitializer}，而是带 {@code @Mod} 注解的普通类，还要扫 {@code @SubscribeEvent}；
 * Quilt 走的是它自己的一套。这些都要在阶段 2/4/8 才实现，但「实例化模组类」这个动作
 * 在内核里的入口只有 {@code LanguageAdapter} 一个，所以本阶段先把
 * {@code neoforge} / {@code forge} / {@code quilt} 三个键注册成透明转发，
 * 行为与 {@code default} 逐字相同——后面替换实现时不需要再动内核的装配代码。
 *
 * <p>为什么不是等到阶段 4 再加：扩展点应当在「还不需要它」的时候就建好，
 * 那时它的验收标准最简单——不破坏现有行为。等到真要用的时候再改内核，
 * 就会同时引入「新功能」和「改装配」两类风险。
 */
public final class DelegatingLanguageAdapter implements LanguageAdapter {
	private final String ecosystem;
	private final LanguageAdapter delegate;

	public DelegatingLanguageAdapter(String ecosystem, LanguageAdapter delegate) {
		if (ecosystem == null || ecosystem.isEmpty()) throw new IllegalArgumentException("ecosystem is required");
		if (delegate == null) throw new IllegalArgumentException("delegate is required");

		this.ecosystem = ecosystem;
		this.delegate = delegate;
	}

	/** 这个适配器服务哪个生态（fabric / quilt / neoforge / forge）。 */
	public String ecosystem() {
		return ecosystem;
	}

	public LanguageAdapter delegate() {
		return delegate;
	}

	@Override
	public <T> T create(ModContainer mod, String value, Class<T> type) throws LanguageAdapterException {
		// 目前与 default 完全等价：原样转发。替换成真正的 @Mod 实例化时，
		// 这里必须保持「要么成功，要么抛 LanguageAdapterException」——
		// 绝不能返回 null 让调用方自己撞空指针（纪律第 1 条：不许静默跳过）。
		return delegate.create(mod, value, type);
	}

	@Override
	public String toString() {
		return "DelegatingLanguageAdapter[" + ecosystem + " -> " + delegate + "]";
	}
}
