# UniLoader 依赖许可证核实（T0.7）

> 本文是阶段 0 的验收产出，对应方案附录 B 的 7 条待核实清单。
> 核实方式分三类，每条都注明来源，**不采信记忆**：
> - **POM**：从上游 Maven 仓库直接读 `<licenses>` 段（命令见文末）
> - **API**：GitHub REST API 的 `license.spdx_id`
> - **文件**：构件内部自带的许可文件（如 `about.html`）
>
> 核实时间：2026-10-07。

---

## 1. 结论摘要（先看这个）

| # | 结论 | 对项目的影响 |
|---|---|---|
| 1 | **`net.fabricmc:tiny-remapper` 是 LGPL-3.0**，不是 Apache-2.0 | 内核 jar 里**已经**含有 LGPL-3.0 代码（上游 fabric-loader 就如此，且被 relocate 进 `net.fabricmc.loader.impl.lib.tinyremapper`）。因此「内核 jar 是 Apache-2.0」这句话必须更精确：**内核源码是 Apache-2.0，但分发内核 jar 需同时满足 LGPL-3.0 的义务** |
| 2 | **`org.ow2.sat4j` 的构件声明 EPL-1.0**（`about.html` 明示），不是常说的「EPL/LGPL 双许可」 | 同样被 relocate 进内核 jar；必须保留其 `about.html` 并注明来源 |
| 3 | **`net.neoforged:bus` 是 `LGPL-2.1-only`**（POM 明示） | 确认 D3 的 `compat-neoforge` 必须独立 jar；且**不得**与含 LGPL-3.0 的内核 jar 合并（见第 4 节） |
| 4 | **Sinytra Connector 是 MIT**（GitHub API） | 方案里「复用前需核实许可证」这条解除：可参考/复用，保留版权头即可 |
| 5 | **Kilt 是 LGPL-2.1**（GitHub API） | 若复用 Kilt 的映射表/特例库，该模块必须按 LGPL-2.1 处理，不能进 Apache-2.0 模块 |
| 6 | **night-config 是 LGPL-3.0**（POM） | 只在 `compat-*` / 工具链中使用，不进 Apache-2.0 内核 |
| 7 | **Mixin（SpongePowered 及 Fabric fork）与 MixinExtras 都是 MIT** | 内核的许可证声明无需因此改动，保留版权头即可 |

---

## 2. 逐项核实结果

### 2.1 内核（`loader/`）的直接依赖

| 构件 | 版本 | 许可证 | 来源 | 是否被 shade 进内核 jar |
|---|---|---|---|---|
| `net.fabricmc:sponge-mixin`（Fabric 的 Mixin fork） | 0.17.4+mixin.0.8.7 | **MIT** | POM `<licenses>` 明示 `The MIT License` | 否（installer 依赖，运行时由启动器提供） |
| `net.fabricmc:mapping-io` | 0.9.1 | **Apache-2.0** | POM 明示 | 是（relocate 到 `impl.lib.mappingio`） |
| `net.fabricmc:class-tweaker` | 0.3.0 | **Apache-2.0** | GitHub API | 是（relocate 到 `impl.lib.classtweaker`） |
| `net.fabricmc:tiny-remapper` | 0.14.1 | **LGPL-3.0** | GitHub API（`FabricMC/tiny-remapper`） | 是（relocate 到 `impl.lib.tinyremapper`） |
| `org.ow2.sat4j:org.ow2.sat4j.{core,pb}` | 2.3.6 | **EPL-1.0** | 构件内 `about.html` 明示 | 是（relocate 到 `impl.lib.sat4j`，并复制 `about-sat4j.html`） |
| `io.github.llamalad7:mixinextras-*` | 0.5.5 | **MIT** | 上游仓库 LICENSE | 否（dev 依赖） |
| `org.ow2.asm:*` | 9.10.1 | **BSD-3-Clause** | 上游 | 否（installer 依赖） |
| `net.minecraft:launchwrapper` | 1.12 | 见上游 | — | 否（installerLaunchWrapper） |

### 2.2 Forge / NeoForge 侧

| 构件 | 版本 | 许可证 | 来源 | 备注 |
|---|---|---|---|---|
| `net.neoforged:bus` | 8.0.5 | **LGPL-2.1-only** | POM `<licenses>` 明示 SPDX `LGPL-2.1-only` | 决定 D3：`compat-neoforge` 独立 jar |
| `net.neoforged:fancymodloader`（`loader` 分类器） | 12.0.8 | **待复核** | GitHub API 返回 `NOASSERTION` | NeoForge 仓库 `README-LICENSE.md` 声明整体为 LGPL-2.1-only；**阶段 4 引入前必须再确认一次**（见第 5 节） |
| `NeoForge`（`universal` jar） | 26.3 | **LGPL-2.1-only** | 仓库 `README-LICENSE.md` | 仅抽取 `net/neoforged/**`，剔除 MC 类 |
| `MinecraftForge`（`universal` jar） | 26.3 | **LGPL-2.1-only** | 仓库 `LICENSE.txt` | patches 目录著作权归 Minecraft Forge 项目 |
| Forge `eventbus` | 8.0.5 | **待复核** | — | 阶段 8 前复核；若与 bus 一致则为 LGPL-2.1-only |

### 2.3 Quilt 侧

| 构件 | 版本 | 许可证 | 来源 |
|---|---|---|---|
| `quilt-loader` | 0.31.0-beta.4 | **Apache-2.0** | GitHub API（`QuiltMC/quilt-loader`） |
| `quilt-mappings` | — | **CC0**（方案记载） | 上游（需在阶段 2 复核一次） |

### 2.4 同类项目（参考 / 复用）

| 项目 | 许可证 | 来源 | 能否复用 |
|---|---|---|---|
| **Sinytra Connector** | **MIT** | GitHub API（`Sinytra/Connector`） | ✅ 可复用，保留版权头 |
| **Kilt** | **LGPL-2.1** | GitHub API（`KiltMC/Kilt`） | ⚠️ 复用其代码/映射表的模块必须按 LGPL-2.1 处理 |
| **Forbric** | Apache-2.0 | 仓库 LICENSE | ✅ 已在移植（见 NOTICE 第二节） |

### 2.5 工具链

| 构件 | 版本 | 许可证 | 来源 |
|---|---|---|---|
| `com.electronwill.night-config:core` | 3.8.1 | **LGPL-3.0** | POM `<licenses>` 明示 |
| `com.diffplug.spotless` / `checkstyle` / JUnit 等构建期工具 | — | Apache-2.0 / EPL / EPL-2.0 | 上游；只在构建期使用，不进入分发物 |

---

## 3. 对仓库许可证矩阵（方案第 5.2 节）的修正

方案原文把内核简写成「Apache-2.0」。核实后需要精确化为：

| 模块 | 源码许可证 | 分发物的许可证状况 |
|---|---|---|
| `loader/`（Fabric fork） | Apache-2.0 | **混合**：自有代码 Apache-2.0，但打包后含 **LGPL-3.0**（tiny-remapper）与 **EPL-1.0**（SAT4j）。分发时须提供这两个组件的源码获取方式，并保留 `about.html` |
| `compat/compat-api`、`tooling/*`、`experiments/*` | Apache-2.0 | 纯 Apache-2.0（零外部依赖，见各 `build.gradle`） |
| `compat/compat-neoforge`、`compat/hooks-neoforge` | **LGPL-2.1-only** | 独立 jar（D8） |
| `compat/compat-forge`、`compat/hooks-forge` | **LGPL-2.1-only** | 独立 jar（D8） |
| `compat/compat-quilt` | Apache-2.0 | 纯 Apache-2.0（移植自 Apache-2.0 的 quilt-loader） |
| `compat/quirks` | 混合 | 每条特例标注来源许可证 |

---

## 4. 新发现的硬约束：LGPL-2.1-only 与 LGPL-3.0 不能合并（风险 R6 的加强）

`compat-neoforge` / `compat-forge` 必须保持 **LGPL-2.1-only**（因为派生自 NeoForge / Forge 的 LGPL-2.1-only 代码），
而内核 jar 内含 **LGPL-3.0** 的 tiny-remapper。

**LGPL-2.1-only 不允许「升级到更高版本」，因此这两者不能合并进同一个 jar。**

这条比方案原文的 D8「分 jar 发布」更强：不只是「应该分」，而是**法律上必须分**。
具体落地：

1. CI 的 `checkLicenseBoundaries`（根 `build.gradle`）已禁止 Apache-2.0 模块的 jar 内出现 `net/neoforged/**`、
   `net/minecraftforge/**`、`net/minecraft/**`；阶段 4/8 引入 LGPL 模块后，要再加一条**反向**检查：
   **LGPL-2.1-only 的 jar 内不得出现 `net/fabricmc/loader/impl/lib/tinyremapper/**`**。
2. 安装器拼装 classpath 时，内核 jar 与 `compat-*` jar 必须是独立条目，不得再打包成一个 fat jar。

---

## 5. 仍未闭环的项（交给后续阶段，不是本次遗漏）

| 项 | 什么时候必须闭环 | 原因 |
|---|---|---|
| `net.neoforged:fancymodloader` 的确切许可证 | **阶段 4 的 T4.1 之前** | GitHub 标注 `NOASSERTION`，POM 无 `<licenses>`；要么查到 NeoForge 仓库内的 LICENSE 文件，要么向上游确认 |
| Forge `eventbus` 的确切许可证 | 阶段 8 的 T8.1 之前 | 同上 |
| `quilt-mappings` 的 CC0 声明 | 阶段 2 的 T2.1 之前 | 方案里是记载，未实测 |
| Mixin 的 **Fabric fork** 与上游 SpongePowered 版本是否许可一致 | 阶段 1 的 T1.1 之前 | 已确认 Fabric fork 的 POM 声明 MIT，但 fork 可能加入了第三方代码，需读其 LICENSE.txt 全文 |

---

## 6. 复核命令（可重跑，结果可复现）

```bash
# 1) 从 POM 读许可证
curl -sS https://maven.fabricmc.net/net/fabricmc/sponge-mixin/0.17.4+mixin.0.8.7/sponge-mixin-0.17.4+mixin.0.8.7.pom | grep -A4 -i '<licenses>'
curl -sS https://maven.neoforged.net/releases/net/neoforged/bus/8.0.5/bus-8.0.5.pom | grep -A4 -i '<licenses>'
curl -sS https://repo1.maven.org/maven2/com/electronwill/night-config/core/3.8.1/core-3.8.1.pom | grep -A4 -i '<licenses>'

# 2) 从 GitHub API 读许可证
for r in Sinytra/Connector KiltMC/Kilt FabricMC/tiny-remapper FabricMC/mapping-io SpongePowered/Mixin quiltmc/quilt-loader; do
  printf '%-28s ' "$r"
  curl -sS "https://api.github.com/repos/$r" | python -c "import json,sys; print((json.load(sys.stdin).get('license') or {}).get('spdx_id'))"
done

# 3) 读构件内部的许可文件（SAT4j 的 about.html）
python - <<'PY'
import glob, zipfile
jar = glob.glob('**/org.ow2.sat4j.core-2.3.6.jar', recursive=True)[0]
print(zipfile.ZipFile(jar).read('about.html').decode('utf-8', 'replace')[:400])
PY
```

> 本机若走 HTTP 代理，先 `export https_proxy=http://127.0.0.1:7897`。
