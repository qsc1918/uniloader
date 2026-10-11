#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 ASM 重定位到 Mixin 的老包名 org.spongepowered.asm.lib，产出一个 shim jar。

用法（由 build.sh 调用）：
    relocate_asm.py <asm版本> <输出jar> <asm jar>...

为什么需要它
------------
老模组（2019–2020 年）是对着**当时**的 Mixin 编译的。那个年代的 Mixin 把 ASM
shade 在自己的包里：`org.spongepowered.asm.lib.**`。所以老模组的自定义注入点
（例如 appleskin 1.0.6 的 `squeek/appleskin/mixin/util/BeforeInc`）里会有
`org/spongepowered/asm/lib/tree/InsnList` 这样的引用。

而 Fabric 现在发布的 `net.fabricmc:sponge-mixin` **把 ASM 留在 org.objectweb.asm
没重定位**（Mixin 内部用的就是这个包），只在 `org/spongepowered/asm/lib/` 下留了
7 个空壳类（ClassVisitor / MethodVisitor / AnnotationVisitor / FieldVisitor /
Attribute / TypePath / tree/ClassNode，每个 300–2900 字节）。
于是老模组注册自定义注入点时：

    java.lang.NoClassDefFoundError: org/spongepowered/asm/lib/tree/InsnList
        at org.spongepowered.asm.mixin.transformer.MixinConfig.initInjectionPoint

Mixin 自己 catch 了，所以**不影响启动**，但那个注入点是真的没注册上——
功能是缺的。官方 fabric-loader 0.19.5 的 jar 里连那 7 个空壳都没有，
所以这是**上游的既有状况**，不是本仓库引入的。

做法
----
把 classpath 上本来就有的 `org.ow2.asm:*`（与 Mixin 用的是同一份、同一版本）
再复制一份到 `org/spongepowered/asm/lib/**`。Mixin 内部走 org.objectweb.asm，
完全不受影响；老模组按老名字找，就能找到了。

安全性
------
- 不改 Mixin 的行为：Mixin 自己用的是 org.objectweb.asm，一个字节没动。
- 版本天然一致：shim 就是从 classpath 上那份 ASM 复制的。
- 唯一的「冲突」是 sponge-mixin 里那 7 个空壳与 shim 里的真类同名。
  shim 必须排在 sponge-mixin **前面**，否则空壳会先被加载、shim 白做。
  即使排错了也只是没修复，不会变差。
"""

import struct
import sys
import zipfile

OLD = b'org/objectweb/asm/'
NEW = b'org/spongepowered/asm/lib/'

# 各 tag 在常量池里占的字节数（不含 tag 本身），None 表示可变长度
FIXED_SIZE = {
    7: 2, 8: 2, 16: 2, 19: 2, 20: 2,        # Class / String / MethodType / Module / Package
    15: 3,                                   # MethodHandle
    3: 4, 4: 4, 9: 4, 10: 4, 11: 4, 12: 4, 17: 4, 18: 4,
    5: 8, 6: 8,                              # Long / Double（占两个槽）
}


def read_u2(b, o):
    return struct.unpack_from('>H', b, o)[0]


def relocate_class(data):
    """把单个 class 的常量池里所有 org/objectweb/asm/ 换成老包名。

    只改常量池里的 Utf8 字节，**后面全部原样拷贝**。
    这是安全的：常量池之后的结构引用的是**索引**而不是字节偏移，
    而 Utf8 的长度前缀是每个条目自带的，改长改短都不影响别的条目。
    """
    if data[:4] != b'\xca\xfe\xba\xbe':
        return None

    count = read_u2(data, 8)
    out = bytearray(data[:10])
    o = 10
    i = 1

    while i < count:
        tag = data[o]

        if tag == 1:  # Utf8
            length = read_u2(data, o + 1)
            raw = data[o + 3:o + 3 + length]
            new = raw.replace(OLD, NEW)
            out.append(1)
            out += struct.pack('>H', len(new))
            out += new
            o += 3 + length
        elif tag in FIXED_SIZE:
            size = FIXED_SIZE[tag]
            out += data[o:o + 1 + size]
            o += 1 + size

            if tag in (5, 6):  # Long / Double 占两个槽
                i += 1
        else:
            raise ValueError('未知常量池 tag %d（偏移 %d）' % (tag, o))

        i += 1

    # 常量池之后原样拷贝：字段 / 方法 / 属性里的字节码偏移都不变
    out += data[o:]

    return bytes(out)


def main():
    if len(sys.argv) < 4:
        print('用法: relocate_asm.py <asm版本> <输出jar> <asm jar>...', file=sys.stderr)
        return 2

    version = sys.argv[1]
    out_jar = sys.argv[2]
    asm_jars = sys.argv[3:]

    written = 0
    skipped = 0

    with zipfile.ZipFile(out_jar, 'w', zipfile.ZIP_DEFLATED) as zout:
        for jar in asm_jars:
            with zipfile.ZipFile(jar) as zin:
                for entry in zin.namelist():
                    if not entry.endswith('.class'):
                        continue

                    # module-info 是 Java 9 的（class file 53），
                    # 老版本 MC 跑在 Java 8 上，放进去只会碍事
                    if entry.endswith('module-info.class'):
                        continue

                    if entry.startswith('META-INF/'):
                        continue

                    if not entry.startswith('org/objectweb/asm/'):
                        continue

                    new_name = entry.replace(
                        'org/objectweb/asm/', 'org/spongepowered/asm/lib/', 1)
                    data = relocate_class(zin.read(entry))

                    if data is None:
                        skipped += 1
                        continue

                    zout.writestr(new_name, data)
                    written += 1

    print('[mixin-asm-shim] ASM %s → %s：写入 %d 个类（跳过 %d）'
          % (version, out_jar, written, skipped))
    return 0


if __name__ == '__main__':
    sys.exit(main())
