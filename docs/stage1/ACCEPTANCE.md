# 阶段 0 + 阶段 1 验收签署（交接用）

> 这份文件回答一个问题：**「阶段 1 之前的所有任务都做完了吗？能不能交给下一个人去做阶段 2？」**
> 它不是论述，是**本次从零重跑出来的结果**。每一条都附可复现命令。
>
> 验收对象：阶段 0（T0.1–T0.7）+ 阶段 1（T1.1–T1.6），共 **13 条任务**。
> 方案真源：`D:/work2/AGENTS.md`（v1.1 阶段 0 回写、v1.2 阶段 1 回写、v1.4 T1.2 补记）。
>
> **本文件所在的提交即本次验收的收尾提交**（用 `git log -1` 取准确哈希）。
> 被验收的实质改动：`34a2884`（T1.1 逃生舱修正）、`56d8849`（T1.2 落地）、`d73adeb`（自检跟上）。

---

## 1. 验收方式（不是「我这边能跑」）

| 步骤 | 命令 | 本次结果 |
|---|---|---|
| 清掉一切产物 | `./gradlew clean :loader:clean` | BUILD SUCCESSFUL |
| **从零构建**（不用构建缓存、强制重跑全部任务） | `./gradlew build --no-build-cache --rerun-tasks` | **41/41 任务全部执行，BUILD SUCCESSFUL** |
| 单测 | 同上（build 内含） | **15 套件 / 114 个用例，全绿** |
| 交接自检 | `./run/preflight.sh` | **OK 12 / WARN 1 / FAIL 0**（WARN 是「有未提交改动」，即本次收尾提交本身） |
| D1 实验 | `./gradlew :experiments:d1-namespace:run` | **8/8 断言 PASS**，覆盖率 100%/100% |

从零构建通过意味着：**结果不依赖任何残留产物**，接手的人拉下来就能复现。

## 2. 四条冒烟路径（全绿才算健康）

| # | 路径 | 命令 | 本次结果 |
|---|---|---|---|
| 1 | 纯 Fabric（26.3） | `./run/smoke.sh --matrix=baseline` | **PASS 7/7** |
| 2 | 别家生态混装（26.3，11 Fabric + 7 NeoForge） | `./run/smoke-foreign.sh` | **PASS 10/10** |
| 3 | 崩溃对照（26.3） | `UL_MODS_DIR=run/mods-broken ./run/smoke.sh --matrix=broken --expect=crash` | **PASS 10/10** |
| 4 | **混淆版本 named 运行时（1.21.11）** | `./run/prepare-named.sh` → `./run/smoke-named.sh` | **PASS 7/7** |

## 3. 双版本支持 —— 这是本次验收的重点

| | **MC 26.3**（26.1 起未混淆） | **MC 1.21.11**（仍有混淆） |
|---|---|---|
| 运行时命名空间 | `official`（== Mojang 真实名） | `named`（按决策 D1 统一到 mojmap） |
| 模组分发命名空间 | `official` | `intermediary` |
| 重映射 | **零重映射**（两侧同名） | 模组 intermediary→named；游戏本体 official→named |
| 四处门闸 G1–G4 | 不适用（不开） | **全开且已验证** |
| 实测 | 11 个真实模组，PASS 7/7 | 11 个真实 1.21.11 模组（含嵌套 **48 个**），PASS 7/7 |
| 映射覆盖率 | 不需要映射 | 类 **100%**（10242/10242）、成员 **100%**（94916/94916） |

1.21.11 上的重映射缓存（T1.2）：

| 指标 | 冷启动 | 热启动（命中缓存） | 原生 Fabric 基线 |
|---|---|---|---|
| `remap` 阶段 | **6774 ms** | **1452 ms（−78.6%）** | 0 ms |
| 启动（`Loading`→`Done`） | 15 s | **8 s** | 8 s |

热启动与原生 Fabric 基线同秒 ⇒ 满足 T1.2 的判据「< 15%」。

## 4. 逐任务验收

### 阶段 0

| 任务 | 状态 | 证据 |
|---|---|---|
| T0.1 仓库骨架 | ✅ | `LICENSE` / `LICENSE-Apache-2.0` / `LICENSE-LGPL-2.1` / `NOTICE` 齐备；`loader/` 由 `git subtree` 导入并保留上游历史；`./gradlew build` 通过 |
| T0.2 版本矩阵 | ✅ | `gradle.properties` 为唯一版本真源；结论见 `docs/stage0/T0.2-version-matrix.md` |
| T0.3 D1 实验 | ✅ | `experiments/d1-namespace` 可执行；本次重跑 **8/8 PASS**；报告 `docs/stage0/T0.3-D1-namespace-experiment.md` |
| T0.4 实验台与冒烟 | ✅ | `run/*.sh`；26.3 真实冒烟（阶段 0 时 6/6，现 7/7）；报告 `docs/stage0/T0.4-real-smoke-report.md` |
| T0.5 诊断骨架 | ✅ | `compat/compat-api` + `tooling/{crash-attribution,load-report}`；崩溃对照冒烟能指认到具体模组（第 3 条路径） |
| T0.6 验收 mod 矩阵 | ✅ | `tooling/mod-matrix/`（matrix.yml 四家 30 个 + fetch-mods.sh + matrix-resolved.json）；实测 baseline 11/11、neoforge 7/10 可用 |
| T0.7 许可证核实 | ✅ | `docs/LICENSES.md`（POM / GitHub API / 构件内许可文件三来源，命令可重跑） |

### 阶段 1

| 任务 | 状态 | 证据（可复现） |
|---|---|---|
| T1.1 命名空间子系统正式化 | ✅ | `impl/compat/namespace/NamespacePolicy`（**三个**命名空间）；`./gradlew :loader:test --tests '*NamespacePolicyTest*'`；与 `MappingConfiguration` 逐一对拍 |
| T1.2 重映射缓存与性能 | ✅ **（撤销 P2 降级）** | `impl/compat/remap/RemapCache`；`./run/prepare-named.sh && ./run/smoke-named.sh`（冷/热见第 3 节）；`./gradlew :loader:test --tests '*RemapCacheTest*'` |
| T1.3 语言适配器注册表（H1） | ✅ | `impl/compat/adapter/`；`./gradlew :loader:test --tests '*LanguageAdapterRegistryTest*'` |
| T1.4 候选查找器插件化（H2） | ✅ | `impl/compat/discovery/`；第 2 条冒烟路径（别家生态被识别而非当「未知文件」） |
| T1.5 变换管线化（H3） | ✅ | `impl/compat/transform/`；`./run/compare-transform-hashes.sh`（**10756 个类逐字节相同**） |
| T1.6 诊断接入 | ✅ | `impl/compat/diagnostics/` + `tooling/load-report`；第 3 条冒烟路径（报告含 `[FAILED]` + 阶段 + 原因 + 各阶段耗时） |

## 5. 明确**没有**验收的（不是遗漏，别当成已完成）

1. **真实客户端「进世界」**：需要显卡与 assets，只测了无头服务端。
2. **注入成功率统计**：需要 `mixin audit`（阶段 7 的 T7.1）；现在只能证明「没有注入失败」，
   不能证明「注入了多少」。
3. **T1.2 缓存命中仍有 ~1.4 s 固定开销**（嵌套模组落盘 + 按内容算键）。不影响判据，
   要再压就得把键改成从父 jar 条目直接算。
4. **`HOOK_INJECTION` 阶段的位置是暂定的**：阶段 7 必须靠真实 patch 实测确定。
5. **`net.neoforged:fancymodloader` / Forge `eventbus` 的许可证**：GitHub 标 `NOASSERTION`、
   POM 无声明，阶段 4 的 T4.1 与阶段 8 的 T8.1 之前必须闭环。

## 6. 本次验收顺带修掉的问题（都已在提交里）

| # | 问题 | 影响 |
|---|---|---|
| 1 | 映射合并把参数表写进了方法名（`startTimerHackThread()`） | 重命名后字节码里多出 `()()`，整条链静默错位 |
| 2 | 反混淆游戏 jar 的缓存键不含映射内容 | 换映射后静默复用旧 jar，表现为「mixin 目标找不到」 |
| 3 | `NamespacePolicy` 忽略 `fabric.runtimeMappingNamespace` | 日志说假话（打 `runtime=named` 而实际跑 intermediary） |
| 4 | `run/env.sh` 不把游戏/模组目录绝对化 | 文档里的 `UL_MODS_DIR=run/mods-broken` 会被解析到游戏目录下面而失败 |
| 5 | 根目录缺 `LICENSE`（T0.1 的验收条目点名要它） | 许可证分层只靠 `NOTICE` 说明，验收条目对不上 |

## 7. 交接状态

- **工作区干净**，无未提交改动（本次收尾提交之后）。
- **可以开工阶段 2**：`docs/stage2/HANDOFF.md` 是给下一个人的交接文档，
  开工前先跑 `./run/preflight.sh`（它会重新打印本文第 2、5 节的内容）。
- 阶段 2 的第一件真事：**让 Quilt 模组真的被加载**（不只是被识别）。
