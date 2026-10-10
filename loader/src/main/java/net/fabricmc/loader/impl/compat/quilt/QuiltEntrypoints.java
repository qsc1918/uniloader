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

import java.lang.reflect.Method;

import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

/**
 * UniLoader fork patch (T2.4): triggers Quilt's {@code pre_launch} entrypoints.
 *
 * <p>为什么这里要写反射，而不是直接 {@code import org.quiltmc...}：
 * Quilt 兼容层按 AGENTS.md 5.1 是独立模块 {@code compat/compat-quilt}，
 * 而内核是组合构建（{@code includeBuild('loader')}）——Gradle 不允许被包含的构建
 * 反向依赖包含它的构建。也就是说内核**编译期拿不到**那个模块的类。
 *
 * <p>所以内核里凡是需要 Quilt 类型的地方都收敛到这一处，走反射。
 * 反射失败不是致命错误：没把 compat-quilt 放上 classpath 的部署里，
 * Quilt 模组的 pre_launch 不触发（记 debug），其余功能照常。
 * 但「静默降级」是不允许的，所以这里明确区分「类不在」与「调用炸了」两种情况，
 * 后者按错误记下来。
 */
public final class QuiltEntrypoints {
	private static final String PRE_LAUNCH_TYPE = "org.quiltmc.loader.api.entrypoint.PreLaunchEntrypoint";
	private static final String PRE_LAUNCH_KEY = "pre_launch";

	private static boolean unavailable;

	private QuiltEntrypoints() {
	}

	/** 触发 Quilt 的 {@code pre_launch} 入口点。没有 Quilt 兼容层时静默返回（记 debug）。 */
	public static void invokePreLaunch(FabricLoaderImpl loader) {
		if (unavailable) return;

		try {
			Class<?> type = Class.forName(PRE_LAUNCH_TYPE);
			Method onPreLaunch = type.getMethod("onPreLaunch");

			// 类型是运行时才知道的，泛型在这里没法表达，所以走原始类型 + 反射调用。
			@SuppressWarnings({"unchecked", "rawtypes"})
			Class rawType = type;

			loader.invokeEntrypoints(PRE_LAUNCH_KEY, rawType, entrypoint -> {
				try {
					onPreLaunch.invoke(entrypoint);
				} catch (ReflectiveOperationException e) {
					throw new RuntimeException("调用 " + PRE_LAUNCH_TYPE + "#onPreLaunch 失败", e);
				}
			});
		} catch (ClassNotFoundException e) {
			unavailable = true;
			Log.debug(LogCategory.ENTRYPOINT,
					"没有找到 %s（Quilt 兼容层不在 classpath 上），跳过 pre_launch 入口点", PRE_LAUNCH_TYPE);
		} catch (NoSuchMethodException e) {
			unavailable = true;
			Log.error(LogCategory.ENTRYPOINT, "%s 上没有 onPreLaunch 方法，Quilt 的 pre_launch 入口点不会触发", PRE_LAUNCH_TYPE, e);
		}
	}
}
