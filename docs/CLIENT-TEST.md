# 客户端验证指南（需要你在有显示器的机器上跑）

> 无头环境测不了客户端（需要显卡 + assets），所以这一份是**给你的操作手册**。
> 服务端那边我已经全自动验完了（1.14 与 1.14.4 各 PASS 10/10）；
> 客户端要验的是「游戏真的能进、画面真的对」——这件事机器判不了，得你看。
> 每一条都写了**怎么算成功、怎么算失败、失败时发我什么**。

---

## 0. 先看这个：你下的模组里有 3 个不是 1.14 的构建

我用 `./run/check-mod-version.sh` 把你 `D:/work2/1.14fabric` 里的每个 jar 都验了一遍
（原理：Fabric 模组是对着 **intermediary** 名字编译的，而每个 MC 版本的 intermediary 都不同，
所以「jar 里的 MC 名字能对上哪个版本的映射」就是它真正对应的版本）。

| jar | 判定 | 证据 |
|---|---|---|
| `dynamic-fps-1.0.2.jar` | **不是 1.14**（1.14.4+） | 1.14 只命中 94.4%；它的 mixin 指向 `method_20813`，而该方法只存在于 1.14.4 的 `class_1255`（1.14 的 `class_1255` 是 `agh`，1.14.4 是 `agq`——混淆表完全不同） |
| `EuphoriaPatcher-1.10.5-r5.9.3-fabric.jar` | **不是 1.14**（最像 1.18.2） | 1.14 只命中 48.2%；且它的 `fabric.mod.json` 含裸控制字符，**标准 JSON 读不出来**（Fabric 自己也读不了） |
| `Ksyxis-1.4.6.jar` | **1.18.2 构建**，且是 NeoForge 模组 | 决定性证据：`class_6904` 只在 1.18.2 里存在；jar 里带 `META-INF/neoforge.mods.toml` |
| `rightclickharvest-2.0.0.jar` | 名字对得上 1.14，**但字节码是 Java 17** | class file 61；1.14 只能跑 Java 8 → 照样用不了 |

其余 7 个（CraftPresence / ModLib / ModMenu / UniLib / alternate-current / appleskin /
config-2 / fabric-api / fabric-language-kotlin）在 1.14 上都没问题。

**这不是你点错了**——模组文件名和 CurseForge 的版本标签都不保证内容真的是那个版本。
以后你自己想验任何一个 jar，一条命令：

```bash
./run/check-mod-version.sh <jar> [候选版本...]
# 例：./run/check-mod-version.sh /d/work2/1.14fabric/dynamic-fps-1.0.2.jar 1.14 1.14.4 1.16.5
```

它会同时报**两个独立的维度**：对得上哪个 MC 版本、需要哪个 Java 版本。

---

## 0.1 一次性准备（复制粘贴即可）

```bash
cd /d/work2/uniloader
export https_proxy=http://127.0.0.1:7897 http_proxy=http://127.0.0.1:7897

# 先确认服务端那边是全绿的（我这边已经验过，你复现一下更放心）
./run/smoke-legacy.sh 1.14
./run/smoke-legacy.sh 1.14.4

# 准备客户端夹具（会下客户端 jar + 库 + natives + assets，assets 约 214MB，慢）
#
# --exclude：把「不是这个 MC 版本」的 jar 排掉。静态检查只能判出
# 「字节码版本不对 / 元数据坏了」，判不出「名字对不上这个 MC 版本」——
# 那种只能靠 check-mod-version.sh 验过之后显式排除。
./run/prepare-legacy.sh 1.14 --client /d/work2/1.14fabric \
    --exclude=dynamic-fps-1.0.2.jar,rightclickharvest-2.0.0.jar
./run/prepare-legacy.sh 1.14.4 --client /d/work2/1.14.4fabric \
    --exclude=rightclickharvest-2.0.0.jar
```

**如果你已经有官方启动器**，assets 可以直接复用，省掉最大的一块下载：

```bash
export UL_ASSETS_DIR="$APPDATA/.minecraft/assets"      # 换成你的实际路径
./run/prepare-legacy.sh 1.14 --client /d/work2/1.14fabric --no-assets
```

> `--no-assets` 目前是通过环境变量 `UL_ASSETS_DIR` 生效的：只要设了它，
> 客户端启动时会用你的 assets，不会去 `run/client-<版本>/assets` 找。

---

## 1. 启动客户端

```bash
cd /d/work2/uniloader
export https_proxy=http://127.0.0.1:7897 http_proxy=http://127.0.0.1:7897

./run/client-legacy.sh 1.14
# 或
./run/client-legacy.sh 1.14.4
```

启动器会自动做对三件事（不需要你设任何东西）：
- **Java 版本**：从官方版本 json 的 `javaVersion` 读 → 1.14/1.14.4 都是 **Java 8**；
- **命名空间**：从夹具里的 `.ul-namespace` 读 → 1.14 是 `intermediary`（零重映射）、
  1.14.4 是 `named`（重映射）；
- **assets / natives / classpath**：从 `run/client-<版本>/` 读。

---

## 2. 怎么算成功

请按顺序确认这五条。**任何一条不成立都算失败**，把日志发我。

| # | 看什么 | 成功的样子 |
|---|---|---|
| 1 | 启动日志 | 出现 `Loading Minecraft 1.14.4 with Fabric Loader 0.19.5`，然后 `Loading N mods:` 列表里有你装的模组 |
| 2 | 主菜单 | 能进到主菜单，**背景是 1.14 的原版全景**（不是黑屏、不是紫黑格） |
| 3 | 模组列表 | 有 Mod Menu 的话，`Mods` 按钮点开能看到列表；至少能看到 `Fabric API`、`Mod Menu`、`Cloth Config`、`AppleSkin`、`CraftPresence`、`UniLib` |
| 4 | 进世界 | 新建一个单人世界（创造模式、超平坦最快），**能进去、能看到方块和天空、能走动** |
| 5 | 不崩 | 玩 2–3 分钟不崩、不卡死。切一下 F3 看调试界面正常 |

**加分项**（能验到更好，验不到也不阻塞）：
- 按 `E` 打开背包，AppleSkin 会在食物上显示饥饿值提示 → 说明 mixin 真的注入进去了；
- CraftPresence 在 Discord 上会显示你在玩 1.14.4（需要你开着 Discord）。

---

## 3. 怎么算失败（以及发我什么）

失败有四种典型形态，**对应的问题完全不同**，所以请照下面的方式收集信息：

| 形态 | 表现 | 大概率是什么 |
|---|---|---|
| A. 起不来 | 窗口没出现，或者出现后立刻关掉 | Java 版本 / classpath / 命名空间 |
| B. 卡在加载 | 卡在 `Mojang` 或资源加载界面不动 | assets 不全 |
| C. 进了主菜单但进不去世界 | 点「创建世界」卡住或崩 | mixin 注入 / 注册表 |
| D. 玩着崩 | 能玩一会儿然后崩 | 具体模组的功能 bug |

**不管哪种，都请把这两个东西发我**：

```bash
cd /d/work2/uniloader/run/client-1.14.4     # 换成你跑的那个版本
ls logs/                                    # 应该有 latest.log
ls crash-reports/ 2>/dev/null               # 有的话一并给我
```

- `logs/latest.log`（**完整文件**，不要只截一段——前 50 行里的版本/命名空间信息最关键）
- `crash-reports/` 里最新那份（如果有）
- 一句人话描述：卡在哪一步、屏幕上是黑的还是花的、有没有报错弹窗

**如果窗口根本没出现**（形态 A），额外给我终端上的完整输出——那种情况下 `logs/` 里可能什么都没有。

---

## 4. 已知的、**不算失败**的现象

这些我都遇到过并且确认无害，看到不用慌：

1. **日志里有 `org.spongepowered.asm.lib.tree.InsnList` 的 NoClassDefFoundError**：
   Mixin 自带的 shaded ASM 树 API 缺类，**Mixin 自己 catch 掉了**，不影响启动。
   服务端冒烟里这条被单独计数并标注为 NOTE，不判失败。
2. **`Failed to load properties from file: server.properties`**：只在服务端出现，客户端无关。
3. **首次启动慢**：要解压 natives、生成 assets 索引缓存，第一次进主菜单可能 30–60 秒。

---

## 5. 如果你愿意顺手帮我验的（可选）

服务端那边已经全自动了，但有两件事只有客户端能回答：

1. **mixin 到底注入成功没有**（服务端只能证明「没报注入失败」，证不了「真的注入了」）。
   最快的判据：**AppleSkin 的饥饿值提示**、**Mod Menu 按钮**。看到就说明注入生效了。
2. **1.14 与 1.14.4 两条命名空间路径在客户端是否都成立**
   （1.14 零重映射 / 1.14.4 重映射 + mixin 命名空间翻译）。
   两边都能进世界，就说明「1.x 兼容」在客户端侧也闭环了。

---

## 6. 复现用的完整命令（一条不漏）

```bash
cd /d/work2/uniloader
export https_proxy=http://127.0.0.1:7897 http_proxy=http://127.0.0.1:7897

# 服务端（应各自 PASS 10/10）
./run/smoke-legacy.sh 1.14
./run/smoke-legacy.sh 1.14.4

# 客户端夹具
./run/prepare-legacy.sh 1.14   --client /d/work2/1.14fabric
./run/prepare-legacy.sh 1.14.4 --client /d/work2/1.14.4fabric

# 客户端
./run/client-legacy.sh 1.14
./run/client-legacy.sh 1.14.4
```
