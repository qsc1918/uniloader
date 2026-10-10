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

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

/**
 * UniLoader fork patch (T2.4): 入口点键名的双注册。
 *
 * <p>照抄 {@code QuiltLoaderImpl} 第 1306-1307 行那段——Quilt 自己就是
 * 先 invoke {@code pre_launch}（Quilt 的接口）再 invoke {@code preLaunch}
 * （Fabric 的接口）。所以「双注册」不是我们发明的兼容策略，而是 Quilt 的既有行为。
 *
 * <p>{@code main} / {@code client} / {@code server} 两边同名，不需要映射；
 * Quilt 的 {@code init} 是 QSL 自己按名字去取的（{@code QuiltLoader.getEntrypointContainers}），
 * 加载器不需要在固定时机触发它，所以也不映射。
 */
public final class QuiltEntrypointKeys {
	private QuiltEntrypointKeys() {
	}

	/**
	 * 返回一个键名在本加载器里对应的全部键名（含自己）。
	 *
	 * <p>顺序有意义：先 Quilt 写法还是先 Fabric 写法照抄 Quilt 的顺序（pre_launch 在前）。
	 */
	public static Collection<String> aliases(String key) {
		if (key == null) return Collections.emptyList();

		if (key.equals("pre_launch")) return Arrays.asList("pre_launch", "preLaunch");
		if (key.equals("preLaunch")) return Arrays.asList("preLaunch", "pre_launch");

		return Collections.singletonList(key);
	}
}
