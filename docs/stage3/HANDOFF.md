# 阶段 3 交接文档（从这里开始）

> 给**下一个人或下一个 AI**。读完这一份就能开工，不需要再翻聊天记录。
> 方案真源仍是 `D:/work2/uniloader/AGENTS.md`（末尾依次是 v1.1 阶段 0 / v1.2 阶段 1 /
> v1.4 阶段 1 补记 / **v1.5 阶段 2 回写**，按时间顺序读）。
> 阶段 2 **六条任务全部完成**：Quilt 模组在 26.3 上真的被加载了，
> 四条冒烟路径全绿（7/7、10/10、10/10、**11/11**），单测 139 个全绿，
> 「Mixin 之前」的字节码管线对拍通过（10755 个类逐字节相同）。
> 开工前先跑 `./run/preflight.sh`（见 0.5 节）。
> 完整实测报告：`docs/stage2/STAGE2-REPORT.md`。

---

## 0. 一句话现状

内核已经能加载真实 Fabric 模组（11 个）与**真实 Quilt 模组**（混装验证通过），
能识别真实 NeoForge / Forge 模组（只识别不加载）。
`org.quiltmc.loader.api` 有了独立模块 `compat/compat-quilt/`。
命名空间判定收敛到一处、字节码管线可插拔且产物逐字节未变、诊断能指认到具体模组。

**阶段 3 要做的第一件真事**：让 Forge / NeoForge 的模组**被看见**——
发现、解析 `mods.toml`、参与同一张依赖图（**不运行**它们）。

## 0.5 开工前先跑交接自检

```bash
cd /d/work2/uniloader
export https_proxy=http://127.0.0.1:7897 http_proxy=http://127.0.0.1:7897
./run/preflight.sh            # 环境 + 依赖 + git + 构建 + 四条冒烟路径，约 25 分钟
./run/preflight.sh --quick    # 只跑环境 + 依赖 + git + 构建，约 1 分钟
```

退出码 0 = 可以开工；1 = 有硬失败，先修。

## 1. 五分钟上手

```bash
cd /d/work2/uniloader

export https_proxy=http://127.0.0.1:7897 http_proxy=http://127.0.0.1:7897
export GRADLE_OPTS="-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897"

./gradlew build                      # 编译 + 139 个单测 + checkstyle/spotless + 许可证边界

./run/smoke.sh --matrix=baseline     # 纯 Fabric，期望 PASS 7/7
./run/smoke-foreign.sh               # 真实 NeoForge 混装，期望 PASS 10/10
./run/smoke-quilt.sh                 # Quilt 与 Fabric 混装，期望 PASS 11/11
./run/make-broken-mod.sh
UL_MODS_DIR=run/mods-broken ./run/smoke.sh --matrix=broken --expect=crash   # 期望 PASS 10/10

# 仍有混淆的 MC（≤1.21.11）上的 named 运行时 + 重映射缓存（T1.2）
./run/prepare-named.sh && rm -rf ~/.ul/cache && ./run/smoke-named.sh && ./run/smoke-named.sh
```

**改内核代码后必须先跑 `./gradlew :loader:spotlessApply`**，否则 `spotlessJavaCheck` 会拦下构建。
**改了 `compat/compat-quilt/` 的代码同理**（该模块没有配 spotless，但别把 CRLF 提交进去）。

## 2. 阶段 1 / 2 留下的可复用件（先看这些，别重造）

| 件 | 位置 | 干什么用 |
|---|---|---|
| 命名空间判定 | `loader/.../impl/compat/namespace/NamespacePolicy` | **三个**命名空间的**唯一**决策点。要加约束就往这里加 |
| 语言适配器注册表（H1） | `loader/.../impl/compat/adapter/` | `default`/`quilt`/`neoforge`/`forge` 四键已占位；`replace()` 换实现 |
| 候选查找器插件化（H2） | `loader/.../impl/compat/discovery/` | `ForeignModRecognizer(s)` + `ForgeModCandidateFinder`；**`isLoadable()` 是「哪个生态能加载」的唯一开关** |
| 变换管线（H3） | `loader/.../impl/compat/transform/` | `TransformPhase` 五阶段；`HOOK_INJECTION` 空且位置**暂定** |
| 内核诊断 | `loader/.../impl/compat/diagnostics/` | `LoaderDiagnostics`、`TransformHashLog` |
| 报告渲染 | `tooling/load-report/` | `./run/report.sh` |
| 重映射缓存（T1.2） | `loader/.../impl/compat/remap/RemapCache` | 整批缓存；键覆盖所有输入 |
| 混淆版本夹具（T1.2） | `run/prepare-named.sh` / `run/smoke-named.sh` | ≤1.21.11 的 named 运行时冒烟 |
| **Quilt 元数据解析（T2.1）** | `loader/.../impl/compat/quilt/` | `QuiltModMetadata(Reader)`、`QuiltManifestArbiter`、`QuiltJson5`、`QuiltBuiltins` |
| **Quilt 依赖语义（T2.2）** | `loader/.../impl/compat/quilt/QuiltCompat` | `provides` 带版本 / `unless` / `load_type`；在 `ModResolver.resolve()` 入口调用 |
| **Quilt API 桥接（T2.3）** | `compat/compat-quilt/`（**独立模块**） | `org.quiltmc.loader.api.*` + `org.uniloader.compat.quilt.QuiltApi` |
| **Quilt 入口点（T2.4）** | `loader/.../impl/compat/quilt/QuiltEntrypoints` | 反射触发 `pre_launch`（内核不能编译期依赖 compat-quilt） |
| 验收脚本 | `run/` | `smoke.sh`、`smoke-foreign.sh`、`smoke-quilt.sh`、`smoke-named.sh`、`prepare-named.sh`、`fetch-quilt-metadata.sh`、`report.sh`、`make-broken-mod.sh`、`compare-transform-hashes.sh` |

## 3. 阶段 3 的任务（T3.1 起，见 AGENTS.md 第 10 节）

> 开工前先把 AGENTS.md 第 10 节的任务清单读一遍，那里的编号是真源。
> 下面只写**在阶段 1/2 之后必须重新审视**的点。

### 3.1 `mods.toml` 解析（T3.1）与 `@Mod` 扫描（T3.2）

- **先拉真实数据，再写解析器**（阶段 2 的教训）：`./tooling/mod-matrix/fetch-mods.sh neoforge`
  已经能拿到 7 个真实 NeoForge mod；`run/mods-foreign/` 里就有真 `mods.toml`。
- 解析器**不要**产出内核的 `LoaderModMetadata`——它不是 Fabric 模组，硬套会让
  `ModResolver` 拿它去和 Fabric 模组比版本区间，语义不对。
  阶段 2 的 `QuiltModMetadata` 之所以能套，是因为 Quilt 的清单语义与 Fabric 同源。
  **NeoForge 的 `mods.toml` 不同源**（依赖用 Maven 区间、有 `mandatory`/`ordering`、
  一个 jar 可声明多个 mod），建议走 `compat/compat-api` 的统一 `ModRecord` 那条路。
- `mods.toml` 的坑：键里含冒号/点号时**不能用点路径查**（FML 的既有行为）。

### 3.2 多加载器仲裁（T3.5）

- 同一个 mod id 同时存在 fabric/forge/neoforge 构建时按优先级只留一个。
  优先级表要**可配置**，并把决定写进 `ul-mods.txt` 供用户改选。
- 别忘了 Quilt：现在 `ForeignModRecognizers.isLoadable("quilt") == true`，
  仲裁时 Quilt 也算「能加载的一家」。

### 3.3 验收矩阵

- `tooling/mod-matrix/matrix.yml` 的 `neoforge` / `forge` 段已有真实 slug 与实测可用性
  （`matrix-resolved.json`）。**别预填「应该能用」**。
- 阶段 3 的验收建议加 `run/smoke-discovery.sh`：四家 mod 同装，产出 `discovery-report.txt`，
  断言每家的生态/id/版本/入口类都在报告里，且依赖冲突检测正确。

## 4. 红线（每次改代码前默念）

1. 绝不把 mixin `required` 改 false / 降级 `defaultRequire`；注入失败必须显式报错 + 降级。
2. 绝不合并多份 patch 过的 MC 字节码。
3. 绝不静默吞异常；catch 必须记录 mod id + 类 + 方法 + 原因。
4. 版本号一律走 `gradle.properties`；**判定依据用证据（有没有 mappings），不用版本号比较**。
5. 状态只有 OK / DEGRADED / FAILED。
6. per-mod 特例只能进 `compat/quirks/`。
7. 内核 fork 改动必须注释 `UniLoader fork patch (T3.x)`，且收敛在 `impl/compat/` 或构建脚本。
8. **新增内核代码必带单测**（阶段 2 的密度：4 份套件 / 25 个用例）。
9. **任何改动「Mixin 之前」管线的提交，必须跑一次哈希对拍**（`run/compare-transform-hashes.sh`）。
   做法见本文件第 6 节（用 `git worktree`，不要 `git stash`）。
10. **每个新子系统都要配一个「故意失败」的对照组**，照 `run/make-broken-mod.sh` 的模式。
11. **LGPL 的兼容层必须独立 jar**：`compat-neoforge/`、`compat-forge/`、`hooks-*` 都不得
    shade 进内核 jar。阶段 3 引入 `compat-api` 之外的模块时，先看 `docs/LICENSES.md`。

## 5. 坑清单（阶段 2 新增，都会咬人）

| 编号 | 事实 | 适用条件 |
|---|---|---|
| **G16** | gson 的 `setLenient(true)` 支持 JSON5 注释（`//`、`/* */`、`#`）与无引号键名/单引号字符串，**但不支持尾逗号**（报 `Expected name`） | 解析任何「JSON5 风格」的清单（`mods.toml` 不是 JSON，但 `neoforge.mods.toml` 之外还有别的 json5 来源时同理） |
| **G17** | 入口点元数据的 `adapter` 为 null 会让**整个模组**在 instantiate 阶段失败：`Could not find adapter 'null' (mod xxx!)`。Fabric 侧的字面量是 `"default"` | 任何别家生态的入口点解析；单测验「解析对不对」验不出这个，必须真跑一次 |
| **G18** | `ForgeModCandidateFinder` 作为候选查找器**先于** `ModDiscoverer` 运行，会把别家 jar「认领」掉；被认领的 jar 到不了按清单解析那条路 | 每次「让某个生态从『只识别』升级成『能加载』」时都要改 `ForeignModRecognizers.isLoadable`，否则解析代码写了也不会被走到，而日志还一切正常 |
| **G19** | `mktemp -d` 在本机受限环境下会被删除保护拦下（`SAFE_DELETE_INVALID_PATH: embedded drive prefix is not allowed`），脚本在收尾 `rm -rf` 时**静默中断**，断言少打几条 | 任何脚本里要临时目录，用仓库内路径（`build/xxx-tmp`），不要用 `/tmp` |
| — | 26.3 上标称 quilt 的 25 个 jar 里只有 1 个真带 `quilt.mod.json`；QSL 最新只到 1.21 | 想用「某生态的模组」做验收前，先解压确认清单文件真的在里面（见 `run/smoke-quilt.sh` 的自检） |
| **G1–G4** | 命名空间相关的四处门闸 | **只在目标 MC 仍有混淆（≤ 1.21.11）时适用**；26.3 上是零重映射，不要打开 |
| **G12–G15** | 映射成员名裸名 / 反混淆 jar 缓存键不含映射 / MixinExtension 会改写带描述符的注解字符串 / 缓存命中仍有固定开销 | 换/改映射时 |
| **E1–E4** | 运行 Java 25 / 工作目录 = 游戏目录 / classpath 用 `run/loader-classpath.sh` / Gradle 锁 9.1.0 | 永远 |

## 6. 怎么做「Mixin 之前」管线的对拍（阶段 2 的做法，照抄）

不要 `git stash`（阶段 0 有一次 stash 后构建其实失败了，那份数据差点被当成有效样本）。
用 **git worktree** 签出对比版本，两边各自构建、各自跑冒烟、各自落盘哈希：

```bash
cd /d/work2/uniloader
# 1) 把当前工作提交掉（一个任务一个提交），记下 before 的 commit
git log --oneline -1

# 2) 在独立工作树里签出 before
git worktree add /d/work2/ul-before <before-commit>
cd /d/work2/ul-before
export https_proxy=http://127.0.0.1:7897 http_proxy=http://127.0.0.1:7897
./gradlew build 2>&1 | grep -E "BUILD"      # 必须确认 BUILD SUCCESSFUL
UL_GAME_DIR=/d/work2/uniloader/run/game UL_MODS_DIR=/d/work2/uniloader/run/mods \
  UL_EXTRA_JVM_ARGS="-Dul.transform.hashFile=D:/work2/ul-before/build/h-before.txt" \
  ./run/smoke.sh --matrix=baseline

# 3) after
cd /d/work2/uniloader
UL_EXTRA_JVM_ARGS="-Dul.transform.hashFile=D:/work2/uniloader/build/h-after.txt" \
  ./run/smoke.sh --matrix=baseline

# 4) 对拍
./run/compare-transform-hashes.sh /d/work2/ul-before/build/h-before.txt \
                                  /d/work2/uniloader/build/h-after.txt

# 5) 收尾
git worktree remove --force /d/work2/ul-before && git worktree prune
```

阶段 2 的结果：`before: 10756 个类 / after: 10756 个类 / 两次都有 10755`，
**10755 个类的变换产物逐字节相同**，只有 1 个类两侧各自离群（加载时序差异，正常）。

## 7. 未闭环项（不是遗漏，是留给后续阶段）

1. **真实客户端「进世界」未测**：需要显卡与 assets，只测了无头服务端。
2. **注入成功率统计未做**：需要 `mixin audit`（阶段 7 的 T7.1）。
3. **`HOOK_INJECTION` 阶段的位置是暂定的**：阶段 7 必须靠实测确定。
4. **`net.neoforged:fancymodloader` / Forge `eventbus` 的许可证**：GitHub 标
   `NOASSERTION`、POM 无声明。**阶段 4 的 T4.1 与阶段 8 的 T8.1 之前必须闭环**
   （见 `docs/LICENSES.md` 第 5 节）。
5. **Quilt 的插件系统 / 虚拟文件系统 / `gui.*`**：明确不做（方案列为 P2）。
   用到它们的 Quilt 模组会 `NoClassDefFoundError`，清单见阶段 2 报告第 3 节。
6. **`org.quiltmc.loader.api` 只实现了实测用到的面**：未实现的方法不存在
   （调用会 `NoSuchMethodError`），不是返回假数据。要补哪些，先用真实 jar 反编译确认。
7. **`load_type=if_required` 与 `unless` 只有单测覆盖**：真实模组里没找到用例。
8. **Quilt 在混淆版本（≤1.21.11）上的端到端未测**：26.3 之外没有可用的真·Quilt 模组。
9. **`compat/compat-quilt` 没有 checkstyle / spotless**：与 `compat/compat-api` 一致
   （根 `subprojects` 只配了 java-library + toolchain + 测试）。要收紧就一起收紧。

## 8. 提交与文档纪律

- 一个任务一个提交，中文，写清「做了什么 + 为什么 + 验收方式」；
- 内核改动标注 `UniLoader fork patch (T3.x)`，并收敛在 `impl/compat/`；
- 报告类产出放 `docs/stage3/`；生成物加「本文件由 … 生成，请勿手改」头 + 重新生成命令；
- **每完成一条任务就把结论回写到 `D:/work2/uniloader/AGENTS.md` 末尾**（追加，不覆盖），
  并在阶段结束时更新本文档为新一版的 HANDOFF；
- 不提交 `build/`、`run/game*/`、`run/mods*/`、任何 `.jar`
  （`.gitignore` 已覆盖 `run/mods-*/`，新增运行目录时记得同步）；
- **新增的独立模块要同步 `settings.gradle`、`NOTICE`（移植来源）与 `run/loader-classpath.sh`
  （运行时 classpath）**——阶段 2 的 `compat/compat-quilt` 就是这三处都要改。

---

## 附：阶段 3 开工前必须补做的调研（v1.6 勘误，2026-11-14）

1. **按目标 MC 版本实测 Forge/NeoForge 的映射形态，不要查版本表。**
   官方仓库一手结论：`1.20.1` 的运行时是 SRG（NeoForge 此分支 fork 自 Forge、
   高度兼容）；`1.20.2`（NeoForge）/ `1.20.4`（Forge）起切到 Mojang 官方名；
   `26.1+` 官方不混淆，official == intermediary == named。
   取证方式：`https://api.github.com/repos/{neoforged/NeoForge|MinecraftForge/MinecraftForge}/contents/build.gradle?ref=<分支>`
   看有没有 `srgPatches` / `createJoinedSRG` / `MCP_MAPPINGS`。
2. **1.20.1 与 1.20.2+ 要分开设计**：前者 NeoForge 与 Forge 可共用清单解析、
   语言适配器、`ModContainer`；后者注册表与事件系统已分叉，阶段 4 与阶段 8
   必须各自独立。T8.6「复用阶段 7 流水线」只适用于机械抽取部分。
3. 交叉核对 `AGENTS.md` 文末 v1.6 勘误与 `docs/REQUESTS.md` 末尾决策记录，
   别再沿用「自 1.20.5 起全用 mojmap」这个已被证伪的说法。
