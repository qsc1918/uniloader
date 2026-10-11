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

package net.fabricmc.loader.impl.transformer;

import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;
import net.fabricmc.loader.impl.game.GameProvider.BuiltinTransform;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.util.SystemProperties;

public final class FabricTransformer {
	private static final boolean DISABLE_ENVIRONMENT_STRIP = SystemProperties.isSet(SystemProperties.DISABLE_ENVIRONMENT_STRIP);

	public static byte[] transform(boolean isDevelopment, EnvType envType, String name, byte[] bytes) {
		Set<BuiltinTransform> transforms = FabricLoaderImpl.INSTANCE.getGameProvider().getBuiltinTransforms(name);
		boolean transformAccess = transforms.contains(BuiltinTransform.WIDEN_ALL_PACKAGE_ACCESS) && FabricLauncherBase.getLauncher().getMappingConfiguration().requiresPackageAccessHack();
		boolean environmentStrip = transforms.contains(BuiltinTransform.STRIP_ENVIRONMENT) && !DISABLE_ENVIRONMENT_STRIP;
		boolean applyClassTweaker = transforms.contains(BuiltinTransform.CLASS_TWEAKS) && FabricLoaderImpl.INSTANCE.getClassTweaker().getTargets().contains(name.replace('.', '/'));

		if (!transformAccess && !environmentStrip && !applyClassTweaker) {
			return bytes;
		}

		ClassReader classReader = new ClassReader(bytes);
		ClassWriter classWriter = new ClassWriter(classReader, 0);
		ClassVisitor visitor = classWriter;
		int visitorCount = 0;

		if (applyClassTweaker) {
			visitor = FabricLoaderImpl.INSTANCE.getClassTweaker().createClassVisitor(FabricLoaderImpl.ASM_VERSION, visitor, null); // TODO: generated classes?
			visitorCount++;
		}

		if (transformAccess) {
			visitor = new PackageAccessFixer(FabricLoaderImpl.ASM_VERSION, visitor);
			visitorCount++;
		}

		if (environmentStrip) {
			EnvironmentStrippingData stripData = new EnvironmentStrippingData(FabricLoaderImpl.ASM_VERSION, envType.toString());
			classReader.accept(stripData, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);

			if (stripData.stripEntireClass()) {
				throw new RuntimeException("Cannot load class " + name + " in environment type " + envType);
			}

			if (!stripData.isEmpty()) {
				visitor = new ClassStripper(FabricLoaderImpl.ASM_VERSION, visitor, stripData.getStripInterfaces(), stripData.getStripFields(), stripData.getStripMethods());
				visitorCount++;
			}
		}

		if (visitorCount <= 0) {
			return bytes;
		}

		classReader.accept(visitor, 0);

		byte[] result = classWriter.toByteArray();

		// UniLoader fork patch (T2.7): the class file version must survive the transform.
		//
		// 这条检查是踩出来的：1.14 上每个被变换过的 MC 类都在 defineClass 时抛
		// UnsupportedClassVersionError（class file 61 vs Java 8 只认到 52），
		// 而报错说的是「这个类版本太新」，看起来像游戏 jar 坏了——实际游戏 jar 一个字节没动。
		// 光靠冒烟测不出来：产物版本被抬高，在支持它的 JVM 上完全正常。
		int versionBefore = readClassFileVersion(bytes);
		int versionAfter = readClassFileVersion(result);

		if (versionBefore > 0 && versionAfter > 0 && versionBefore != versionAfter) {
			Log.error(LogCategory.TRANSFORM,
					"Fabric 内建变换改写了 %s 的 class file 版本：%d → %d（变换阶段=%s）",
					name, versionBefore, versionAfter, transforms);
		}

		return result;
	}

	/** class file version lives in bytes 6..7, big endian. */
	private static int readClassFileVersion(byte[] bytes) {
		if (bytes == null || bytes.length < 8) return -1;
		return ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
	}
}
