# 阶段 1 实测报告（T1.1 – T1.6）

> 这份报告记录的是**真的跑过**的东西，不是「脚本已就绪」。
> 任务编号出自 `D:/work2/AGENTS.md`；目标改写出自 `docs/stage1/HANDOFF.md`（阶段 0 的结论改变了前提）。
> 实测时间：2026-10-07。被测对象：UniLoader 内核（`loader/` = fabric-loader 0.19.5 fork）+ 原版 Minecraft 26.3。

---

## 0. 结论摘要

| 任务 | 结论 | 硬证据 |
|---|---|---|
| T1.1 命名空间子系统正式化 | **完成** | 23 个单测（三个命名空间；含与 `MappingConfiguration` 逐一对拍）；冒烟 PASS 7/7；日志 `runtime=official modDist=official remap=false mojmap=true` |
| T1.2 重映射缓存与性能 | **降级为 P2**（HANDOFF 允许） | 理由与重新启用的验收命令见第 3 节；26.3 上「重映射未执行」已成为证据文件里的一条事实 |
| T1.3 扩展点 H1：语言适配器注册表 | **完成** | 8 个单测；冒烟 PASS 7/7 |
| T1.4 扩展点 H2：候选查找器插件化 | **完成** | 15 个单测；**7 个真实 NeoForge mod 与 11 个真实 Fabric mod 混装 PASS 10/10** |
| T1.5 扩展点 H3：变换管线化 | **完成** | **10756 个类的变换产物逐字节相同**（原始输出见 `T1.5-hash-compare.txt`）；13 个单测 |
| T1.6 诊断接入 | **完成** | 6 个单测；全绿路径 PASS 7/7、崩溃路径 PASS 10/10（报告里出现 `[FAILED]` + 阶段 + 原因） |

阶段 1 新增 **65 个单测**（内核 59 + 报告模块 6），仓库单测总数 **105 个全部通过**；
`./gradlew build` 通过（含 checkstyle / spotless / 许可证边界检查）。

---

## 1. 环境（可复现）

| 项 | 值 |
|---|---|
| 操作系统 | Windows 11（Git Bash） |
| 运行 Java | Zulu **25.0.3**（构建用 21；运行必须 ≥ 25） |
| Gradle | 9.1.0（wrapper 锁定） |
| 原版游戏 | Minecraft **26.3** 官方服务端（`./run/fetch-game.sh`） |
| Fabric 侧模组 | `run/mods/` 下 11 个真实 mod（`./tooling/mod-matrix/fetch-mods.sh baseline`） |
| NeoForge 侧模组 | `run/mods-foreign/` 下 7 个真实 mod（`./tooling/mod-matrix/fetch-mods.sh neoforge`） |
| 网络 | 全部走 `http://127.0.0.1:7897` 代理 |

NeoForge 矩阵实测可用性（MC 26.3，`fetch-mods.sh neoforge` 的输出）：
可用 7（jei / curios / jade / appleskin / sophisticated-backpacks / pipez / architectury-api），
不可用 3（create 无 26.3 发布；ferritecore / ftb-quests 下载 404）。
**「查不到」本身就是结论**——它不是「还没试」。

---

## 2. T1.1 命名空间子系统正式化

**做了什么**

- 新增 `impl/compat/namespace/NamespacePolicy`：**三个**命名空间（运行时 / 模组分发 / 游戏 jar）的唯一决策点。
  运行时与模组分发命名空间的三条路径：
  1. 未混淆版本（本机目标 26.3）：没有 mappings → `official`，**零重映射**；
  2. 混淆版本（≤ 1.21.11）：有 mappings → 按决策 D1 取 `named`，模组从 `intermediary` 重映射过来；
  3. 显式覆盖 `-Dul.namespace=auto|official|named|intermediary`，非法值**抛异常退出**，绝不猜一个默认值。
- **游戏 jar 的命名空间（门闸 G4）也收进来了**：`gameNamespace()` 原本散在
  `MinecraftGameProvider.initialize()` 里，自己又写了一遍「有没有 mappings」的判断，与
  `MappingConfiguration` 各算一套。而 G4 的那句 `if (!gameNs.equals(runtimeNs)) → 反混淆`
  依赖的正是这两个值——两处一旦漂移，表现是「游戏被莫名其妙地重映射了一遍」，日志里还看不出是谁改的。
  顺带把 G4 触发时的日志从 debug 提到 info：这是稀有且影响巨大的事件，应当在日志里一眼可见。
- `MappingConfiguration` 的两处命名空间计算改为调用它，删掉了原来的 `computeRuntimeNamespace()`。
- `FabricLoaderImpl.setup()` 的 `remapRegularMods` 从 `isDevelopmentEnvironment()` 改为
  `NamespacePolicy.remapFabricMods(decision)`（门闸 G1），并留 `-Dul.remapFabricMods=false` 总开关。
- `-Dul.namespace.strict=strict|lenient`（默认 strict）：命名空间不匹配的模组在 strict 下落 DEGRADED
  并写进报告，lenient 下只记日志——两者都不会静默跳过。

**为什么这么改**

上游把「是不是开发环境」混进了命名空间判定（`dev ? named : intermediary`），这正是门闸 G1 的由来。
UL 要同时喂 Fabric 与 NeoForge/Forge，必须让「运行时用什么名字」只有一个地方说了算，
否则兼容层与内核会各算一套、静默漂移。**判定依据是「有没有 mappings」这个证据，不是比较版本号**——
工程纪律第 4 条禁止硬编码 MC 版本号，fabric-loom 的 `MinecraftUnobfuscated` 判定用的也是同一个证据。

**验收**

```bash
./gradlew build
./gradlew :loader:test --tests '*NamespacePolicyTest*'
./run/smoke.sh --matrix=baseline
grep 命名空间判定 run/game/logs/smoke-baseline.log
```

实测：23 个单测通过；冒烟 PASS 7/7；日志为
`命名空间判定：runtime=official modDist=official remap=false mojmap=true（未检测到 mappings：该 MC 版本未混淆，内核与模组同处官方名，零重映射）`。

单测里有一组是**对拍**：`NamespacePolicy` 的判定与 `MappingConfiguration` 的真实返回值必须一致
（用真实的 `MappingConfiguration` + 一份最小的 tiny v2 映射文件驱动）。对拍覆盖
「必须一致」的两条（无 mappings → official；`fabric.runtimeMappingNamespace` 作为逃生舱优先于策略）
与「唯一有意分歧」的一条（有 mappings 且非 dev：上游 `intermediary`，UL 按 D1 取 `named`）。

---

## 3. T1.2 重映射缓存与性能 —— 降级为 P2（附重新启用的条件）

**决定：本阶段不实现，降级为 P2。** HANDOFF 第 6 节明确允许这个选择，理由在此记录完整。

**理由**

1. **26.3 上零收益。** 目标 MC 未混淆 ⇒ 内核与模组同处 `official` ⇒ `remap=false` ⇒ 缓存永远命中不了任何东西。
2. **该路径在 26.3 上无法执行，因此无法验收。** 工程纪律第 9 条要求每个阶段有可执行验收命令；
   T1.2 的验收（1.21.11 上的冷/热对比）需要：拉一份 1.21.11 游戏、准备**源命名空间（intermediary）**的
   remap classpath（门闸 G2）、放开 refmap 的 dev-only 分支（门闸 G3）。这是独立的一块工作，
   不属于阶段 1 的任何其它任务。
3. **缓存键比看上去难。** `RuntimeModRemapper` 用**一个** `TinyRemapper` 处理整批模组，
   并且把各模组的 class-tweaker **合并**成一个（`mergedClassTweaker`）再挂到 remapper 上。
   因此某个模组的重映射产物**取决于同批其它模组**。**按单个 mod 算缓存键是错的**，
   缓存键必须覆盖整批输入：所有模组 jar 的内容 + 合并后的 class-tweaker + 映射文件 +
   `modNs → runtimeNs` 这一对命名空间 + remap classpath。这是一条实测出来的约束，
   留给接手的人，免得踩一遍。

**26.3 上仍可验证的那一半（已落地）**

「应观察到重映射根本没发生」现在是**证据文件里的一条事实**，不是靠推断：

```bash
grep -c "timing	remap" run/game/.uniloader/kernel-diagnostics.txt   # 期望 0（该阶段从未进入）
grep "重映射未执行"      run/game/.uniloader/kernel-diagnostics.txt   # 期望有一条，附判定理由
```

**重新启用时的验收命令（照抄即可）**

```bash
UL_NAMESPACE=named ./run/smoke.sh --matrix=baseline          # 冷启动（清空 ~/.ul/cache）
rm -rf ~/.ul/cache && UL_NAMESPACE=named ./run/smoke.sh --matrix=baseline
UL_NAMESPACE=named ./run/smoke.sh --matrix=baseline          # 热启动
# 判据：热启动 remap 阶段耗时 ≈ 0，且总启动耗时与原生 Fabric 差距 < 15%
# 基线：D1 报告第 6 节（20 个 mod：冷 827 ms / 热 829 ms，≈41 ms/mod，无 cache）
```

---

## 4. T1.3 / T1.4 扩展点 H1 / H2

### 4.1 T1.3 语言适配器注册表（H1）

- 新增 `impl/compat/adapter/LanguageAdapterRegistry`：`default` / `quilt` / `neoforge` / `forge` 四个键的集中注册点。
- 新增 `DelegatingLanguageAdapter`：三个新键本阶段都是透明转发，行为与 `default` 逐字相同。
  阶段 2/4/8 用 `replace()` 换成真实现，**内核装配代码一行都不用改**。
- `installInto()` 只填表里还没有的键：模组自己声明的同名键仍然优先，上游那条
  「Duplicate language adapter key」硬错误必须保留。

**为什么**：扩展点应当在还不需要它的时候就建好，那时验收标准最简单——不破坏现有行为。
等到阶段 4 真要用的时候再改内核，就会同时引入「新功能」和「改装配」两类风险。

验收：`./gradlew :loader:test --tests '*LanguageAdapterRegistryTest*'`（8 个）+ 冒烟 PASS 7/7。

### 4.2 T1.4 候选查找器插件化（H2）

- `ModCandidateFinder` 从包级私有改为 `public`（它本来就是「候选从哪来」的唯一扩展点），
  新增 `ModCandidateConsumer.acceptForeign`：语义是「我认出它是哪一家的了，但本阶段还不能加载」。
- 新增 `impl/compat/discovery`：`ForeignModRecognizer`（纯识别接口）、
  `ForeignModRecognizers`（按清单文件认人的注册表）、`ForgeModCandidateFinder`（扫描并认领）。
- `ModDiscoverer`：被认领的路径不再走「按 `fabric.mod.json` 解析」；没有 `fabric.mod.json` 时
  先按识别器认一遍，认不出来才退回 `nonFabricMods`。
- 识别结果顺带过一遍 `NamespacePolicy.isCompatible`：在混淆版本上运行时是 `intermediary`
  而 NeoForge/Forge 全基于 mojmap，那种组合下即使阶段 4/8 做完了也跑不起来——现在说清楚比那时再排查便宜。
- **本阶段刻意只识别不解析**（不引入 TOML 解析、不产出候选）：解析与依赖语义是阶段 3 的事。

**验收（真实模组，不是造出来的样本）**

```bash
./tooling/mod-matrix/fetch-mods.sh neoforge      # UL_MODS_DIR=run/mods-foreign
./run/smoke-foreign.sh
```

实测 **PASS 10/10**：18 个 jar（11 Fabric + 7 NeoForge）全部起来，7 个别家模组逐个被识别，
`non-fabric` 警告 0 条。日志形态：

```
识别为 NeoForge mod: jei-26.3-neoforge-31.9.0.58.jar（依据 META-INF/neoforge.mods.toml；本阶段只识别不解析，尚未支持加载）
```

`run/smoke.sh` 里的别家识别断言是**自检**的（模组目录里真有别家的 jar 才断言），
所以纯 Fabric 矩阵下不需要额外开关。

---

## 5. T1.5 变换管线化（H3）—— 硬验收：逐字节不变

### 5.1 做了什么

把 `KnotClassDelegate.getPreMixinClassByteArray()` 里硬写的三步
（入口点补丁 → 读原始字节 → Fabric 内建转换）改成一条有序、可插拔的链：

| 新增（`impl/compat/transform`） | 作用 |
|---|---|
| `TransformPhase` | 阶段枚举，顺序即 `ordinal()`，一眼可见可断言 |
| `ClassTransformer` | 一步变换；返回 `null` 表示「我没意见」 |
| `TransformContext` | 不可变上下文，把上游散落的自由变量（类名 / env / dev / 命名空间 / allowFromParent）收成一个参数 |
| `TransformChain` | 阶段序 + `sortIndex` + 注册序；`freeze()` 之后晚注册是**硬错误** |
| `EntrypointPatchTransformer` / `RawReadTransformer` / `FabricBuiltinTransformer` | 三个内建步骤 |

设计移植自 Forbric 的 `TransformChain` / `TransformPhase`（Apache-2.0），但**去掉了 `predepends`
拓扑排序**：链上只有三个内建步骤，谁先谁后阶段名就说清了，加一层没有实现者的拓扑排序
只是多一段没被测过的代码（阶段 7 真需要时再加，那时它会带着真实测试用例进来）。

`HOOK_INJECTION` 阶段本阶段是空的，它的位置**是暂定的**——等阶段 7 有真实 patch 要注入时，
位置必须靠实测决定，不靠猜。

### 5.2 怎么把「逐字节不变」变成可执行的

冒烟测试证明不了这件事：顺序错了游戏照样能起，只是某个注入悄悄失效。所以先插桩再改结构：

- `-Dul.transform.hashFile=<路径>`（`impl/compat/diagnostics/TransformHashLog`）记录每个类经过
  「Mixin 之前」那一步之后的 SHA-256；没设开关时每类只多一次静态字段判断。
- `run/compare-transform-hashes.sh` 做对拍：**两次都出现过的类，哈希必须逐字节相同**；
  只出现在其中一次的类只记录不判定（哪些类被加载取决于启动时序）。

```bash
# before：固定顺序
UL_EXTRA_JVM_ARGS="-Dul.transform.hashFile=D:/work2/uniloader/build/t1.5-hash-before3.txt" \
  ./run/smoke.sh --matrix=baseline
# 改成管线后
UL_EXTRA_JVM_ARGS="-Dul.transform.hashFile=D:/work2/uniloader/build/t1.5-hash-after.txt" \
  ./run/smoke.sh --matrix=baseline
./run/compare-transform-hashes.sh build/t1.5-hash-before3.txt build/t1.5-hash-after.txt
```

### 5.3 实测结果

| 对拍 | 两次都有 | 仅一侧 | 哈希不一致 | 结论 |
|---|---|---|---|---|
| **before3（固定顺序）vs after（管线）** | 10756 | 0 / 0 | **0** | **PASS** |
| before3 vs after2（管线，第二次） | 10756 | 0 / 0 | **0** | PASS |
| after vs after2（噪声基线，同一份代码） | 10755 | 1 / 1 | 0 | PASS |
| before3 vs before（**离群样本**，同为固定顺序） | 10756 | 0 / **1161** | 0 | 见下 |

**关于那个离群样本。** 第一次采集的 before 跑出 11917 个类，比其余三次（10756）多 1161 个
（全是 `carpet.*`，是 Carpet 的 Scarpet 引擎在启动之后才懒加载的类）。这不是行为差异：
用同一份旧代码重跑（before3，构建确认成功）得到 10756，与管线版本**集合完全一致**。
差别来自 180 秒窗口内加载到了哪一步——`smoke.sh` 用 `timeout` 掐掉服务端，类加载的进度因此不是确定的。
这也是对拍脚本「只出现在一侧的类不计入判定」的原因，写在这里以免下次误读。

原始输出：`docs/stage1/T1.5-hash-compare.txt`。

### 5.4 踩到的坑（值得记住）

第一版 `TransformHashLog` 用「退出时一次性写文件」+ shutdown hook。结果**什么都没写出来**，
而且日志里连 hook 有没有跑都看不出来（shutdown 阶段的日志被已经停掉的日志系统吞了），白跑一轮。
改成**边跑边追加**之后立刻可见。教训：给「用信号掐掉」的进程做度量，不要依赖 shutdown hook。

---

## 6. T1.6 诊断接入

### 6.1 做了什么

- 内核新增 `impl/compat/diagnostics/LoaderDiagnostics`：阶段计时（discovery / resolve / remap /
  instantiate / lifecycle）、模组登记表、失败与降级记录、备注 → 落在 `.uniloader/kernel-diagnostics.txt`。
- `FabricLoaderImpl` 接入：`setup()` 给 discovery / resolve / remap 计时；`finishModLoading()`
  **先登记全部模组再实例化**（指认的前提是「id 在表里」）；`invokeEntrypoints` 逐个模组计时，
  失败时按入口点归到 `instantiate`（FAILED，模组没起来）或 `lifecycle`（DEGRADED，世界已能进），
  带上 mod id + 入口点定义 + 原因后再按 Fabric 语义继续向上抛。整批重映射失败会指认到批内每个模组。

  **与任务原文的一处偏差（有意）**：任务写的是「在 `Hooks.java:52-64` 的入口点调用处包裹埋点」。
  实际埋在 `FabricLoaderImpl.invokeEntrypoints()`——它是**唯一实现**，而调用点有 5 处
  （`Knot:156`、`Hooks:52/53/63/64`、`FabricTweaker:153`）。埋在唯一实现处等于一次覆盖全部，
  而埋在 `Hooks.java` 会漏掉 `preLaunch` 与 launchwrapper 那条路——那种漏法是「日志里少了几个入口点」，
  不会报错，只会让报告看起来更干净。
- 报告侧新增 `KernelDiagnosticsReader` + `ReportMain`：把证据灌进阶段 0 已经写好的
  `ModCatalog` / `Diagnostics`，渲染 `mods-report.txt`。`compat-api` 的 `Diagnostics` 新增 `record()`。
- `run/report.sh` 渲染入口；`run/make-broken-mod.sh` 造一个初始化必炸的对照模组；
  `run/smoke.sh` 新增 `--expect=boot|crash`。
  另新增 **`run/preflight.sh`**：一条命令确认交接状态健康（环境 + 依赖 + git + 构建 + 三条冒烟路径），
  并打印「已验收 / 未验收」清单。

**为什么中间隔一层文件**：内核是独立构建（`includeBuild('loader')`），不能反向依赖诊断模块，
而且内核 jar 还要过 proguard。分工是「内核记事实并落盘 / 报告模块读证据并渲染」——
多一层文件换来内核干净，顺带让**上一次崩溃的那次启动**也能被渲染出来。

三条硬约束编进了代码：本类任何方法都不抛异常（诊断不许破坏它所报告的那次启动）；
失败必须带 id + 阶段 + 原因，三者皆空时写出「这是内核的 bug」；FAILED 之前先把证据落盘。

### 6.2 验收

```bash
./run/smoke.sh --matrix=baseline                                   # 全绿路径
./run/make-broken-mod.sh
UL_MODS_DIR=run/mods-broken ./run/smoke.sh --matrix=broken --expect=crash
```

实测：

- 全绿路径 **PASS 7/7**（含「全绿时不留下 `mods-report.txt`——文件的存在本身就是信号」）。
- 崩溃路径 **PASS 10/10**。报告内容：

```
  [FAILED] UL Broken  (ul-broken)
      生态：fabric　版本：1.0.0　文件：ul-broken.jar
      阶段：instantiate
      原因：入口点 'main' 的 'ulbroken.Broken' 抛异常（java.lang.IllegalStateException: 故意抛的：…）

各阶段耗时（毫秒）
------------------
  discovery: 8
  resolve: 14
  instantiate: 0
```

**踩到的坑**：专服的 `Main` 会自己 catch 掉入口点异常、打一句 `Failed to start the minecraft server`
然后正常返回，**退出码是 0**。所以「有没有崩」不能看退出码，要看「有没有跑到超时」+
「日志里有没有指认到具体模组」。第一版断言就是栽在这里。

---

## 7. 阶段 1 之后仍然没有闭环的（不是遗漏，是留给后续阶段）

1. **真实客户端「进世界」未测**：需要显卡与 assets。阶段 2 起建议至少在一个图形环境补一次。
2. **注入成功率统计未做**：需要 `mixin audit`（阶段 7 的 T7.1 工具）。目前冒烟只能证明「没有注入失败」，
   不能证明「注入了多少」。
3. **T1.2 的混淆版本路径**（第 3 节）。
4. **`net.neoforged:fancymodloader` / Forge `eventbus` 的许可证**：GitHub 标 `NOASSERTION`，POM 无声明。
   阶段 4 的 T4.1 与阶段 8 的 T8.1 之前必须闭环（见 `docs/LICENSES.md` 第 5 节）。
5. **`HOOK_INJECTION` 阶段的位置**：本阶段为空，位置暂定；阶段 7 必须靠实测确定。

---

## 8. 一条命令复现整份报告

```bash
cd /d/work2/uniloader
export https_proxy=http://127.0.0.1:7897 http_proxy=http://127.0.0.1:7897
export GRADLE_OPTS="-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897"

./gradlew build                                                   # 编译 + 105 个单测 + checkstyle/spotless + 许可证边界
./run/fetch-game.sh && ./tooling/mod-matrix/fetch-mods.sh baseline
./run/smoke.sh --matrix=baseline                                  # 期望 PASS 7/7
./tooling/mod-matrix/fetch-mods.sh neoforge                       # UL_MODS_DIR=run/mods-foreign
./run/smoke-foreign.sh                                            # 期望 PASS 10/10
./run/make-broken-mod.sh
UL_MODS_DIR=run/mods-broken ./run/smoke.sh --matrix=broken --expect=crash   # 期望 PASS 10/10
```
