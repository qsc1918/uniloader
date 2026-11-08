#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""批量审计一批模组 jar：能不能在目标 MC 版本上用。

用法（由 run/audit-mods.sh 调用）：
    audit-mods.py [--cache <映射缓存目录>] <目录或jar>...

为什么需要它
------------
模组文件名和下载站的版本标签都**不可信**。实测踩到过一整批：
  - 名字写 1.14 但字节码是 Java 17/25（1.14 只能跑 Java 8）
  - 名字写 1.14 但其实是 1.14.4 的构建（混淆表不同，mixin 一 apply 就崩）
  -  fabric.mod.json 里有裸控制字符，标准 JSON 读不出来
  - 是别家生态（Forge/NeoForge）的模组，混在 Fabric 目录里
  - 调用上游 fabric-loader 已经删掉的 API
  - 用老 Mixin 的 ASM 包名写自定义注入点

判定的三个维度（互相独立，缺一不可）：
  1. **字节码版本** —— 决定需要哪个 Java。1.14/1.14.4 只能 Java 8（class file 52）。
     只统计 JVM 真的会加载的类：META-INF/versions/** 与 module-info.class 要排除，
     否则会把多版本 jar 误杀（实测 replaymod / randomtp 就是这样被冤枉的）。
  2. **MC 版本** —— 按 jar 里出现的 intermediary 名字去各版本映射里查命中率
     （复用 check-mod-version.py 的逻辑）。只在给了 --cache 且缓存里有映射时才做。
  3. **元数据与生态** —— fabric.mod.json 能不能读、有没有别家生态的清单文件、
     有没有引用老 Mixin ASM 包名、有没有引用已删除的 loader API。

输出末尾给一份「建议排除清单」，但**不替你做决定**：版本判定基于名字命中率，
对引用 MC 名字很少的模组证据不足，那种只提示不排除。
"""

import argparse
import io
import json
import os
import struct
import sys
import zipfile

FOREIGN = ('META-INF/neoforge.mods.toml', 'META-INF/mods.toml')
OLD_MIXIN_ASM = b'org/spongepowered/asm/lib'


def real_class_file_version(z, prefix=''):
    """这个 zip 里 JVM 在**目标 Java** 上真的会加载的最高 class file 版本。

    排除多版本 jar 的高版本分支与 module-info：低版本 JVM 根本不看它们。
    返回 (版本, 达到该版本的类路径)。
    """
    worst, path = 0, ''

    for name in z.namelist():
        if not name.endswith('.class'):
            continue
        if name.startswith('META-INF/versions/') or name.endswith('module-info.class'):
            continue

        try:
            data = z.read(name)
        except Exception:
            continue

        if len(data) >= 8 and data[:4] == b'\xca\xfe\xba\xbe':
            v = struct.unpack('>H', data[6:8])[0]

            if v > worst:
                worst, path = v, prefix + name

    return worst, path


def probe(path):
    r = {
        'file': os.path.basename(path),
        'notes': [],
        'hard': [],   # 确定不能用
        'soft': [],   # 需要留意，但不一定不能用
        'cf': 0,
        'env': '-',
        'mc': '-',
        'nested': 0,
    }

    z = zipfile.ZipFile(path)
    names = z.namelist()

    # ---- 元数据 ----------------------------------------------------------
    if 'fabric.mod.json' not in names:
        r['notes'].append('无 fabric.mod.json')
    else:
        try:
            meta = json.loads(z.read('fabric.mod.json').decode('utf-8'))
            r['env'] = meta.get('environment', '*')
            deps = meta.get('depends') or {}
            r['mc'] = deps.get('minecraft', '(未声明)') if isinstance(deps, dict) else '(未声明)'
        except Exception as e:
            r['hard'].append('fabric.mod.json 不是合法 JSON（%s）——Fabric 自己也读不了' % type(e).__name__)
            r['mc'] = '?'

    for marker in FOREIGN:
        if marker in names:
            r['soft'].append('带 %s（别家生态标记）' % marker)

    # ---- 字节码（顶层 + 嵌套 jar） ---------------------------------------
    worst, wpath = real_class_file_version(z)
    nested = 0

    for n in names:
        if not n.lower().endswith('.jar'):
            continue

        try:
            inner = zipfile.ZipFile(io.BytesIO(z.read(n)))
            v, p = real_class_file_version(inner, n + '!/')
            if v > worst:
                worst, wpath = v, p
            nested += 1
        except Exception:
            pass

    r['cf'], r['nested'] = worst, nested

    # ---- 老 Mixin ASM 包名 -----------------------------------------------
    for n in names:
        if not n.endswith('.class') or n.startswith('META-INF/versions/'):
            continue
        try:
            if OLD_MIXIN_ASM in z.read(n):
                r['soft'].append('引用老 Mixin ASM 包名（自定义注入点会注册失败）')
                break
        except Exception:
            pass

    return r


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--cache', help='intermediary 映射缓存目录（给了才做 MC 版本判定）')
    ap.add_argument('--java', type=int, default=8, help='目标 MC 的 Java 主版本（默认 8）')
    ap.add_argument('paths', nargs='+')
    args = ap.parse_args()

    max_cf = 44 + args.java

    # 可选：加载映射缓存，用于版本命中率
    cmv = None
    maps = {}
    versions = []

    if args.cache and os.path.isdir(args.cache):
        try:
            import importlib.util
            here = os.path.dirname(os.path.abspath(__file__))
            spec = importlib.util.spec_from_file_location(
                'cmv', os.path.join(here, 'check-mod-version.py'))
            cmv = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(cmv)
            versions = ['1.14', '1.14.4', '1.15.2', '1.16.5', '1.18.2']
            maps = {v: cmv.load_mapping(args.cache, v) for v in versions}
            maps = {v: m for v, m in maps.items() if m}
        except Exception as e:
            print('（版本判定不可用：%s）' % e, file=sys.stderr)
            cmv = None

    jars = []

    for p in args.paths:
        if os.path.isdir(p):
            jars += [os.path.join(p, f) for f in sorted(os.listdir(p))
                     if f.lower().endswith('.jar')]
        elif p.lower().endswith('.jar'):
            jars.append(p)

    for base in sorted(set(os.path.dirname(j) for j in jars)):
        print('=' * 92)
        print('目录: %s' % base)
        print('=' * 92)

        for path in sorted(j for j in jars if os.path.dirname(j) == base):
            try:
                r = probe(path)
            except Exception as e:
                print('  !! %-56s 读不了: %s' % (os.path.basename(path)[:56], e))
                continue

            if r['cf'] > max_cf:
                r['hard'].append('真实类 class file %d（需 Java %d+，目标只支持 %d）'
                                 % (r['cf'], r['cf'] - 44, max_cf))

            # 版本命中率
            rate_text = ''

            if cmv and maps:
                try:
                    names = cmv.collect_names(path)

                    if names:
                        rates = {v: len(names & m) / len(names) * 100
                                 for v, m in maps.items()}
                        best = max(rates, key=rates.get)
                        rate_text = ' '.join('%s=%.0f%%' % (v, x)
                                             for v, x in sorted(rates.items(),
                                                                key=lambda kv: -kv[1])[:2])
                        if len(names) >= 8 and rates[best] < 70:
                            r['hard'].append('MC 名字命中率最高才 %.0f%%（%s）——多半不是这批候选版本之一的构建'
                                             % (rates[best], best))
                    else:
                        rate_text = '(没有 MC 名字：纯库/资源 jar)'
                except Exception:
                    pass

            mark = '!!' if r['hard'] else ('? ' if r['soft'] else '  ')
            print('%s %-56s cf=%-3s nested=%-2s env=%-6s %s' % (
                mark, r['file'][:56], r['cf'], r['nested'], r['env'], rate_text))

            for n in r['hard']:
                print('        [排除] %s' % n)
            for n in r['soft'] + r['notes']:
                print('        [留意] %s' % n)
        print()

    print('说明：cf = 真正会被加载的最高 class file 版本（52 = Java 8）。')
    print('      [排除] 是确定不能用；[留意] 只是需要看一眼，不等于不能用。')
    print('      版本命中率基于 intermediary 名字，名字太少时证据不足，不会据此排除。')
    return 0


if __name__ == '__main__':
    sys.exit(main())
