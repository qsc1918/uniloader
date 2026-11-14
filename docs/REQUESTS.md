# 待办与需求清单（阶段 2 缺口 + 1.14 兼容）

> 这份是给**人**看的行动清单：哪些我自己做、哪些需要你决策、哪些需要你动手。
> 每一条都注明了「为什么」和「怎么算成功」，不要只看标题。
> 更新时间：2026-10-11。真源仍是 `AGENTS.md`（阶段任务编号以它为准）。

---

## 0. 先说结论：**1.14 与 1.14.4 的服务端都跑通了**（2026-10-11 实测）

| 版本 | 命名空间路径 | 冒烟结果 | 关键证据 |
|---|---|---|---|
| **1.14** | 无 mojmap → **intermediary 运行时、零重映射** | **PASS 10/10** | `Loading Minecraft 1.14` → `Done (11.798s)` |
| **1.14.4** | 有 mojmap → **named 运行时 + 模组重映射** | **PASS 10/10** | mojmap 覆盖率 100%（4976 类 / 40155 成员）；42 个模组重映射入缓存；`为 mixin 装上命名空间重映射：intermediary → named`；`Done (12.563s)` |

**客户端**在无头环境也跑通了（无头能推到的最远处）：

| 版本 | 模组数 | 推进到 | 崩溃 |
|---|---|---|---|
| 1.14 | 44 | `Setting user` → `LWJGL 3.2.1` | 0 |
| 1.14.4 | 59 | `Setting user` → `LWJGL 3.2.2` → `OpenAL initialized` → `Sound engine started` → 世界加载 | 0 |

客户端部分我做到「无头环境能推到的最远处」：`Loading 47 mods` + CLIENT 环境的 Mixin 初始化完成，
再往后就需要显卡了 —— 剩下的交给你，见 **`docs/CLIENT-TEST.md`**（里面有运行命令与判定标准）。

### 复现命令

```bash
cd /d/work2/uniloader
export https_proxy=http://127.0.0.1:7897 http_proxy=http://127.0.0.1:7897

./run/prepare-legacy.sh 1.14   /d/work2/1.14fabric
./run/prepare-legacy.sh 1.14.4 /d/work2/1.14.4fabric
./run/smoke-legacy.sh 1.14        # 期望 PASS 10/10
./run/smoke-legacy.sh 1.14.4      # 期望 PASS 10/10

# 客户端（需要显示器；见 docs/CLIENT-TEST.md）
./run/prepare-legacy.sh 1.14   --client /d/work2/1.14fabric
./run/client-legacy.sh 1.14
```

## 0.0 你下载的模组里有一个版本不对（已自动拦下）

`rightclickharvest-2.0.0.jar`（1.14 与 1.14.4 两个目录里都有）是**用 Java 17 编译的**，
不是 1.14 时代的构建。它会 mixin 1.14 的 `Block`，而 **Mixin 会把目标类提升到与 mixin 类
相同的 class file 版本** → `Block` 变成 class file 61，Java 8 只认到 52 → 整批类加载失败。

报错长得像「游戏 jar 坏了」：

```
net/minecraft/class_2248 has been compiled by a more recent version
(class file version 61.0), this version of the Java Runtime only recognizes
class file versions up to 52.0
```

而游戏 jar 一个字节都没动过。**现在 `prepare-legacy.sh` 会在拷模组时就检查字节码版本并跳过**，
并打印原因。如果你手上有真正 1.14 版的 RightClickHarvest，放进来就能用。

## 0.1 原始结论（保留）：1.14 已经跑起来了

```
[09:56:05] [main/INFO]: Loading Minecraft 1.14 with Fabric Loader 0.19.5
[09:56:05] [main/INFO]: Loading 5 mods:
[09:56:15] [Server thread/INFO]: Done (6.447s)! For help, type "help"
```

用 Java 8（`C://Program Files\Zulu\zulu-8`）启动，**服务端 6.4 秒完成启动**，
世界生成完毕。也就是说「旧版本能不能跑」这个问题已经有了答案：**能**。

> 复现命令（当前需要手动拼 classpath，脚本化在 B3/B4）：
> ```bash
> G=/d/work2/uniloader/run/game-1.14
> export https_proxy=http://127.0.0.1:7897
> UL_GAME_DIR=$G ./run/fetch-game.sh 1.14          # 已修：fat jar 不再挂 libraries
> BASE=$(UL_GAME_DIR=$G bash run/loader-classpath.sh --no-game)
> cd $G && "/c/Program Files/Zulu/zulu-8/bin/java" \
>   -Dfabric.gameDir=$G -Dfabric.modsFolder=$G/mods \
>   -cp "$BASE;$(cygpath -w $G/server.jar)" \
>   net.fabricmc.loader.impl.launch.knot.KnotServer --gameDir $G --nogui
> ```

### 这一轮实测新发现的两个硬坑（都已修）

1. **1.14 的服务端 jar 是自包含 fat jar**（24464 个条目，自带 netty / guava /
   **log4j-core**），而 1.18+ 是 bundler（165 个条目 + `META-INF/libraries/`）。
   对 fat jar 再挂一遍 version json 里的 libraries，等于把 log4j 塞进 classpath 两份：
   log4j2 的插件表读不出来 → 每条日志格式都被判成 `Unrecognized format specifier` →
   回退成「只往控制台打 error」。
   **症状极其误导**：游戏其实正常跑起来了、世界都生成了，但**一行日志都没有**，
   看起来像「卡死在启动阶段」。我一开始就是这么误判的（以为是线程死锁，去抓 jstack）。
   已修：`fetch-game.sh` 检测形态，fat jar 不挂 libraries。
2. **`fetch-game.sh` 用 Python 的 urllib 下载，走代理直接连不上**（WinError 10061）。
   仓库里早就写过「脚本里的网络一律用 curl」，这个脚本漏了。已修。

## 0.1 素材可得性（核实过）

我核实了 1.14 需要的全部素材，**都能拿到**：

| 素材 | 状态 |
|---|---|
| MC 1.14 / 1.14.4 服务端 jar | 可得（Mojang piston-meta，HTTP 200） |
| 1.14 的 42 个库 / 1.14.4 的 53 个库 | 随 version json 可得 |
| `net.fabricmc:intermediary:1.14` | 可得（maven.fabricmc.net，HTTP 200） |
| 1.14 的 Fabric 模组 | Modrinth 上有 **723** 个；1.14.4 有 965 个 |
| Java 8 运行时 | **本机已有**：`C:\Program Files\Zulu\zulu-8`（JDK，含 javac）；另有 JRE `C:\Program Files\Java\jre1.8.0_503` |

**已确认挡在路上的两个硬问题**（都是我的代码，我来修）：

1. **`compat/compat-quilt` 是 Java 21 字节码**（class file 65），而内核是 Java 8（52）。
   1.14 必须跑在 Java 8 上 → 这个模块的类在 Java 8 上会直接 `NoClassDefFoundError`。
   根因：根 `build.gradle` 的 `subprojects` 只设了 toolchain 21，没设 `release`。
2. **`run/env.sh` 硬编码要求 Java 25**。1.14 需要 Java 8，得改成「按目标 MC 版本挑 Java」。

---

## 一、需要你决策（我无法替你定，会直接影响我怎么做）

### D1. 目标小版本：1.14 还是 1.14.4？还是两个都要？

**这不是「顺手都做」的问题，两条路的技术路径不一样：**

| | MC 1.14 | MC 1.14.4 |
|---|---|---|
| Mojang 官方 mappings | **没有**（1.14.4 才开始发布 `client_mappings`） | 有 |
| 命名空间判定 | 没有 mojmap → 退回 **intermediary 运行时**，模组也是 intermediary → **零重映射** | 有 mojmap → 走 D1 的 **named 运行时 + 重映射** |
| 技术含义 | 等价于「原生 Fabric 的生产行为」，**最简单、最稳** | 复用 T1.2 在 1.21.11 上验过的那条重映射链 |

**我的建议：两个都要，顺序是 1.14 先、1.14.4 后。**
- 1.14 先做：它是最短的路径，能最快回答「旧版本到底能不能跑」这个问题；
- 1.14.4 后做：它证明「重映射链在 2019 年的映射上也能跑」，而 1.14.4 是 1.14 时代真正的主力版本（模组更多）。

**要你回答**：只做 1.14 / 只做 1.14.4 / 两个都做（我推荐第三个）。

### D2. 1.x 的验收抽样范围

「兼容 1.x」不能靠「跑通一个版本」来宣称。我建议按**时代**抽样四个版本：

| 抽样版本 | 代表什么 | 备注 |
|---|---|---|
| **1.14** | 最老、无官方 mappings、零重映射 | 你的目标 |
| **1.16.5** | 有 mojmap 的早期、1.x 里最热门的长期版本 | 3191 个模组 |
| **1.18.2** | 中期、bundler 之前的最后一批 | 5664 个模组 |
| **1.21.11** | 最后一个混淆版本 | T1.2 已经验过 |

**要你回答**：这四个够不够？还是你只要 1.14 + 26.3 两端？

### D3. 1.x 的验收标准到哪一步？

- **无头服务端启动 + 模组加载**（我现在就能自动跑，判定标准明确）；
- 还是**必须包含「真实客户端进世界」**（需要你配合，见 R1）。

**要你回答**：1.x 上「算成功」的门槛是什么。

---

## 二、需要你动手（我做不到）

### R1. 真实客户端「进世界」测试

**为什么我做不了**：需要显卡、需要下载 assets（几百 MB），无头环境跑不了渲染。

**什么时候需要**：等我把 1.14 的无头冒烟跑通之后。

**我会给你**：一条命令 + 判定标准（看到什么算成功、看到什么算失败、失败时把哪一段日志发我）。

### R2. Modrinth 之外的模组（可能）

**为什么需要你**：1.14 时代大量模组只发在 CurseForge 上，而 CurseForge 的 API 需要 key，
本机没有；我也查过，没有可用的 CurseForge 连接器。

**我先自己做**：先在 Modrinth 上找（723 个 1.14 模组，大概率够）。**只有找不到的时候才会麻烦你。**

**如果你要帮我**：先别动手，等我给出具体清单（mod 名 + 版本 + 为什么需要它）。

### R3. 授权我跑长任务

现在完整 `preflight.sh` 约 25 分钟；加上 1.x 冒烟会到 40–60 分钟。
如果你希望我每一步都跑完整验证（而不是只跑受影响的那条路径），**请明确告诉我**——
否则我会按「只跑受影响的路径」来做，更快但覆盖面小。

---

## 三、我自己做（不需要你，列出来让你知道工作量）

### A. 阶段 2 的缺口（我上一轮自己审计出来的）

| # | 事项 | 状态 |
|---|---|---|
| A1 | `compat/compat-quilt` 字节码降到 Java 8（**1.14 的硬前提**） | **已完成**（两个 jar 都已是 class file 52） |
| A2 | `QuiltModMetadata.setVersion()` 抛异常 → 版本覆盖会让游戏硬崩 | **已完成**（改成可写字段 + 单测） |
| A3 | 根节点 `minecraft` 段被丢弃 → client-only 的 Quilt 模组会在服务端加载 | **已完成**（补解析 + 4 个单测） |
| A4 | 补单测；`compat-quilt` 建测试源码集 | **部分完成**（`QuiltJson5Test` 8 个 + 环境/setVersion 5 个；仓库单测 139 → **152**；`QuiltApi` 与 compat-quilt 模块的测试仍缺） |
| A5 | 嵌套 `quilt.mod.json`（Quilt 模组里嵌 Quilt 模组）没有测试也没有夹具 | 待做（自造夹具） |
| A6 | T2.2 用**真实 QSL 元数据**跑一次真实的 `ModResolver`（不用装 QSL） | 待做 |
| A7 | `docs/stage2/ACCEPTANCE.md`（阶段 0+1 有这份签署文件） | 待做 |
| A8 | `compat/compat-quilt` 接上 checkstyle / spotless | 待做 |
| A9 | T2.3 补一个自造测试 mod（用到更多 `org.quiltmc` 方法） | 待做 |
| A10 | 最终跑一次**完整** preflight（不是 `--quick`） | 待做（冒烟都单独跑过，全绿） |
| A11 | Mixin 自带的 shaded ASM 树 API 缺类（`org.spongepowered.asm.lib.tree.InsnList`），导致**旧式自定义注入点注册失败**（appleskin 1.14 就用了） | 已定位，未修：这是上游 fabric-loader/Mixin 的打包方式（ASM 是 installer 依赖、不随内核分发），不是本仓库引入的。修法是把 relocated ASM 树 API 打进内核 jar 或随 classpath 分发——会牵动许可证与体积，需要单独决策。已在冒烟里单独计数为 NOTE |
| A12 | 老模组审计工具 | **已完成**：`run/check-mod-version.sh`（判它是哪个 MC 版本）+ `run/check-mod-api.sh`（查它引用的 loader API 在内核里是否存在） |
| A13 | Mixin 老包名 ASM shim | **已实现但默认关**（`UL_MIXIN_ASM_SHIM=1`）：实测对 appleskin 1.0.6 无用——注册成功但 apply 时 `AbstractMethodError`。只修「类找不到」，修不了「API 变了」 |
| A14 | 客户端在无头环境能推多远 | **已完成**：1.14（44 mods）/ 1.14.4（59 mods，named+重映射）都到 `Sound engine started`、零崩溃 |

### B. 1.14 / 1.x 兼容

| # | 事项 | 状态 |
|---|---|---|
| B1 | `run/env.sh` 按目标 MC 版本挑 Java | **已完成**（= B9，从官方 version json 的 `javaVersion` 读） |
| B2 | `run/fetch-game.sh` 支持 1.14 的 jar 布局 | **已完成**（fat jar / bundler 分支 + curl 下载） |
| B3 | 老版本夹具脚本 | **已完成**（`run/prepare-legacy.sh`，参数顺序无关 + `--client` + `--exclude`） |
| B4 | 老版本冒烟脚本 | **已完成**（`run/smoke-legacy.sh` + `run/client-legacy.sh`） |
| B5 | 1.14 的真实模组矩阵 | **部分完成**：用户两批共 62 个真实 jar 已实测；`matrix.yml` 尚未固化 |
| B6 | 内核里对 1.14 的兼容点排查 | **部分完成**（fat jar 分支、Java 选择、日志编码、类版本检查）；`McVersionLookup` 等未逐项审 |
| B7 | 1.14 的 Fabric 模组是否需要重映射 | **已完成**：1.14 零重映射、1.14.4 走 named + 重映射，两条都实测跑通 |
| B8 | 1.14 的 mappings 链 | **已完成**（`--mojmap` 变可选 + 两命名空间直通） |
| B9 | `run/env.sh` 按目标 MC 挑 Java | **已完成**（从官方版本 json 的 `javaVersion` 读，不写版本表） |
| B10 | 客户端验证脚本 + 判定标准 | **已完成**（`run/fetch-client.sh` / `run/client-legacy.sh` / `docs/CLIENT-TEST.md`） |

---

## 四、怎么算成功（判定标准，先写下来免得事后扯皮）

### 1.14 冒烟（无头服务端）

**成功**：
- 服务端日志出现 `Done (x.xxxs)! For help, type "help"`；
- 日志里 `Loading N mods:` 列表包含所有装进去的模组 id；
- 没有 `Mixin apply failed` / `NoClassDefFoundError` / `ClassNotFoundException`；
- 没有留下 `mods-report.txt`（它的存在本身就是「有模组没被如实上报」的信号）。

**失败**：以上任意一条不成立。把 `run/game-1.14/logs/smoke-1.14.log` 发我。

### 1.14 客户端进世界（需要你）

**成功**：能创建/进入一个单人世界，画面正常渲染，模组的功能按钮/物品在。
**失败**：崩溃、黑屏、卡在加载。把 `logs/latest.log` + `crash-reports/` 里最新那份发我。

---

## 五、进度（2026-10-11）

**已完成**：A1、A2、A3、A4 的一部分、B2，外加一个 1.14 的端到端启动实测。
仓库单测 139 → **152 个全绿**；26.3 的四条冒烟路径未受影响（baseline 已复检 7/7）。

**下一步（不需要你回答也能做）**：B8（1.14 的 intermediary 链）→ B3/B4（1.14 夹具与冒烟脚本）
→ B5（1.14 的真实模组矩阵）。做完这些，「1.14 上加载真实模组」就有了可执行的验收。

**需要你先回答 D1 / D2 / D3**，才能定抽样范围与验收门槛；否则我会先按
「1.14 + 1.16.5 + 1.18.2 + 1.21.11」这个建议范围推进。


---

# 2026-11-08 更新：第二批模组（62 个）与这一轮实测

## 你给的新目录

- `D:/work2/1.14mods`（24 个）、`D:/work2/1.14.4mods`（38 个）。
- 旧目录 `1.14fabric` / `1.14.4fabric` 已不在。

## 审计结论：这批质量比第一批高很多，但**不是零问题**

一条命令自己复验：`./run/audit-mods.sh /d/work2/1.14mods /d/work2/1.14.4mods`

| 目录 | 确定要排除 | 需要留意 |
|---|---|---|
| 1.14mods | `dark-loading-screen-1.5.0`（`fabric.mod.json` 非法 JSON，且名字 100% 命中 **1.16.5**）、`more_mobs-v1.5.11-mc1.14-26.3.9-mod`（class file **69 = Java 25**，还带 NeoForge/Forge 清单） | `Ksyxis`（1.18.2 的 NeoForge 模组，只识别不加载）、`ears-fabric-1.14-1.4.7`（引用老 Mixin ASM 包名）、`CustomSkinLoader`（client-only bootstrap） |
| 1.14.4mods | `dark-loading-screen-1.5.0`（同上） | `Ksyxis`、`missiles-0.1`（同时带 `META-INF/mods.toml`） |

**名字骗人但实际没问题**的几个（别一刀切）：`swingthrough-1.0.5+1.15`、`merenc-fabric-1.16.x`、
`randomtp-3.3.0+1.15`、`replaymod-1.14.4-2.6.20` —— 后两个的"高字节码"只在
`META-INF/versions/9|10|16/` 里，Java 8 根本不加载它们。**我原来的检查会误杀，已修。**

## 实测（2026-11-08）

| 场景 | 结果 |
|---|---|
| 1.14 服务端，21 个模组（排除 ViaFabric / dark-loading-screen / more_mobs） | **PASS 10/10** |
| 1.14.4 服务端，35 个模组（排除 TechReborn / RebornCore / dark-loading-screen） | 见下方待办 R16 |
| 26.3 baseline 回归 | **PASS 7/7** |

### 两个失败都是**模组集本身**，不是加载器崩溃

1. **1.14 全量（24 个）→ `Incompatible mods found`**：`ViaFabric` 需要
   `fabric-resource-loader-v0`，而这批里配的是老版 `fabric-0.2.7+build.127`。
   排除 ViaFabric 后 PASS 10/10。要留它就补对应版本的 Fabric API。
2. **1.14.4 全量（38 个）→ `There were unfixable conflicts`**：重映射阶段报
   `Mapping target name conflicts detected`，冲突来自 RebornCore 的
   `RebornInventory.setChanged` 与 `RebornExplosion.explode`——它们和 MC 的
   `class_1263.method_5431` / `class_1927.method_8348` 都映射到同一个名字。
   这是 **UniLoader 特有的 named 运行时路径**才会遇到的（原版 Fabric 1.14.4 生产环境
   用 intermediary 运行时、不重映射到 named）。登记为 **R16**，见下。

## 这一轮修掉的脚本 bug（都会让结果「看起来像别的问题」）

| # | 问题 | 后果 |
|---|---|---|
| G30 | 字节码检查把 `META-INF/versions/**` 里的高版本类也算进去 | 误杀 replaymod / randomtp 这种完全能用的多版本 jar |
| G31 | 给 Windows 版 Python 传的路径没 `cygpath -w` | **含中文或方括号**的模组文件名读不到 → 静态检查静默失效，日志还打「带  —— 别家生态模组」 |
| G32 | 清空夹具目录后不复核 `rm` 结果 | 被占用的 jar 留在目录里 → `--exclude` 看起来失效、冒烟反复崩在同一个模组上 |
| G33 | `fabric-loom:1.11-SNAPSHOT` 在线解析会随时间失效 | 一个月后再跑夹具直接失败；已加「在线失败自动 `--offline` 重试」 |

## 新增待办

| # | 事项 | 影响 |
|---|---|---|
| **R16** | RebornCore/TechReborn 的 refmap 与 named 运行时的方法名冲突 | 中：只在 named 路径（1.14.4–1.21.11）上、且装这类模组时触发。要么在 tiny-remapper 层容忍同名映射，要么把这类模组列进专项豁免 |
| **A15** | `docs/REQUESTS.md` 里 B1/B3/B4/B5/B6 的状态此前一直没同步（现已更正） | 文档 |
| **B11** | `tooling/mod-matrix/matrix.yml` 固化这两批实测模组 | 文档/验收 |
| **A16** | 完整 `preflight.sh`（非 `--quick`）仍未跑过一次 | 验收 |

## 现在**需要你做的**（按价值排序）

1. **客户端验证**（唯一我做不了的）：`1.14` 与 `1.14.4` 各跑一次
   `./run/prepare-legacy.sh <版本> --client <新 mods 目录> --exclude=...` →
   `./run/client-legacy.sh <版本>`，进主菜单 + 新建单人世界 + 走动 2 分钟。
   判定标准与失败时发我什么，见 `docs/CLIENT-TEST.md`。
2. **1.18.2 的 QSL + 真·Quilt 模组**：你说 QSL 有 1.18.2–1.21。这是补上
   「真·Quilt 生态 + 重映射路径」端到端验收的唯一缺口（阶段 2 唯一没做实的组合）。
3. **1.16.5 / 1.18.2 / 1.21.11 的模组各一批**（你之前同意的抽样范围）。
   我用同样的 `audit-mods.sh` 先验，再跑冒烟。
4. **ViaFabric / TechReborn 的正确配套版本**（可选）：想让这两个也进矩阵，
   需要给 ViaFabric 配对应的 Fabric API；TechReborn 那对要等 R16 有结论。
5. **授权跑长任务**：完整 `preflight.sh` 约 25–40 分钟，你说一声我就跑。

---

# 2026-11-08 追加：1.14.4 客户端实测崩溃 → 查出 named 路线的系统性问题

## 用户实测到的崩溃

```
Could not execute entrypoint stage 'preLaunch' ... provided by 'replaymod'
Caused by: IllegalStateException: Could not resolve target ClassInfo for net/minecraft/class_3298
    at customskinloader.bootstrap.fabric.v1.TransformerBootstrap.applyMixinTargets
```

**元凶不是 replaymod**（它只是 preLaunch 阶段第一个触发 Mixin 配置初始化的人），
是 `customskinloader-bootstrap 15.1`：它的 `MixinConfigPlugin` 在运行时通过反射
**动态**往 Mixin 内部塞 target（`targetClassNames` / `ClassInfo.addMixin`），
硬编码了 intermediary 名 `net/minecraft/class_3298`。
`class_3298` 在 1.14 / 1.14.4 / 1.15 / 1.16 / 1.18 的映射里**都存在**，
所以不是「类名对不上版本」——是 **named 运行时下游戏 jar 里根本没有这个类**
（已被反混淆成 Mojang 名）。动态加的 target 不走「mixin 命名空间重映射」那条路
（那条只处理 mixins.json 里静态声明的 target）。

## 对照实验（无头环境，全量 35 个模组，不手动排除任何东西）

| 运行时 | 结果 |
|---|---|
| `named` + 重映射（原默认） | 崩：R16 refmap 冲突 / CSL 的 ClassInfo / replaymod `@Inject` 描述符不匹配 |
| **`intermediary` 零重映射** | **`Loading 94 mods` → `Setting user` → `LWJGL 3.2.2` → `OpenAL initialized` → `Sound engine started`，零崩溃**；`Transformed net/minecraft/class_3298` 正常执行 |

named 路线至少三个独立问题，全部源于「模组是按 intermediary 编译/设计的」：

| # | 现象 | 根因 |
|---|---|---|
| 1 | `Unfixable conflicts`（RebornCore/TechReborn） | refmap 方法名与 named 撞车 = **R16** |
| 2 | `class_3298` ClassInfo 解析失败（CustomSkinLoader） | 插件动态 target 硬编码 intermediary 名 |
| 3 | `@Inject` 描述符不匹配（replaymod `Mixin_PostRenderWorldCalback`） | 重映射后签名与 mixin 注解对不上 |

而 **intermediary 零重映射正是原版 Fabric 1.14.4 生产环境的行为**。

## 已落地（不改默认）

- `prepare-legacy.sh` 新增 `--namespace=intermediary|named`：显式选运行时命名空间。
  默认仍是「有 mojmap → named」（不推翻 D1 的判定，1.21.11 的 named 路径已验过）。
- `client-legacy.sh` / `smoke-legacy.sh` 支持 `UL_NAMESPACE` 显式覆盖，
  不改夹具就能做两种运行时的对照。
- `docs/CLIENT-TEST.md` 把 1.14.4 的推荐命令改成 `--namespace=intermediary`，
  **不再需要手动排除任何模组**（脚本会自动跳掉坏清单的 `dark-loading-screen`）。

## 待你拍板的架构决策

**要不要把老版本（≤1.21.11 有混淆的版本）的默认运行时改成 intermediary 零重映射？**

- 支持：贴近原版 Fabric 生产行为、一次绕开上面三个问题、模组零重映射启动更快；
  用户最终目标是「兼容所有有 Fabric 的版本」，不是「统一命名空间」。
- 反对：AGENTS.md 的 D1 实验明确要求 named 路径能跑，且 1.21.11 的 48 模组 named
  验收已通过；改默认会让那部分验收需要重跑并重述。
- 我的建议：**老版本默认 intermediary，named 作为可选实验路径保留**
  （`--namespace=named` 仍可用，D1 的验证结果不丢）。但这属于改方案，等你点头。

在决策之前，客户端验证请一律用 `--namespace=intermediary`。

---

# 决策记录：老版本默认运行时要不要改成 intermediary（待用户拍板，未实施）

> 起因：用户问「AGENTS.md 的 D1 实验明确要求 named 路径能跑，为什么？之前 AI 调研这么写
> 应该有原因，会不会影响后面阶段」。本节把原始论证、代码级依赖、版本窗口、影响清单
> 和选项全部查清列明。**当前代码默认未改动。**

## 一、D1 的原始要求是什么、为什么

`AGENTS.md` 第 55–62 行（调研结论第 4、5 条）原文要点：

> **最大的隐藏障碍是命名空间，不是事件总线。** Fabric 生产环境把 MC 反混淆到
> **intermediary**，而 **NeoForge/Forge 生态在现代版本全部使用 Mojang 官方名（mojmap）**
> 〔**v1.6 勘误**：原文「自 1.20.5 起」已证伪，见 `AGENTS.md` 文末 v1.6〕。
> 两套名字对不上，所有兼容工作都无从谈起。

> **决策 D1（最重要）：把内核运行时命名空间统一到 mojmap**，让 Fabric 侧 mod 加载时
> 重映射到 named。理由：
> 1. Forge/Neo 侧（API jar、846/623 个 patch、AT 文件、coremod）全部基于 mojmap，
>    是**体量最大、最不可能改动的一方**；
> 2. Fabric 侧已有现成重映射通道，且 **Fabric 开发环境本来就跑在 named 上**；
> 3. 只需**单向**重映射（Fabric → named），工作量减半；
> 4. **兼容层的所有 Mixin 代码可以直接照抄 NeoForge patch 的可读 Mojang 名字**，
>    可维护性天差地别。
>
> 代价：所有 Fabric mod 加载时需 remap、Mixin refmap 必须按 named 解析。
> 验证不过则**回退方案 B（内核用 intermediary，Forge/Neo 侧重映射）**。

**所以 D1 的动机不是「兼容老 Fabric 模组」，而是「让 Forge/NeoForge 能和 Fabric 共存」。**
老模组兼容是 10-11 之后才新增的需求，写 D1 时不在考虑范围内——这正是冲突的来源。

## 二、这个依赖有多硬（查过的证据，不是推测）

| 层 | 证据 |
|---|---|
| **代码（已有保护）** | `NamespacePolicy.isCompatible()` 第 387 行：`neoforge`/`forge` 只有 `decision.isMojmapRuntime()` 才返回 true；`ModDiscoverer.java:290` 对不匹配项调 `reportMismatch` → **DEGRADED 或 WARN，绝不静默**。所以改默认不会悄悄坏，会明确报警。 |
| **数据（真实文件）** | `NeoForge/patches/net/minecraft/world/item/ArrowItem.java.patch` 里全是 `net.minecraft.world.item.ItemStack`、`LivingEntity`；`NeoForge/src/main/resources/META-INF/accesstransformer.cfg` 里是 `public net.minecraft.advancements.triggers.CriteriaTriggers register(...)`。**全是 Mojang 名**，intermediary 运行时下游戏 jar 里一个都不存在。 |
| **阶段 4–8 的任务** | T4.1 注入 NeoForge API jar（其引用的 MC 类是 Mojang 名）；T4.3 AT→class-tweaker 翻译（规则里是 `net.minecraft.world.item.ItemStack f_12345`）；T6.1 `MappedRegistry.frozen` 的 `@Shadow`；T7.1 patch-analyzer 从 846 个 patch 抽出的 `targetClass`/`targetMethod` 全是 Mojang 名 → 翻成 Mixin 后在 intermediary 下全部找不到目标；T8.6 Forge 的 623 个 patch 同理。 |
| **阶段 1 基础设施** | T1.2 `RemapCache`、G3 mixin refmap 重映射、G4 游戏 jar 反混淆、`prepare-named.sh`——都是为 named 造的，1.21.11 上 48 个真实模组验过（冷 6774 ms → 热 1452 ms，热启动与原生 Fabric 同秒）。改默认**不删代码**，但默认路径不再经过它们。 |

阶段 1 HANDOFF 第 1.2 节第 5 条已经把话挑明：

> 混淆版本上运行时是 intermediary 而 NeoForge/Forge 全基于 mojmap，
> 那种组合下**即使阶段 4/8 做完了也跑不起来**——现在说清楚比那时再排查便宜。

## 三、关键洞察：D1 真正起作用的版本窗口很窄

| MC 范围 | 混淆 / 映射 | Forge/Neo 的名字 | D1 named 是否必要 |
|---|---|---|---|
| **26.1+（26.3）** | 不再混淆 | `official == intermediary == named` | **免费得到**，零重映射（AGENTS.md 0.3 节结论） |
| **1.20.2(Neo) / 1.20.4(Forge) – 1.21.11** | 混淆 + 有 mojmap | 已切 Mojang 官方名 | **真正需要 named** |
| **1.14 – 1.20.1** | 混淆 + 有 mojmap（1.14 无） | **运行时是 SRG/MCP**；NeoForge 首个 Release 就是 1.20.1 且高度兼容 Forge（原文写「尚不存在」是硬错误） | **不需要 named**（named 也帮不上 SRG） |

AGENTS.md 第 1001 行自己也写了：

> D1 实验改用 1.21.11 的真实映射，是为了**验证机制**（将来向下支持 1.21.11、
> 或某个生态以别的命名空间分发 mod 时要用）

**即：之前 AI 给 named 的定位就是「验证机制 + 为将来 Forge/Neo 混装铺路」，
不是「所有有混淆的版本都必须跑 named」。** 用户要兼容到 1.14，绝大多数版本落在
D1 不起作用的区间。

另外 D9 决策写明「单一 MC 版本起步（建议 26.3），版本矩阵后置」——
**阶段 4–8 大概率先在 26.3 上做，而 26.3 上 official 就是 named，根本不需要重映射。**
只有等 T11.4「版本矩阵升级流程」把 Forge/Neo 兼容下放到 1.20.2/1.20.4–1.21.11 时，
named 才会重新成为默认需求。

## 四、改默认的影响清单

**好处（已实测）**
- 1.14 / 1.14.4 全量模组跑通：服务端 PASS 11/11、客户端**能进主菜单和进世界**（用户 11-14 确认）。
- 一次绕开 named 路线的三个已知问题：R16 refmap 冲突、CustomSkinLoader 动态 target 的
  `class_3298` ClassInfo、replaymod `@Inject` 描述符不匹配。
- 与原版 Fabric 生产行为一致；零重映射 → 无缓存、无 refmap 风险、启动更快。

**代价 / 风险**
1. **阶段 4–8 在 1.20.2/1.20.4–1.21.11 做 Forge/Neo 混装时必须显式切回 named**。
   不切的话 `isCompatible` 会把 NeoForge/Forge mod 记 DEGRADED（**会明确报警，不会静默**）。
2. **验收口径要重述**：阶段 1 v1.4 的 T1.2（1.21.11、48 mods、冷/热缓存）是 named 路径
   的验收。改默认后这些命令要显式 `--namespace=named`；**代码、机制、验收结果一个都不丢**，
   只是从「默认路径」变成「显式路径」，文档要说清楚。
3. **新增一条长期漂移风险**：named 路径若长期不走默认，阶段 4 重新启用时可能已腐化。
   建议加护栏：`preflight.sh` 保留一条 named 冒烟（这是唯一新增的工作量，约半天）。
4. **AGENTS.md 需回写**：D1 决策表、第 5 节理由、阶段 1 的 1.2 节差异说明、T1.1 验收描述
   都要加限定语（「named 用于需要 Forge/Neo 混装的混淆版本」）。属文档工作，不是代码。
5. **`isCompatible` 的报错文案要更具体**：改默认后用户更容易撞上，应提示
   「检测到 NeoForge/Forge mod，请用 --namespace=named 重跑」。
6. **时序限制**：`NamespacePolicy.current()` 在 `FabricLoaderImpl.setup():226`，
   `discoverMods` 在 `:245` —— **决策发生在模组发现之前**。所以「看到 Forge mod 就自动切
   named」在当前架构下做不到，只能由启动参数/安装器/兼容层声明来驱动。

## 五、三个选项

| 选项 | 做法 | 后续阶段影响 | 近期体验 |
|---|---|---|---|
| **A（我推荐）** | 老混淆版本默认 `intermediary`；named 保留为 `--namespace=named` 显式路径；阶段 4 开工前加 named 回归护栏 | 阶段 3 零变动；阶段 4+ 每个验收命令加 `--namespace=named`（26.3 上其实不需要）；文档回写 | 1.14/1.14.4 开箱即用，不用带参数 |
| **B（最保守）** | 默认不动，只把 `--namespace=intermediary` 固化进验证手册（现状） | 零变动 | 每次都要带参数；默认路径一装真实老模组就崩 |
| **C（不推荐）** | 按 MC 版本窗口自动选 | 需引入版本号表 / 或按生态预判 | 表面最省事，但**违反工程纪律第 4 条（禁硬编码版本号）**和 T1.1「判定依据是有没有 mappings，不比版本号」的约定；且时序上做不到按生态自动切 |

我的判断是 **A**。理由：D1 的硬依赖只落在「1.20.2/1.20.4–1.21.11 上做 Forge/Neo 混装」这个
**尚未排期**的场景；D9 又规定版本矩阵后置，阶段 4–8 先在 26.3（named 免费）上做。
用一个「现在真实发生、已复现三次」的兼容问题，去迁就一个「将来才可能、且已有
DEGRADED 保护」的场景，不划算。但这属于改 D1 方案，**等用户拍板后再动**。

## 六、如果选 A，后续计划的具体变动（逐阶段）

| 阶段 | 变动 |
|---|---|
| 阶段 3（Forge/Neo 发现层，T3.1–T3.6） | **零变动**。本来就只发现不运行，`isCompatible` 记 DEGRADED 正是设计行为。 |
| 阶段 4（NeoForge 运行时骨架） | 新增前置一条：确认夹具是 named（26.3 上自动满足）；若目标 MC 是 1.20.2/1.20.4–1.21.11，所有验收命令显式 `--namespace=named`。不改 T4.1–T4.5 的内容。另按 v1.6 勘误，1.20.1 与 1.20.2+ 要分开设计。 |
| 阶段 5 / 6 / 7 / 8 | 所有 hook / Mixin 验收仍在 named 下跑，与现文档一致；只需在夹具脚本里固化命名空间，不加新任务。 |
| 阶段 9–11 | 无变动；T11.4 版本矩阵流程里补一条「下放到混淆版本时切 named」的检查项。 |
| 阶段 1 文档 | T1.2 / v1.4 验收加「显式路径」限定；D1 决策表加限定语；R1 更新为「named 路径长期不跑默认的漂移风险」。 |
| 新增 | `preflight.sh` 加一条 named 冒烟护栏（防漂移），约半天工作量。 |

**未选之前**：客户端/服务端验证一律继续用 `--namespace=intermediary`。

---

# Forge/NeoForge 侧兼容性详细评估（改默认会不会有大问题）

> 用户的核心担心：新版本官方不混淆、天生统一；**旧版本**会变成怎样？Fabric 侧
> 改成 intermediary 没事甚至更好，但 Forge/NeoForge 那边兼容性有没有大问题？
>
> 本节证据只取：本仓库代码、官方 GitHub 仓库逐分支构建脚本、已实测的冒烟结果。
> **Forbric 是明确标注的反面教材，其自述文档不作为本节证据**（用户 11-14 提醒）。

## 结论先行

| MC 段 | 改默认后 Forge/Neo 的处境 | 是否"大问题" |
|---|---|---|
| **26.1+（不混淆）** | **完全不受影响**，与现在一模一样 | 否，数学上确定 |
| **1.14 – 1.20.1（运行时 SRG）** | **现在就不兼容**，改默认反而从"误判兼容"变成"明确降级" | 否，是改善 |
| **1.20.2(Neo) / 1.20.4(Forge) – 1.21.11（混淆 + mojmap）** | 从"直接可用"变成"需要重映射" | **唯一真实代价**，但不是不兼容 |

## 1. 新版本（26.1+）为什么零影响 —— 代码级证明

`NamespacePolicy.Decision.isMojmapRuntime()`（内核源码）：

```java
return NS_NAMED.equals(runtimeNamespace)
        || (NS_OFFICIAL.equals(runtimeNamespace) && !mappingsPresent);
```

26.3 没有 mappings、`runtimeNamespace = official` → **返回 true**，
`isCompatible("neoforge"/"forge", ...)` 因此返回 true。

更关键的是**改默认改不到它**：`--namespace=intermediary` 只在
`prepare-legacy.sh` 里生效，而 26.3 走的是 `run/server.sh` / `client.sh`
的 `official` 默认分支，根本不经过老版本夹具脚本。26.3 上
`official == intermediary == named` 三者同名，"选哪个"在物理上没有区别。

本地参考仓库也在 26.3：`NeoForge/` 是 `26.3.x` 分支
（`minecraft_version=26.3`），`MinecraftForge/` 是 `26.3` 分支。
那些全是 Mojang 名的 patch / AT 在 26.3 上直接可用。
**D9 又规定先在 26.3 做阶段 4–8 → 近期工作零影响。**

## 2. 1.14 – 1.20.1（SRG 时代）：改默认没有损失，反而更诚实

官方证据：NeoForge `1.20.1` 分支的 `build.gradle` 里是
`MAPPING_CHANNEL='parchment'`、`srgPatches=true`、`createJoinedSRG`、`MCP_MAPPINGS`；
MinecraftForge `1.20.1` 虽编译用 `official`，运行时仍生成 SRG。
**这一段的 Forge mod 引用的是 SRG 名——既不是 intermediary，也不是 mojmap。**

- 当前 **named 默认**下：`isMojmapRuntime()` 为 true → `isCompatible` 返回 true，
  即**误判为兼容**（阶段 3–8 尚未开工，所以这个漏洞还没暴露）。
- 改成 **intermediary 默认**：返回 false → 明确记 DEGRADED。
- 要真正跑这一段的 Forge mod，**两种策略都需要额外做 SRG → 目标命名空间
  的重映射**，这是方案里目前完全没有的新工作，与选 A 还是选 B 无关。

所以这一段改默认**不产生新损失**，只是把"假装能用"变成"如实说不兼容"。

## 3. 1.20.2 / 1.20.4 – 1.21.11：唯一真实代价，性质是"重映射换了一边"

| | named 默认（D1） | intermediary 默认（方案 B） |
|---|---|---|
| Fabric mod | 需重映射 intermediary→named | **零重映射** |
| Forge/Neo mod（mojmap） | **直接可用** | **需重映射 mojmap→intermediary** |
| 游戏 jar | 反混淆到 named | 反混淆到 intermediary |

**代价不是"不兼容"，而是把重映射成本从 Fabric 侧搬到 Forge/Neo 侧。**
但要如实说清，这不是零成本：

- 本仓库 `mappings.tiny` 是三命名空间（official → intermediary → named），
  反向边在数据上是存在的，`RuntimeModRemapper` 用的 tiny-remapper 也能反着跑；
- **但** Forge/Neo mod 的重映射面比 Fabric mod 复杂：AT（accesstransformer）、
  coremod、`@Mod` 扫描、mixin refmap、FML 的 reobf 语义、以及 1.20.2+
  与 1.20.1 的差异都要覆盖。当前 `RuntimeModRemapper` 只处理 Fabric mod。
  这是一块**新的、实质性的工程**，不是改个参数。
- 保护仍在：`isCompatible` 会明确报 DEGRADED，**不会静默坏掉**。

**为什么现在不必付这笔钱**：阶段 3–8 一行代码都没开工；D9 规定版本矩阵后置、
先在 26.3（named 免费）上做；真正要下放到混淆版本是 T11.4 之后的事。
到那时可以二选一：显式 `--namespace=named`（开关已经做好），或正式实现方案 B。

## 4. "结果应该不会差多少"这个判断对不对

**基本对，但有一个实质区别要讲清**：不是"零影响"，而是
「把成本从**已开工且天天在用**的 Fabric 侧，挪到**尚未开工**的 Forge 侧」。

- 项目当前目标 = 1.14/1.14.4 老 Fabric 模组 + 26.3 新版本。
  这两段里 Forge 侧成本是 **0**（前者根本没 Forge mod，后者免费）。
- 而 named 默认在 1.14/1.14.4 上已实测三个独立崩溃（R16 refmap、
  CustomSkinLoader 动态 target、replaymod 注入描述符），
  **排除任何模组都绕不开**，只有零重映射能全量跑通。
- 所以眼下的取舍是：一个"现在真实发生、已复现三次"的问题，
  对一个"将来才可能、已有 DEGRADED 保护、可用一个开关解决"的场景。

## 5. 窗口比原先以为的宽了多少、影响多大

原来写 1.20.5–1.21.11，官方核实后是 **1.20.2(Neo) / 1.20.4(Forge) – 1.21.11**，
宽了约 2–3 个小版本。但：

- 这些版本上**一行 Forge/Neo 代码都没写**，`isCompatible` 的 DEGRADED 就是当前全部行为；
- 需要跑 Forge/Neo 混装时 `--namespace=named` 已可用（1.21.11 上 48 模组验过）；
- 唯一新增的是「阶段 3 前置调研要按目标 MC 版本实测映射形态」，已写进
  `docs/stage3/HANDOFF.md`。

**结论不变，仍推荐选项 A。**

## 6. 如果选 A，逐阶段变动（按 v1.6 勘误更新）

| 阶段 | 变动 |
|---|---|
| 阶段 3（发现层） | 零命名空间变动；**新增**按目标 MC 版本实测映射形态、且 1.20.1 与 1.20.2+ 分开设计 |
| 阶段 4（NeoForge 骨架） | 26.3 上零变动（official 即 named）；下放到 1.20.2/1.20.4–1.21.11 时验收命令显式 `--namespace=named` |
| 阶段 5/6/7/8 | 内容不变；阶段 8 不能简单复用阶段 7 的事件/注册表语义（1.20.2+ 已分叉） |
| 阶段 9–11 | T11.4 版本矩阵流程补「下放到混淆版本时切 named」检查项 |
| 阶段 1 文档 / 全局 | `AGENTS.md` v1.6 回写、D1 决策表加限定语、R1 更新 |
| 新增护栏 | `preflight.sh` 保留一条 named 冒烟，防该路径长期不走默认而漂移（约半天） |

**未拍板前**：代码默认未动；客户端/服务端验证继续用 `--namespace=intermediary`。
