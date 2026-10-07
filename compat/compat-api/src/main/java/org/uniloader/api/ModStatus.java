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
 * 一个模组在本次启动里的状态，只有这三种（工程纪律第 6 条：不存在第四种「看起来成功」）。
 *
 * <p>这条纪律来自 Forbric 的反面教材：它把 mixin 的 {@code required} 全部改成 false、
 * 把 {@code defaultRequire} 降级为 0，于是注入失败被静默跳过，半应用的 mixin 直到游戏
 * 运行时才炸，而此时日志里看不出是谁。UL 的做法相反：任何一个环节出问题都必须落到
 * {@link #DEGRADED} 或 {@link #FAILED} 上，并带上可读原因，写进 {@code mods-report.txt}。
 */
public enum ModStatus {
	/** 完整加载，一切正常。 */
	OK,
	/**
	 * 部分功能不可用，但游戏能跑、别的 mod 不受影响。
	 *
	 * <p>例如：一个 Forge mod 依赖的能力（Quilt 插件系统、ModLauncher ITransformationService）
	 * UL 明确不支持，则它自己的注册照常进行，只是那一项能力缺失。必须给出原因。
	 */
	DEGRADED,
	/** 拒绝加载，并给出可读原因。 */
	FAILED;

	/** 该状态是否需要写进 {@code mods-report.txt}（OK 且无备注时不写，见 LoadReport）。 */
	public boolean isProblem() {
		return this != OK;
	}
}
