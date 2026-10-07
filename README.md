# UniLoader

以 **Fabric Loader 为内核**、目标是同时加载 **Fabric / Quilt / NeoForge / Forge** 四家模组的统一模组加载器。

技术方案的完整论述在仓库外的 `D:/work2/AGENTS.md`（阶段划分与任务编号 `T{阶段}.{序号}` 都出自那里）。
本 README 只讲**怎么用这个仓库**。

---

## 仓库布局

```
uniloader/
├── LICENSE-Apache-2.0 / LICENSE-LGPL-2.1 / NOTICE   ← 许可证分层，见 NOTICE
├── gradle.properties        ← ★ 全仓库唯一的版本真源（T0.2）
├── loader/                  ← fabric-loader fork（Apache-2.0，保留上游完整 git 历史）
├── compat/
│   └── compat-api/          ← L2 统一抽象层：生态标签、三态降级、诊断（Apache-2.0）
├── tooling/
│   ├── crash-attribution/   ← 崩溃归因：栈帧 → jar → mod（Apache-2.0，移植自 Forbric）
│   ├── load-report/         ← 加载报告：每个 mod 的 OK / DEGRADED / FAILED
│   └── mod-matrix/          ← 验收模组清单 + 拉取脚本（不提交 mod 二进制）
├── experiments/
│   └── d1-namespace/        ← T0.3：决策 D1（mojmap 命名空间）可行性实验
├── run/                     ← client.sh / server.sh / smoke.sh（T0.4）
└── docs/
    ├── LICENSES.md          ← T0.7 依赖许可证核实（含两处影响分发的发现）
    └── stage0/              ← 阶段 0 的结论归档
```

## 构建

```bash
./gradlew build            # 全部模块 + 内核（内核是组合构建，见 settings.gradle）
./gradlew check            # 测试 + 许可证边界检查
```

- 构建需要 **Java 21+**（内核要求），运行目标为 **Java 25**（与 NeoForge/Forge 对齐）。
- 受限网络下先设代理，再跑构建（命令见 `docs/stage0/T0.2-version-matrix.md` 第 4 节）。
- Gradle 版本锁定 **9.1.0**（上游指定）；升到 9.7 会构建失败，原因记录在同文档第 3 节。

## 跑实验与冒烟

```bash
# T0.3：D1 命名空间可行性实验（先拉真实映射，约 15 MB）
cd experiments/d1-namespace
./scripts/fetch-mappings.sh 1.21.11 build/d1/mappings
cd ../..
./gradlew :experiments:d1-namespace:run      # 报告写到 experiments/d1-namespace/build/d1/D1-report.md

# T0.4：冒烟（需要先有内核 jar，以及 run/game 里的 MC 原版文件）
./gradlew :loader:build
./run/smoke.sh --matrix=baseline
./run/client.sh      # 客户端
./run/server.sh      # 无头服务端

# T0.6：按矩阵拉取验收模组（不提交二进制）
./tooling/mod-matrix/fetch-mods.sh baseline
```

## 内核改动约定

`loader/` 是上游 fabric-loader 的 fork，**改动一律收敛、并标注**：

- 所有 fork 改动在代码/脚本里以 `UniLoader fork patch (T0.x)` 注释标注；
- 上游跟进：`git subtree pull --prefix=loader fabric-upstream master`；
- 新增代码放 `impl/compat/` 或独立模块，**不动与 Fabric 兼容无关的行为**（工程纪律第 8 条）。

## 许可证边界（硬约束）

- 内核与自研模块：Apache-2.0；Forge/NeoForge 兼容层：**LGPL-2.1-only 且必须独立 jar**。
- `./gradlew checkLicenseBoundaries` 会失败于「Apache-2.0 的 jar 里出现了 LGPL 兼容层或 MC 的类」。
- 已核实的两个额外约束（见 `docs/LICENSES.md`）：
  - 内核 jar 内含 **LGPL-3.0** 的 tiny-remapper 与 **EPL-1.0** 的 SAT4j（上游如此），
    因此「内核 jar 是 Apache-2.0」并不准确；
  - **LGPL-2.1-only 与 LGPL-3.0 不能合并进同一个 jar**，所以兼容层与内核必须分开分发。

## 阶段 0 状态

| 任务 | 状态 | 产出 |
|---|---|---|
| T0.1 仓库骨架 | ✅ | 本仓库；`loader/` 保留上游历史；`./gradlew build` 通过 |
| T0.2 版本矩阵 | ✅ | `gradle.properties` + `docs/stage0/T0.2-version-matrix.md`（含「26.x 不再混淆」的关键事实与证据） |
| T0.3 D1 实验 | ✅ 机制验证通过 | `docs/stage0/T0.3-D1-namespace-experiment.md`（覆盖率 100%/100%，四处门闸清单，适用条件已限定） |
| T0.4 实验台与冒烟 | ✅ 脚本就绪 | `run/{env,client,server,smoke}.sh`；真实 MC 端到端留给阶段 1 |
| T0.5 诊断骨架 | ✅ | `tooling/crash-attribution`、`tooling/load-report` + 单测 |
| T0.6 验收 mod 矩阵 | ✅ 清单就绪 | `tooling/mod-matrix/{matrix.yml,fetch-mods.sh}`；可用性由脚本实测 |
| T0.7 许可证核实 | ✅ | `docs/LICENSES.md`（含 4 项仍未闭环的复核项） |

## 一个必须知道的事实：26.x 不再混淆

**MC 26.1 起 Mojang 不再混淆游戏**，官方版本 json 里已经没有 `client_mappings`
（实测 26.3 / 26.4-snapshot-3 的 `downloads` 只有 `client` / `server`，而 1.21.11 还带
`client_mappings` / `server_mappings`）。因此：

- 26.3 上**不存在也不需要** intermediary 映射——`maven.fabricmc.net` 的 intermediary 停在 1.21.11 是正常现象；
- 内核的运行时命名空间默认就是 `official`（= Mojang 真实名），mod 的分发命名空间也是 `official`，
  **两边天然同名，零重映射**；`run/*.sh` 的默认模式因此是 `official`（不设任何命名空间属性）；
- D1 那套「Fabric → named 重映射」只在目标 MC 仍有混淆（**<= 1.21.11**）时才需要，
  `docs/stage0/T0.3-D1-namespace-experiment.md` 的四处门闸也只在那条路上适用。

**仍未闭环的一件事**：D1 的真实 MC 端到端启动未在本机执行——需要 MC 原版字节码与真实 mod，
脚本与门闸已就绪，属于阶段 1 的 T1.1/T1.2 与第 19 节的 L3 冒烟。
