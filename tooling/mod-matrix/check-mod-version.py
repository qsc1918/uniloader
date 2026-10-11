#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""判断一个模组 jar 到底是**为哪个 MC 版本**编译的。

用法（由 run/check-mod-version.sh 调用，不直接跑）：
    check-mod-version.py <jar> <候选版本>... --cache <目录>

原理：Fabric 模组是**对着 intermediary 名字**编译的（class_1255、method_20813…），
而每个 MC 版本的 intermediary 都不一样（1.14 的 class_1255 是 agh，1.14.4 是 agq）。
所以：把 jar 里出现的所有 MC 名字抠出来，拿去每个候选版本的映射里查，
**命中率最高的那个版本就是它真正对应的版本**。

这不是启发式猜测，是可验证的判定：命中率 100% 基本就是它；命中率 70% 说明是邻近版本；
命中率个位数说明压根不是这个时代的模组。
"""

import io
import json
import os
import re
import struct
import subprocess
import sys
import zipfile

NAME_RE = re.compile(rb'\b(?:class|method|field)_\d+\b')

# 这些是 Fabric 自己的 API 名字，跟 MC 版本无关，不能算进命中率
IGNORED_PREFIXES = (
    b'fabric', b'net/fabricmc', b'org/spongepowered',
)


def collect_names(jar):
    """抠出 jar 里出现的所有 intermediary 风格名字（去重）。"""
    names = set()

    with zipfile.ZipFile(jar) as zf:
        for entry in zf.namelist():
            if not entry.endswith('.class'):
                continue

            try:
                data = zf.read(entry)
            except Exception:
                continue

            for m in NAME_RE.finditer(data):
                names.add(m.group().decode('ascii'))

    return names


def load_mapping(cache_dir, version):
    """拿到这个版本的 intermediary 名字集合（class/method/field 三种）。"""
    tiny = os.path.join(cache_dir, 'intermediary-%s.tiny' % version)

    if not os.path.exists(tiny):
        jar = os.path.join(cache_dir, 'intermediary-%s.jar' % version)
        url = ('https://maven.fabricmc.net/net/fabricmc/intermediary/%s/intermediary-%s-v2.jar'
               % (version, version))

        if not os.path.exists(jar):
            tmp = jar + '.tmp'
            r = subprocess.run(['curl', '-fsSL', '--retry', '3', '--max-time', '300', '-o', tmp, url],
                               capture_output=True)

            if r.returncode != 0:
                return None

            os.replace(tmp, jar)

        try:
            with zipfile.ZipFile(jar) as zf:
                data = zf.read('mappings/mappings.tiny')
        except Exception:
            return None

        with open(tiny, 'wb') as fh:
            fh.write(data)

    names = set()

    with open(tiny, encoding='utf-8') as fh:
        for line in fh:
            # tiny v2：c <official> <intermediary> / m <desc> <official> <intermediary>
            parts = line.rstrip('\n').split('\t')

            for part in parts:
                # **类名是带路径的**（net/minecraft/class_1255），方法/字段是裸名。
                # 只匹配裸名的话，所有类都解析不出来 —— 之前就是这么错的，
                # 结果把「class_1255 在 1.14.4 里找不到」当成证据，结论完全反了。
                # 内部类还要拆 $（class_1255$class_1256）。
                for segment in part.replace('$', '/').split('/'):
                    if NAME_RE.fullmatch(segment.encode('ascii', 'ignore')):
                        names.add(segment)

    return names


def main():
    args = sys.argv[1:]

    if '--cache' in args:
        i = args.index('--cache')
        cache_dir = args[i + 1]
        args = args[:i] + args[i + 2:]
    else:
        cache_dir = 'build/intermediary-cache'

    if len(args) < 2:
        print('用法: check-mod-version.py <jar> <候选版本>... [--cache <目录>]', file=sys.stderr)
        return 2

    jar = args[0]
    versions = args[1:]

    if not os.path.isfile(jar):
        print('找不到文件: %s' % jar, file=sys.stderr)
        return 2

    os.makedirs(cache_dir, exist_ok=True)

    names = collect_names(jar)

    if not names:
        print('这个 jar 里没有任何 intermediary 风格的名字 —— 它可能不是 Fabric 模组，')
        print('或者是个不碰 MC 代码的纯资源包。')
        return 0

    print('模组: %s' % os.path.basename(jar))

    # 顺手报一下字节码版本：**两个维度是独立的**。
    # 实测样本 rightclickharvest-2.0.0.jar 的名字 100% 命中 1.14，但它是 Java 17 编译的
    # —— 在 Java 8（1.14 唯一能跑的 JVM）上照样加载不了。
    # 所以「对得上哪个 MC」和「需要哪个 Java」必须分开看。
    with zipfile.ZipFile(jar) as zf:
        worst = 0

        for entry in zf.namelist():
            if not entry.endswith('.class'):
                continue
            try:
                data = zf.read(entry)
            except Exception:
                continue
            if len(data) >= 8 and data[:4] == b'\xca\xfe\xba\xbe':
                worst = max(worst, struct.unpack('>H', data[6:8])[0])

    if worst:
        need_java = worst - 44
        print('字节码版本: class file %d → 需要 Java %d 或更高%s'
              % (worst, need_java,
                 '（注意：1.14/1.14.4 只能跑 Java 8）' if need_java > 8 else ''))

    print('抠出 %d 个去重后的 MC 名字' % len(names))
    print()

    # 每个候选版本：命中集合 + 缺失集合
    per_version = []

    for version in versions:
        mapping = load_mapping(cache_dir, version)

        if mapping is None:
            print('  %-12s 拿不到这个版本的 intermediary（网络？版本号写错？）' % version)
            continue

        hit = {n for n in names if n in mapping}
        miss = names - hit
        per_version.append((len(hit) / len(names) * 100, hit, miss, version))

    if not per_version:
        return 1

    per_version.sort(key=lambda x: -x[0])

    print('  %-12s %8s %12s' % ('MC 版本', '命中率', '命中/总数'))
    print('  ' + '-' * 44)

    for rate, hit, miss, version in per_version:
        bar = '#' * int(rate / 5)
        print('  %-12s %7.1f%% %6d/%-6d %s' % (version, rate, len(hit), len(names), bar))

    best_rate, best_hit, best_miss, best_version = per_version[0]

    # 决定性证据：只在某一个候选里能解析出来的名字。
    # 命中率会被「本来就存在的名字」稀释（比如 method_3777 每个版本都有），
    # 真正说明问题的是那些**只在这个版本里存在**的名字。
    decisive = {}

    for name in names:
        owners = [v for _, hit, _, v in per_version if name in hit]

        if len(owners) == 1:
            decisive.setdefault(owners[0], []).append(name)

    if decisive:
        print()
        print('决定性名字（只在一个候选版本里存在，这才是真正的证据）：')

        for version, ns in sorted(decisive.items(), key=lambda kv: -len(kv[1])):
            print('  %-12s %s' % (version, ', '.join(sorted(ns)[:10])))

    if best_miss:
        print()
        print('在 %s 里**找不到**的名字（前 10 个）：%s'
              % (best_version, ', '.join(sorted(best_miss)[:10])))

    print()

    tied = [v for rate, _, _, v in per_version if rate == best_rate]

    if best_rate >= 99.0:
        if len(tied) == 1:
            print('结论：**这个模组就是为 MC %s 编译的**（命中率 100%%）。' % best_version)
        else:
            # 并列要说清楚，不能挑一个报 —— 相邻版本常常互相都解析得通。
            print('结论：它在 %s 上都 100%% 解析得通（这些版本的映射在这批名字上重合）。'
                  % ' 与 '.join(tied))
            print('      但**不在** %s 上 —— 那些版本各有名字对不上。'
                  % '、'.join(v for _, _, _, v in per_version if v not in tied))
    elif len(decisive) == 1 and best_rate >= 50.0:
        # 有且只有一个版本能解释这些名字 → 判定成立，即使命中率不高
        only = next(iter(decisive))
        print('结论：**这个模组是为 MC %s 编译的**。' % only)
        print('      虽然整体命中率只有 %.1f%%，但有 %d 个名字只在 %s 里存在、'
              % (best_rate, len(decisive[only]), only))
        print('      在别的候选版本里都找不到 —— 这个方向性证据比命中率更硬。')
    elif best_rate >= 60.0:
        print('结论：最像 MC %s（命中率 %.1f%%），但没有决定性名字。' % (best_version, best_rate))
        print('      可能是同一时代的邻近版本，也可能是模组引用了少量别的 API。')
    else:
        print('结论：**证据不足**（最高命中率只有 %.1f%%，且没有决定性名字）。' % best_rate)
        print('      把候选版本列全一点再试（例如加上 1.16.5 / 1.18.2 / 1.21.11），')
        print('      或者确认它到底是不是 Fabric 模组。')

    return 0


if __name__ == '__main__':
    sys.exit(main())
