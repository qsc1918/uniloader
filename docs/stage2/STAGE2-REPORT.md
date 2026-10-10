# 阶段 2 实测报告：Quilt 兼容

> 方案真源：`D:/work2/uniloader/AGENTS.md` 第 9 节（阶段 2 任务清单 T2.1–T2.6）。
> 结构照 `docs/stage1/STAGE1-REPORT.md`：每条任务写「做了什么 / 为什么 /
> 可复现的验收命令 / 实测结果 / 踩到的坑」。
> 本文件是**人写的报告**，不是生成物；数字全部来自本机实测，命令都贴在正文里。

---

## 0. 一句话结论

**Quilt 模组在 MC 26.3 上真的被加载了**：真实 Quilt 模组 simple-voice-chat
（`voicechat-quilt-2.6.24+26.3.jar`）与 11 个真实 Fabric 模组混装，无头服务端
完成启动，`./run/smoke-quilt.sh` **PASS 11/11**；该模组的入口点跑起来了
（`[voicechat] Compatibility version 20` → `Voice chat server started at port 24454`），
它调用的 `org.quiltmc.loader.api` 是我们提供的桥接。

同时必须说清楚**一件做不到的事**：方案原文写的验收
「QSL + 5 个 Quilt mod + 10 个 Fabric mod 同装」**在 26.3 上不存在**——
QSL 最新只到 MC 1.21，且 26.3 上标称 quilt 的 25 个 jar 里只有 1 个真带
`quilt.mod.json`。证据与替代验收见第 7 节。

四条冒烟路径全绿：纯 Fabric **7/7**、别家生态混装 **10/10**、崩溃对照 **10/10**、
**Quilt 混装 11/11**。仓库单测 **114 → 139 个全绿**（新增 4 份套件 / 25 个用例）。
「Mixin 之前」的字节码管线**逐字节对拍通过**：两次都出现的 10755 个类，变换产物完全相同。

---

## 1. T2.1 移植 quilt.mod.json 解析器

### 做了什么

| 件 | 位置 | 作用 |
|---|---|---|
| `QuiltModMetadata` | 内核 `impl/compat/quilt/` | 解析产物，实现内核的 `LoaderModMetadata` |
| `QuiltModMetadataReader` | 同上 | 流式解析器（不建中间树） |
| `QuiltEntrypointMetadata` | 同上 | 入口点（含 `adapter`） |
| `QuiltJson5` | 同上 | JSON5 尾逗号预处理 |
| `QuiltManifestArbiter` | 同上 | `fabric.mod.json` 与 `quilt.mod.json` 同时存在时**明确**选一份 |
| `QuiltLoadType` / `QuiltProvidedMod` | 同上 | `load_type` / 带版本的 `provides` |

**关键设计：让 Quilt 模组直接产出一个 `LoaderModMetadata`，而不是另起一套「统一元数据」。**
求解器、mixin 引导、入口点调用、诊断全都只认这一个接口，让它当 Quilt 模组的载体，
后面所有环节一行都不用改。`getType()` 返回 `"quilt"`，报告里才能区分生态。

**JSON5 没有引入新依赖。** 内核里那份 shaded gson 的 `setLenient(true)` 实测支持
注释（`//`、`/* */`、`#`）、无引号键名、单引号字符串——**但不支持尾逗号**
（`Expected name`）。所以只为尾逗号写了一个字符串感知的预处理器
（`QuiltJson5`），而不是引一个 JSON5 库：新增运行时依赖要过一遍许可证审查，
而真实 `quilt.mod.json` 用到的 JSON5 特性就这些。

### 为什么

阶段 1 的 `ForeignModRecognizers` 已经能「认」`quilt.mod.json`，但只打一句日志。
本阶段要把它从「识别」升级成「解析并产出候选」。

### 可复现的验收命令

```bash
cd /d/work2/uniloader
export https_proxy=http://127.0.0.1:7897 http_proxy=http://127.0.0.1:7897

# 4 份提交进仓库的真实夹具（QSL 顶层 / QFAPI base / qsl_base / voicechat）
./gradlew :loader:test --tests '*QuiltModMetadataReaderTest*'

# 批量：把真实 jar 里的 quilt.mod.json 全抠出来再解析一遍
./run/fetch-quilt-metadata.sh --extra
UL_QUILT_METADATA_DIR=D:/work2/uniloader/build/quilt-metadata \
    ./gradlew :loader:test --tests '*QuiltModMetadataReaderTest*'
```

### 实测结果

```
[quilt-metadata] 真实文件 52 份，解析失败 0 份
```

52 份全部来自真实发布物（QSL 一个 jar 里的 50 个嵌套模组 + 顶层 + simple-voice-chat），
**不是照着 schema 编的样本**。方案要求「20 个成功率 100%」，实测 52/52。

### 踩到的坑

1. **尾逗号**：gson lenient 支持注释却不支持尾逗号。第一版直接用 lenient 解析，
   JSON5 单测立刻红。
2. **清单仲裁不能靠注册顺序**。阶段 1 的 `ForeignModRecognizers` 是「先注册先赢」，
   发现层又只看 `fabric.mod.json`，合起来的行为是「谁碰巧先被扫到算谁」。
   现在规则收在 `QuiltManifestArbiter.choose()` 一处，并有单测钉住。
3. **发现层有三条路径**：目录、jar 文件、嵌套 jar 流。三条都要改，
   嵌套那条（`computeJarStream`）只能单向扫，得先扫一遍看有哪些清单、
   `rewind()` 之后再解析选中的那份。

---

## 2. T2.2 实现 provides 语义与求解器增强

### 做了什么

三件事，**都在求解之前做**，因为此时候选集已经齐了，可以问「谁在场」：

| 语义 | 做法 |
|---|---|
| `provides` 带版本 | 判定基准从「提供者的版本」换成「被声明的提供版本」（`ProvidedVersionDependency`） |
| `unless` | 「除非 X 在场，否则依赖 Y」→ X 在场就把这条依赖整条删掉 |
| `load_type` | 首次求解失败时，摘掉 `if_possible` / `if_required` 的模组重试一次 |

**刻意不改 SAT 求解器。** Fabric 的求解器本来就能处理 `provides`（按 id），
差别只在版本比对拿的是谁。改「喂给求解器的输入」比改求解器本身风险小一个数量级。

`quilt_loader` 内建模组是必须的：真实 Quilt 模组清一色声明
`depends: [{id: "quilt_loader", versions: ">=0.31.0-beta.4"}]`（QSL 是 `>=0.25.0`），
没有这个 id 在场，求解器会直接判「依赖无法满足」，一个 Quilt 模组都进不来。

### 为什么

QSL 声明「我提供 `fabric-api-base` 版本 `0.4.42+16948103d1`」，而 QSL 自己的版本是
`11.0.0-alpha.3+0.102.0-1.21`。依赖 `fabric-api-base >=0.4.0 <1.0` 的 Fabric 模组
若拿提供者的版本去比，`11.0.0` 落在区间外 → **误判失败**。这不是理论问题，
QSL 的 provides 就是这么写的（见 `loader/src/test/resources/quilt/qfapi-base.json`）。

### 可复现的验收命令

```bash
./gradlew :loader:test --tests '*QuiltCompatTest*'
```

### 实测结果

`QuiltCompatTest` 全绿，其中三条直接对应上面说的语义：

- `dependencyOnProvidedVersionIsJudgedByTheProvidedVersion`：先断言「直接比提供者版本
  应当不匹配」（前置条件），再断言处理之后判定为满足；
- `providedVersionOutsideRangeStillFails`：提供的版本真的不在区间里时，**不能**
  因为「有模组提供了它」就算满足；
- `realModIdIsLeftAlone`：真有一个叫这个名字的模组在场时，Fabric 原有判定是对的，
  一条都不改。

### 踩到的坑 / 与方案原文的差异

1. **`if_required` 按 `if_possible` 处理**。Quilt 的 `if_required` 是「只有别的模组
   真的依赖它时才加载」，Fabric 没有对应概念。近似成「能加载就加载」是**多加载**，
   比少加载安全（少加载会在运行时 `NoClassDefFoundError`）。差异已登记。
2. **`optional: true` 的 depends 降级成 `RECOMMENDS`**。Quilt 的语义是「只有它真的
   在场时才校验版本」，Fabric 的 kind 里最接近的是 `RECOMMENDS`（缺失不阻断、
   在场但不匹配会告警）。`SUGGESTS` 太弱（完全不校验），`DEPENDS` 太强（缺失即失败）。
3. **`group` 缺失时补空串而不是报错**。Quilt 的 schema 里 `group` 是必填，但真实
   世界里确实有漏写的 jar；缺了它不是致命的（只影响报告显示）。补空串而不是
   把启动打挂，但 `id` / `version` 缺失仍然硬报错。

---

## 3. T2.3 org.quiltmc.loader.api API 桥接

### 做了什么

新增独立模块 **`compat/compat-quilt/`**（Apache-2.0，保留 Quilt 版权头），
按 AGENTS.md 5.1 装两份东西：

- **API 面**：`QuiltLoader` / `ModContainer` / `ModMetadata` / `Version` /
  `LoaderValue` / `MountOption` / `ModLicense` / `ModContributor` /
  `entrypoint.{EntrypointContainer, GameEntrypoint, PreLaunchEntrypoint, EntrypointUtil,
  EntrypointException}` / `minecraft.MinecraftQuiltLoader`
- **实现**：`org.uniloader.compat.quilt.QuiltApi`，数据全部来自内核现有的
  `FabricLoaderImpl` / `ModContainerImpl`，**不另存一份**（两份数据迟早不一致，坑 G8）。

**范围不是拍脑袋定的**：拿真实 Quilt 模组反编译，看它到底调了哪些方法。
`voicechat-quilt-2.6.24+26.3.jar` 里引用 `org/quiltmc` 的只有 4 个类，用到的方法就这些：

```
QuiltLoader.isModLoaded(String) / isDevelopmentEnvironment() / getGameDir()
QuiltLoader.getModContainer(String) : Optional<ModContainer>
QuiltLoader.getEntrypointContainers(String, Class) : List<EntrypointContainer>
MinecraftQuiltLoader.getEnvironmentType() : net.fabricmc.api.EnvType   ← 注意返回的是 Fabric 的 EnvType
ModContainer.metadata() : ModMetadata
ModMetadata.version() : Version / .name() : String
Version.raw() : String
```

桥接按这个清单实现，**没有实现的接口方法宁可不写，也不返回假数据**。

### 为什么

Quilt 模组的字节码里直接引用 `org.quiltmc.loader.api.*`，没有这个包就
`NoClassDefFoundError`。Quilt Loader 是 Apache-2.0，所以可以和内核同处一个 jar；
但按 5.1 它是独立模块，所以做成独立 jar（`compat-quilt-0.1.0.jar`）。

### 依赖方向（这一节是重点）

内核是**组合构建**（`includeBuild('loader')`），Gradle 不允许被包含的构建反向依赖
包含它的构建。所以内核编译期**拿不到** `compat-quilt` 的类。

于是方向只能是 `compat-quilt -> 内核`：

- `compat-quilt` 用 `compileOnly` 引用内核 jar（运行时内核一定在场，不该打进这个 jar）；
- 内核里唯一需要 Quilt 类型的那一处（Knot 触发 `pre_launch`）收敛到
  `QuiltEntrypoints`，**走反射**，并且区分两种情况：
  「类不在 classpath 上」→ debug 并跳过（正常降级）；
  「方法调用炸了」→ error（不静默）。

### 可复现的验收命令

```bash
./gradlew :compat:compat-quilt:build
./run/smoke-quilt.sh          # 真实 Quilt 模组真的调到了这套 API
```

### 实测结果

- `compat-quilt-0.1.0.jar`（29 968 字节）构建通过；
- `./run/smoke-quilt.sh` PASS 11/11，日志里该模组走完了
  `Compatibility version 20` → `Loading plugins` → `Voice chat server started at port 24454`。

### 踩到的坑 / 明确没做的部分

1. **入口点 `adapter` 为 null 会让模组直接 setup 失败**。第一版
   `QuiltEntrypointMetadata.getAdapter()` 在没写 adapter 时返回 `null`，结果：
   `Could not find adapter 'null' (mod voicechat!)` → 整个模组在 instantiate 阶段挂掉。
   Fabric 侧用的字面量是 `"default"`（`V1ModMetadataParser:283`）。**这个坑是冒烟测出来的，
   不是想出来的**——单测只验了「解析出来的值对不对」，没验「内核能不能用这个值」。
2. **Quilt 的插件系统与虚拟文件系统明确不做**（方案原文列为 P2）。
   `CachedFileSystem` / `FasterFiles` / `ExtendedFiles` / `gui.*` / `plugin.*` /
   `config` 这些包**没有提供**；用到它们的模组会 `NoClassDefFoundError`。
   本阶段实测的真实模组没用到这些。
3. **`LoaderValue` 只有一个 `NULL` 实现**。`quilt.mod.json` 里的自定义段
   （`modmenu` / `multiconnect` / …）本阶段不保留，`ModMetadata.value(key)` 一律返回
   `LoaderValue.NULL`。那些段是给别的模组读的，不是给加载器读的；要支持得按段名逐个移植。
4. **`ModContainer.getClassLoader()` 是启发式**：内核没有给每个模组留 ClassLoader 的引用，
   这里返回调用时刻的上下文类加载器。写在注释里，不假装精确。

---

## 4. T2.4 Entrypoint 键名双注册

### 做了什么

- Knot 在 `preLaunch` 之后接着触发 `pre_launch`，**两个键各用各自的接口类型各触发一遍**
  （照 `QuiltLoaderImpl:1306-1307` 的顺序：先 Quilt 的 `pre_launch`，再 Fabric 的
  `preLaunch`）。不是把其中一个改写成另一个——接口不同，改写了就调不到方法。
- `QuiltEntrypointKeys`：键名别名表（`preLaunch` ↔ `pre_launch`），
  供 `QuiltApi.getEntrypointContainers()` 合并用；合并是安全的，因为按 `type` 过滤，
  接口不兼容的条目本来就不会被返回。

### 为什么

Quilt 模组把 pre-launch 入口点写在 `pre_launch` 键下、实现的是
`org.quiltmc.loader.api.entrypoint.PreLaunchEntrypoint`；Fabric 模组写在 `preLaunch` 下、
实现 `net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint`。两边都得触发。

**`main` / `client` / `server` 不需要映射**：Quilt 用的键名与接口和 Fabric 完全相同
（真实模组 `QuiltVoicechatMod implements net.fabricmc.api.ModInitializer`，键是 `main`）。
**`init` 也不需要映射**：那是 QSL 自己按名字去 `getEntrypointContainers("init", …)` 取的，
加载器不该在固定时机触发它。

### 可复现的验收命令

```bash
./gradlew :loader:test --tests '*QuiltEntrypointKeysTest*'
./run/smoke-quilt.sh      # 真实模组的 main 入口点跑起来了
```

### 实测结果

`QuiltEntrypointKeysTest` 全绿；冒烟日志里真实 Quilt 模组的 `main` 入口点执行完毕。

---

## 5. T2.5 Mixin 配置前缀兼容

### 做了什么

Quilt 注册 mixin 配置时会加 `"#" + modid + ":" + config` 前缀
（上游 `QuiltMixinBootstrap:113`，解码在 `MixinServiceKnot:222`）。
我们在**元数据层**就把前缀剥掉（`QuiltModMetadataReader.normalizeMixinConfigs`），
交给内核的 `FabricMixinBootstrap` 时已经是裸配置名，内核侧不需要认识这个前缀。

### 为什么

两种做法（内核解码前缀 / 元数据层规范化）里选后者：内核不该知道别家生态的私有编码，
而这层信息在解析时就已经有了（前缀里的 modid 就是当前模组的 id）。

### 可复现的验收命令

```bash
./gradlew :loader:test --tests '*QuiltModMetadataReaderTest*'   # stripsQuiltMixinConfigPrefix
./run/smoke-quilt.sh
```

### 实测结果

单测 `stripsQuiltMixinConfigPrefix` 通过；冒烟里真实 Quilt 模组的
`voicechat.mixins.json` 被注册，**断言「没有注入失败与重映射失败」通过**。

> 说明：本阶段只证明「带 mixin 的 Quilt 模组能注入成功」，**不能证明注入了多少**
> ——那需要 `mixin audit`（阶段 7 的 T7.1）。这一点与阶段 1 的结论一致。

---

## 6. T2.6 QSL 混装验收

### 做了什么

- `run/smoke-quilt.sh`：Quilt 与 Fabric **混装**冒烟。拼一个独立目录
  `run/mods-quilt-mixed`（11 个 Fabric + 1 个 Quilt），两边各自干净，
  不往 `run/mods` 里临时塞东西再撤（那是 T1.4 踩过的坑）。
  脚本会先自检「Quilt 目录里到底有没有真带 `quilt.mod.json` 的 jar」，
  没有就直接报错退出——否则跑出来的「Quilt 冒烟」走的其实是 Fabric 路径，等于没验。
- `run/smoke.sh` 新增 Quilt 断言：带 `quilt.mod.json` 的 jar 不能被当成
  「只识别不加载」、`quilt_loader` 内建模组在场、`quilt.mod.json` 里声明的模组 id
  真的出现在「Loading N mods」列表里。
- `run/preflight.sh`：冒烟从三条路径扩到四条。

### 可复现的验收命令

```bash
cd /d/work2/uniloader
./run/smoke-quilt.sh --fetch     # 首次会拉 Quilt 矩阵
./run/smoke-quilt.sh
```

### 实测结果

```
[UL] 混装目录 run/mods-quilt-mixed：Fabric 11 个 + Quilt 1 个（其中 1 个真带 quilt.mod.json）
  [PASS] 内核完成了模组发现（日志里有 Loading ... mods）
  [PASS] 服务端完成启动（Done）
  [PASS] 没有注入失败与重映射失败
  [PASS] 没有类/方法解析错误
  [PASS] 报告步骤读到了内核落下的诊断证据
  [PASS] 本次启动全绿，按设计不留下 mods-report.txt
  [PASS] 带 quilt.mod.json 的 jar（1 个）没有被当成「只识别不加载」
  [PASS] quilt_loader 内建模组在场（Quilt 模组的 depends 有着落）
  [PASS] quilt.mod.json 声明的模组真的进了模组列表（1/1）
  [PASS] Quilt 兼容层没有需要降级的地方
  [PASS] 服务端按超时被正常终止（无头冒烟预期行为）
PASS — 11 项断言全部通过（矩阵=quilt）
```

### 踩到的坑（这一条很关键）

**`ForgeModCandidateFinder` 会在发现层之前把 Quilt jar「认领」掉。**
认领的语义是「认出来了但本阶段加载不了」（阶段 1 的 `acceptForeign`）。
我一开始只在 `ModDiscoverer` 里加了「按 quilt.mod.json 解析」，结果冒烟仍然打出
「识别为 Quilt mod: …（尚未支持加载）」——因为 `ForgeModCandidateFinder` 作为候选查找器
**先**跑，被认领的 jar 根本到不了 `ModDiscoverer` 的解析路径。

修法：`ForeignModRecognizers` 新增 `isLoadable(ecosystem)`，
`ForgeModCandidateFinder` 对能加载的生态不再认领。
「哪个生态能加载」只有这一处 switch 说了算，阶段 4/8 改的也是它。
对应的 T1.4 单测按新行为更新（并写明这是**有意**的行为改变）。

---

## 7. 26.3 上的 Quilt 生态：实测结论

方案原文的 T2.6 验收是「QSL + 5 个 Quilt mod + 10 个 Fabric mod（含 fabric-api）同装，
能进世界」。**在 26.3 上做不到**，这不是实现问题，是生态现状。两条证据：

**证据一：QSL 在 26.3 上不存在。**

```bash
curl -fsSL "https://api.modrinth.com/v2/project/qsl/version" | python -c "
import json,sys
vs=json.load(sys.stdin)
print(sorted({x for v in vs for x in v.get('game_versions',[]) if x.count('.')>=1}))"
# ['1.18.2', '1.19', ..., '1.20.6', '1.21']    ← 最新是 1.21（2024-08），没有 26.3
```

顺带纠正清单里的两个 slug：`quilted-fabric-api` 与 `quilt-standard-libraries`
在 Modrinth 上都是 404，QSL 的 slug 就叫 **`qsl`**。

**证据二：26.3 上标称 quilt 的 jar，25 个里只有 1 个真带 `quilt.mod.json`。**
按「标称 quilt + 26.3」搜出 25 个 jar 逐个解压检查：

```
simple-voice-chat        | quilt.mod.json=True  | fabric.mod.json=False   ← 唯一一个真的
modmenu / lithium / jade / moreculling / betterf3 / midnightlib / ... (24 个)
                         | quilt.mod.json=False | fabric.mod.json=True    ← Fabric jar 被顺手勾了 quilt 兼容
```

**所以替代验收是这样的：**

| 方案原文要求 | 26.3 上的实际做法 | 结果 |
|---|---|---|
| QSL 混装、`provides` 让 Fabric 模组解析成功 | 用真实 QSL jar（1.21）的**真实 provides 数据**在解析/求解层验证 | T2.2 单测全绿（见第 2 节） |
| 5 个 Quilt mod 同装 | 26.3 上只有 1 个真·Quilt mod，就把它与 11 个 Fabric mod 混装 | PASS 11/11 |
| 能进世界 | 无头服务端完成启动（`Done`） | PASS |

**「查不到」本身就是结论。** 清单里绝不预填「应该能用」，`matrix.yml` 的 quilt 段
把上面这段结论和它的证据写在了注释里，`matrix-resolved.json` 记的是实测值。

---

## 8. 四条冒烟路径（全绿）

| 路径 | 命令 | 结果 |
|---|---|---|
| 纯 Fabric | `./run/smoke.sh --matrix=baseline` | **PASS 7/7** |
| 别家生态混装 | `./run/smoke-foreign.sh` | **PASS 10/10** |
| 崩溃对照 | `./run/make-broken-mod.sh && UL_MODS_DIR=run/mods-broken ./run/smoke.sh --matrix=broken --expect=crash` | **PASS 10/10** |
| **Quilt 混装** | `./run/smoke-quilt.sh` | **PASS 11/11** |

前三条与阶段 1 完全一致 —— 这是「加了一个生态没有把原来能跑的搞坏」的证据。

---

## 9. 与方案原文的差异（都有理由）

| # | 方案原文 | 实际做法 | 理由 |
|---|---|---|---|
| 1 | 从 quilt-loader 移植 `ModMetadataReader` / `V1ModMetadataReader` / `V1ModMetadataImpl`（整文件） | **按接口签名重写**，只移植语义 | 上游解析器直接产出 `org.quiltmc.loader.impl` 的内部类型，而内核需要的是 `LoaderModMetadata`，整文件拷过来反而要再包一层；重写还能把「抄来的语义」与「UL 自己的决策」分清 |
| 2 | 产出 `QuiltModMetadata extends UniMetadata` | 产出 `QuiltModMetadata implements LoaderModMetadata` | 内核只认这一个接口，另起一套统一元数据会让求解器/诊断全部要改 |
| 3 | 支持 JSON5（隐含要引 JSON5 库） | 用内核已有的 shaded gson + 一个尾逗号预处理器 | 实测 gson lenient 已覆盖注释/无引号键名/单引号；为尾逗号引一个运行时依赖要过许可证审查，不划算 |
| 4 | 扩展 `ModSolver` / `ModResolver:45-208` 支持 `provides` | **不改求解器**，在求解前改写输入 | Fabric 的求解器本来就能处理 provides（按 id），差别只在版本比对拿谁；改输入比改求解器风险小一个数量级 |
| 5 | `load_type` 的 `if_required` | 近似成 `if_possible`（多加载） | Fabric 无对应概念；多加载比少加载安全（少加载 → 运行时 `NoClassDefFoundError`） |
| 6 | `org.quiltmc.loader.api` 桥接放在内核 | 独立模块 `compat/compat-quilt/`（5.1 的要求） | 5.1/5.2 把它列为独立模块；且「移植来的代码」与「内核 fork patch」是两种东西，混在一个 jar 里以后单独换实现/单独审计都要先做一次考古 |
| 7 | （未提）内核如何拿到 Quilt 类型 | 内核 → compat-quilt 走反射，只此一处 | Gradle 不允许被包含的构建反向依赖包含它的构建 |
| 8 | T2.6 验收「QSL + 5 Quilt + 10 Fabric」 | 26.3 上只有 1 个真·Quilt mod；QSL 的 provides 改在解析层验证 | 见第 7 节的两条实测证据 |

---

## 10. 新增的坑（G16–G19）

| 编号 | 事实 | 处理 |
|---|---|---|
| **G16** | **gson 的 lenient 模式支持 JSON5 注释（`//`、`/* */`、`#`）与无引号键名/单引号字符串，但不支持尾逗号**（报 `Expected name`） | 尾逗号在进解析器之前用字符串感知的预处理器剥掉；别指望 lenient 兜住 JSON5 |
| **G17** | **入口点的 `adapter` 为 null 会让整个模组在 instantiate 阶段失败**（`Could not find adapter 'null' (mod xxx!)`）。Fabric 侧的字面量是 `"default"` | 别家生态的入口点元数据必须补默认 adapter。单测验「解析对不对」验不出这个——只有真跑一次才炸 |
| **G18** | **`ForgeModCandidateFinder` 会在发现层之前把别家 jar「认领」掉**；被认领的 jar 到不了 `ModDiscoverer` 的解析路径 | 认领语义是「认出来了但加载不了」，所以「能不能加载」必须有一处说了算（`ForeignModRecognizers.isLoadable`），能加载的生态不再认领 |
| **G19** | **`mktemp -d` 在受限环境下会被删除保护拦下**（`SAFE_DELETE_INVALID_PATH: embedded drive prefix`），脚本会在收尾时静默中断，断言少打几条 | 脚本里的临时目录改用仓库内路径（`build/xxx-tmp`），不用 `/tmp` |

---

## 11. 风险登记册更新

| ID | 原判断 | 更新后 | 依据 |
|---|---|---|---|
| **R14（新增）** | Quilt 生态在目标 MC 上可能没有可用模组，导致「混装验收」无法执行 | 影响中 / **概率高且已发生** | 26.3 上 25 个标称 quilt 的 jar 只有 1 个真带 `quilt.mod.json`；QSL 停在 1.21。缓解：`matrix.yml` 记录实测结论，验收改为「用真实数据在解析层验 provides + 用唯一可用的真·Quilt mod 做混装」 |
| **R15（新增）** | Quilt 的插件/虚拟文件系统未实现，用到它们的模组会 `NoClassDefFoundError` | 影响中 / 概率低（当前实测模组未用到） | 桥接范围按真实模组的实际调用面定；未实现的包在报告第 3 节逐条列出 |
| **R11** 兼容层代码无上游测试覆盖 | 影响中 / 概率中 | **不变** | 本阶段新增 4 份测试套件（解析器 / 依赖语义 / 仲裁 / 键名），夹具全部来自真实 jar |
| **R13** 混淆版本路径上的映射质量决定成败 | 影响高 / 概率中 | **不变** | 本阶段没有改动映射合并与反混淆缓存 |

---

## 12. 明确没有验收什么

1. **Quilt 模组在仍有混淆的 MC（≤1.21.11）上的行为**：入口是
   `UL_MC_VERSION=1.21 ./run/prepare-named.sh`，但 26.3 之外没有可用的真·Quilt 模组
   做端到端（QSL 停在 1.21，且它是给 Quilt Loader 写的，不是给 Fabric 内核）。
2. **Quilt 的插件系统 / 虚拟文件系统 / `gui.*`**：明确不做（方案列为 P2）。
3. **注入成功率**：只能证明「没有注入失败」，不能证明「注入了多少」——需要
   `mixin audit`（阶段 7 的 T7.1）。
4. **真实客户端「进世界」**：仍然只测了无头服务端（阶段 1 起就未闭环）。
5. **`load_type=if_required` 的精确语义**：近似成 `if_possible`，没有构造
   「有别的模组依赖它时才加载」的对照实验。
6. **`unless` 在真实模组上的行为**：只有单测覆盖，真实 Quilt 模组里没找到用例。
7. **`org.quiltmc.loader.api` 的完整面**：只实现了真实模组实测用到的部分 +
   能合理实现的相邻方法；未实现的方法**不存在**（调用会 `NoSuchMethodError`），
   不是返回假数据。

---

## 13. 复现整条链

```bash
cd /d/work2/uniloader
export https_proxy=http://127.0.0.1:7897 http_proxy=http://127.0.0.1:7897
export GRADLE_OPTS="-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897"

./gradlew build                                     # 编译 + 全部单测 + checkstyle/spotless + 许可证边界

./run/fetch-game.sh                                 # 拉原版 MC 服务端
./tooling/mod-matrix/fetch-mods.sh baseline
./tooling/mod-matrix/fetch-mods.sh quilt
./run/fetch-quilt-metadata.sh --extra

./run/smoke.sh --matrix=baseline                    # 期望 PASS 7/7
./run/smoke-foreign.sh                              # 期望 PASS 10/10
./run/make-broken-mod.sh
UL_MODS_DIR=run/mods-broken ./run/smoke.sh --matrix=broken --expect=crash   # 期望 PASS 10/10
./run/smoke-quilt.sh                                # 期望 PASS 11/11

UL_QUILT_METADATA_DIR=D:/work2/uniloader/build/quilt-metadata \
    ./gradlew :loader:test --tests '*QuiltModMetadataReaderTest*'   # 期望 52 份解析失败 0 份

./run/preflight.sh                                  # 交接自检（四条冒烟）
```
