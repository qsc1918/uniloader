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
