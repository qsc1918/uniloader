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

package net.fabricmc.loader.impl.compat.transform;

import net.fabricmc.api.EnvType;

/**
 * UniLoader fork patch (T1.5)：一次变换调用的上下文。不可变。
 *
 * <p>它是把「上游那 15 行里散落的自由变量」收成一个参数：{@code name}（已经是点号形式）、
 * {@code isDevelopment}、{@code envType}，以及 {@code allowFromParent}
 * ——最后这个原本是 {@code getRawClassByteArray} 的参数，藏在读取那一步里，
 * 收进上下文之后读取步骤就不必是每次调用现造的 lambda 了（热路径上每个类都会走这里）。
 *
 * <p>{@code runtimeNamespace} 现在没有消费者（零重映射时它没有意义），但先放进来：
 * 阶段 7 的 hook 注入要按命名空间决定注入点的写法，到那时再从上下文里补，
 * 会牵动所有已有实现的签名。
 */
public final class TransformContext {
	private final String className;
	private final EnvType envType;
	private final boolean development;
	private final String runtimeNamespace;
	private final boolean allowFromParent;

	public TransformContext(String className, EnvType envType, boolean development,
			String runtimeNamespace, boolean allowFromParent) {
		if (className == null) throw new IllegalArgumentException("className is required");
		if (envType == null) throw new IllegalArgumentException("envType is required");

		this.className = className;
		this.envType = envType;
		this.development = development;
		this.runtimeNamespace = runtimeNamespace;
		this.allowFromParent = allowFromParent;
	}

	/** 点号形式的类名，例如 {@code net.minecraft.world.World}。 */
	public String className() {
		return className;
	}

	public EnvType envType() {
		return envType;
	}

	public boolean development() {
		return development;
	}

	public String runtimeNamespace() {
		return runtimeNamespace;
	}

	/** 找不到类时是否允许向父加载器再找一次。 */
	public boolean allowFromParent() {
		return allowFromParent;
	}

	@Override
	public String toString() {
		return "TransformContext{" + className + ", env=" + envType
				+ ", dev=" + development + ", ns=" + runtimeNamespace
				+ ", allowFromParent=" + allowFromParent + '}';
	}
}
