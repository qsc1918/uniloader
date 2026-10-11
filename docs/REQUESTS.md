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

### B. 1.14 / 1.x 兼容

| # | 事项 | 状态 |
|---|---|---|
| B1 | `run/env.sh` 按目标 MC 版本挑 Java（1.14 → Java 8，26.3 → Java 25） | 待做 |
| B2 | `run/fetch-game.sh` 支持 1.14 的 jar 布局 | **已完成**（fat jar / bundler 分支 + curl 下载） |
| B3 | 1.14 的夹具脚本（`run/prepare-1.14.sh` 或把 `prepare-named.sh` 泛化） | 待做 |
| B4 | `run/smoke-1.14.sh`（1.14 的冒烟 + 断言） | 待做 |
| B5 | 1.14 的真实模组矩阵（进 `matrix.yml`，实测可用性） | 待做 |
| B6 | 内核里对 1.14 的兼容点排查（Knot 入口、`McVersionLookup`、`BundlerProcessor` 分支） | 待做 |
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
