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

package org.uniloader.experiment.d1;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import org.uniloader.experiment.d1.MergedMappings.Sample;

/**
 * 用 ASM 合成一个「典型的 Fabric mod jar」：字节码里引用的是 intermediary 名字。
 *
 * <p>为什么是合成的而不是真的 mod：D1 要验证的是<b>机制</b>（intermediary 名字能不能被换成
 * mojmap 名字、换得全不全、换错了会不会静默丢失），而不是某个具体 mod 能不能跑。
 * 用真实映射里的真实条目来合成，机制结论是真的，同时不依赖下载几十 MB 的 mod 与 MC。
 *
 * <p>合成的 jar 具备真实 mod 的全部相关特征：
 * <ul>
 *   <li>class 文件里对 MC 类 / 成员的直接引用（intermediary 名）</li>
 *   <li>{@code fabric.mod.json}</li>
 *   <li>{@code refmap.json}（loom 的 {@code named:intermediary} 键形式）</li>
 *   <li>MANIFEST 里可选的 {@code Fabric-Loom-Mixin-Remap-Type}（决定内核是否启用 MixinExtension）</li>
 * </ul>
 */
public final class SyntheticFabricMod {
	public static final String CLASS_BASE = "ul/sample/SampleMod";
	public static final String CLASS_NAME = CLASS_BASE;
	public static final String REMAP_TYPE_HEADER = "Fabric-Loom-Mixin-Remap-Type";

	/** 合成类上带的 mixin 注解，用来观察「注解里的字符串会不会跟着重映射」（G3）。 */
	public static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";
	public static final String INJECT_DESC = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	public static final String AT_DESC = "Lorg/spongepowered/asm/mixin/injection/At;";

	private SyntheticFabricMod() {
	}

	/** 与 {@link #build(Path, String, List, String)} 相同，但类名带后缀（性能测试需要互不相同的类）。 */
	public static Path build(Path jar, String modId, List<Sample> samples, String remapType) throws IOException {
		return build(jar, modId, samples, remapType, CLASS_BASE);
	}

	/**
	 * @param remapType null 表示不写该 manifest 头（内核按 {@code fabric.defaultMixinRemapType} 兜底，
	 *                  默认 "mixin"，即<b>不</b>启用 MixinExtension）
	 */
	public static Path build(Path jar, String modId, List<Sample> samples, String remapType, String className)
			throws IOException {
		Files.createDirectories(jar.getParent());

		try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jar))) {
			Manifest manifest = new Manifest();
			manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
			if (remapType != null) manifest.getMainAttributes().putValue(REMAP_TYPE_HEADER, remapType);

			zos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
			manifest.write(zos);
			zos.closeEntry();

			write(zos, "fabric.mod.json", fabricModJson(modId, className));
			write(zos, "refmap.json", refmapJson(modId, samples));
			write(zos, className + ".class", classBytes(samples, className));
		}

		return jar;
	}

	private static void write(ZipOutputStream zos, String name, byte[] bytes) throws IOException {
		zos.putNextEntry(new ZipEntry(name));
		zos.write(bytes);
		zos.closeEntry();
	}

	private static byte[] fabricModJson(String modId, String className) {
		return ("{\n"
				+ "  \"schemaVersion\": 1,\n"
				+ "  \"id\": \"" + modId + "\",\n"
				+ "  \"version\": \"1.0.0\",\n"
				+ "  \"name\": \"UniLoader D1 sample mod\",\n"
				+ "  \"entrypoints\": { \"main\": [ \"" + className.replace('/', '.') + "\" ] },\n"
				+ "  \"depends\": { \"minecraft\": \"1.21.11\", \"fabricloader\": \">=0.19\" }\n"
				+ "}\n").getBytes(StandardCharsets.UTF_8);
	}

	/** loom 的形式：{@code named:intermediary}，因为 loom 编译时用 named、发布时打成 intermediary。 */
	private static byte[] refmapJson(String modId, List<Sample> samples) {
		StringBuilder sb = new StringBuilder("{\n  \"mappings\": { \"named\": {}, \"intermediary\": {} },\n  \"data\": {\n");

		for (Sample s : samples) {
			sb.append("    \"named:intermediary\": {\n");
			sb.append("      \"").append(s.ownerNamed().replace('/', '.')).append('.').append(s.memberNamed())
					.append("\": \"").append(s.ownerIntermediary().replace('/', '.')).append('.')
					.append(s.memberIntermediary()).append("\"\n");
			sb.append("    }\n");
			break; // 一条即可：实验关心的是「这个文件会不会被改写」
		}

		sb.append("  },\n  \"refmap\": { \"" + modId + "\": true }\n}\n");
		return sb.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] classBytes(List<Sample> samples, String className) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, className, null, "java/lang/Object", null);

		// @Mixin(TargetIntermediary.class) —— 让 MixinExtension 有东西可改，
		// 从而能真正观察到「注解里的类名会不会跟着重映射」。
		if (!samples.isEmpty()) {
			org.objectweb.asm.AnnotationVisitor mixin = cw.visitAnnotation(MIXIN_DESC, true);
			mixin.visit("value", org.objectweb.asm.Type.getObjectType(samples.get(0).ownerIntermediary()));
			mixin.visitEnd();
		}

		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();

		int index = 0;

		for (Sample s : samples) {
			MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
					"probe" + index++, "(Ljava/lang/Object;)V", null, null);

			// @Inject(method = "<memberIntermediary>", at = @At("HEAD"))
			// 注解里的字符串是普通常量，字节码重映射不会碰它 —— 这正是 refmap 存在的理由，
			// 也是运行时命名空间一旦变成 named 就必须处理的地方（G3）。
			//
			// 只给「方法」样本挂 @Inject：@Inject(method=...) 的目标必须是方法名，
			// 拿字段名去挂，MixinExtension 解析不出来，实验就变成了在测一个不成立的场景。
			if (s.isMethod()) {
				org.objectweb.asm.AnnotationVisitor inject = mv.visitAnnotation(INJECT_DESC, true);
				org.objectweb.asm.AnnotationVisitor methods = inject.visitArray("method");
				methods.visit(null, s.memberIntermediary());
				methods.visitEnd();
				org.objectweb.asm.AnnotationVisitor atArray = inject.visitArray("at");
				org.objectweb.asm.AnnotationVisitor at = atArray.visitAnnotation(null, AT_DESC);
				at.visit("value", "HEAD");
				at.visitEnd();
				atArray.visitEnd();
				inject.visitEnd();
			}

			mv.visitCode();
			mv.visitVarInsn(Opcodes.ALOAD, 0);
			mv.visitTypeInsn(Opcodes.CHECKCAST, s.ownerIntermediary());

			if (s.isMethod()) {
				mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, s.ownerIntermediary(), s.memberIntermediary(), s.descriptor(), false);
			} else {
				mv.visitFieldInsn(Opcodes.GETFIELD, s.ownerIntermediary(), s.memberIntermediary(), s.descriptor());
			}

			boolean wide = s.descriptor().equals("J") || s.descriptor().equals("D");
			mv.visitInsn(wide ? Opcodes.POP2 : Opcodes.POP);
			mv.visitInsn(Opcodes.RETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}

		cw.visitEnd();
		return cw.toByteArray();
	}

	/**
	 * 合成「游戏本体」的桩 jar，用作重映射的 classpath。真实环境里就是 MC 本体。
	 *
	 * <p>为什么必须有它：tiny-remapper 的 MixinExtension 要改写 {@code @Inject(method="...")} 这类字符串，
	 * 必须先知道目标成员是谁——而它只能从 classpath 上找到的目标类去解析。没有这个 classpath，
	 * 注解字符串就<b>改不动</b>，而这正是生产环境最容易踩空的地方（G2/G3）。
	 *
	 * <p>{@code named=true} 生成目标命名空间（named）的类，{@code false} 生成源命名空间（intermediary）的类。
	 * 两种都要，因为「重映射 classpath 该放哪个命名空间」本身就是要实验测出来的事实，不能靠猜。
	 */
	public static Path buildGameStub(Path jar, List<Sample> samples, boolean named) throws IOException {
		Files.createDirectories(jar.getParent());

		// 一个类一个 class 文件，成员取并集：同一个类可能同时有字段样本和方法样本，
		// 只写第一个会让第二个成员在 classpath 上找不到，重映射于是静默地不生效。
		Map<String, List<Sample>> byOwner = new java.util.LinkedHashMap<>();

		for (Sample s : samples) {
			byOwner.computeIfAbsent(named ? s.ownerNamed() : s.ownerIntermediary(), x -> new java.util.ArrayList<>()).add(s);
		}

		try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jar))) {
			for (Map.Entry<String, List<Sample>> e : byOwner.entrySet()) {
				ClassWriter cw = new ClassWriter(0);
				cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, e.getKey(), null, "java/lang/Object", null);

				for (Sample s : e.getValue()) {
					String member = named ? s.memberNamed() : s.memberIntermediary();

					if (s.isMethod()) {
						MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, member, s.descriptor(), null, null);
						mv.visitCode();
						emitDefaultReturn(mv, s.descriptor());
						mv.visitMaxs(0, 0);
						mv.visitEnd();
					} else {
						cw.visitField(Opcodes.ACC_PUBLIC, member, s.descriptor(), null, null).visitEnd();
					}
				}

				cw.visitEnd();

				zos.putNextEntry(new ZipEntry(e.getKey() + ".class"));
				zos.write(cw.toByteArray());
				zos.closeEntry();
			}
		}

		return jar;
	}

	private static void emitDefaultReturn(MethodVisitor mv, String descriptor) {
		String ret = descriptor.substring(descriptor.lastIndexOf(')') + 1);

		switch (ret) {
			case "V" -> mv.visitInsn(Opcodes.RETURN);
			case "J" -> {
				mv.visitInsn(Opcodes.LCONST_0);
				mv.visitInsn(Opcodes.LRETURN);
			}
			case "D" -> {
				mv.visitInsn(Opcodes.DCONST_0);
				mv.visitInsn(Opcodes.DRETURN);
			}
			case "F" -> {
				mv.visitInsn(Opcodes.FCONST_0);
				mv.visitInsn(Opcodes.FRETURN);
			}
			default -> {
				if (ret.startsWith("L") || ret.startsWith("[")) {
					mv.visitInsn(Opcodes.ACONST_NULL);
					mv.visitInsn(Opcodes.ARETURN);
				} else {
					mv.visitInsn(Opcodes.ICONST_0);
					mv.visitInsn(Opcodes.IRETURN);
				}
			}
		}
	}

	/** 只挑能被机械生成的形态：无参方法，或任意字段。 */
	public static List<Sample> usable(List<Sample> candidates, int wanted) {
		return candidates.stream()
				.filter(s -> !s.isMethod() || s.descriptor().startsWith("()"))
				.limit(wanted)
				.toList();
	}

	/** 供报告展示的一条样本的可读形式。 */
	public static String describe(Sample s) {
		return s.ownerIntermediary() + "." + s.memberIntermediary() + s.descriptor()
				+ "  →  " + s.ownerNamed() + "." + s.memberNamed();
	}

	/** 读取 jar 内某个条目的内容（用于比对 refmap 是否被改写）。 */
	public static String readEntry(Path jar, String entry) throws IOException {
		try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(jar.toFile())) {
			ZipEntry e = zf.getEntry(entry);
			if (e == null) return "";
			return new String(zf.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/** jar 里所有条目的名字，用于断言「非 class 文件没有被丢掉」。 */
	public static java.util.Set<String> entries(Path jar) throws IOException {
		try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(jar.toFile())) {
			return zf.stream().map(ZipEntry::getName).collect(java.util.stream.Collectors.toSet());
		}
	}

	public static Map<String, String> emptyRefmapExpectation() {
		return Map.of();
	}
}
