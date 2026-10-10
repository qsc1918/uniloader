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

/**
 * UniLoader fork patch (T2.1): 一个 jar 里同时有 {@code fabric.mod.json} 与
 * {@code quilt.mod.json} 时，<b>明确</b>决定走哪一份。
 *
 * <p>为什么必须有这么一个仲裁点：阶段 1 的 {@code ForeignModRecognizers} 是
 * 「先注册先赢」，而发现层又只看 {@code fabric.mod.json}。两处顺序各自成立，
 * 合起来的实际行为就成了「谁碰巧先被扫到」，这是巧合而不是规则——
 * 一个同时带两份清单的 jar 到底按谁加载，必须有唯一一处说了算的地方。
 *
 * <p>规则：<b>有 quilt.mod.json 就按 Quilt 加载</b>。理由是它是信息量更大的那一份
 * （有 group / provides 带版本 / load_type），而且真实的 Quilt Loader 也是这么做的。
 * 规则写在这里，改规则只改这一处。
 */
public final class QuiltManifestArbiter {
	public static final String FABRIC_MANIFEST = "fabric.mod.json";
	public static final String QUILT_MANIFEST = "quilt.mod.json";

	public enum Manifest {
		FABRIC(FABRIC_MANIFEST),
		QUILT(QUILT_MANIFEST);

		private final String fileName;

		Manifest(String fileName) {
			this.fileName = fileName;
		}

		public String fileName() {
			return fileName;
		}
	}

	private QuiltManifestArbiter() {
	}

	/**
	 * @param hasFabric jar 里有 fabric.mod.json
	 * @param hasQuilt  jar 里有 quilt.mod.json
	 * @return 该用哪份清单；两份都没有时返回 null
	 */
	public static Manifest choose(boolean hasFabric, boolean hasQuilt) {
		if (hasQuilt) return Manifest.QUILT;
		if (hasFabric) return Manifest.FABRIC;
		return null;
	}

	/**
	 * 两份都在时给日志用的一句话。只在真的发生冲突时才调，
	 * 否则每次启动都会刷一行没用的信息。
	 */
	public static String describeConflict(String id) {
		return (id != null ? id : "这个模组") + " 同时带 " + FABRIC_MANIFEST + " 与 " + QUILT_MANIFEST
				+ "，按 Quilt 加载（规则见 QuiltManifestArbiter）：quilt.mod.json 的信息量更大，"
				+ "且真实的 Quilt Loader 也是这么选的。";
	}
}
