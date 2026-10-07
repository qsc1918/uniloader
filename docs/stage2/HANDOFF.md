# 阶段 2 交接文档（从这里开始）

> 给**下一个人或下一个 AI**。读完这一份就能开工，不需要再翻聊天记录。
> 方案真源仍是 `D:/work2/AGENTS.md`（末尾有「阶段 1 结论回写 v1.2」）。
> 阶段 1 已完成：五条任务完成、T1.2 按许可降级为 P2；
> 新增 59 个单测（仓库共 99 个全绿）；三条冒烟路径全部通过。
> 完整实测报告：`docs/stage1/STAGE1-REPORT.md`。

---

## 0. 一句话现状

内核已经能加载真实 Fabric 模组（11 个）、**能识别**真实 NeoForge 模组（7 个，只识别不加载）、
命名空间判定收敛到一处、字节码管线可插拔且**产物逐字节未变**、诊断能指认到具体模组。
阶段 2 要做的第一件真事是：**让 Quilt 模组真的被加载**（不只是被识别）。

## 1. 五分钟上手

```bash
cd /d/work2/uniloader

export https_proxy=http://127.0.0.1:7897 http_proxy=http://127.0.0.1:7897
export GRADLE_OPTS="-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897"

./gradlew build                      # 编译 + 99 个单测 + checkstyle/spotless + 许可证边界

./run/smoke.sh --matrix=baseline     # 纯 Fabric，期望 PASS 7/7
./run/smoke-foreign.sh               # 真实 NeoForge 混装，期望 PASS 10/10
./run/make-broken-mod.sh
UL_MODS_DIR=run/mods-broken ./run/smoke.sh --matrix=broken --expect=crash   # 期望 PASS 10/10
```

**改内核代码后必须先跑 `./gradlew :loader:spotlessApply`**，否则 `spotlessJavaCheck` 会拦下构建
（它会把你新写的 CRLF 转成 LF）。

## 2. 阶段 1 留下的可复用件（先看这些，别重造）

| 件 | 位置 | 干什么用 |
|---|---|---|
| 命名空间判定 | `loader/.../impl/compat/namespace/NamespacePolicy` | 运行时/模组分发命名空间的**唯一**决策点。要加约束就往这里加，不要再散到调用点 |
| 语言适配器注册表（H1） | `loader/.../impl/compat/adapter/` | `default`/`quilt`/`neoforge`/`forge` 四个键已占位；`replace()` 换实现 |
| 候选查找器插件化（H2） | `loader/.../impl/compat/discovery/` | `ForeignModRecognizer` + `ForeignModRecognizers` + `ForgeModCandidateFinder`；`acceptForeign` 是「认出来了但还加载不了」的语义 |
| 变换管线（H3） | `loader/.../impl/compat/transform/` | `TransformPhase` 五个阶段；`HOOK_INJECTION` 是空的，位置**暂定** |
| 内核诊断 | `loader/.../impl/compat/diagnostics/` | `LoaderDiagnostics`（阶段耗时/三态/归因）、`TransformHashLog`（逐类产物哈希） |
| 报告渲染 | `tooling/load-report/` | `KernelDiagnosticsReader` + `ReportMain`；`./run/report.sh` |
| 验收脚本 | `run/` | `smoke.sh`（`--expect=boot\|crash`）、`smoke-foreign.sh`、`report.sh`、`make-broken-mod.sh`、`compare-transform-hashes.sh` |

## 3. 阶段 2 的任务（T2.1 起，见 AGENTS.md 第 9 节）

> 开工前先把 AGENTS.md 第 9 节的任务清单读一遍，那里的编号是真源。
> 下面只写**在阶段 1 之后必须重新审视**的点。

### 3.1 Quilt 元数据解析

- `quilt.mod.json` 与 `fabric.mod.json` 高度同源，但**不是**同一个 schema：它有 `quilt_loader` 段、
  `provides` 语义、`entrypoints` 里可以带 `adapter` 字段。
- **已有挂载点**：`ForeignModRecognizers` 已经能认 `quilt.mod.json`（本阶段它只会打一句「识别为 Quilt mod」）。
  阶段 2 要做的是把它从「识别」升级成「解析并产出候选」。
- **注意顺序**：`ForeignModRecognizers.match()` 是**先注册先赢**，`quilt.mod.json` 排在最前。
  一个同时带 `fabric.mod.json` 与 `quilt.mod.json` 的 jar（Quilt 模组常见）目前走 Fabric 路径——
  这个判定必须显式化，不能靠注册顺序的巧合。

### 3.2 QSL 的 `provides` 语义

- `quilted-fabric-api` 声明「提供」fabric-api 的各个子模块 id。这会让**依赖求解器**的输入发生变化：
  一个依赖 `fabric-api` 的 Fabric 模组，应该能被 QSL 满足。
- 求解器是 SAT4j（`ModResolver`），`provides` 在 Fabric 侧本来就有——重点是 Quilt 的 `provides` 语义
  是否与 Fabric 一致（**不要假设**，拿真实 jar 验）。

### 3.3 验收矩阵

- `tooling/mod-matrix/matrix.yml` 里已经有 `quilt` 矩阵（quilted-fabric-api / quilt-standard-libraries /
  no-teleporting-in-nether / quark-oddities …），`available` 一律是 `unknown`。
- **第一步就是跑 `./tooling/mod-matrix/fetch-mods.sh quilt`**，看 MC 26.3 上到底有几个可用。
  「查不到」本身就是结论，不要跳过这一步去写解析器。
- 建议照 `run/smoke-foreign.sh` 的样子加一个 `run/smoke-quilt.sh`：
  **Quilt 模组与 Fabric 模组混装**才是阶段 2 的真正验收（单跑 Quilt 说明不了共存）。

## 4. 红线（每次改代码前默念）

1. 绝不把 mixin `required` 改 false / 降级 `defaultRequire`；注入失败必须显式报错 + 降级。
2. 绝不合并多份 patch 过的 MC 字节码。
3. 绝不静默吞异常；catch 必须记录 mod id + 类 + 方法 + 原因。
4. 版本号一律走 `gradle.properties`；**判定依据用证据（有没有 mappings），不用版本号比较**。
5. 状态只有 OK / DEGRADED / FAILED（`compat-api` 已落地）。
6. per-mod 特例只能进 `compat/quirks/`。
7. 内核 fork 改动必须注释 `UniLoader fork patch (T2.x)`，且收敛在 `impl/compat/` 或构建脚本。
8. **新增内核代码必带单测**（阶段 1 已为每个新组件配了测试，保持这个密度）。
9. **任何改动「Mixin 之前」管线的提交，必须跑一次哈希对拍**（`run/compare-transform-hashes.sh`）。
10. **每个新子系统都要配一个「故意失败」的对照组**，照 `run/make-broken-mod.sh` 的模式。

## 5. 坑清单（阶段 1 新增，都会咬人）

| 编号 | 事实 | 适用条件 |
|---|---|---|
| **G5** | 专服的 `Main` 会 catch 掉入口点异常后正常返回，**退出码是 0** | 「有没有崩」看「有没有跑到超时」+「日志有没有指认到模组」，不看退出码 |
| **G6** | 用信号掐掉的进程，**shutdown hook 里的日志会被已停用的日志系统吞掉** | 度量要**边跑边落盘**，不要依赖 shutdown hook |
| **G7** | `ModCandidateFinder` 原本包级私有 | 扩展点插件化的前提（阶段 1 已改 public） |
| **G9** | `RuntimeModRemapper` 用**一个** `TinyRemapper` 处理整批模组并合并 class-tweaker | 重映射缓存键必须覆盖整批输入（T1.2 的约束） |
| **G10** | `run/env.sh` 的 `try_java` 用「返回 1」表示**找到了** Java | 任何 `set -e` 的脚本 source 它都会静默退出；先 `set +e` 再 source |
| **G11** | checkstyle 的 `JavadocStyle` 要求首句以 **ASCII 句点**结尾 | 中文 Javadoc 要么结尾用 `.`，要么放进 `impl/compat/**`（已放宽） |
| — | 加载到哪些类**不是确定的**（180 秒窗口 + `timeout` 掐掉） | 同代码两次运行差 1–2 个类属正常；对拍只判定两侧都有的类 |
| **G1–G4** | 命名空间相关的四处门闸 | **只在目标 MC 仍有混淆（≤ 1.21.11）时适用**；26.3 上是零重映射，不要打开 |
| **E1–E4** | 运行 Java 25 / 工作目录 = 游戏目录 / classpath 用 `run/loader-classpath.sh` / Gradle 锁 9.1.0 | 永远 |
| **L1/L2** | LGPL-2.1-only 不能与 LGPL-3.0 合并进同一 jar；仓库禁提交 MC 字节码与 mod 二进制 | 阶段 4/8 引入 LGPL 模块时 / 永远 |

## 6. 未闭环项（不是遗漏，是留给后续阶段）

1. **真实客户端「进世界」未测**：需要显卡与 assets。建议阶段 2 至少在一个图形环境补一次。
2. **注入成功率统计未做**：需要 `mixin audit`（阶段 7 的 T7.1）。目前冒烟只能证明「没有注入失败」，
   不能证明「注入了多少」。
3. **T1.2 的混淆版本路径**：见 AGENTS.md 1.2 第 3 条（含缓存键约束与重新启用的验收命令）。
4. **`net.neoforged:fancymodloader` / Forge `eventbus` 的许可证**：GitHub 标 `NOASSERTION`、POM 无声明。
   **阶段 4 的 T4.1 与阶段 8 的 T8.1 之前必须闭环**（见 `docs/LICENSES.md` 第 5 节）。
5. **`HOOK_INJECTION` 阶段的位置是暂定的**：阶段 7 必须靠实测确定，不许猜。

## 7. 提交与文档纪律

- 一个任务一个提交，中文，写清「做了什么 + 为什么 + 验收方式」；
- 内核改动标注 `UniLoader fork patch (T2.x)`，并收敛在 `impl/compat/`；
- 报告类产出放 `docs/stage2/`；生成物加「本文件由 … 生成，请勿手改」头 + 重新生成命令；
- **每完成一条任务就把结论回写到 `D:/work2/AGENTS.md` 末尾**（追加，不覆盖），
  并在阶段结束时更新本文档为新一版的 HANDOFF；
- 不提交 `build/`、`run/game/`、`run/mods/`、`run/mods-foreign/`、`run/mods-broken/`、任何 `.jar`。
