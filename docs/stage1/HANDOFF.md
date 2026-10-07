# 阶段 1 交接文档（从这里开始）

> 给**下一个人或下一个 AI**。读完这一份就能开工，不需要再翻聊天记录。
> 方案真源仍是 `D:/work2/AGENTS.md`（末尾有「阶段 0 结论回写 v1.1」）。
> 阶段 0 已完成并通过真实实测：`./gradlew build` 通过、13 个单测通过、
> 真实 MC 26.3 + 11 个真实模组冒烟 **PASS 6/6**（见 `docs/stage0/T0.4-real-smoke-report.md`）。

---

## 0. 一句话现状

内核（fabric-loader 0.19.5 的 fork）**已经能跑起来并加载真实的 Fabric 模组**；
阶段 1 要做的不是「让它能跑」，而是**把命名空间与扩展点正式化**，为后面接 Quilt / NeoForge / Forge 铺路。

## 1. 五分钟上手

```bash
cd /d/work2/uniloader

# 网络（本机必需；JVM 不读 env 代理，Gradle 要额外传 GRADLE_OPTS）
export https_proxy=http://127.0.0.1:7897 http_proxy=http://127.0.0.1:7897
export GRADLE_OPTS="-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897"

./gradlew build                # 全部模块 + 内核（组合构建 includeBuild('loader')）
./gradlew check                # 测试 + 许可证边界检查

# 跑一次真实游戏（服务端，无头）
./run/fetch-game.sh                          # 拉原版 26.3 服务端 + 114 库 → run/game/
./tooling/mod-matrix/fetch-mods.sh baseline  # 拉 11 个真实 mod → run/mods/
./run/smoke.sh --matrix=baseline             # 一条命令输出 PASS/FAIL
```

## 2. 环境硬要求（踩过就知道疼）

| 项 | 要求 | 为什么 |
|---|---|---|
| **运行** Java | **25**（`run/env.sh` 会自动挑） | MC 26.3 / NeoForge / Forge 都是 class file 69.0；用 21 会在 bundler 阶段抛 `UnsupportedClassVersionError`，报错在 loader 内部，极易误判成内核坏了 |
| **构建** Java | 21（toolchain 自动） | fabric-loader 的构建下限 |
| Gradle | **9.1.0**（wrapper 已锁定，别升） | 9.7 会让 fabric-loader 构建失败（`project(":").sourceSets` 语义变化） |
| Gradle 分发包 | 腾讯云镜像 `https://mirrors.cloud.tencent.com/gradle/gradle-9.1.0-bin.zip` | `services.gradle.org` 会 307 跳 GitHub Release，受限网络下不稳 |
| 代理 | `127.0.0.1:7897` | 用户指定；Python `urllib` 走代理会 `CERTIFICATE_VERIFY_FAILED`，所以仓库里的脚本一律用 `curl` |

## 3. 仓库地图（阶段 0 之后）

```
uniloader/
├── gradle.properties            ★ 唯一版本真源（禁止在 Java 里硬编码版本）
├── loader/                      fabric-loader fork（git subtree，上游 remote: fabric-upstream）
│   └── build.gradle             ← 唯一的 fork 改动：proguard 的 Java 8 rt.jar 路径兼容 JRE 布局
├── compat/compat-api/           L2 抽象层：ModStatus 三态 / DegradationPolicy / ModCatalog / Diagnostics
├── tooling/crash-attribution/   栈帧 → jar → mod 的归因（移植自 Forbric）
├── tooling/load-report/         mods-report.txt：每个 mod 的 OK/DEGRADED/FAILED
├── tooling/mod-matrix/          matrix.yml（四家 30 个 mod）+ fetch-mods.sh + matrix-resolved.json（实测可用性）
├── experiments/d1-namespace/    D1 实验（真实映射 + 真实 tiny-remapper，可重复执行）
├── run/
│   ├── env.sh                   Java/目录/jar 定位；**运行挑 Java ≥25**
│   ├── loader-classpath.sh      classpath 拼装：内核 + asm/mixin + 原版游戏
│   ├── fetch-game.sh            拉原版 MC（本仓库不分发 MC 字节码）
│   ├── server.sh / client.sh    exec 前 cd 到游戏目录；默认命名空间模式 official
│   └── smoke.sh                 启动 → 6 项断言 → PASS/FAIL
└── docs/
    ├── LICENSES.md              许可证核实（含两条影响分发的硬约束）
    └── stage0/                  T0.2 版本矩阵 / T0.3 D1 报告 / T0.4 真实冒烟报告
```

## 4. 先读这三份（各 5 分钟）

1. **`docs/stage0/T0.2-version-matrix.md`** —— 版本矩阵 + **「26.1 起 MC 不再混淆」的证据链**。
   这是本阶段最重要的事实，直接决定阶段 1 做什么、不做什么。
2. **`docs/stage0/T0.3-D1-namespace-experiment.md`** —— D1 实验（覆盖率 100%/100%）+ 四处门闸 G1–G4 + 适用条件。
3. **`docs/LICENSES.md`** —— 改依赖/打包前必看。

## 5. 已经证实的事实（不要重新论证）

| # | 事实 | 证据 |
|---|---|---|
| 1 | **MC 26.1 起不再混淆**，官方版本 json 无 `client_mappings`；26.3 上内核与 mod 天然同处 `official` 命名空间、**零重映射** | `docs/stage0/T0.2-version-matrix.md` 第 2 节（三处证据）+ 实测日志 `Mappings not present!` 且反混淆/重映射日志条数为 0 |
| 2 | **intermediary → named 的重映射机制可行且换得全**（类 100%、成员 100%），但**只在目标 MC 仍有混淆（≤1.21.11）时才需要** | `docs/stage0/T0.3-D1-namespace-experiment.md` |
| 3 | 内核**能加载真实模组**：MC 26.3 + 11 个真实 mod（含嵌套共 52 个）启动到 `Done`，客户端专属 mod 被正确跳过，依赖求解失败给出可读建议 | `docs/stage0/T0.4-real-smoke-report.md` |
| 4 | 许可证分层比方案原文更严：内核 jar 内含 **LGPL-3.0**（tiny-remapper）与 **EPL-1.0**（SAT4j）；**LGPL-2.1-only 不能与 LGPL-3.0 合并进同一 jar** | `docs/LICENSES.md` 第 3、4 节 |

## 6. 阶段 1 任务改写（T1.1–T1.6）

> 原方案是在「Fabric 生产环境跑 intermediary」的前提下写的。现在前提变了，
> 下表给的是**在新事实下的实际目标**与验收方式。任务编号与方案一致，方便回写。

### T1.1 命名空间子系统正式化 —— **目标改写**

- **原目标**：把内核运行时命名空间切到 mojmap，Fabric 侧 mod 重映射过去。
- **实际目标**：新增 `impl/compat/namespace/NamespacePolicy`，把「命名空间怎么定」收成一处，并明确三条路径：
  1. **未混淆版本（≥26.1，默认）**：runtime = `official`，**零重映射**，不设任何系统属性；
  2. **混淆版本（≤1.21.11）**：runtime = `named`，mod 从 `intermediary` 重映射（即 G1+G2+G3 全开）；
  3. 显式覆盖：`ul.namespace=official|named|intermediary`，非法值必须报错退出（`run/server.sh` 已有这个校验）。
- **验收**：
  - `./run/smoke.sh --matrix=baseline` 仍 PASS（默认路径不能被改坏）；
  - 新增单测：给定「有无 mappings 文件 × 版本是否未混淆」，`NamespacePolicy` 的判定与
    `MappingConfiguration.computeRuntimeNamespace()` 的结果**逐一对拍**（这是防止两处逻辑漂移的关键）；
  - 用 `-Dul.namespace=official` 与不设属性两种方式跑，日志与行为一致。
- **注意**：不要为了「统一」而在 26.3 上强行打开重映射——那会引入 G2/G3 的全部风险却毫无收益。

### T1.2 重映射缓存与性能

- **目标**：`RuntimeModRemapper` 的产物落盘到 `~/.ul/cache/remapped-<hash>`，命中则跳过。
- **验收**：在**混淆版本**（1.21.11）上做冷/热对比；在 26.3 上应观察到「重映射根本没发生」（耗时 ≈ 0）。
- **基线**：D1 报告第 6 节（20 个 mod：冷 827 ms / 热 829 ms，≈41 ms/mod，无 cache）。
- **注意**：本任务在 26.3 上**没有收益**，属于「为 ≤1.21.11 与跨命名空间 mod 预留」。如果阶段 1 决定只支持 26.3，
  可以把 T1.2 降级为 P2 并记录理由。

### T1.3 扩展点 H1：语言适配器注册表

- **目标**：在 `FabricLoaderImpl.setupLanguageAdapters()` 之外新增 `LanguageAdapterRegistry`，
  支持按生态注册 adapter（`default` / `quilt` / `neoforge` / `forge`）。
- **验收**：注册一个 no-op 的 `neoforge` adapter，不破坏现有行为（`./run/smoke.sh` 仍 PASS）。
- **位置**：`loader/src/main/java/net/fabricmc/loader/impl/util/DefaultLanguageAdapter.java` +
  `FabricLoaderImpl.setupLanguageAdapters()`（H1）。
- **注意**：这是后面实例化 `@Mod` 类最干净的挂载点（阶段 4 的 T4.2 会用）。

### T1.4 扩展点 H2：候选查找器插件化

- **目标**：`ModCandidateFinder` 改为可插拔列表；新增 `ForgeModCandidateFinder` 骨架（本阶段只识别不解析）；
  改造 `ModDiscoverer` 的 `nonFabricMods`：不再丢弃未知 jar，而是按注册的 finder 尝试识别。
- **验收**：往 `run/mods` 里放一个 NeoForge mod jar，日志出现「识别为 NeoForge mod: xxx（尚未支持）」
  而不是「未知文件」；同时**不能**因为放了这个 jar 就让本来能跑的 Fabric mod 起不来（回归 `./run/smoke.sh`）。
- **注意**：`nonFabricMods` 的当前行为在实测中已经被验证过（11 个 jar 里混入客户端专属 mod 时被正确分类），
  改这里要跑一次真实冒烟。

### T1.5 扩展点 H3：变换管线化

- **目标**：移植 Forbric 的 `TransformChain` / `TransformPhase` 设计，把
  `KnotClassDelegate.getPreMixinClassByteArray()` 里的固定顺序改成有序、可插拔、带 phase 与 sortIndex 的管线。
- **验收**：**现有 Fabric 行为逐字节不变**——用同一组 mod 做转换产物 hash 对比（这是硬验收，不是「看起来没变」）。
- **注意**：Forbric 的 `TransformChain` 是 Apache-2.0，移植时保留版权头（见 NOTICE 第二节）。

### T1.6 诊断接入

- **目标**：在 `Hooks.java` 的入口点调用处包裹诊断埋点（耗时、异常归因到 mod），
  把结果接到阶段 0 已有的 `Diagnostics` / `LoadReport`（`compat-api` + `tooling/load-report`）。
- **验收**：`mods-report.txt` 能列出每个 mod 的各阶段耗时；故意让一个 mod 初始化抛异常，报告里能看到
  `[DEGRADED]`/`[FAILED]` + 阶段 + 原因。
- **注意**：阶段 0 已经写好三态模型与报告渲染，这里只需要**喂数据**，不要另起一套。

## 7. 坑清单（都会咬人）

| 编号 | 事实 | 适用条件 |
|---|---|---|
| **G1** | `FabricLoaderImpl.setup()` 的 `remapRegularMods = isDevelopmentEnvironment()` 生产恒 false | 仅混淆版本（≤1.21.11） |
| **G2** | `RuntimeModRemapper.getRemapClasspath()` 缺 `fabric.remapClasspathFile` 直接抛异常；**该 classpath 必须是「源命名空间」（intermediary）的 MC+库**。只给 named 名时：类名照常重映射、**成员名静默不变** | 仅混淆版本 |
| **G3** | `refmap.json` 键是 `named:intermediary`；`@Inject(method=...)` 是常量，**实测 MixinExtension（static）也不改写它** → 要用内核侧 `MixinIntermediaryDevRemapper` + `mixin.env.remapRefMap=true`（把 dev-only 分支放开） | 仅混淆版本 |
| **G4** | `MinecraftGameProvider.initialize()`：gameNs=runtimeNs=named 会跳过反混淆，只有 MC 本体已是 mojmap 时才对 | 仅混淆版本 |
| **E1** | 运行必须 Java 25；构建 21 | 永远 |
| **E2** | 工作目录必须 = 游戏目录（`eula.txt`/`server.properties`/`world` 都是相对路径） | 永远 |
| **E3** | classpath 必须含 installer 依赖（asm/mixin），用 `run/loader-classpath.sh` | 永远 |
| **E4** | Gradle 锁 9.1.0；分发包用腾讯云镜像 | 永远 |
| **L1** | LGPL-2.1-only（compat-neoforge/forge）与 LGPL-3.0（内核内 tiny-remapper）**不能合并进同一 jar** | 阶段 4/8 引入 LGPL 模块时 |
| **L2** | 仓库禁止提交 MC 字节码与 mod 二进制（`run/game`、`run/mods` 已 gitignore） | 永远 |

## 8. 未闭环项（不是遗漏，是留给后续阶段）

1. **真实客户端「进世界」未测**：需要显卡与 assets。阶段 1 的 T1.1/T1.2 建议至少在一个图形环境补一次。
2. **注入成功率统计未做**：需要 `mixin audit`（阶段 7 的 T7.1 工具）。目前冒烟只能证明「没有注入失败」，
   不能证明「注入了多少」。
3. **`net.neoforged:fancymodloader` / Forge `eventbus` 的许可证**：GitHub 标 `NOASSERTION`，POM 无声明。
   **阶段 4 的 T4.1 与阶段 8 的 T8.1 之前必须闭环**（见 `docs/LICENSES.md` 第 5 节）。

## 9. 建议的执行顺序

1. **先跑一遍现有验收**（`./gradlew build` + `./run/smoke.sh --matrix=baseline`），确认起点是绿的；
2. **T1.1**（命名空间正式化）——它是后面所有事的地基，且改动可控；
3. **T1.6**（诊断接入）——早做，后面每个阶段都靠它看现象；
4. **T1.3 / T1.4**（H1 / H2 扩展点）——阶段 3/4 的前置；
5. **T1.5**（变换管线化）——改动面最大，放在最后，且必须用 hash 对拍守住「行为不变」。

每完成一条：**把结论回写到 `D:/work2/AGENTS.md` 末尾**，并在 `docs/stage1/` 留下可复现的命令与输出。

## 10. 提交与文档纪律

- 一个任务一个提交，中文，写清「做了什么 + 为什么 + 验收方式」；
- 内核改动标注 `UniLoader fork patch (T1.x)`，并收敛在 `impl/compat/`；
- 报告类产出放 `docs/stage1/`，生成物加「本文件由 … 生成，请勿手改」头 + 重新生成命令；
- 不提交 `build/`、`run/game/`、`run/mods/`、任何 `.jar`。
