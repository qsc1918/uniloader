#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""检查模组引用的 fabric-loader API 在**当前内核**里到底有没有。

用法（由 run/check-mod-api.sh 调用）：
    check-mod-api.py <内核jar> <模组jar>...

为什么需要它：
  老模组（2019 年前后）用的是当时的 loader API，而上游 fabric-loader 会**删掉**一些旧方法。
  删掉之后，模组编译期没问题（它是对着老 loader 编的），运行期才抛
  `NoSuchMethodError` —— 而这个错误只在**该模组真的被调用到时**才出现，
  所以一个一个跑会变成「崩一次修一次」。

  实测样本：ModMenu 1.5.3 调用 `ModMetadata.getCustomElement(String)`，
  这个方法在 loader 0.19.5 里已经不存在 → 客户端在入口点阶段崩，
  报错只说 NoSuchMethodError，不会告诉你「这是 loader 删掉的 API」。

做法：从模组的常量池里取出所有对 `net/fabricmc/**` 的方法引用（owner + 名字 + 描述符），
  再去内核 jar 里查这个方法是否真的存在（解析内核 class 的方法表）。
  这是精确判定，不是启发式。
"""

import struct
import sys
import zipfile


def read_u2(b, o):
    return struct.unpack_from('>H', b, o)[0]


def read_u4(b, o):
    return struct.unpack_from('>I', b, o)[0]


def parse_constant_pool(data):
    """返回常量池列表：每项 (tag, a, b) —— 具体含义按 tag 解释。"""
    count = read_u2(data, 8)
    pool = [None] * count
    o = 10
    i = 1

    while i < count:
        tag = data[o]
        o += 1

        if tag == 1:  # Utf8
            length = read_u2(data, o)
            o += 2
            pool[i] = (1, data[o:o + length].decode('utf-8', 'replace'))
            o += length
        elif tag in (7, 8, 16, 19, 20):  # Class/String/MethodType/Module/Package
            pool[i] = (tag, read_u2(data, o))
            o += 2
        elif tag in (15,):  # MethodHandle
            pool[i] = (tag, data[o], read_u2(data, o + 1))
            o += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):  # 4 字节项
            pool[i] = (tag, read_u2(data, o), read_u2(data, o + 2))
            o += 4
        elif tag in (5, 6):  # Long/Double（占两个槽）
            pool[i] = (tag, read_u4(data, o), read_u4(data, o + 4))
            o += 8
            i += 1
        else:
            raise ValueError('未知常量池 tag %d' % tag)

        i += 1

    return pool, o


def utf8(pool, idx):
    entry = pool[idx]
    return entry[1] if entry and entry[0] == 1 else None


def class_name(pool, idx):
    entry = pool[idx]
    if entry and entry[0] == 7:
        return utf8(pool, entry[1])
    return None


def class_info(data):
    """解析一个 class，返回 (自己声明的 (名字,描述符) 集合, 父类, [接口])。"""
    pool, o = parse_constant_pool(data)

    o += 4  # access_flags, this_class
    super_name = class_name(pool, read_u2(data, o))
    o += 2
    interfaces = read_u2(data, o)
    o += 2
    iface_names = []

    for _ in range(interfaces):
        iface_names.append(class_name(pool, read_u2(data, o)))
        o += 2

    def skip_attributes(off):
        n = read_u2(data, off)
        off += 2

        for _ in range(n):
            off += 2
            length = read_u4(data, off)
            off += 4 + length

        return off

    fields = read_u2(data, o)
    o += 2

    for _ in range(fields):
        o += 6
        o = skip_attributes(o)

    methods = read_u2(data, o)
    o += 2
    out = set()

    for _ in range(methods):
        o += 2  # access
        name = utf8(pool, read_u2(data, o))
        desc = utf8(pool, read_u2(data, o + 2))
        o += 4
        out.add((name, desc))
        o = skip_attributes(o)

    return out, super_name, iface_names


# 只关心**加载器自己的 API**：
#   net/fabricmc/loader/**  —— loader 的 API 与实现
#   net/fabricmc/api/**     —— ModInitializer / EnvType 这些
# 刻意**不含** net/fabricmc/fabric/**：那是 Fabric API（一个模组，不是加载器），
# 它由 fabric-api jar 提供。不加这条过滤的话，任何用 Fabric API 的模组都会被误报
# （实测踩到：appleskin 引用 fabric.api.network.*，被错判成「内核里找不到」）。
LOADER_API_PREFIXES = ('net/fabricmc/loader/', 'net/fabricmc/api/')


def is_loader_api(owner):
    return owner.startswith(LOADER_API_PREFIXES)


def index_kernel(kernel_jar):
    """内核里每个 net/fabricmc 类的 (已声明方法, 父类, 接口)。"""
    index = {}

    with zipfile.ZipFile(kernel_jar) as zf:
        for entry in zf.namelist():
            if not entry.endswith('.class') or not entry.startswith('net/fabricmc/'):
                continue

            try:
                index[entry[:-6]] = class_info(zf.read(entry))
            except Exception:
                continue

    return index


# java/lang/Object 上的方法：任何类都**继承**得到。
# 不列出来的话，`ModMetadata.equals(Object)` 这种引用会被误报成「方法不存在」——
# 实测踩到（modmenu-1.7.18），而它其实是完全正常的调用。
OBJECT_METHODS = {
    ('equals', '(Ljava/lang/Object;)Z'),
    ('hashCode', '()I'),
    ('toString', '()Ljava/lang/String;'),
    ('getClass', '()Ljava/lang/Class;'),
    ('notify', '()V'),
    ('notifyAll', '()V'),
    ('wait', '()V'),
    ('wait', '(J)V'),
    ('wait', '(JI)V'),
    ('clone', '()Ljava/lang/Object;'),
    ('finalize', '()V'),
}


def declares(index, cls, name, desc):
    """这个类自己有没有声明这个方法。"""
    info = index.get(cls)
    return info is not None and (name, desc) in info[0]


def resolves(index, owner, name, desc, seen=None):
    """沿继承链查方法，返回 (找得到?, 能否确定?)。

    为什么返回值有两个：
      继承链里可能有**内核索引枚举不到**的类——最典型的是 JDK 的类，
      例如 `CustomValue$CvArray.forEach` 来自 `java/util/List` 的默认方法。
      那种情况我们**证明不了它缺失**，硬报「不存在」就是误报，
      代价是让人把一个能跑的模组排掉。

    所以：
      - 链上所有类都枚举得到（在内核里，或是 java/lang/Object）→ 结论**确定**；
      - 链上有枚举不到的类（JDK 的 List / AbstractList …）→ 结论**不确定**，只提示。

    实测两个样本正好各占一边：
      · ModMenu 1.5.3 调 `ModMetadata.getCustomElement` —— 链只有 Object → **确定缺失**（真崩了）；
      · dynamic-fps 调 `CvArray.forEach` —— 链上有 java/util/List → **不确定**（其实能跑）。
    """
    if (name, desc) in OBJECT_METHODS:
        return True, True

    if owner == 'java/lang/Object':
        return False, True

    if seen is None:
        seen = set()

    if owner in seen or owner is None:
        return False, True

    seen.add(owner)

    if declares(index, owner, name, desc):
        return True, True

    info = index.get(owner)

    if info is None:
        # 枚举不到（JDK 的类、或其它 jar 提供的类）→ 证明不了缺失
        return False, False

    _, super_name, ifaces = info
    found = False
    certain = True

    for parent in [super_name] + list(ifaces):
        if not parent:
            continue

        ok, sure = resolves(index, parent, name, desc, seen)

        if ok:
            return True, True

        if not sure:
            certain = False

    return False, certain


def mod_provided_classes(mod_jar):
    """这个模组**自己**提供的类（用于排除「引用自己」的误报）。"""
    with zipfile.ZipFile(mod_jar) as zf:
        return {n[:-6] for n in zf.namelist() if n.endswith('.class')}


def referenced_loader_methods(mod_jar, own_classes):
    """模组引用的所有**加载器 API** 方法：(owner, 名字, 描述符) -> 出现它的类。"""
    refs = {}

    with zipfile.ZipFile(mod_jar) as zf:
        for entry in zf.namelist():
            if not entry.endswith('.class'):
                continue

            try:
                data = zf.read(entry)
                pool, _ = parse_constant_pool(data)
            except Exception:
                continue

            for item in pool:
                # Methodref(10) / InterfaceMethodref(11)：class + NameAndType
                if not item or item[0] not in (10, 11):
                    continue

                owner = class_name(pool, item[1])
                nat = pool[item[2]]

                if not owner or not is_loader_api(owner):
                    continue
                # 模组自己带的类（例如 Kotlin 的 *Kt 门面类）不算加载器 API
                if owner in own_classes:
                    continue
                if not nat or nat[0] != 12:
                    continue

                name = utf8(pool, nat[1])
                desc = utf8(pool, nat[2])

                refs.setdefault((owner, name, desc), set()).add(entry)

    return refs


def main():
    if len(sys.argv) < 3:
        print('用法: check-mod-api.py <内核jar> <模组jar>...', file=sys.stderr)
        return 2

    kernel_jar = sys.argv[1]
    mod_jars = sys.argv[2:]

    index = index_kernel(kernel_jar)

    total_missing = 0

    for mod in mod_jars:
        refs = referenced_loader_methods(mod, mod_provided_classes(mod))
        missing = []
        unknown = []

        for (owner, name, desc), users in sorted(refs.items()):
            if owner not in index:
                # 整个类都不在内核里：可能是可选依赖，也可能是老 API 被整个删了
                missing.append((owner, name, desc, '类不存在（确定）', users))
                continue

            found, certain = resolves(index, owner, name, desc)

            if found:
                continue

            if certain:
                missing.append((owner, name, desc, '方法不存在（继承链已完整枚举，确定缺失）', users))
            else:
                unknown.append((owner, name, desc, '无法判定（继承链上有内核索引枚举不到的类，多半是 JDK 的）', users))

        print('=' * 70)
        print('模组: %s' % mod.split('/')[-1])
        print('  引用加载器 API 方法 %d 个' % len(refs))

        if not missing and not unknown:
            print('  **全部存在** —— 这个模组引用的 loader API 当前内核都提供。')
            continue

        if unknown:
            print('  **%d 个无法判定**（多半是从 JDK 类继承来的，不是问题）：' % len(unknown))

            for owner, name, desc, why, users in unknown:
                print('    ? %s.%s%s   [%s]' % (owner.replace('/', '.'), name, desc, why))

        if not missing:
            continue

        print('  **%d 个找不到**：' % len(missing))

        for owner, name, desc, why, users in missing:
            print('    - %s.%s%s   [%s]' % (owner.replace('/', '.'), name, desc, why))
            print('        被这些类调用: %s' % ', '.join(sorted(users)[:3]))

        total_missing += len(missing)

    print('=' * 70)

    if total_missing:
        print('合计 %d 处引用在内核里找不到。' % total_missing)
        print('这些模组跑起来会在**被调用到的那一刻**抛 NoSuchMethodError。')
        print('这不是本仓库引入的：是上游 fabric-loader 删掉了这些旧 API。')
    else:
        print('所有模组引用的 loader API 都存在。')

    return 0


if __name__ == '__main__':
    sys.exit(main())
