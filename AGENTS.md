# 统一模组加载器（UniLoader）技术方案

> 目标：打造一个能同时加载 **Fabric / Quilt / NeoForge / Forge** 四家模组、且彼此无兼容问题的 Minecraft 模组加载器。
> 技术路线：以 **Fabric Loader** 为内核 fork 魔改（Mixin 优先、轻量灵活），在其上构建 **Forge/NeoForge 事件总线 → Mixin 注入** 的兼容层。
> 本文档不含代码，只做架构决策、实施路线与可派发的任务拆解。所有任务以 `T{阶段}.{序号}` 编号，可直接交给多个 AI 分步执行。

- 文档版本：v1.0
- 调研基准（工作区内实际代码状态，2026-10）：
  - `fabric-loader` 0.19.5（Apache-2.0）
  - `fabric-api`（Apache-2.0）、`fabric-loom`（MIT）
  - `quilt-loader` 0.31.0-beta.4（Apache-2.0，仍在活跃更新）
  - `quilt-mappings`（CC0）
  - `NeoForge`（LGPL-2.1-only，MC 26.3，Java 25）
  - `MinecraftForge`（LGPL-2.1-only，MC 26.3，Java 25）
  - `Minecraft-Forbric-mod-loader`（Apache-2.0，娱乐/半成品项目，MC 26.2）
- 暂定项目名：**UniLoader**（下文简称 UL）。包名沿用 `net.fabricmc.loader.*` 以保证 Fabric 生态二进制兼容。
- **AI特别提示：一定要善于使用git管理版本，方便错误时回退。**
---

## 目录

1. [十条核心判断（先看这个）](#1-十条核心判断先看这个)
2. [现状调研摘要](#2-现状调研摘要)
3. [总体大纲：先干什么、后干什么](#3-总体大纲先干什么后干什么)
4. [总体架构与关键决策](#4-总体架构与关键决策)
5. [仓库布局与许可证矩阵](#5-仓库布局与许可证矩阵)
6. [工程纪律（AI 执行红线）](#6-工程纪律ai-执行红线)
7. [阶段 0：地基与实验台](#阶段-0地基与实验台)
8. [阶段 1：Fabric 内核基线改造（命名空间切换）](#阶段-1fabric-内核基线改造命名空间切换)
9. [阶段 2：Quilt 兼容](#阶段-2quilt-兼容)
10. [阶段 3：统一发现层（四家 mod 都能被看见）](#阶段-3统一发现层四家-mod-都能被看见)
11. [阶段 4：NeoForge 运行时骨架](#阶段-4neoforge-运行时骨架)
12. [阶段 5：生命周期与事件投递](#阶段-5生命周期与事件投递)
13. [阶段 6：注册表兼容（最难）](#阶段-6注册表兼容最难)
14. [阶段 7：Hook 复刻流水线（把 846 个 patch 翻译成 Mixin）](#阶段-7hook-复刻流水线把-846-个-patch-翻译成-mixin)
15. [阶段 8：Forge 兼容](#阶段-8forge-兼容)
16. [阶段 9：子系统补齐](#阶段-9子系统补齐)
17. [阶段 10：稳定性、可观测性与降级](#阶段-10稳定性可观测性与降级)
18. [阶段 11：生态、构建与发布](#阶段-11生态构建与发布)
19. [测试与验收体系](#19-测试与验收体系)
20. [风险登记册](#20-风险登记册)
21. [附录 A：关键类与文件索引](#附录-a关键类与文件索引)
22. [附录 B：待核实清单](#附录-b待核实清单)

---

## 1. 十条核心判断（先看这个）

1. **Forbric 的失败不是"方向错"，而是"地基决策错"。** 它把 Forge 与 NeoForge 各自 patch 过的 MC 字节**合并成一个基底 jar**（约 1000 处同方法冲突，999 处取 NeoForge、5 处取 Forge），导致大量方法体/描述符与原版不一致，Mixin 锚点找不到 → 半应用 → 崩溃。它的架构立意（单一 ClassLoader + 统一模型 + 统一变换管线）是对的，可以借鉴；**"合并基底"这条路必须彻底放弃**。

2. **本项目正确的工作方式：MC 本体用一份原版字节码，所有 Forge/NeoForge 的 patch 以 Mixin/ASM 的形式"补"上去。** 绝不合并两份改过的 MC。

3. **绝不静默跳过注入失败。** Forbric 在 `forbric-kernel/src/main/java/.../mixin/ForbricMixinService.java`（499-524 行）用正则把 `"required":true` 改成 `false`、`defaultRequire` 改成 `0`，代码注释自己承认这导致"一半生效的 mixin 直到运行时崩溃才暴露"。UL 必须：**要么注入成功，要么显式报警并降级该 mod，绝不假装成功**。

4. **最大的隐藏障碍是命名空间，不是事件总线。** Fabric 生产环境把 MC 反混淆到 **intermediary**（`MappingConfiguration.computeRuntimeNamespace()`，非 dev 且有 mappings 时 → `intermediary`；`MinecraftGameProvider.java:344-367` 走 `GameProviderHelper.deobfuscate`），Fabric mod 也用 intermediary。而 NeoForge/Forge 生态自 1.20.5 起全部使用 **Mojang 官方名（mojmap）**。两套名字对不上，所有兼容工作都无从谈起。

5. **决策 D1（最重要）：把内核运行时命名空间统一到 Mojang 官方名（named/mojmap），让 Fabric 侧的 mod 在加载时被重映射到 named。** 理由：
   - Forge/NeoForge 侧（API jar、846/623 个 patch、AT 文件、coremod）全部基于 mojmap，是体量最大、最不可能改动的一方；
   - Fabric 侧**已有现成的重映射通道**（`RuntimeModRemapper` + `MappingResolver` + tiny-remapper），且 Fabric **开发环境本来就是跑在 named 上的**，说明这条路径被官方支持，只是生产环境默认关闭（`FabricLoaderImpl.java:210` `remapRegularMods = isDevelopmentEnvironment()`）；
   - 只需单向重映射（Fabric → named），而不是双向，工作量减半；
   - 兼容层的所有 Mixin 代码可以直接照抄 NeoForge patch 的**可读 Mojang 名字**书写，可维护性天差地别。
   - 代价：所有 Fabric mod 加载时需 remap（有磁盘 cache）、Mixin refmap 必须按 named 解析。**这是阶段 0 必须优先验证的实验，验证不过则回退到方案 B（内核用 intermediary，Forge/Neo 侧重映射）。**

6. **决策 D2：Forge/NeoForge 的 API 不重写，直接复用官方 jar。** 把 `neoforge-universal` / `forge-universal` 中 **`net/neoforged/**`、`net/minecraftforge/**` 及其资源（AT、mixin 配置）** 作为"内建 mod"注入，剔除其中包含的 MC 类。这样 mod 调用的是真正的 Forge/Neo API，行为语义 100% 一致，避免重造 API 时不可避免的行为漂移。

7. **决策 D3：事件总线本身不自己写。** 直接引入官方的 `net.neoforged:bus`（NeoForge 侧）与 Forge 的 `eventbus`（Forge 侧）jar，让它们原生运行。UL 的工作从"实现总线"变成"**在正确的时机把正确的事件对象 post 到总线上**"，工作量从"造轮子"变为"补 hook 点"。

8. **决策 D4：兼容顺序 = Fabric（基线）→ Quilt → NeoForge → Forge。**
   - Quilt 排第二：Quilt Loader 本身就是 Fabric Loader 的 fork，元数据（quilt.mod.json）和 API 高度同源，工作量最小（约 1-2 个模块），能快速验证"多生态共存"架构；
   - NeoForge 排第三：它的 FML 边界清晰（`net.neoforged.fml` 加载器 / `net.neoforged.neoforge` 游戏 API，通过 `IBindingsProvider` SPI 连接），社区活跃，patch 组织最规整；
   - Forge 排最后：它刚迁移到全新的 `BusGroup / EventBus.create / CancellableEventBus` 总线模型（与 NeoForge 的 `IEventBus` 已严重分叉），生态相对萎缩，且与 NeoForge 的语义重命名（`RegistryObject`→`DeferredHolder`、`ForgeHooks`→`CommonHooks` 等）已经很多。

9. **许可证上完全可行，但必须分层分 jar。** 内核（Fabric fork）Apache-2.0；Forge/Neo 兼容层因派生自 LGPL-2.1-only 的 patch，必须保持 **LGPL-2.1-only 并作为独立 jar 发布**，不得混入 Apache-2.0 内核 jar。两家都明确声明"通过普通 Java 引用机制使用其库的 mod 不被感染"，因此 Apache-2.0 内核 + LGPL-2.1 兼容层的组合在法律上是干净的（详见 [第 5 节](#5-仓库布局与许可证矩阵)）。

10. **不要重造 Fabric API 兼容层。** Sinytra Connector 做的是反向（Fabric mod 跑在 NeoForge 上，Forge 侧止于 1.20.1），Kilt 做的是我们这个方向但停在 1.21.1 且是"用户态 Fabric mod"（受限于类加载顺序、要重映射 SRG）。UL 的差异化是**内核级**实现——这既是最大优势，也是最大责任：一旦内核崩，全线崩。因此**可观测性与降级机制必须从阶段 0 就内建**，不能留到最后。

---

## 2. 现状调研摘要

### 2.1 Fabric Loader 0.19.5（内核，Apache-2.0）

**启动链路**
```
KnotClient/KnotServer.main
  → Knot.launch(args, EnvType)              Knot.java
  → Knot.init(): 环境解析 / classpath 归一 / createGameProvider
  → KnotClassLoaderInterface.create(...)     Knot.java:134-137
  → FabricLoaderImpl.load() → setup() → freeze()
      ├─ ModDiscoverer.discoverMods()       discovery/ModDiscoverer.java:88-218
      ├─ ModResolver.resolve()              discovery/ModResolver.java:45-208（SAT4j 求解）
      ├─ ModPrioSorter.sort / ModSolver.solve
      └─ RuntimeModRemapper.remap(...)      FabricLoaderImpl.java:254（仅 dev）
  → FabricLoaderImpl.loadClassTweakers()    FabricLoaderImpl.java:524-541（class-tweaker）
  → FabricMixinBootstrap.init(...)          launch/FabricMixinBootstrap.java:46-158
  → classLoader.initializeTransformers()    Knot.java:150
  → invokeEntrypoints("preLaunch")          Knot.java:156
  → provider.launch(cl)                     MinecraftGameProvider.java:500-521
```

**字节码转换总闸门**：`KnotClassDelegate.getPreMixinClassByteArray()`（452-479 行），顺序为
`provider.getEntrypointTransformer().transform(name)` → 原始字节 → `FabricTransformer.transform(...)`；
`canTransformClass()`（481-485）目前只排除 `org.apache.logging.log4j`。

**入口点机制**：`main` / `client` / `server` / `preLaunch` 全是**字符串字面量**（`Hooks.java:52-64`、`Knot.java:156`），解析层对 key **无白名单**（`V1ModMetadataParser.readEntrypoints():263-330`）。→ **新增自定义入口点类型（如 `forge:construct`）零成本**。

**语言适配器**：实现在 `util/DefaultLanguageAdapter.java`（不是 `impl.language`），注册在 `FabricLoaderImpl.setupLanguageAdapters():486-503`。→ 这是实例化 Forge `@Mod` 类最干净的挂载点。

**Mixin**：`FabricMixinBootstrap.init():85-98` 遍历所有 mod 的 `metadata.getMixinConfigs(side)` 注册；`MixinServiceKnot.getTransformers():226-233` **当前返回空集合**，是官方预留的扩展位。

**访问放宽**：已从 `net.fabricmc.accesswidener` 迁移到 `net.fabricmc:class-tweaker:0.3.0`（`build.gradle:116`）。

**对外稳定 API**：`net.fabricmc.api.*`、`net.fabricmc.loader.api.*`（含 `MappingResolver`、`ObjectShare`、`LanguageAdapter`）。其余 `net.fabricmc.loader.impl.*` 全部是内部实现。

**推荐 Hook 点（按整洁度排序）**

| # | 位置 | 用途 |
|---|---|---|
| H1 | `util/DefaultLanguageAdapter.java` + `FabricLoaderImpl.setupLanguageAdapters()` | 注册 `"forge"` / `"neoforge"` 语言适配器，负责 `@Mod` 类实例化与 `@SubscribeEvent` 扫描 |
| H2 | `discovery/ModCandidateFinder.java` + `ModDiscoverer.java:325`（`nonFabricMods`） | 新增 `ForgeModCandidateFinder`，让 `mods.toml` / `neoforge.mods.toml` 也产出统一 `ModCandidateImpl`，直接复用 SAT 求解 |
| H3 | `KnotClassDelegate.getPreMixinClassByteArray()` / `canTransformClass()` | 字节码层唯一总闸门，可插入 Forge 式转换链 |
| H4 | `MixinServiceKnot.getTransformers()`（当前空） | 官方预留的 Mixin transformer 扩展位 |
| H5 | `Hooks.java:45-69` + `Knot.java:156` | 生命周期锚点，追加 `invokeEntrypoints("forge:setup")` 等 |

### 2.2 NeoForge（LGPL-2.1-only，MC 26.3，Java 25）

- Mod 声明：`META-INF/neoforge.mods.toml`；`@Mod` 注解来自外部依赖 **`net.neoforged:fancymodloader:loader:12.0.8`**（`net.neoforged.fml.common.Mod`），不在本仓库内。
- 构造注入：`NeoForgeMod(IEventBus modEventBus, Dist dist, ModContainer container)`（`common/NeoForgeMod.java:535`）。
- 事件总线：`net.neoforged:bus:8.0.5`，`IEventBus` / `Event` / `ICancellableEvent` / `@SubscribeEvent` / `EventPriority` / `BusBuilder`；主总线 `NeoForge.EVENT_BUS`（`common/NeoForge.java:17`）。
- 生命周期事件：`net.neoforged.fml.event.lifecycle.*`（`FMLConstructModEvent` / `FMLCommonSetupEvent` / `FMLClientSetupEvent` / `FMLDedicatedServerSetupEvent` / `InterModEnqueueEvent` / `InterModProcessEvent` / `FMLLoadCompleteEvent`），**全部继承 `ParallelDispatchEvent`**，由 `ModWorkManager` + `ParallelTransition` 驱动。
- 注册表（最难）：`GameData.unfreezeData() → NewRegistryEvent → GameData.postRegisterEvents() 派发 RegisterEvent → GameData.freezeData()`；`DeferredRegister.register(IEventBus)` 内部是 `bus.addListener(this::addEntries)`；`RegisterEvent.register(...)` 最终落到原版 `Registry.register(...)`（`registries/RegisterEvent.java:46-62`）。`DeferredHolder extends Holder.Reference`，靠 `seenRegisterEvent` / `seenNewRegistryEvent` 保证时序。
- Patch：**846 个**，分布 `net/minecraft/world` 378、`client` 221、`server` 63、`data` 40、`core` 29、`com/mojang/renderpearl` 27、`network` 24。运行时只有 2 个 mixin（`BlockEntityTypeAccessor`、`MappedRegistryAccessor`）+ 5 个 coremod 类（已无 JS coremod / CoreModManager）。
- 高频被 patch 的原版类：`LivingEntity`、`Player`、`Entity`、`client.gui.Hud`、`client.Minecraft`、`ServerPlayer`、`ServerLevel`、`CreativeModeInventoryScreen`、`ItemStack`、`MinecraftServer`。

### 2.3 MinecraftForge（LGPL-2.1-only，MC 26.3，Java 25）

- Mod 声明：`META-INF/mods.toml`；`@Mod` 在 `javafmllanguage/src/main/java/net/minecraftforge/fml/common/Mod.java`。
- **事件总线已换新模型**：`net.minecraftforge.eventbus.api.bus.{BusGroup, EventBus, CancellableEventBus, MutableEvent, RecordEvent, InheritableEvent, Cancellable, SelfDestructing}`；`MinecraftForge.EVENT_BUS` 是 `EventBusMigrationHelper.INSTANCE`（`BusGroup.DEFAULT` 的包装）。监听器必须 **static**，通过 **LambdaMetafactory** 绑定（不是 ASM 生成类），见 `javafmllanguage/.../AutomaticEventSubscriber.java` 与 `EventBusSubscriberLogic`（125-407）。
- `FMLJavaModLoadingContext.get()` 已 `@Deprecated(forRemoval=true)`，转向构造注入。
- 子模块：`fmlloader`（发现/排序）、`fmlcore`（`ModLoader`/`ModList`/`ModStateManager`/`ModWorkManager`）、`javafmllanguage`（`@Mod` + `FMLModContainer` + `AutomaticEventSubscriber`）、`lowcodelanguage`、`mclanguage`。
- Patch：**623 个** + `forge-transformers`（`ForgeModTransformers` / `FieldToMethodTransformer` / `MethodRedirector`）。

### 2.4 Quilt Loader 0.31.0-beta.4（Apache-2.0，仍活跃）

- **Quilt 能直接读 `fabric.mod.json`**：`impl/fabric/metadata/FabricModMetadataReader.java` + `impl/plugin/fabric/StandardFabricPlugin.java`。反向（Fabric 读 quilt）**不存在**，全仓 grep "quilt" 零命中。
- `src/fabric/` 三个 source set（`api`/`impl`/`legacy`）提供完整 `net.fabricmc.*` 兼容层，`FabricLoaderImpl extends FabricLoader` 全量桥接到 `QuiltLoader`。
- `quilt.mod.json`：schema_version 仅支持 **1**，支持 JSON5；`quilt_loader` 块含 `id` / `group`（必填）/ `version` / `entrypoints` / `jars` / `language_adapters` / `load_type` / **`provides`** / `depends` / `breaks`（含 `unless`）/ `intermediate_mappings` / `required_features`。
- **`provides` 是关键**：QSL 通过 `"provides": ["fabric-crash-handler"]` 声明自己提供 fabric-api 子模块 id，让依赖 fabric-api 的 mod 在 Quilt 上解析成功。
- Quilt 独有：插件系统（在发现/求解阶段、classpath 建立前运行，可改写 mod 列表与元数据）、sat4j 求解器、整套虚拟文件系统（`QuiltJoinedFileSystem` / `QuiltMemoryFileSystem` / `QuiltZipFileSystem` + transform cache）、mixin 配置加 `"#modid:config"` 前缀。
- Entrypoint：Quilt 用 `pre_launch`，Fabric 用 `preLaunch`，`QuiltLoaderImpl.java:1306-1307` 两个都调。
- Mixin 版本与 Fabric 完全一致（`0.17.4+mixin.0.8.7` + mixin_extras `0.5.5`），ASM 同为 `9.10.1`。

> **重要提示**：从纯工程量看，"以 quilt-loader 为内核"能免费获得 Fabric 兼容（它已原生支持）。但 Quilt 仍是 beta、社区规模小、插件与虚拟 FS 层复杂度高。本方案仍按你的要求 **以 Fabric Loader 为内核**，但 Quilt 兼容阶段应**优先移植 quilt-loader 的成熟实现**（Apache-2.0，保留版权头即可合法移植），而不是自己重写一遍。

### 2.5 Forbric（Apache-2.0，MC 26.2 硬编码）

- 自述完成度：单 mod 加载无错 89.0%，进世界 91.8%，**完整混装仍崩**，"0.3.0 研究项目"。
- 机制：① 统一发现（按清单文件分类 jar）→ ② **合并基底 jar**（失败总根源）→ ③ 单一 `ForbricClassLoader` + 单一 `KernelLifecycle`（2958 行）→ ④ `GameEventMultiplexer` 补发合并后丢失的事件。
- **失败的具体原因（要在 UL 中避免）**：
  - 合并基底导致方法与描述符偏离原版，`mixin/MergedBaseAbsorbedCalls.java` / `MergedBaseCalleeSwaps.java` 就是在清点"消失的锚点"；debugify / EnhancedVisuals / MoogsEndStructures 因此失败。
  - `ForbricMixinService.java:499-524` 把 `required` 全改 false → **注入失败静默跳过**，半应用的 mixin 直到运行时才爆。
  - 130+ 个 per-mod 硬编码注入器（`Create*MixinAdapter` ×10、`CarpetMixinAdapter`、`IrisEarlyGamePathTransformer`…），完全不可扩展。
  - MC 版本硬编码 `FALLBACK_GAME_VERSION = "26.2"`，库列表、NeoForge 小版本分支全部写死。
  - ModLauncher 的 `ITransformationService` / `ILaunchPluginService` / `coremods.json` / 自定义 locator 直接跳过且无警告。

- **值得摘出来的资产（可直接移植/参考）**

  | 文件 | 价值 |
  |---|---|
  | `metadata/forge/ModsTomlParser.java` + `ForgeModEntry` / `FmlConfigElements` | 干净的 `mods.toml` / `neoforge.mods.toml` 解析器（night-config），并复刻了 FML 的取值语义（含"键含冒号/点号不能用点路径查"这个坑） |
  | `metadata/forge/ForgeVersionRange.java` / `ForgeVersionRangeTranslator.java` | Forge Maven 版本区间 ↔ SemVer 双向转换 |
  | `metadata/fabric/FabricModJsonReader.java` | 独立、无 Loader 依赖的 `fabric.mod.json` 读取器 |
  | `discovery/ModAnnotationScanner.java` | 用 ASM 按**描述符**匹配 `@Mod`，且能分辨 Forge / NeoForge 两个同名注解（不编译期依赖 NeoForge） |
  | `transform/TransformChain.java` + `TransformPhase` / `ClassTransformer` | 把 Fabric 固定管线与 Forge 的 `@SortingIndex` 合成**一个有序可插拔变换管线**（拓扑排序 + sortIndex 打破平局），设计干净 |
  | `access/AccessTransformerParser.java` / `AccessTransformer.java` | 独立的 Forge AT 语法解析与应用 |
  | `boot/GameEventMultiplexer.java` | "一侧 hook 从存活 hook 补发"的模式 + 单 owner 防重复触发——Forge↔Neo 事件映射的最小骨架 |
  | `boot/CrashAttribution.java` + `boot/KernelLoadReport.java` | 崩溃后从栈帧 jar 名反推嫌疑 mod、生成 `crash-analysis.txt`；统一的 OK/DEGRADED/FAILED mod 目录 |
  | `boot/MultiLoaderArbiter.java` | 同一 mod 的多 loader 构建只留一个，写 `forbric-mods.txt` 让用户改选 |

### 2.6 同类项目

| 项目 | 方向 | 现状 | 对 UL 的意义 |
|---|---|---|---|
| **Sinytra Connector** | Fabric mod 跑在 NeoForge/Forge 上 | 活跃；Forge 侧止于 1.20.1（beta.49） | 反向方案。其兼容性数据库与 mod 特例库值得参考；不要重复实现 |
| **Kilt** | Forge mod 跑在 Fabric 上 | 1.20.1–1.21.1，实验性，靠 SRG→Intermediary 重映射 | 同方向但**用户态实现**（作为 Fabric mod 运行），无法控制类加载与变换顺序。UL 是内核级，应避免其局限；代码复用前需核实其许可证 |
| **Forbric** | 取代加载器本身 | 半成品 | 见 2.5 |

---

## 3. 总体大纲：先干什么、后干什么

```
阶段 0   地基：仓库/许可证/构建/实验台/验收矩阵            ← 一切的前提
阶段 1   Fabric 内核基线改造（命名空间切到 mojmap）        ← 决定项目生死的技术验证
阶段 2   Quilt 兼容                                       ← 第一个胜果，验证多生态共存架构
阶段 3   统一发现层（mods.toml / neoforge.mods.toml）     ← Forge/Neo 的"看得见"
阶段 4   NeoForge 运行时骨架（API jar + @Mod + 总线）      ← Forge/Neo 的"跑起来"
阶段 5   生命周期与事件投递（FML 状态机复刻）
阶段 6   注册表兼容（unfreeze/register/freeze）           ← 全项目最难的一块
阶段 7   Hook 复刻流水线（846 个 patch → Mixin，分批）     ← 最长尾、可并行、可持续
阶段 8   Forge 兼容（新 BusGroup 总线模型）
阶段 9   子系统补齐（网络/配置/能力/流体/渲染/GUI）
阶段 10  稳定性、可观测性、降级
阶段 11  生态、构建与发布
```

**兼容顺序与理由**

| 顺序 | 生态 | 规模 | 为什么排在这里 |
|---|---|---|---|
| 1 | **Fabric** | 基线 | 内核本身就是它，必须先把基线跑稳（阶段 1） |
| 2 | **Quilt** | S/M | 与 Fabric 同源，元数据可移植、Mixin 版本一致、无命名空间问题；成本最低、见效最快，能提前暴露"多生态共存"的架构缺陷 |
| 3 | **NeoForge** | XL | FML 边界清晰（`fml` / `neoforge` 两层 + `IBindingsProvider` SPI），社区活跃，patch 组织最规整；是当前 Forge 系的主力 |
| 4 | **Forge** | XL | 总线模型刚换新（`BusGroup`/`EventBus.create`），与 NeoForge 分叉严重；生态相对萎缩；且大量 mod 已有 NeoForge 版本，优先级自然更低 |

**并行性**：阶段 7（Hook 复刻）在阶段 6 完成后即可长期并行推进，且是唯一可以无限横向扩展、分发给多个 AI 同时干的阶段。阶段 8 与阶段 7 的后半段也可并行。

---

## 4. 总体架构与关键决策

### 4.1 分层架构

```
┌─────────────────────────────────────────────────────────────────────┐
│  L4  游戏层：原版 Minecraft（单一字节码，绝不合并 patch 后的 MC）        │
├─────────────────────────────────────────────────────────────────────┤
│  L3  兼容层（分 jar 发布，LGPL-2.1-only）                             │
│      ├─ compat-neoforge    NeoForge API jar + @Mod 适配器 + 状态机    │
│      ├─ compat-forge       Forge API jar + @Mod 适配器 + 状态机       │
│      ├─ hooks-neoforge     846 个 patch 翻译出的 Mixin（分批）         │
│      ├─ hooks-forge        623 个 patch 翻译出的 Mixin（分批）         │
│      └─ compat-quilt       quilt.mod.json 解析 + org.quiltmc.api 桥   │
│                                                          (Apache-2.0)│
├─────────────────────────────────────────────────────────────────────┤
│  L2  统一抽象层 compat-api（Apache-2.0）                              │
│      UniModCandidate / UniMetadata / UniEventSink / UniRegistry       │
│      / HookRegistry / DegradationPolicy / Diagnostics                 │
├─────────────────────────────────────────────────────────────────────┤
│  L1  加载器内核 = fabric-loader fork（Apache-2.0）                     │
│      Knot / KnotClassLoader / ModDiscoverer / ModResolver(SAT4j)      │
│      / LanguageAdapter / Entrypoint / ClassTweaker / Mixin 引导       │
│      ★ 运行时命名空间改为 mojmap（决策 D1）                            │
│      ★ 新增 ForgeModCandidateFinder（H2）/ ForgeLanguageAdapter（H1）  │
├─────────────────────────────────────────────────────────────────────┤
│  L0  工具链：patch-analyzer（自动抽 hook 清单）/ mod-matrix（验收）     │
│            / installer / crash-attribution                            │
└─────────────────────────────────────────────────────────────────────┘
```

### 4.2 关键决策表

| 编号 | 决策 | 选定方案 | 备选与回退 |
|---|---|---|---|
| **D1** | 运行时命名空间 | **mojmap（Mojang 官方名）**；Fabric 侧 mod 加载时 remap 到 named | 方案 B：内核用 intermediary，Forge/Neo 侧 remap。若阶段 0 实验证明 Fabric 侧 refmap/性能不可行则回退 |
| **D2** | Forge/Neo API 来源 | **复用官方 universal jar**（只取 `net/neoforged/**`、`net/minecraftforge/**`，剔除 MC 类） | 自研 API（不推荐，行为漂移不可控） |
| **D3** | 事件总线 | **复用官方 bus jar**（`net.neoforged:bus`、`forge:eventbus`） | 自研 UniBus（仅在前者无法注入时考虑） |
| **D4** | 兼容顺序 | Fabric → Quilt → NeoForge → Forge | — |
| **D5** | MC 字节码来源 | **单一原版字节码 + Mixin 补 hook**，绝不合并 patch 后的 MC | Forbric 的合并基底（已证明失败） |
| **D6** | 失败处理 | **显式降级 + 报告**，绝不静默跳过 | 静默跳过（Forbric 教训，禁止） |
| **D7** | 内核选择 | fabric-loader fork，保留 `net.fabricmc.loader.*` 包名 | quilt-loader fork（Fabric 兼容免费，但 beta + 复杂度高） |
| **D8** | 许可证分层 | Apache-2.0 内核 / LGPL-2.1-only 兼容层，**分 jar 发布** | 混合进单一 jar（边界模糊，不推荐） |
| **D9** | 版本支持 | **单一 MC 版本起步**（建议 26.3），版本矩阵后置 | 一开始做多版本矩阵（Forbric 教训：硬编码尚可，多版本会爆炸） |
| **D10** | 特例处理 | per-mod 特例必须放在隔离的 `quirks` 模块，带开关与到期注释 | 散落在注入器里（Forbric 教训，禁止） |

### 4.3 Hook 点映射（Forge/Neo 事件 → 注入位置）

事件总线的翻译本质是三件事：

```
① 事件对象   —— 由官方 API jar 提供（D2），不改
② 监听器注册 —— 由官方 bus jar + AutomaticEventSubscriber 提供（D3），不改
③ 派发时机   —— ★ 这是 UL 唯一必须自己做的：把 NeoForge/Forge patch 里
                 "在原版方法中插入 hook 调用" 的位点，用 Mixin 复刻出来
```

即：`NeoForge patches/LivingEntity.patch` 中 `+CommonHooks.onLivingUpdate(entity)` 这一行，
在 UL 里变成 `hooks-neoforge` 模块中的一个 Mixin：

```java
@Mixin(LivingEntity.class)
public class LivingEntityHook {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void ul$neo$onLivingUpdate(CallbackInfo ci) {
        if (CommonHooks.onLivingUpdate((LivingEntity) (Object) this)) ci.cancel();
    }
}
```

**工作量从"实现事件系统"变成"机械地搬运注入点"**——这正是可以自动化 + 分派给多个 AI 并行的部分（阶段 7）。

---

## 5. 仓库布局与许可证矩阵

### 5.1 建议布局

```
uniloader/
├── LICENSE-Apache-2.0
├── LICENSE-LGPL-2.1
├── NOTICE
├── loader/                      ← fabric-loader fork，Apache-2.0
│   ├── HEADER（保留 Fabric 版权头）
│   └── src/main/java/net/fabricmc/loader/impl/compat/...   ← 新增
├── compat/
│   ├── compat-api/              ← Apache-2.0，统一抽象层
│   ├── compat-quilt/            ← Apache-2.0（移植自 quilt-loader，保留其版权头）
│   ├── compat-neoforge/         ← LGPL-2.1-only
│   ├── compat-forge/            ← LGPL-2.1-only
│   ├── hooks-neoforge/          ← LGPL-2.1-only（自 NeoForge patches 派生）
│   ├── hooks-forge/             ← LGPL-2.1-only（自 Forge patches 派生）
│   └── quirks/                  ← 混合许可，per-mod 特例（带开关）
├── tooling/
│   ├── patch-analyzer/          ← 扫描 patches 生成 hook 清单
│   ├── mod-matrix/              ← 兼容性测试矩阵
│   └── crash-attribution/       ← 移植自 Forbric
├── installer/
├── build.gradle / settings.gradle
└── gradle.properties            ← 版本矩阵，绝不硬编码在 Java 代码里
```

### 5.2 许可证矩阵

| 模块 | 许可证 | 依据 | 硬约束 |
|---|---|---|---|
| `loader/`（Fabric fork） | Apache-2.0 | 上游 fabric-loader LICENSE | 保留 Fabric 版权头与 NOTICE |
| `compat-api/` | Apache-2.0 | 自研 | — |
| `compat-quilt/` | Apache-2.0 | quilt-loader 是 Apache-2.0，可合法移植 | 保留 Quilt 版权头；NOTICE 注明移植来源 |
| `compat-neoforge/`、`hooks-neoforge/` | **LGPL-2.1-only** | NeoForge `README-LICENSE.md` 明示 SPDX `LGPL-2.1-only` | 独立 jar；保留 `LICENSE-LGPLv2.1`；文件头标注派生自 NeoForge patches |
| `compat-forge/`、`hooks-forge/` | **LGPL-2.1-only** | Forge `LICENSE.txt`：patches 目录著作权归 Forge 项目 | 同上；另需尊重"patches 目录内容著作权归 Minecraft Forge 项目"的声明 |
| `tooling/` | Apache-2.0 | 自研（crash-attribution 移植自 Forbric，Apache-2.0） | 保留 Forbric 版权头 |
| `quilt-mappings`（若使用） | CC0 | 上游 | 无需署名义务（但建议 ATTRIBUTION） |

**LGPL-2.1 合规要点**
1. **分 jar 是硬要求**：LGPL 库必须能与主程序分离、可被用户替换（LGPL-2.1 §6 的"重链接权"）。因此 `compat-*`、`hooks-*` 必须作为独立 jar 分发，不得 shade 进 Apache-2.0 内核 jar。
2. **必须提供 LGPL 部分的完整源码**（它就是开源仓库本身，天然满足）。
3. **两家都明确放弃"传染"**：NeoForge README 与 Forge LICENSE.txt 均声明"mods/项目通过普通 Java 引用机制使用其库，不受其许可约束"。因此 Apache-2.0 内核依赖 LGPL 兼容层是允许的。
4. **不得分发 Minecraft 反编译源码或 patch 产物**。仓库里只放构建脚本与 hook 清单，MC 源码由构建期拉取。
5. **Mixin / MixinExtras 的许可证需在阶段 0 核实**（见附录 B）。

---

## 6. 工程纪律（AI 执行红线）

以下条目从 Forbric 与 Kilt 的失败中提炼，**每个执行 AI 的 prompt 里都必须带上**：

1. **禁止把 `required` 从 `true` 改成 `false`，禁止把 `defaultRequire` 降级。** 注入失败必须显式抛出/记录并触发降级（见第 6 条）。
2. **禁止合并多份 patch 过的 MC 字节码。** MC 只有一份原版字节码，其余全靠 Mixin 叠加。
3. **禁止静默吞掉异常。** 所有 catch 块必须：记录 mod id + 类名 + 方法名 + 原因，并写入诊断报告。
4. **禁止硬编码 Minecraft 版本号、库列表、Forge/NeoForge 小版本。** 一律走 `gradle.properties` / 版本矩阵文件（Forbric 因硬编码 26.2 而在升级时一次打掉四个补丁）。
5. **禁止 per-mod 特判散落在通用注入器里。** 特例只能进 `quirks/` 模块，必须带：作用 mod id、触发条件、开关、移除条件注释。
6. **降级策略三态**：mod 状态只能是 `OK` / `DEGRADED`（部分功能不可用，但游戏能跑）/ `FAILED`（拒绝加载并给出可读原因）。不存在第四种"看起来成功"。
7. **不要重写 Forge/NeoForge API、不要重写事件总线。** 只做"搬运注入点"和"打通生命周期"。
8. **不要动 `net.fabricmc.loader.impl` 里与 Fabric 兼容无关的行为**，除非有明确理由；所有新增代码放 `impl/compat/` 或独立模块。
9. **每个阶段必须有可执行的验收命令与基准 mod 清单**（见第 19 节），没有验收就不算完成。
10. **提交前跑 `LoaderUtil.verifyNotInTargetCl()` 相关自检**：新增的 loader 类不能出现在 KnotClassLoader 可见路径下（这是 Fabric 内建的防护，改错地方会直接抛异常）。

---

## 阶段 0：地基与实验台

**目标**：把仓库、许可证、构建、实验台、验收矩阵搭起来，并完成**决策 D1 的可行性验证**（这是决定项目生死的实验）。
**规模**：M
**依赖**：无

### 任务清单

- **T0.1 建立仓库骨架**
  - Fork `fabric-loader` 到 `uniloader/loader/`，保留 git 历史（便于后续 merge 上游）。
  - 按 5.1 建立目录结构；写入 `LICENSE-Apache-2.0`、`LICENSE-LGPL-2.1`、`NOTICE`。
  - `NOTICE` 中至少声明：Fabric Loader / Quilt Loader / NeoForge / MinecraftForge / Forbric 的来源与许可。
  - **验收**：`./gradlew build` 通过；`LICENSE` 与 `NOTICE` 齐备。

- **T0.2 锁定版本矩阵**（写入 `gradle.properties`，不进 Java 代码）
  - MC 版本：建议 **26.3**（与 NeoForge/Forge 当前目标一致）。
  - Java：构建 21+，运行 25（与 NeoForge/Forge 对齐）。
  - 依赖对齐：ASM 9.10.1、Mixin `0.17.4+mixin.0.8.7`、MixinExtras 0.5.5、tiny-remapper 0.14.1、mapping-io 0.9.1、SAT4j 2.3.6、night-config（Forbric 用的 3.8.x）。
  - **验收**：单一文件可改全链路版本。

- **T0.3 ★ 决策 D1 可行性实验（最高优先级）**
  目标：证明"Fabric 内核跑在 mojmap 命名空间下，主流 Fabric mod 仍能正常加载"。
  步骤：
  1. 强制 `fabric.runtimeMappingNamespace=named`、`fabric.gameMappingNamespace=named`（`SystemProperties.java:28-30`），使 `MinecraftGameProvider.java:344` 的 `!gameNs.equals(runtimeNs)` 分支不成立，跳过 deobfuscate。
  2. 打开生产环境的 mod 重映射：`FabricLoaderImpl.java:210` 的 `remapRegularMods` 改为 `true`（或由新属性控制），让 `RuntimeModRemapper.remap()`（:254）在生产也执行。
  3. 打开 Mixin 的 named refmap 解析：确认 `mixin.env.remapRefMap`（`FabricMixinBootstrap.java:72` 附近）与 mod jar 内的 `refmap.json` 的 named 条目可用。
  4. 用 10 个主流 Fabric mod 实测（见 19.2 基线清单）。
  - **验收**：10 个 mod 全部加载、能进世界、Mixin 注入成功率 100%、无 `ClassNotFoundException`、启动耗时增加 < 30%（有 cache 稳态下）。
  - **回退**：若 refmap 或性能不可行 → 采用方案 B（内核 intermediary，Forge/Neo 侧重映射），并在文档中记录结论。

- **T0.4 建立实验台与冒烟脚本**
  - 用 `fabric-loom`（MIT）或 `ModDevGradle`（当前 NeoForge 推荐，2.0.142）搭建可一键启动的 client / server。
  - 提供 `run/client.sh`、`run/server.sh`、`run/smoke.sh`（启动 → 进世界 → 退出 → 解析日志断言）。
  - **验收**：一条命令可跑完冒烟并输出 PASS/FAIL。

- **T0.5 建立诊断骨架**（移植 Forbric 的 `CrashAttribution` + `KernelLoadReport`）
  - 输出 `crash-analysis.txt`：从栈帧 jar 名反推嫌疑 mod。
  - 输出 `mods-report.txt`：每个 mod 的 `OK/DEGRADED/FAILED`。
  - **验收**：故意放一个会崩的 mod，报告能正确指认。

- **T0.6 建立验收 mod 矩阵**（见第 19 节）
  - 收集 30-50 个代表性 mod（四家各若干），记录 id / 版本 / 来源 / 期望行为。
  - **验收**：`mod-matrix/` 下有清单与自动化下载脚本（不要提交 mod 二进制）。

- **T0.7 许可证核实**（附录 B）
  - 核实 Mixin / MixinExtras / Sinytra Connector / Kilt / night-config / SAT4j 的许可证。
  - **验收**：`docs/LICENSES.md` 列全。

---

## 阶段 1：Fabric 内核基线改造（命名空间切换）

**目标**：把 T0.3 实验固化为内核正式能力，同时为后续生态接入打好扩展点。
**规模**：L
**依赖**：阶段 0

### 任务清单

- **T1.1 命名空间子系统正式化**
  - 新增 `impl/compat/namespace/NamespacePolicy`，统一管理 `RuntimeMappingNamespace` / `GameMappingNamespace` / `DefaultModDistributionNamespace`。
  - 新增启动参数 `ul.namespace=strict|auto`，默认 strict（任何命名空间不匹配的 mod 直接报 `DEGRADED` 而非崩溃）。
  - **验收**：内核可在 intermediary 与 named 两种模式间切换，切换后 10 个基线 mod 均正常。

- **T1.2 重映射缓存与性能**
  - `RuntimeModRemapper` 的产物落盘到 `~/.ul/cache/remapped-<hash>`，命中则跳过。
  - 冷启动/热启动耗时对比报告。
  - **验收**：热启动耗时与原生 Fabric 差距 < 15%。

- **T1.3 扩展点 H1：语言适配器注册表**
  - 在 `FabricLoaderImpl.setupLanguageAdapters():486-503` 之外，新增 `LanguageAdapterRegistry`，支持按命名空间/生态注册 adapter（`default` / `quilt` / `neoforge` / `forge`）。
  - **验收**：注册一个 no-op 的 `neoforge` adapter，不破坏现有行为。

- **T1.4 扩展点 H2：候选查找器插件化**
  - `ModCandidateFinder` 改为可插拔列表；新增 `ForgeModCandidateFinder` 骨架（本阶段只识别不解析）。
  - 改造 `ModDiscoverer.java:325` 的 `nonFabricMods`：不再丢弃未知 jar，而是按注册的 finder 尝试识别。
  - **验收**：放入一个 Forge mod jar，日志中出现"识别为 NeoForge mod: xxx（尚未支持）"而非"未知文件"。

- **T1.5 扩展点 H3：变换管线化**
  - 移植 Forbric 的 `TransformChain` / `TransformPhase` 设计，把 `KnotClassDelegate.getPreMixinClassByteArray()` 里的固定顺序改成一个有序、可插拔、带 phase 与 sortIndex 的管线。
  - **验收**：现有 Fabric 行为逐字节不变（用同一组 mod 做转换产物 hash 对比）。

- **T1.6 诊断接入**
  - 在 `Hooks.java:52-64` 的入口点调用处包裹诊断埋点（耗时、异常归因到 mod）。
  - **验收**：`mods-report.txt` 能列出每个 mod 的各阶段耗时。

---

## 阶段 2：Quilt 兼容

**目标**：让 Quilt 模组与 Fabric 模组在同一游戏里混装。这是第一个完整胜果，也是"多生态共存"架构的验证。
**规模**：M（因为可以直接移植 quilt-loader 的 Apache-2.0 实现）
**依赖**：阶段 1

### 任务清单

- **T2.1 移植 quilt.mod.json 解析器**
  - 从 `quilt-loader/src/main/java/org/quiltmc/loader/impl/metadata/qmj/` 移植 `ModMetadataReader` / `V1ModMetadataReader` / `V1ModMetadataImpl`（Apache-2.0，保留版权头）。
  - 支持 JSON5；支持 `quilt_loader` 块全部字段，特别是 **`group`（必填）** 与 **`provides`**。
  - 产出 UL 内部的 `QuiltModMetadata extends UniMetadata`。
  - **验收**：随机 20 个 Quilt mod 的 `quilt.mod.json` 解析成功率 100%。

- **T2.2 实现 `provides` 语义与求解器增强**
  - Fabric 的 `ModResolver` 没有 `provides` 概念。需要扩展 `ModSolver`/`ModResolver.java:45-208`，让一个 mod 可以"提供"多个 id。
  - 支持 `breaks`（含 `unless`）、`load_type`（always/if_possible/if_required）。
  - **验收**：只装 QSL（不装 fabric-api），依赖 fabric-api 子模块的 Fabric mod 能解析成功。

- **T2.3 `org.quiltmc.loader.api` API 桥接**
  - 反向移植 quilt-loader 的 `src/fabric/` 思路：提供 `org.quiltmc.loader.api.{QuiltLoader, ModContainer, ModMetadata, MountOption, CachedFileSystem, FasterFiles, ExtendedFiles, LoaderValue, gui.*}`。
  - 提供 `org.quiltmc.loader.api.entrypoint.{GameEntrypoint, PreLaunchEntrypoint, EntrypointContainer, EntrypointUtil}`。
  - **验收**：编译一个引用 `org.quiltmc.loader.api` 的测试 mod 能正常调用。

- **T2.4 Entrypoint 键名双注册**
  - `pre_launch`（Quilt）与 `preLaunch`（Fabric）都要触发，参照 `QuiltLoaderImpl.java:1306-1307`。
  - `main` / `client` / `server` 共用，但需同时接受 `net.fabricmc.api.*` 与 `org.quiltmc.loader.api.entrypoint.GameEntrypoint` 两种父类型。
  - **验收**：Quilt mod 与 Fabric mod 的入口点都在正确时机触发。

- **T2.5 Mixin 配置前缀兼容**
  - Quilt 注册 mixin 配置时加 `"#"+modid+":"+config` 前缀（`QuiltMixinBootstrap.java:112`）。UL 的 `FabricMixinBootstrap` 需能识别并解码该前缀，或直接规范化去掉。
  - **验收**：带 mixin 的 Quilt mod 注入成功。

- **T2.6 QSL 混装验收**
  - **验收**：QSL + 5 个 Quilt mod + 10 个 Fabric mod（含 fabric-api）同装，能进世界，功能正常。

> **注意**：Quilt 的插件系统（`QuiltPluginManager`）与虚拟文件系统是本阶段**明确不做**的部分（列为 P2 可选）。若某个 Quilt mod 用了插件，标记为 `DEGRADED` 并给出可读原因。

---

## 阶段 3：统一发现层（四家 mod 都能被看见）

**目标**：让 Forge / NeoForge 的 mod 能被发现、解析、参与依赖求解，与 Fabric / Quilt mod 在同一张依赖图里。本阶段**不运行**它们。
**规模**：M
**依赖**：阶段 1（阶段 2 可并行）

### 任务清单

- **T3.1 移植 mods.toml 解析器**
  - 从 Forbric 移植 `metadata/forge/ModsTomlParser.java`、`ForgeModEntry`、`FmlConfigElements`（Apache-2.0）。
  - 同时支持 `META-INF/mods.toml`（Forge）与 `META-INF/neoforge.mods.toml`（NeoForge）。
  - 必须复刻 FML 的取值语义（含"键含冒号/点号不能用点路径查"这个坑）。
  - **验收**：50 个真实 mods.toml 解析成功率 ≥ 98%，失败项有明确报错。

- **T3.2 `@Mod` 注解 ASM 扫描**
  - 移植 `discovery/ModAnnotationScanner.java`：按**描述符**匹配注解，能区分 `net.minecraftforge.fml.common.Mod` 与 `net.neoforged.fml.common.Mod`（不编译期依赖任何一家）。
  - 同时扫描 `@EventBusSubscriber`（Forge）与 `@net.neoforged.neoforge.common.EventBusSubscriber`（NeoForge）。
  - **验收**：扫描 50 个真实 mod，准确率 100%。

- **T3.3 版本区间翻译**
  - 移植 `ForgeVersionRange` / `ForgeVersionRangeTranslator`：Forge Maven 区间 ↔ SemVer 双向转换。
  - 支持 Forge 的 `[4,)`、`[4,5)`、NeoForge 的 `[3,]` 等写法。
  - **验收**：与真实 FML 行为对拍 100 个区间表达式，结果一致。

- **T3.4 统一 ModCandidate**
  - 新增 `ForgeModCandidateFinder`（挂到 T1.4 的插件化查找器上），产出 `UniModCandidate`，带上 `ecosystem` 标签（FABRIC/QUILT/NEOFORGE/FORGE）。
  - 处理嵌套 jar（jar-in-jar）：Forge 用 JarJar（`META-INF/jarjar/` + `[[jars]]`），Fabric 用 `getJars()`（`NestedJarEntry`，`ModDiscoverer.computeNestedMods:473-507`）。统一到一套。
  - **验收**：同时放入四家 mod，依赖冲突检测正确，日志可读。

- **T3.5 多加载器仲裁**
  - 移植 Forbric 的 `MultiLoaderArbiter`：同一个 mod id 同时存在 fabric/forge/neoforge 构建时，按优先级（Fabric > Quilt > NeoForge > Forge，可配置）只留一个，并写入 `ul-mods.txt` 供用户改选。
  - **验收**：同装 JEI 的 fabric 版与 neoforge 版，只加载一个且日志说明原因。

- **T3.6 发现层报告**
  - 新增 `discovery-report.txt`：列出每个 mod 的生态、id、版本、入口类、依赖解析结果。
  - **验收**：四家 mod 混装时报告完整。

---

## 阶段 4：NeoForge 运行时骨架

**目标**：让一个 NeoForge 模组能被真正实例化、注册事件监听器、完成构造阶段。
**规模**：L
**依赖**：阶段 3

### 任务清单

- **T4.1 引入 NeoForge API jar（内建 mod）**
  - 构建期从 Maven 取 `net.neoforged:neoforge:<ver>:universal`，**只抽取 `net/neoforged/**` 与其资源**（`neoforge.mixins.json`、`META-INF/accesstransformer.cfg` 等），剔除 `net/minecraft/**` 与 `com/mojang/**`。
  - 抽取产物作为 UL 的"内建 mod"注入 classpath。
  - 同时引入 `net.neoforged:bus:8.0.5`、`net.neoforged:fancymodloader`（仅取 `net/neoforged/fml/**`，不取其启动器）。
  - **验收**：`net.neoforged.neoforge.common.NeoForgeMod` 类可被加载；`java -jar` 启动无 `NoClassDefFoundError`。

- **T4.2 实现 `neoforge` 语言适配器（H1）**
  - 实现 `NeoForgeLanguageAdapter implements LanguageAdapter`：在 `create()` 中
    1. 用 ASM 扫描结果找到 `@Mod` 类；
    2. 按构造函数签名注入 `IEventBus` + `ModContainer`（+ `Dist`）；
    3. 扫描 `@SubscribeEvent` / `@EventBusSubscriber`，通过官方 bus 的注册 API 挂上；
    4. 返回实例。
  - 注册为 `"neoforge"`（T1.3 的注册表）。
  - **验收**：一个 hello-world NeoForge mod 的 `@Mod` 类被实例化并打印日志。

- **T4.3 Access Transformer → Class Tweaker 翻译**
  - Forge/NeoForge 的 AT 语法（`public net.minecraft.world.item.ItemStack f_12345 # field`）→ Fabric 的 class-tweaker 规则。
  - 注意：Fabric class-tweaker 只支持"放宽"，Forge AT 也主要是放宽 + `mutable`，基本可映射；遇 `-f`（final 移除）等需单独处理。
  - 生效位置：`FabricTransformer.transform()` 的 `BuiltinTransform.CLASS_TWEAKS` 分支（`FabricTransformer.java:38,49-52`）。
  - **验收**：NeoForge 的 `accesstransformer.cfg` 全部规则生效，无 `IllegalAccessError`。

- **T4.4 ModContainer 与 ModList 适配**
  - 提供 `net.neoforged.fml.ModContainer` / `ModList` / `ModLoadingContext` 的 UL 实现，数据来自 `UniModCandidate`。
  - **验收**：mod 调用 `ModList.get().isLoaded("xxx")` 返回正确结果。

- **T4.5 Dist / 侧判定**
  - `Dist.CLIENT` / `Dist.DEDICATED_SERVER` 与 Fabric `EnvType` 的映射；`DistExecutor` 的等效实现。
  - **验收**：客户端专用代码在服务端不被加载（`@OnlyIn(Dist.CLIENT)` 等价物）。

---

## 阶段 5：生命周期与事件投递

**目标**：复刻 FML 的状态机，让 NeoForge mod 走完完整的生命周期。
**规模**：L
**依赖**：阶段 4

### 任务清单

- **T5.1 FML 生命周期状态机**
  - 复刻 `ModLoadingStage`：`ERROR → VALIDATE → CONSTRUCT → COMMON_SETUP → SIDED_SETUP → ENQUEUE_IMC → PROCESS_IMC → COMPLETE → DONE`，每阶段一个 `DeferredWorkQueue`。
  - 派发 `FMLConstructModEvent` / `FMLCommonSetupEvent` / `FMLClientSetupEvent` / `FMLDedicatedServerSetupEvent` / `InterModEnqueueEvent` / `InterModProcessEvent` / `FMLLoadCompleteEvent`。
  - **验收**：一个注册了全部生命周期监听器的测试 mod，日志顺序与原生 NeoForge 一致。

- **T5.2 ParallelDispatchEvent 语义**
  - NeoForge 的生命周期事件全部继承 `ParallelDispatchEvent`，由 `ModWorkManager` 的 `DrivenExecutor`（同步）+ `parallelExecutor`（异步）驱动。
  - UL 需保证：同步阶段在主线程、异步阶段可并发、`DeferredWorkQueue` 的延迟任务在正确阶段执行。
  - **验收**：`FMLCommonSetupEvent#enqueueWork` 提交的任务在下个阶段执行。

- **T5.3 挂到内核生命周期锚点（H5）**
  - 在 `Hooks.java:45-69`（`startClient`/`startServer`）与 `Knot.java:156`（`preLaunch`）追加派发点。入口点 key 是自由字符串，无需改解析器。
  - **验收**：NeoForge mod 的 `FMLClientSetupEvent` 在 Fabric mod 的 `ClientModInitializer` 之后的确定时机触发（顺序需在文档中明确定义）。

- **T5.4 InterModComms（IMC）**
  - 实现 IMC 的发送/接收（NeoForge 侧 API 已有，只需打通派发）。
  - **验收**：两个 mod 通过 IMC 通信成功。

- **T5.5 配置系统**
  - Forge/NeoForge 的 `ModConfig`（`net.neoforged.fml.config.ModConfig`）与 night-config 集成；`ConfigWatcher` 的文件监听。
  - **验收**：mod 生成并读取 `config/*.toml`。

---

## 阶段 6：注册表兼容（最难）

**目标**：让 `DeferredRegister` 注册的方块/物品/实体等正确落到原版注册表，并能存档、能出现在创造模式物品栏。
**规模**：XL
**依赖**：阶段 5

> 这是全项目技术难度最高的一块。原版 `MappedRegistry` 在 1.21+ 有 `frozen` 字段，frozen 后 `register` 会抛异常；NeoForge 通过 patch 让 `MappedRegistry extends BaseMappedRegistry` 从而获得 `unfreeze/freeze`。UL 不能改父类，必须用 Mixin 直接操作 `frozen` 字段。

### 任务清单

- **T6.1 `MappedRegistry` 解冻/冻结 Mixin**
  - 用 Mixin 给原版 `MappedRegistry` 加 `ul$unfreeze()` / `ul$freeze()`（`@Shadow` 字段写入）。
  - 提供与 NeoForge `GameData.unfreezeData()` / `freezeData()` 等价的内核 API。
  - **验收**：解冻后 `Registry.register` 不抛异常；冻结后抛（与原版一致）。

- **T6.2 注册时序引擎**
  - 复刻 `GameData.postRegisterEvents()` 的顺序：Attribute、DATA_COMPONENT_TYPE 优先 → 原版顺序 → modded 按字母序。
  - 派发 `NewRegistryEvent` → `RegisterEvent` → `ModifyRegistriesEvent` / `NewDatapackRegistryEvent`。
  - 通过 `ModLoader.postEventWrapContainerInModOrder(registerEvent)` 的等价实现，保证 mod 顺序。
  - **验收**：注册顺序与原生 NeoForge 的日志逐一比对一致。

- **T6.3 DeferredRegister / DeferredHolder 行为对齐**
  - `DeferredRegister.register(IEventBus)` 内部是 `bus.addListener(this::addEntries)` + `bus.addListener(this::addRegistry)`（`registries/DeferredRegister.java:315-320`），需保证两个 listener 都被正确触发。
  - `DeferredHolder extends Holder.Reference` 的延迟绑定语义（`seenRegisterEvent` / `seenNewRegistryEvent`）必须保持——否则会出现"拿到的是未绑定的空 Holder"。
  - **验收**：`DeferredHolder.get()` 在注册后返回正确对象；在注册前访问有明确报错而非 NPE。

- **T6.4 冻结失败回滚**
  - 复刻 `RegistryManager.revertToVanilla()` 与 `RegistrySnapshot`：注册失败时能回滚到干净状态并给出可读报告。
  - **验收**：故意注册非法 id，游戏给出清晰错误而非世界损坏。

- **T6.5 端到端验收**
  - **验收**：一个 NeoForge mod 用 `DeferredRegister` 注册 10 个方块 + 10 个物品 + 1 个创造模式标签，与 Fabric mod 同装，能进世界、能放置、能存档重开、物品栏顺序正确。

---

## 阶段 7：Hook 复刻流水线（把 846 个 patch 翻译成 Mixin）

**目标**：把 NeoForge 的 846 个 patch（以及后续 Forge 的 623 个）中"在原版方法里插入 hook 调用"的位点，机械地复刻成 Mixin。
**规模**：XL（可无限横向并行）
**依赖**：阶段 6

> 这是唯一适合"多 AI 并行"的阶段。关键是先做**自动化清单生成**，再按批次派发。

### 任务清单

- **T7.1 patch-analyzer 工具**
  - 扫描 `NeoForge/patches/**/*.patch`，对每个 `+` 行做模式匹配，抽取：
    - 形如 `NeoForge.EVENT_BUS.post(...)` / `BusGroup.DEFAULT.post(...)`；
    - `CommonHooks.*` / `ForgeHooks.*` / `NeoForgeHooks.*` 调用；
    - 被插入的原版类 + 方法 + 相对位置（HEAD/TAIL/某条指令前后）；
    - 是否 `cancellable`（返回值被用作 `if`）；
    - 涉及的参数（用于生成 Mixin 方法签名）。
  - 输出 `hooks-neoforge/manifest.yml`：每条含 `id / targetClass / targetMethod / at / cancellable / hookCall / priority / sourcePatch / sourceLine`。
  - **验收**：抽取覆盖率自检（能识别出 patches 中 ≥ 90% 的事件 post 点），遗漏项人工抽查补齐。

- **T7.2 优先级矩阵**
  - 按"多少 mod 依赖"排序，而不是按 patch 顺序。建议分档：
    - **P0（首批）**：实体 tick、玩家 tick、方块放置/破坏、实体加入/离开世界、伤害计算、死亡、物品使用、攻击实体、右键交互、掉落、服务端启动/停止、玩家登录/登出、世界加载/卸载、创造模式物品栏、客户端 tick、渲染 HUD。
    - **P1**：生物群系、结构生成、村民交易、附魔、冶炼、酿造、铁砧、经验、饥饿、药水、投射物、骑乘、刷怪。
    - **P2**：数据包、配方、进度、命令、网络包收发、粒子、声音、粒子效果、模型加载。
    - **P3**：其余（GUI、字体、截图、调试、数据包注册表…）。
  - **验收**：每档有明确的"完成度定义"（例如 P0 = 20 个基准 mod 全部通过）。

- **T7.3 分批实现（派发给多个 AI）**
  - 每批 20-40 个 hook，一个批次 = 一个 PR = 一组测试。
  - 每个 hook 的产出必须包含：Mixin 类 + 单元测试（用 Mixin audit / 反编译断言）+ manifest 条目标记 `implemented`。
  - **验收**：`mixin audit` 无失败；相关基准 mod 通过。

- **T7.4 Hook 冲突检测**
  - 同一个原版方法被多家（NeoForge + Forge）以及多个 Fabric mod 同时注入时，必须有确定性的顺序与冲突检测。
  - 移植 Forbric 的 `GameEventMultiplexer` 思路：同一注入点上的多家 hook 由**单一 owner** 统一调用，避免重复触发；单 owner 防重复。
  - **验收**：同装一个 NeoForge mod 与一个 Forge mod 都监听 `LivingTick`，两个监听器都被调用，且各只被调用一次。

- **T7.5 回归网**
  - 每次新增 hook 后跑全量基准 mod 冒烟。
  - **验收**：CI 中全绿才允许合并。

---

## 阶段 8：Forge 兼容

**目标**：让 Forge 模组（不是 NeoForge）也能跑。
**规模**：XL
**依赖**：阶段 5（可与阶段 7 后半段并行）

### 任务清单

- **T8.1 引入 Forge API jar**
  - 同 T4.1，但处理 `net/minecraftforge/**`。注意 Forge 的子模块划分：`fmlloader` / `fmlcore` / `javafmllanguage` / `lowcodelanguage` / `mclanguage`，需要都取 `net/minecraftforge/fml/**`。
  - **验收**：`net.minecraftforge.common.ForgeMod` 可加载。

- **T8.2 新事件总线模型适配（最大差异点）**
  - Forge 已迁移到 `net.minecraftforge.eventbus.api.bus.{BusGroup, EventBus, CancellableEventBus, MutableEvent, RecordEvent, InheritableEvent, Cancellable, SelfDestructing}`。
  - `MinecraftForge.EVENT_BUS` = `EventBusMigrationHelper.INSTANCE`（`BusGroup.DEFAULT` 包装）。
  - 监听器必须 **static** 且通过 **LambdaMetafactory** 绑定（`javafmllanguage/.../AutomaticEventSubscriber.java` + `EventBusSubscriberLogic:125-407`）。
  - 需实现 UL 版本的 `AutomaticEventSubscriber`：读 ASM 扫描到的 `@EventBusSubscriber`，按 `bus()` 选 `BusGroup`，用 LambdaMetafactory 绑定静态方法。路由：事件实现 `IModBusEvent` → mod bus，否则 `BusGroup.DEFAULT`。
  - **验收**：一个带 `@EventBusSubscriber` 的 Forge 测试 mod，static 监听器被正确调用。

- **T8.3 `FMLJavaModLoadingContext` 与构造注入**
  - Forge 侧仍大量使用 `FMLJavaModLoadingContext`（虽已 deprecated），需提供；同时支持新版构造注入。
  - **验收**：两种写法的 mod 都能跑。

- **T8.4 Forge 生命周期**
  - 同 T5.1，但用 `net.minecraftforge.fml.ModLoadingStage`（`fmlcore`）与 `ModStateProvider` 的映射。
  - **验收**：生命周期日志顺序与原生 Forge 一致。

- **T8.5 Forge 注册表**
  - 复用阶段 6 的引擎，适配 `RegistryObject` / `ForgeRegistries` / `RegistryManager`（Forge 侧命名）。
  - **验收**：`RegistryObject` 注册的物品正常。

- **T8.6 Forge patch 复刻（623 个）**
  - 复用阶段 7 的工具与流水线，扫描 `MinecraftForge/patches/`。
  - 注意 Forge 的注入点可能与 NeoForge 重叠 → 依赖 T7.4 的冲突检测。
  - **验收**：Forge 基准 mod 通过。

---

## 阶段 9：子系统补齐

**规模**：XL（每一项都可独立派发）
**依赖**：阶段 6 / 8（部分可提前）

| 编号 | 子系统 | 要点 |
|---|---|---|
| T9.1 | **网络通道** | NeoForge 的 `PayloadRegistrar` / `RegisterPayloadHandlersEvent` ↔ Fabric 的 `PayloadTypeRegistry` / `ServerPlayNetworking`。两边模型不同，需做双向桥。 |
| T9.2 | **能力系统** | NeoForge `capabilities/{BlockCapability, EntityCapability, ItemCapability, CapabilityRegistry, RegisterCapabilitiesEvent}` ↔ Fabric 的 `fabric-api` lookup/transfer API。注意 NeoForge 已彻底重写，与旧 `Capability` 不兼容。 |
| T9.3 | **流体与能量** | `net.neoforged.neoforge.fluids` / `energy` ↔ Fabric transfer API（`fabric-transfer-api-v1`）。 |
| T9.4 | **客户端渲染** | `RenderType` 注册、`IBakedModel` 扩展、HUD 层（`GuiLayerManager`）、`RegisterGuiLayersEvent`。Fabric 侧无统一等价物，需自建。 |
| T9.5 | **菜单/屏幕** | `MenuScreens` / `RegisterMenuScreensEvent` ↔ Fabric 的 client entrypoint 手动注册。 |
| T9.6 | **数据包与配方** | `AddReloadListenerEvent` / `RegisterRecipeSerializers` ↔ Fabric resource reload listener。 |
| T9.7 | **生物群系与世界生成** | `BiomeModifiers` / `ModifyRegistriesEvent` ↔ Fabric Biome API。 |
| T9.8 | **事件订阅的注解扫描缓存** | 全量 ASM 扫描的性能优化与 cache，避免每次启动重复扫全部 jar。 |

---

## 阶段 10：稳定性、可观测性与降级

**规模**：M
**依赖**：全程（从阶段 0 开始就应内建，此处做收口）

- **T10.1 三态降级策略落地**：`OK` / `DEGRADED` / `FAILED` 贯穿发现、解析、实例化、注入、生命周期五个阶段。
- **T10.2 崩溃归因**：栈帧 → jar → mod 的映射；`crash-analysis.txt` 中指出"最可能肇事的 mod"与"次可能"。
- **T10.3 启动顺序确定性**：所有并发（并行 mod 加载、并行注入）必须有确定性顺序，可复现。
- **T10.4 性能预算**：冷/热启动耗时、内存、Mixin 应用耗时，设阈值并在 CI 中回归。
- **T10.5 混装压力测试**：50+ mod 四家混装的长时间运行（进世界、跑 30 分钟、退出、重进、存档迁移）。
- **T10.6 明确"不支持"清单**：在文档中列出已知不支持的能力（如 Quilt 插件、ModLauncher `ITransformationService`、JS coremod），并在运行时给出清晰提示而非沉默。

---

## 阶段 11：生态、构建与发布

**规模**：M

- **T11.1 安装器**：产出可分发的 installer（类比 Fabric installer / NeoForge installer），处理 MC + 内核 + 兼容层的拼装。**不得分发 MC 字节码**。
- **T11.2 开发侧构建插件**：给 mod 开发者用的 Gradle 插件（基于 `fabric-loom` 或 `ModDevGradle`），支持声明"我要同时支持四家"。
- **T11.3 兼容性数据库**：类似 Sinytra Connector 的 compatibility DB，自动跑基准 mod 并发布结果。
- **T11.4 版本矩阵升级流程**：升级 MC 版本时的标准作业流程（重跑 patch-analyzer → diff hook 清单 → 处理失效 hook → 全量回归）。这是长期维护成本的核心，必须流程化。
- **T11.5 文档与贡献指南**：架构文档、hook 编写规范（含"如何把一个 patch 翻译成 Mixin"的教程）、许可证合规说明。

---

## 19. 测试与验收体系

### 19.1 分层

| 层 | 内容 | 频率 |
|---|---|---|
| L1 单元 | 解析器（mods.toml / quilt.mod.json / 版本区间 / AT）对拍测试 | 每次提交 |
| L2 注入 | `mixin audit` + 目标方法存在性断言（反编译比对） | 每次提交 |
| L3 冒烟 | 启动 → 进世界 → 退出，日志断言无 ERROR | 每次提交 |
| L4 功能 | 基准 mod 的具体行为断言（放置方块、打开 GUI、合成…） | 每批 hook |
| L5 压力 | 50+ 混装长时运行 | 每版本 |

### 19.2 基准 mod 清单建议（阶段 0 建立）

- **Fabric（10）**：fabric-api、Sodium（渲染 + 大量 mixin）、Iris、REI 或 JEI(Fabric)、ModMenu、Cloth Config、Architectury API、Traveler's Backpack 之类含方块/物品注册的、Carpet（极端注入）、Lithium（极端注入）
- **Quilt（5）**：QSL 全套（验证 `provides`）、2-3 个仅 Quilt 的 mod
- **NeoForge（10）**：NeoForge 本体测试 mod、JEI(Neo)、Create 或同类大型（DeferredRegister + 渲染 + 能力）、Curios/Trinkets 等价物、一个重度 `@EventBusSubscriber` 的 mod
- **Forge（5）**：JEI(Forge)、一个 DeferredRegister 全量使用的大型 mod、一个重度客户端渲染的 mod

> 选择原则：每个 mod 必须能**明确验证一类能力**，且尽量选择"注入最狠"的那批（Sodium/Carpet/Lithium）来压测 Mixin 管线。

### 19.3 关键验收命令（示意）

```bash
./gradlew build                 # 全模块编译
./runw smoke --matrix=baseline  # 基线冒烟（10 个 Fabric mod）
./runw smoke --matrix=quilt     # Quilt 混装
./runw smoke --matrix=neoforge  # NeoForge 混装
./runw smoke --matrix=forge     # Forge 混装
./runw audit                    # mixin audit + hook manifest 覆盖率
./runw report                   # 生成 mods-report / crash-analysis
```

---

## 20. 风险登记册

| ID | 风险 | 影响 | 概率 | 缓解 |
|---|---|---|---|---|
| R1 | **D1 命名空间实验失败**（Fabric 侧 remap 到 named 不可行） | 高（架构返工） | 中 | 阶段 0 优先验证；回退方案 B 已在决策表中备选 |
| R2 | **Hook 复刻工作量爆炸**（846+623 个 patch） | 高 | 高 | 用 patch-analyzer 自动化抽取；按 mod 依赖度分档；允许长期渐进 |
| R3 | **Mixin 注入点冲突**（Fabric mod 与 UL 的 hook 抢同一位置） | 高 | 高 | T7.4 单 owner 多路复用；注入优先级与顺序确定性；冲突检测告警 |
| R4 | Forge 与 NeoForge **语义分叉持续扩大** | 中 | 高 | 两家各自独立 adapter；不做统一抽象，避免抽象泄漏 |
| R5 | **MC 版本升级打掉全部 hook** | 高 | 高 | T11.4 标准化升级流程；hook manifest 带 sourcePatch 便于 diff |
| R6 | **LGPL 合规边界模糊**（兼容层 shade 进内核） | 高（法律） | 低 | 分 jar 是硬约束；CI 中加许可证检查（禁止 Apache jar 内含 LGPL 类） |
| R7 | **性能不可接受**（全量 remap + 大量 mixin） | 中 | 中 | remap 落盘 cache；ASM 扫描 cache；性能预算进 CI |
| R8 | **上游变更**（Fabric Loader 升级导致 fork 冲突） | 中 | 中 | 保留 git 历史；内核改动尽量收敛到 `impl/compat/`；定期 rebase |
| R9 | **Quilt 生态萎缩导致投入浪费** | 低 | 中 | Quilt 兼容成本本来就低（阶段 2 规模 M），沉没成本可控 |
| R10 | **MC EULA / 分发合规** | 高（法律） | 低 | 不分发 MC 字节码与反编译源码；installer 只做拉取与拼装 |

---

## 附录 A：关键类与文件索引

### Fabric Loader（内核）
| 用途 | 路径（相对 `fabric-loader/`） |
|---|---|
| 启动入口 | `src/main/java/net/fabricmc/loader/impl/launch/knot/Knot.java` |
| 类加载/转换总闸门 | `.../launch/knot/KnotClassDelegate.java:427-485` |
| 主流程 | `src/main/java/net/fabricmc/loader/impl/FabricLoaderImpl.java:209-295, 486-541` |
| Mod 发现 | `.../discovery/ModDiscoverer.java:88-218, 325` |
| 依赖求解 | `.../discovery/ModResolver.java:45-208` |
| 元数据解析（schema 0/1） | `.../metadata/ModMetadataParser.java:138-152` |
| 入口点存储 | `.../entrypoint/EntrypointStorage.java:36-251` |
| 入口点织入 | `minecraft/.../game/minecraft/patch/EntrypointPatch.java:48-652` |
| 生命周期锚点 | `minecraft/.../game/minecraft/Hooks.java:45-69` |
| Mixin 引导 | `.../launch/FabricMixinBootstrap.java:46-158` |
| Mixin 服务扩展位 | `.../launch/knot/MixinServiceKnot.java:226-233` |
| 语言适配器 | `.../util/DefaultLanguageAdapter.java:36-159` |
| 命名空间判定 | `.../launch/MappingConfiguration.java:129-159` |
| MC 反混淆 | `minecraft/.../MinecraftGameProvider.java:322-367` |
| 系统属性清单 | `.../util/SystemProperties.java:20-45` |
| 变换器 | `.../transformer/FabricTransformer.java:31-80` |

### NeoForge
| 用途 | 路径（相对 `NeoForge/`） |
|---|---|
| 主总线 | `src/main/java/net/neoforged/neoforge/common/NeoForge.java:17` |
| 注册表核心 | `src/main/java/net/neoforged/neoforge/registries/GameData.java:68-104` |
| 延迟注册 | `.../registries/DeferredRegister.java:315-320` |
| 注册事件 | `.../registries/RegisterEvent.java:46-62` |
| FML 绑定 SPI | `src/main/java/net/neoforged/neoforge/internal/NeoForgeBindings.java` |
| Patch 目录 | `patches/`（846 个） |
| 运行时 Mixin | `src/main/resources/neoforge.mixins.json` |
| Coremod | `coremods/src/main/java/net/neoforged/neoforge/coremods/` |

### Forge
| 用途 | 路径（相对 `MinecraftForge/`） |
|---|---|
| `@Mod` 注解 | `javafmllanguage/src/main/java/net/minecraftforge/fml/common/Mod.java` |
| 事件订阅扫描 | `javafmllanguage/.../fml/javafmlmod/AutomaticEventSubscriber.java` |
| 订阅逻辑 | `.../EventBusSubscriberLogic.java:125-407` |
| 生命周期阶段 | `fmlcore/src/main/java/net/minecraftforge/fml/ModLoadingStage.java` |
| 状态机 | `fmlcore/.../ModStateManager.java`、`src/main/java/net/minecraftforge/fml/core/ModStateProvider.java` |
| 变换器 | `forge-transformers/src/main/java/net/minecraftforge/forge/transformers/` |
| Patch 目录 | `patches/`（623 个） |

### Quilt Loader
| 用途 | 路径（相对 `quilt-loader/`） |
|---|---|
| 读 fabric.mod.json | `src/main/java/org/quiltmc/loader/impl/fabric/metadata/FabricModMetadataReader.java` |
| fabric 兼容插件 | `.../impl/plugin/fabric/StandardFabricPlugin.java` |
| QMJ 解析 | `.../impl/metadata/qmj/{ModMetadataReader, V1ModMetadataReader, V1ModMetadataImpl}.java` |
| Fabric API 桥 | `src/fabric/{api,impl,legacy}/java/net/fabricmc/` |
| 入口点双调 | `.../impl/QuiltLoaderImpl.java:1306-1307` |
| Mixin 前缀 | `.../impl/launch/knot/QuiltMixinBootstrap.java:112` |

### Forbric（可摘资产）
| 资产 | 路径（相对 `Minecraft-Forbric-mod-loader/forbric-kernel/`） |
|---|---|
| mods.toml 解析 | `src/main/java/.../metadata/forge/ModsTomlParser.java` |
| 版本区间 | `.../metadata/forge/ForgeVersionRange{,Translator}.java` |
| 注解扫描 | `.../discovery/ModAnnotationScanner.java` |
| 变换管线 | `.../transform/TransformChain.java`、`TransformPhase.java` |
| AT 解析 | `.../access/AccessTransformer{,Parser}.java` |
| 事件补发 | `.../boot/GameEventMultiplexer.java` |
| 崩溃归因 | `.../boot/CrashAttribution.java`、`KernelLoadReport.java` |
| 多加载器仲裁 | `.../boot/MultiLoaderArbiter.java` |
| **反面教材** | `.../mixin/ForbricMixinService.java:499-524`（禁止模仿） |

---

## 附录 B：待核实清单

阶段 0 的 T0.7 必须给出结论：

1. **Mixin / MixinExtras 的许可证**（SpongePowered Mixin 与 Fabric 的 fork，以及 MixinExtras）——影响内核的许可证声明。
2. **`net.neoforged:bus` 与 Forge `eventbus` 的许可证**——若不是 LGPL-2.1-only 而是 LGPL-2.1-or-later 或 MIT，会影响 `compat-*` 模块的许可声明。
3. **`net.neoforged:fancymodloader` 的许可证**——若复用其 `net/neoforged/fml/**` 类。
4. **Kilt / Sinytra Connector 的许可证**——若参考或复用其映射表/特例库。
5. **`night-config`、`SAT4j` 的许可证**——都在构建/运行时依赖链上。
6. **当前主流 MC 版本与四家加载器的最新版本对齐情况**——确定 T0.2 的版本矩阵（本文按仓库现状写 26.3，需在开工时复核）。
7. **原版 `MappedRegistry` 在当前 MC 版本中 `frozen` 的确切实现**——决定 T6.1 的 Mixin 写法（字段名与访问方式可能随版本变化）。

---

## 给执行 AI 的通用派发模板

每个任务派发时，prompt 至少包含以下五段：

```
【背景】UniLoader 是一个以 fabric-loader 为内核、兼容四家模组生态的加载器。
        完整方案见 D:/work2/docs/统一模组加载器技术方案.md，本任务是其中的 T{x}.{y}。

【上下文：必读文件】
        - <本任务相关的上游文件绝对路径清单>
        - <本任务要修改的 UL 文件绝对路径清单>

【任务】<具体、可验证的一件事>

【验收】<可执行的命令 + 期望现象>；没有验收视为未完成。

【红线】严格遵守方案第 6 节"工程纪律"十条，尤其是：
        - 禁止静默跳过注入失败
        - 禁止合并 patch 后的 MC 字节码
        - 禁止硬编码 MC 版本号
        - 本任务涉及的模块许可证为 <Apache-2.0 | LGPL-2.1-only>，
          新增文件必须带正确的版权头
```

---

# 阶段 0 完成情况与结论回写（v1.1，2026-10-07）

> 本节是方案要求的「实际结论回写」。代码与文档都在 `D:/work2/uniloader/`（独立 git 仓库）。
> 回写内容只写**实测事实**，推断与未闭环项分开标注。

## 0.1 交付物与验收状态

| 任务 | 状态 | 产出与验收 |
|---|---|---|
| T0.1 仓库骨架 | ✅ | `uniloader/`（git 仓库，7 次提交）；`loader/` 由 `git subtree` 导入，保留 fabric-loader 0.19.5 的**完整上游历史**（remote `fabric-upstream`，可 `git subtree pull` 跟进）。**验收：`./gradlew build` 通过**（含内核 `proguardJar` / `finalJar`） |
| T0.2 版本矩阵 | ✅ | `gradle.properties` 为唯一版本真源；结论与阻塞项见 `docs/stage0/T0.2-version-matrix.md` |
| T0.3 D1 实验 | ✅ 机制验证通过 | `experiments/d1-namespace`（可执行）；报告归档 `docs/stage0/T0.3-D1-namespace-experiment.md` |
| T0.4 实验台与冒烟 | ✅ **真实跑通** | `run/*.sh`；**MC 26.3 + 11 个真实 mod 冒烟 PASS 6/6**（阶段 0 时是 6 项断言；阶段 1 的 T1.6 又加了断言，现在是 **7/7**），报告见 `uniloader/docs/stage0/T0.4-real-smoke-report.md` |
| T0.5 诊断骨架 | ✅ | `tooling/crash-attribution` + `tooling/load-report` + `compat/compat-api`；**13 个单测全通过**（含「故意放一个会崩的 mod，报告能正确指认」） |
| T0.6 验收 mod 矩阵 | ✅ **实测 11/11 可用** | `tooling/mod-matrix/`：matrix.yml（四家 30 个 mod）+ fetch-mods.sh + matrix-resolved.json（baseline 矩阵对 26.3 全部可拉取）；不提交 mod 二进制 |
| T0.7 许可证核实 | ✅ | `docs/LICENSES.md`（POM / GitHub API / 构件内许可文件三种来源，命令可重跑） |

## 0.2 ★ 决策 D1 的验证结果：**可行（机制范围内）**

**实验方式**（可重复执行，不是论述）：用 **MC 1.21.11 的真实映射**（intermediary + Mojang 官方映射，按 official 名合并成
intermediary→named），合成一个「典型 Fabric mod」（ASM 生成、引用真实 intermediary 名、带 `refmap.json` 与 `@Mixin/@Inject`），
再用**与内核 `RuntimeModRemapper` 完全一致的 tiny-remapper 配置**重映射。

| 指标 | 实测 |
|---|---|
| 类覆盖率 | **100.00%**（10242 / 10242） |
| 成员覆盖率 | **100.00%**（94916 / 94916） |
| 类名 / 成员名换成 mojmap | ✅ 全部样本通过（含方法） |
| intermediary 名字彻底消失 | ✅ |
| 非 class 资产（fabric.mod.json）保留 | ✅ |
| 性能基线（20 个 mod） | 冷 827 ms / 热 829 ms（≈41 ms/mod，尚无落盘 cache） |

**D1 结论**：Fabric mod 以 intermediary 编译的字节码可以完整、准确地重映射到 mojmap，
**不回退到方案 B**。R1 的概率由「中」下调为「低」，但影响仍为「高」——因为下面这条：

### 0.2.1 实验查证出的四处门闸（**仅当目标 MC 仍有混淆时适用**，即 <= 1.21.11）

| 编号 | 位置 | 实测事实 | 必须做什么 |
|---|---|---|---|
| **G1** | `FabricLoaderImpl.setup()`：`remapRegularMods = isDevelopmentEnvironment()` | 生产环境恒为 `false` | 生产也 remap（用 `ul.remapFabricMods` 控制） |
| **G2** | `RuntimeModRemapper.getRemapClasspath()` | 缺 `fabric.remapClasspathFile` 直接抛异常；**且实测该 classpath 必须是「源命名空间」（intermediary）的 MC+库**——只给 named 名时，**成员名会静默地不重映射**（类名照常重映射，最容易骗过冒烟） | 生产必须提供该文件，内容为 intermediary 名的 MC 与库 |
| **G3** | mod jar 内 `refmap.json`（loom 写的键是 `named:intermediary`）与 `@Inject(method=...)` 注解字符串 | 注解字符串是**常量**，字节码重映射不碰它；**实测 MixinExtension（`fabric.defaultMixinRemapType=static`）也不会改写它**〔⚠️ 这条只对**裸名样本**成立，**已被 v1.4 的 G14 修正**：带描述符时 MixinExtension 会改写〕，refmap 同样原样保留 | 内核自己解决：把 `FabricMixinBootstrap` 里 dev-only 的 `MixinIntermediaryDevRemapper` + `mixin.env.remapRefMap=true` 放开到生产（Fabric 官方机制，只是被 `isDevelopment()` 挡住）。**v1.4 已落地并验证** |
| **G4** | `MinecraftGameProvider.initialize()` 的 `!gameNs.equals(runtimeNs)` | 把 `fabric.gameMappingNamespace` 与 `fabric.runtimeMappingNamespace` 都设成 named 确实能「跳过 deobfuscate」，**但只有在 MC 本体已经是 mojmap 时才是对的**；喂官方混淆 jar 会全线对不上名 | 由安装器保证 MC 本体预先 remap 到 mojmap（或改为 gameNs=official → runtimeNs=named 走一次反混淆） |

## 0.3 ★ 关键事实修正：**MC 26.1 起 Mojang 不再混淆**（因此 26.3 不需要任何映射）

> 本节原先把「26.3 没有 intermediary」写成阻塞项，是**判断错误**。
> 经用户指出并用三处证据核实后改正：没有映射不是「发布没跟上」，而是**没有混淆就不需要映射**。

### 证据（三处，都可重跑）

1. **官方版本元数据**：`1.21.11` 的 `downloads` = `[client, client_mappings, server, server_mappings]`；
   `26.3` 与 `26.4-snapshot-3` = `[client, server]`——**不再发布映射**。
   这解释了 `maven.fabricmc.net` 的 intermediary 为什么停在 1.21.11。
2. **loom 自己就是这么判的**（工作区 `fabric-loom` 源码）：
   `MinecraftMetadataProvider.isUnobfuscated()` = `版本 >= 1.21.11 未混淆快照 && !downloads.containsKey("client_mappings")`；
   成立时 `productionNamespace=OFFICIAL`、`useIntermediateMappings=false`、`defaultMixinRemapType=STATIC`。
   另有 `UnobfuscatedMappingNsCompleter` 把 official 名复制进 intermediary 命名空间
   → 未混淆版本下 **official == intermediary == named，三者同名**。
3. **内核侧默认值本来就是 official**：`SystemProperties.RUNTIME_MAPPING_NAMESPACE` 注释写明
   *"default: official for unobfuscated games"*；`computeRuntimeNamespace()` 在**没有 mappings 文件**时返回 `official`；
   `getDefaultModDistributionNamespace()` 只在 runtime != official 时才退回 intermediary。

### 对 UL 的意义（好消息）

| | 26.3（未混淆） | <= 1.21.11（混淆） |
|---|---|---|
| 运行时命名空间 | `official`（== Mojang 真实名） | 默认 `intermediary`（dev 为 `named`） |
| mod 分发命名空间 | `official` | `intermediary` |
| 是否需要重映射 | **完全不需要** | 需要（Fabric → named） |
| D1 的目标（统一到 mojmap） | **免费得到** | 需要用 D1 的机制去挣 |

**即：在 26.3 上，内核与四家 mod 天然同处一个命名空间**，D1 想解决的「统一命名空间」在这个版本上自动成立。
下方 0.2.1 的四处门闸（G1–G4）**只在支持 <= 1.21.11 时才适用**，不构成 26.3 的施工内容。

### 决策更新

- 首个目标版本仍是 **26.3**；不需要为它找 intermediary，也不需要自维护映射（原「三条出路」作废）。
- `run/server.sh` / `run/client.sh` 的默认命名空间模式已改为 **`official`**（即不设任何命名空间属性）；
  `named` / `intermediary` 分支保留，注释写明「仅用于目标 MC 仍有混淆（<= 1.21.11）的场景」。
- D1 实验改用 1.21.11 的真实映射，是为了**验证机制**（将来向下支持 1.21.11、或某个生态以别的命名空间分发 mod 时要用），
  不是为了 26.3。其结论与四处门闸清单依然有效，只是适用条件写清楚了。

## 0.4 风险登记册的状态更新

| ID | 原判断 | 更新后 | 依据 |
|---|---|---|---|
| **R1** D1 实验失败 | 影响高 / 概率中 | **影响高 / 概率低（26.3 上甚至不适用）** | 覆盖率 100%/100%，机制通过；且 26.1 起 MC 不再混淆，26.3 上内核与 mod 天然同命名空间（见 0.3），D1 的风险面大幅收窄 |
| **R2** Hook 复刻工作量爆炸 | 影响高 / 概率高 | 不变 | 本阶段未触及 |
| **R6** LGPL 合规边界模糊 | 影响高 / 概率低 | **影响高 / 概率低，但约束更严** | 实测：内核 jar 内含 **LGPL-3.0**（tiny-remapper）与 **EPL-1.0**（SAT4j）；**LGPL-2.1-only 不允许升级，因此与 LGPL-3.0 不能合并进同一个 jar** → 「分 jar」不只是设计偏好，是法律必须。CI 需在阶段 4/8 加反向检查（LGPL-2.1-only 的 jar 内不得出现 `net/fabricmc/loader/impl/lib/tinyremapper/**`） |
| **R7** 性能不可接受 | 影响中 / 概率中 | **影响中 / 概率中（已有基线）** | 冷 827 ms / 热 829 ms（20 mod）；落盘 cache 未实现，T1.2 的 15% 预算以此为起点 |
| **R10** MC EULA / 分发合规 | 影响高 / 概率低 | 不变 | 仓库仍不含 MC 字节码；`run/game` 与 `run/mods` 已 gitignore |

## 0.5 附录 B 待核实清单的结论

| # | 项 | 结论 |
|---|---|---|
| 1 | Mixin / MixinExtras 许可证 | Mixin（SpongePowered 及 Fabric fork，POM 明示）= **MIT**；MixinExtras = **MIT** |
| 2 | `net.neoforged:bus` / Forge eventbus | bus = **LGPL-2.1-only**（POM 明示）；Forge eventbus **待阶段 8 前复核** |
| 3 | `fancymodloader` | GitHub API 返回 `NOASSERTION`、POM 无 `<licenses>` → **阶段 4 的 T4.1 前必须闭环** |
| 4 | Kilt / Sinytra Connector | Connector = **MIT**（可复用）；Kilt = **LGPL-2.1**（复用其代码的模块必须按 LGPL 处理） |
| 5 | night-config / SAT4j | night-config = **LGPL-3.0**（POM）；SAT4j = **EPL-1.0**（构件内 `about.html`，非常说的「EPL/LGPL 双许可」） |
| 6 | 四家加载器版本对齐 | MC 26.3 是当前 release，与 NeoForge/Forge/fabric-api 一致；Fabric 侧 intermediary 停在 1.21.11 是**正常现象**（26.1 起不再混淆，见 0.3） |
| 7 | `MappedRegistry.frozen` 的确切实现 | 未做（属阶段 6 的 T6.1 前置，本阶段不涉及） |

## 0.5.1 真实端到端实测（T0.4 的实测部分）

在**真实的 MC 26.3 服务端 + 11 个真实 Fabric mod**（fabric-api / sodium / iris / lithium / carpet / REI /
modMenu / cloth-config / architectury / travelersbackpack / forge-config-api-port）上跑通：

- `Loading Minecraft 26.3 with Fabric Loader 0.19.5` → `Loading 52 mods:`（含 fabric-api 的 40+ 嵌套子模块）
  → `Done (0.728s)!`，冒烟 **PASS 6/6**（当时的断言集合是 6 项；阶段 1 的 T1.6 之后是 7/7）；
- 日志 `Mappings not present!`，且**反混淆/重映射相关日志条数为 0** —— 这是 0.3 节结论的直接实证；
- 客户端专属 mod（sodium / iris / modMenu 及 fabric-api 的客户端子模块）被 `SERVER environment disabled: [...]` 正确跳过；
- 依赖求解失败给出可读建议（`travelersbackpack` 需要 `forgeconfigapiport`，并给出「安装任意版本」的修复建议）；
- 真实 mixin 冲突被显式报出：`architectury` 与 `carpet` 同时改写 `getClimateSettings`，
  日志明确说明谁覆盖了谁 —— 这是阶段 7 的 T7.4（Hook 冲突检测）的真实样本。

过程中修掉的 4 个问题都在脚本/环境层（运行必须 Java 25、工作目录必须是游戏目录、classpath 必须含
installer 依赖、mod 矩阵 slug 与硬依赖），内核本身未改。详见 `uniloader/docs/stage0/T0.4-real-smoke-report.md`。

**阶段 1 的交接文档**：`uniloader/docs/stage1/HANDOFF.md`（环境、命令、任务改写、坑清单、建议顺序）。

## 0.6 给阶段 1 的三条前置提醒

1. **T1.1 的命名空间子系统要按「26.3 不需要任何开关」来设计**：默认路径是 official 且零重映射；
   为 <= 1.21.11 预留 named/intermediary 模式即可（`ul.namespace` 参数的设计目标就是这件事）。
2. **G2 是最容易骗过冒烟的一条**：只给 named classpath 时，类名照样被重映射、日志干净、测试通过，
   但成员名静默不变。阶段 1 的验收必须**逐成员断言**，不能只看「启动成功」。
3. **G3 不要指望 tiny-remapper 的 MixinExtension**：实测它不改写 `@Inject(method=...)`
   〔⚠️ 同样只对**裸名样本**成立，见 v1.4 的 G14〕。
   推荐用 Fabric 自带的 `MixinIntermediaryDevRemapper`（把 dev-only 分支放开），
   并用带真实 mixin 的 mod 做回归。**v1.4 已按此落地，并在 48 个真实模组上验证。**

---

*文档版本 v1.1（阶段 0 回写）。后续每个阶段完成后继续在本节之后追加。*

---

# 阶段 1 完成情况与结论回写（v1.2）

> ⚠️ **本节写于 v1.2，其中 T1.2 的状态已被下面的「阶段 1 补记（v1.4）」取代**：
> T1.2 当时按 HANDOFF 许可降级为 P2，后因「必须兼容 1.x」撤销降级并完成。
> 本节其余结论（T1.1/T1.3–T1.6、坑 G5–G11、风险 R1–R12）仍然有效。

> 追加，不覆盖。任务编号出自第 8 节；**目标改写**出自 `uniloader/docs/stage1/HANDOFF.md`
> （阶段 0 的结论改变了前提：26.3 未混淆、零重映射）。
> 完整实测报告与原始输出：`uniloader/docs/stage1/STAGE1-REPORT.md`、`uniloader/docs/stage1/T1.5-hash-compare.txt`。

## 1.0 一句话结论

阶段 1 六条任务：**五条完成、一条（T1.2）按 HANDOFF 明确许可降级为 P2 并记录了重新启用的条件**。
阶段 1 新增 65 个单测（仓库单测总数 105，全绿）；三条冒烟路径全部通过：
纯 Fabric `PASS 7/7`、**7 个真实 NeoForge mod 与 11 个真实 Fabric mod 混装 `PASS 10/10`**、
崩溃对照组 `PASS 10/10`；T1.5 的硬验收达成——**10756 个类的变换产物逐字节相同**。

## 1.1 六条任务的最终状态

| 任务 | 状态 | 交付物 | 可执行验收 |
|---|---|---|---|
| **T1.1** 命名空间子系统正式化 | 完成 | `loader/.../impl/compat/namespace/NamespacePolicy`（**三个**命名空间：运行时 / 模组分发 / 游戏 jar 的 G4）；`MappingConfiguration` 两处判定 + `MinecraftGameProvider` 的 G4 判定均改为调用它；`FabricLoaderImpl.setup()` 的 G1 门闸改由策略驱动 | `./gradlew :loader:test --tests '*NamespacePolicyTest*'`（23 个）+ `./run/smoke.sh --matrix=baseline` |
| **T1.2** 重映射缓存与性能 | **降级 P2** | 26.3 上「重映射未执行」已成为 `kernel-diagnostics.txt` 里的一条事实 | 见 1.2 第 3 条 |
| **T1.3** 扩展点 H1：语言适配器注册表 | 完成 | `impl/compat/adapter/{LanguageAdapterRegistry,DelegatingLanguageAdapter}`；`setupLanguageAdapters()` 改为按生态装入 | `./gradlew :loader:test --tests '*LanguageAdapterRegistryTest*'`（8 个） |
| **T1.4** 扩展点 H2：候选查找器插件化 | 完成 | `impl/compat/discovery/*`；`ModCandidateFinder` 改 public + `acceptForeign`；`ModDiscoverer` 的 `nonFabricMods` 改为「先识别再退回」 | `./run/smoke-foreign.sh`（真实 NeoForge 模组混装） |
| **T1.5** 扩展点 H3：变换管线化 | 完成 | `impl/compat/transform/*`；`KnotClassDelegate.getPreMixinClassByteArray` 改为走链 | `./run/compare-transform-hashes.sh <before> <after>`（**逐字节**） |
| **T1.6** 诊断接入 | 完成 | `impl/compat/diagnostics/LoaderDiagnostics` + `tooling/load-report` 的 `KernelDiagnosticsReader`/`ReportMain` + `run/report.sh` | `./run/smoke.sh --matrix=baseline` / `--expect=crash` |

新增脚本：`run/report.sh`、`run/make-broken-mod.sh`、`run/smoke-foreign.sh`、`run/compare-transform-hashes.sh`，
以及交接自检 `run/preflight.sh`（环境 + 依赖 + git + 构建 + 三条冒烟路径，一条命令确认接手状态健康）。
新增内核开关：`ul.namespace`、`ul.namespace.strict`、`ul.remapFabricMods`、`ul.transform.hashFile`、`UL_EXTRA_JVM_ARGS`。

## 1.2 与方案原文的差异（都有理由，不是省略）

1. **`ul.namespace` 的语义把两处说法合并了。** 原文写 `ul.namespace=strict|auto`（默认 strict）；
   HANDOFF 写 `ul.namespace=official|named|intermediary`。合并为两个正交开关：
   - `ul.namespace=auto|official|named|intermediary`（默认 `auto`）：**运行时用哪个命名空间**；
   - `ul.namespace.strict=strict|lenient`（默认 `strict`）：**命名空间不匹配的 mod 记 DEGRADED 还是只记日志**。
   混在一个参数里会让「我想强制 named」和「我想宽松处理」互相打架。

2. **T1.1 的判定依据是「有没有 mappings」，不是比较版本号。** 原文的验收「内核可在 intermediary 与 named
   两种模式间切换，切换后 10 个基线 mod 均正常」在 26.3 上**无法构成有意义的验证**：26.3 没有 mappings，
   切到 intermediary 只是把运行时与分发两侧都设成 intermediary（零重映射），换不到任何新信息。
   于是验收改为**逐一对拍**：`NamespacePolicy` 的判定必须与 `MappingConfiguration` 的真实返回值一致，
   覆盖「必须一致」的两条与「唯一有意分歧」的一条（有 mappings 且非 dev：上游 `intermediary`，
   UL 按 D1 取 `named`）。这条对拍比「切换后能跑」更能防漂移——它连「两处逻辑各改一半」都抓得住。

3. **T1.2 降级为 P2，理由如下（HANDOFF 第 6 节明确许可）。**
   - 26.3 上零收益：`remap=false`，缓存永远命中不了任何东西；
   - 该路径在 26.3 上不可执行 ⇒ 无法验收。它的验收需要一份 1.21.11 游戏 + **源命名空间（intermediary）**的
     remap classpath（G2）+ 放开 refmap 的 dev-only 分支（G3），是独立的一块工作，不属于阶段 1 任何任务。
     工程纪律第 9 条要求每个阶段有可执行验收命令，**没有验收的代码不进内核**；
   - **缓存键比看上去难（实测得出的约束，留给接手的人）**：`RuntimeModRemapper` 用**一个** `TinyRemapper`
     处理整批模组，并把各模组的 class-tweaker **合并**成一个再挂上去。因此某个模组的产物**取决于同批其它模组**，
     **按单个 mod 算缓存键是错的**。缓存键必须覆盖整批输入：所有模组 jar 内容 + 合并后的 class-tweaker +
     映射文件 + `modNs → runtimeNs` 这一对 + remap classpath。
   - **26.3 上仍可验证的那一半已经落地**：
     `grep -c "timing	remap" run/game/.uniloader/kernel-diagnostics.txt` 期望 **0**（该阶段从未进入），
     且有一条 `重映射未执行：…（判定理由）` 的备注。
   - **重新启用时的验收命令**：`rm -rf ~/.ul/cache && UL_NAMESPACE=named ./run/smoke.sh --matrix=baseline`（冷）
     → `UL_NAMESPACE=named ./run/smoke.sh --matrix=baseline`（热）；判据是热启动 remap 阶段耗时 ≈ 0
     且总启动耗时与原生 Fabric 差距 < 15%（基线：D1 报告第 6 节，20 mod 冷 827 ms / 热 829 ms）。

4. **T1.5 去掉了 Forbric 的 `predepends` 拓扑排序。** 链上只有三个内建步骤，谁先谁后阶段名就说清了；
   加一层没有实现者的拓扑排序只是多一段没被测过的代码。阶段 7 真需要「A 必须在 B 之前注入」时再加，
   那时它会带着真实测试用例进来。

5. **T1.4 刻意只识别不解析。** 不引入 TOML 解析、不产出候选、不写状态——解析与依赖语义是阶段 3 的事。
   本阶段唯一的产出是「日志里说清这是谁、凭什么、为什么没加载」，而它的验收标准最简单：不破坏现有行为。
   识别结果顺带过一遍 `NamespacePolicy.isCompatible`：混淆版本上运行时是 intermediary 而 NeoForge/Forge
   全基于 mojmap，那种组合下即使阶段 4/8 做完了也跑不起来——现在说清楚比那时再排查便宜。

## 1.3 阶段 1 实测出来的坑（新增 G5–G11）

| 编号 | 事实 | 处理 |
|---|---|---|
| **G5** | **专服的 `Main` 会自己 catch 掉入口点异常**，打一句 `Failed to start the minecraft server` 后正常返回，**退出码是 0** | 「有没有崩」不能看退出码，要看「有没有跑到超时」+「日志里有没有指认到具体模组」。`run/smoke.sh --expect=crash` 已按此写 |
| **G6** | 用信号（TERM）掐掉的进程，**shutdown hook 里的日志会被已经停用的日志系统吞掉** | 给这类进程做度量不要依赖 shutdown hook，要**边跑边落盘**。`TransformHashLog` 第一版就是栽在这里，白跑一轮 |
| **G7** | `ModCandidateFinder` 原本是**包级私有** | 「扩展点插件化」的前提是先把它 public（fork 改动已标注 T1.4）；否则兼容层只能往内核包里塞代码 |
| **G8** | `TransformChain.apply(className, …)` 与 `TransformContext.className` 会重复 | 单一事实来源：类名只放上下文里。两处各写一遍，迟早会不一致 |
| **G9** | T1.2 的缓存键必须覆盖**整批**输入（`TinyRemapper` 跨 mod 合并 class-tweaker） | 见 1.2 第 3 条 |
| **G10** | `run/env.sh` 的 `try_java` 用「返回 1」表示**找到了** Java | 任何 `set -e` 的脚本 `source` 它，都会在刚找到 Java 的瞬间**静默退出**。`run/make-broken-mod.sh` 已改为先 `set +e` 再 source |
| **G11** | `loader` 的 checkstyle `JavadocStyle` 要求 Javadoc 首句以 **ASCII 句点**结尾，中文的「。」过不了 | 只对 `net/fabricmc/loader/impl/compat/**` 放宽这一条（`checkstyle.xml` 里新增 `SuppressionSingleFilter`，已标注 fork patch）；上游代码仍受完整约束 |
| — | `spotless` 会把新文件的 CRLF 转成 LF | 新增内核文件后先跑 `./gradlew :loader:spotlessApply`，否则 `spotlessJavaCheck` 会拦下构建 |
| — | 加载到哪些类**不是确定的**：`smoke.sh` 用 `timeout` 掐服务端，180 秒窗口内加载到哪一步会有波动（实测同代码两次运行差 1–2 个类；某次离群样本多 1161 个 `carpet.*`） | 对拍脚本只判定「两次都出现过的类」，一侧独有的只记录不判定。**别把集合差异读成行为差异** |

## 1.4 风险登记册更新

| ID | 原判断 | 更新后 | 依据 |
|---|---|---|---|
| **R1** D1 实验失败 | 影响高 / 概率低（26.3 上不适用） | **不变** | 阶段 1 未新增风险面；26.3 上判定链已收敛到 `NamespacePolicy` 一处并有对拍测试 |
| **R2** Hook 复刻工作量爆炸 | 影响高 / 概率高 | **不变（但多了两个可挂载点）** | T1.5 提供了 `TransformPhase.HOOK_INJECTION`，T1.3 提供了 `neoforge`/`forge` 语言适配器键。**注意 `HOOK_INJECTION` 的位置是暂定的**，阶段 7 必须靠实测确定，不许猜 |
| **R3**（注册表语义漂移） | 影响高 / 概率中 | **不变** | 阶段 1 未触及 |
| **R6** LGPL 合规边界模糊 | 影响高 / 概率低，但约束更严 | **不变** | 阶段 1 未新增 LGPL 依赖；内核新增代码全部在 `impl/compat/` 且无第三方依赖 |
| **R7** 性能不可接受 | 影响中 / 概率中（已有基线） | **影响中 / 概率中，基线更新** | 阶段 1 新增了可执行的性能观测点：`LoaderDiagnostics` 的阶段耗时（discovery / resolve / remap / instantiate / lifecycle）与 `ul.transform.hashFile` 的逐类产物哈希。**T1.5 的管线没有引入可测的性能回归**（对拍覆盖 10756 个类，产物逐字节相同）。T1.2 的 15% 预算仍以 D1 报告第 6 节为起点 |
| **R10** MC EULA / 分发合规 | 影响高 / 概率低 | **不变** | 仓库仍不含 MC 字节码与 mod 二进制；新增 `run/mods-foreign/`、`run/mods-broken/` 已加进 `.gitignore` |

新增风险登记：

| ID | 风险 | 影响 / 概率 | 缓解 |
|---|---|---|---|
| **R11** | 新增的兼容层代码（`impl/compat/**`）**没有上游测试覆盖**，只有自己写的单测 | 影响中 / 概率中 | 阶段 1 已为每个新组件配单测（59 个）；后续阶段必须保持「新增内核代码必带单测」 |
| **R12** | `HOOK_INJECTION` 阶段的位置是暂定的，阶段 7 若选错位置，注入会**静默失效** | 影响高 / 概率中 | 阶段 7 必须用真实 patch 实测确定位置，并配 `mixin audit`（T7.1）证明「注入了多少」，而不是「没有报错」 |

## 1.5 给阶段 2 的前置提醒

1. **Quilt 排在第二是对的**：`LanguageAdapterRegistry` 的 `quilt` 键已经占好，
   `ForeignModRecognizers` 已经能认 `quilt.mod.json`——阶段 2 要做的是**解析** `quilt.mod.json`
   并把它变成候选（阶段 3 的统一发现层会复用同一套）。
2. **别在 26.3 上强行打开重映射。** 那会引入 G2/G3 的全部风险却毫无收益。判定链已经收在
   `NamespacePolicy` 一处，要加约束就往那里加，不要再散到调用点。
3. **开工前先跑 `./run/preflight.sh`**：它会把「已验收 / 未验收」的清单打印出来，
   免得把「没测」当成「没问题」。
4. **`ModDiscoverer.nonFabricMods` 的语义已经变了**：现在只有「连是哪一家都认不出来」的 jar 才会进去。
   阶段 3 改造发现层时注意，别把识别过的又塞回去。
5. **任何改动「Mixin 之前」管线的提交，都必须跑一次哈希对拍**（`run/compare-transform-hashes.sh`）。
   冒烟测试证明不了这件事。
6. **崩溃路径的验收模板已经就位**：`run/make-broken-mod.sh` + `./run/smoke.sh --matrix=broken --expect=crash`。
   后续阶段每加一个子系统，都应该照这个模式补一个「故意失败」的对照组。

## 1.6 补记（交接前的独立审计发现并补上的缺口）

交接前对阶段 1 做了一次独立审计（不靠记忆，重新从零构建 + 重跑三条冒烟路径），
发现 T1.1 的任务原文要求 `NamespacePolicy` 统一管理**三个**命名空间
（`RuntimeMappingNamespace` / `GameMappingNamespace` / `DefaultModDistributionNamespace`），
而 v1.2 只做了两个——**游戏 jar 的命名空间（门闸 G4）还留在
`MinecraftGameProvider.initialize()` 里，自己又写了一遍「有没有 mappings」的判断**。

这不是「少写了个函数」，而是 G4 的真实风险面：那句
`if (!gameNs.equals(runtimeNs)) → 对游戏本体做反混淆` 依赖的两个值来自两处独立逻辑，
一旦漂移，表现是「游戏被莫名其妙地重映射了一遍」，而且日志里看不出是谁改的。已补齐：

- `NamespacePolicy.gameNamespace(override, mappingsPresent, namespaces, development, client)`
  ——判定与上游**逐字等价**（包括 `namespaces == null` 时不做任何判断、留在 official 这条保守分支，
  照抄而不靠推理省略）；`MinecraftGameProvider` 改为调用它。
- G4 即将触发时的日志从 **debug 提到 info**：这是稀有且影响巨大的事件，应当在日志里一眼可见。
- 新增 6 个单测覆盖 G4 的全部分支（未混淆 / 显式覆盖 / dev+named / 生产 / 老 MC 的
  clientOfficial·serverOfficial / namespaces 读不到），并断言 **26.3 上 gameNs 必须等于 runtimeNs**。

审计还确认了另一处与任务原文的偏差（有意，已写进 `docs/stage1/STAGE1-REPORT.md` 第 6 节）：
T1.6 原文要求在 `Hooks.java:52-64` 包裹埋点，实际埋在 `FabricLoaderImpl.invokeEntrypoints()`
——它是唯一实现，而调用点有 5 处（`Knot`、`Hooks`×4、`FabricTweaker`），
埋在 `Hooks.java` 会漏掉 `preLaunch` 与 launchwrapper 那条路，而且不会报错。

补齐后的状态：仓库单测 **105 个全绿**，`./run/preflight.sh` **10 OK / 0 FAIL**
（三条冒烟路径：纯 Fabric 7/7、别家生态混装 10/10、崩溃对照 10/10）。

---

*文档版本 v1.3（阶段 1 回写 + 交接前审计补记）。后续每个阶段完成后继续在本节之后追加。*

---

# 阶段 1 补记（v1.4）：T1.2 撤销降级 —— 混淆版本（≤1.21.11）支持落地

> 追加，不覆盖。**起因**：需求被明确为「必须同时支持 1.x」——「26.1 起不再混淆」是 Mojang
> 最近才做的决定，大量模组仍面向 1.x，这部分受众不能放弃。于是撤销 T1.2 的 P2 降级
> （原降级依据见 v1.2 第 1.2 节第 3 条），把「内核在**仍有混淆**的 MC 上跑 named 运行时 +
> 模组重映射」这条路真正走通并做了真实验收。
> 完整实测报告：`uniloader/docs/stage1/STAGE1-REPORT.md` 第 3 节。

## 2.0 一句话结论

阶段 1 六条任务**全部完成**（T1.2 撤销降级、补齐实现）。
在**真实 MC 1.21.11**（仍有混淆）上，11 个真实 1.21.11 Fabric 模组（含嵌套共 **48 个**）
以 **named 运行时**端到端跑通，冒烟 **PASS 7/7**；重映射缓存有真实冷/热数据：
`remap` 阶段 **6774 ms → 1452 ms（−78.6%）**，**热启动与原生 Fabric 基线同秒（8 s vs 8 s）**，
满足「< 15%」的判据。仓库单测 **114 个全绿**，`./gradlew build` 通过。

## 2.1 T1.2 的交付物

| 件 | 位置 | 作用 |
|---|---|---|
| 整批重映射缓存 | 内核 `impl/compat/remap/RemapCache` | 键覆盖整批输入；`remapped-<key>/` + `.complete` 标记；`commit` 先写标记再原子改名；目标已存在时保留先到的那一份 |
| 缓存接线 | 内核 `RuntimeModRemapper.remap()`（fork patch T1.2） | 命中→直接指向缓存产物并返回；未命中→写临时目录、`commit` 后再 `setPaths`；失败必丢弃半成品 |
| 门闸 G3 落地 | 内核 `FabricMixinBootstrap.init()` | 上游把 mixin 的命名空间重映射锁在 `isDevelopment()` 里；改成「两侧命名空间不同且 mappings 里都有」。未混淆版本行为零变化 |
| 逃生舱一致性 | 内核 `NamespacePolicy.current()` | 把 `-Dfabric.runtimeMappingNamespace` 算进判定，否则策略日志与实际不符 |
| 夹具：三命名空间映射 + intermediary 游戏 jar | `experiments/d1-namespace` 的 `PrepareLoaderNamespaces` | 合成 `official → intermediary → named`；把原版游戏 jar 重映射到 intermediary（门闸 G2 要求 remap classpath 是**源命名空间**） |
| 夹具脚本 | `run/prepare-named.sh`、`run/smoke-named.sh` | 一条命令备齐夹具 / 一条命令跑 named 运行时冒烟 |

**缓存键（G9 的约束）**：`RuntimeModRemapper` 用**一个** `TinyRemapper` 处理整批模组并**合并**
各模组的 class-tweaker，所以某个模组的产物取决于同批其它模组。键必须覆盖：每个模组的 jar 内容 +
各自的 class-tweaker 内容 + **class-tweaker 的合并顺序** + 映射文件 + `modNs → runtimeNs` +
remap classpath + mixin remap 类型 + 缓存格式版本。**按单个 mod 算键是错的。**

## 2.2 验收（可执行）

```bash
cd /d/work2/uniloader
export https_proxy=http://127.0.0.1:7897 http_proxy=http://127.0.0.1:7897
./run/prepare-named.sh                     # 夹具（1.21.11 游戏 + 映射 + 中间产物 + 模组）
rm -rf ~/.ul/cache && ./run/smoke-named.sh # 冷：PASS 7/7，日志「重映射完成并写入缓存」
./run/smoke-named.sh                       # 热：PASS 7/7，日志「重映射缓存命中 … 跳过 48 个模组」
```

| 指标 | 冷启动 | 热启动 | 原生 Fabric 基线¹ |
|---|---|---|---|
| 冒烟 | PASS 7/7 | PASS 7/7 | PASS 7/7 |
| `remap` 阶段 | 6774 ms | 1452 ms | 0 ms |
| 启动（Loading → Done） | 15 s | 8 s | 8 s |

¹ 基线 = 同一套游戏与模组跑 `UL_NAMESPACE=intermediary`（等价于原生 Fabric 生产配置：两侧同名、零重映射）。

## 2.3 走通这条路时找出来的三个真问题

1. **映射合并把方法名连参数表一起写进了名字**（阶段 0 的 `MergedMappings.extractName`）。
   mojmap 行形如 `985:1001:void startTimerHackThread() -> p`，取值时没去掉 `()`，
   于是 tiny v2 里出现名为 `startTimerHackThread()` 的成员，重命名后字节码里真的多出一个 `()()`。
   **阶段 0 没发现**是因为 D1 实验只拿这个名字当「输出里有没有它」的搜索串，带 `()` 也搜得到。
   已修。
2. **反混淆产物按 `(gameId, gameVersion, 命名空间, loader 版本)` 缓存**
   （`gameDir/.fabric/remappedJars/<id>-<ver>/server-named.jar`），**不含映射内容**。
   映射改了而版本没改时会静默复用上次那份错的 jar，表现是「mixin 目标找不到」这种
   与映射毫无表面关系的错。**换映射后必须清 `.fabric/remappedJars/`。**
3. **`NamespacePolicy` 没把 `-Dfabric.runtimeMappingNamespace` 算进判定**，逃生舱一开，
   策略算出的判定（`runtime=named`、`remap=true`）就与内核实际用的值不一致——**日志说假话**。
   已修，并加了单测断言两者一致。

## 2.4 新增的坑（G12–G15）

| 编号 | 事实 | 处理 |
|---|---|---|
| **G12** | 映射合并的成员名必须去掉参数表（`startTimerHackThread` 而不是 `startTimerHackThread()`）；tiny v2 的成员名是**裸名** | 换/改映射后逐成员核对，别只看「有没有映射」 |
| **G13** | 反混淆游戏 jar 的缓存键不含映射内容 | 换映射后清 `run/game*/.fabric/remappedJars/`，否则静默复用旧 jar |
| **G14** | tiny-remapper 的 `MixinExtension` **会**改写带描述符的注解字符串（`@Inject(method="name(desc)ret")`、`@At(target="Lowner;name(desc)ret")`）。D1 报告「MixinExtension 不改写 @Inject 字符串」的观察基于**裸名样本**，不能推广 | 判定 mixin 注解字符串的命名空间时，一律用**真实 mod jar**验证 |
| **G15** | 缓存命中时 `remap` 阶段不是 0：嵌套模组要落盘、缓存键要按内容算，48 个模组约 1.4 s | 判据看**总启动与基线之差**，不是这一项归零。要再压就把键改成从父 jar 条目直接算 |

## 2.5 与 v1.2 的差异（都有理由）

1. **T1.2 从「降级 P2」改为「完成」。** v1.2 的降级理由（26.3 上零收益、无法验收）在当时成立，
   但前提是「只支持 26.3」。需求明确要支持 1.x 后，前提不成立——这条路**是**可验收的，
   只是要先铺夹具（1.21.11 游戏 + 三命名空间映射 + intermediary 版游戏 jar）。
2. **`NamespacePolicy.current()` 新增逃生舱分支**：这是 v1.2 的一处遗漏（策略不知道
   `fabric.runtimeMappingNamespace` 的存在），修完策略的判定与内核实际用的值必然一致。
3. **`MergedMappings.extractName` 修正**：阶段 0 的 `writeTiny` 也受影响（它同样写出带 `()` 的
   成员名），只是当时没有真实消费者。

## 2.6 风险登记册更新

| ID | 原判断 | 更新后 | 依据 |
|---|---|---|---|
| **R1** D1 实验失败 | 影响高 / 概率低（26.3 上不适用） | **影响高 / 概率低 → 机制已端到端验证** | 1.21.11 + 48 个真实模组跑通；D1 的「机制可行」从合成样本升级为真实端到端 |
| **R7** 性能不可接受 | 影响中 / 概率中（已有基线） | **影响中 / 概率低** | 冷 6774 ms → 热 1452 ms；热启动与原生 Fabric 同秒（8 s vs 8 s） |
| **R11** 兼容层代码无上游测试覆盖 | 影响中 / 概率中 | **不变** | T1.2 新增 `RemapCache` 的 9 个单测；内核单测密度保持 |
| **新增 R13** | 混淆版本路径上的**映射质量**直接决定成败（G12 那类畸形名会让整条链静默错位） | 影响高 / 概率中 | 换/改映射后必须重跑 `run/prepare-named.sh` 并清 `.fabric/remappedJars/`；阶段 2 起凡引入新映射来源都要走一遍 |

## 2.7 给阶段 2 的补充提醒

1. **支持 1.x 的入口已经就位**：`NamespacePolicy` 的三条路径 + `run/prepare-named.sh` /
   `run/smoke-named.sh`。要验 Quilt 在混淆版本上的行为，直接 `UL_MC_VERSION=<版本> ./run/prepare-named.sh`。
2. **`run/smoke-foreign.sh` 在受限环境下可能被删除保护拦下**（它临时把别家 jar 复制进
   `run/mods` 再撤掉）。可改用独立目录：把两边的 jar 复制进 `run/mods-mixed`，
   再 `UL_MODS_DIR=run/mods-mixed ./run/smoke.sh --matrix=foreign`。
3. **改「Mixin 之前」管线仍必须哈希对拍**；改了缓存键的组成时记得同步 bump
   `RemapCache.FORMAT_VERSION`（等价于整体作废缓存，比事后删目录可靠）。

## 2.8 验收签署

阶段 0（T0.1–T0.7）+ 阶段 1（T1.1–T1.6）共 **13 条任务**已逐条验收，签署文件
**`uniloader/docs/stage1/ACCEPTANCE.md`**。验收方式是「从零重跑」而不是复述：

- `./gradlew clean :loader:clean` → `./gradlew build --no-build-cache --rerun-tasks`
  → **41/41 任务全部执行，BUILD SUCCESSFUL**（不依赖任何残留产物）；
- `./run/preflight.sh` → **OK 12 / WARN 1 / FAIL 0**（WARN 只是「有未提交改动」= 签署提交本身）；
- 四条冒烟路径：纯 Fabric **7/7**、别家生态混装 **10/10**、崩溃对照 **10/10**、
  **混淆版本（1.21.11）named 运行时 7/7**；
- D1 实验 **8/8 PASS**，映射覆盖率 100%/100%。

验收过程中又修掉 5 个问题（映射成员名、反混淆 jar 缓存键、逃生舱日志、`env.sh` 相对路径、
根目录缺 `LICENSE`），都写在 `ACCEPTANCE.md` 第 6 节。**未验收项也在那里逐条列明**
（客户端进世界、注入成功率、缓存固定开销、`HOOK_INJECTION` 位置、两个未闭环许可证）。

---

*文档版本 v1.4（阶段 1 补记：T1.2 撤销降级、混淆版本支持落地）。后续每个阶段完成后继续在本节之后追加。*
