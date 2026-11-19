# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# citybuilder

本项目是 **MTM_worldgen**（`F:\MC\MTM_worldgen`，真实地貌地形生成）与 **MTR Advanced**（`P:\Minecraft-Transit-Railway-391ce5d1800fbc70b7f565571c692287828867fa`，MTR 3.2.2 的运营模拟分支）的共同配套 mod：在大地形尺度上自然地生成城市，并让城市按规则逐步扩展。建筑模型素材取自网络上 MIT、CC 等开放协议授权的作品。

> 当前状态（2026-11-19）：只有构建骨架（加载器入口、平台服务、一个冒烟测试），还没有城市逻辑；尚未建 git 仓库。下文标 **【待决定】** 的是尚未确定的事项，括号内为建议默认值；确定后删除标记。

## 常用命令

在 Claude Code 沙箱里 Gradle 要在沙箱外运行，并带上 `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=F:\MC\citybuilder\.gradle\uds`（该目录需存在），否则报 `Unable to establish loopback connection`（与 MTM_worldgen 相同）。

```
gradlew build                                   # 构建全部并跑 core 单元测试，mod jar 在 fabric/build/libs、forge/build/libs
gradlew :core:test                              # 单元测试
gradlew :core:test --tests '*CityBuilderCoreTest.modIdIsAValidNamespace'   # 单个测试
gradlew :fabric:runClient / :forge:runClient    # 开发环境客户端（未验证）
```

## 代码结构

单一 Minecraft 版本（1.20.1）的 Architectury 布局，Gradle 9.6.0、Architectury Loom 1.17（`loom-remap`，Mojang 映射）、Architectury 插件 3.5、Shadow 9.6.1，加载器版本与 MTM_worldgen 的 1.20.1 相同（`gradle.properties`）。JDK 17。

- `core/`：城市逻辑，纯 Java，**不得引用 Minecraft 类**；不单独出 jar，作为源码目录编进 `common`，单元测试只在这里跑
- `common/`：Minecraft 侧代码。入口 `CityBuilder.init()`（由两个加载器入口调用）；`platform.Platform` 是加载器服务接口，各加载器经 `META-INF/services` 注册实现。common 里不能用 Fabric API 类
- `fabric/`、`forge/`：只做注册与平台适配，不写逻辑。`shadowJar` 打包 common，`remapJar` 产出正式 jar
- 对两个兄弟 mod 是软依赖（`fabric.mod.json` 的 `suggests`、`mods.toml` 的 `mandatory = false`），用 `Platform.isModLoaded(CityBuilder.WORLDGEN_MOD_ID / MTR_MOD_ID)` 判断是否存在
- 还没有 Mixin 和 access widener。加 Mixin 时注意 MTM_worldgen CLAUDE.md "开发约定"里关于 1.20.1 refmap 的坑（Fabric jar 的 mixin 配置要由 `shadowJar` 补上 `refmap`，否则开发环境正常、正式游戏启动即崩），可照搬它的 `remapJar` 检查

## 语言

- 与用户对话一律使用中文，整个会话保持不变（工具输出、代码、英文文档都不改变这一点）
- 代码、标识符、提交信息、代码注释用英文；本文件保持中文（与两个兄弟项目一致）

## 与两个兄弟项目的关系

改动涉及对方项目的接口时，先读对方仓库的 `CLAUDE.md`：MTM_worldgen 的很长（约 100 KB，按需查章节），MTR 分支另有大量 `MTR-*.md` 设计文档。不要直接修改兄弟项目的代码，需要它们配合时向用户说明。

**MTM_worldgen**（mod id `mtm_worldgen`，包 `com.mtm.worldgen`，MIT）
- 比例 1 格 = 1 米，配合 MTR 列车；陆地多山（日本式），平地集中在河口、盆地，并保留连片大平原"给大城市"——城市选址应优先利用这些平地
- 地形逻辑在 `core/.../logic`，纯 Java、不引用 Minecraft 类；入口 `TerrainLogic`（每个种子 + 配置一份，`TerrainLogic.forWorld` 共享），可直接求任意坐标的地表高度、群系、地貌类型、水系等（如 `sampleSurfaceHeight`、`sampleBiome`、`sampleLandformKind`、`isVillageGround`、`findNearest`）。城市选址、道路走向应通过这些查询而不是生成区块后再扫描
- **确定性原则**：结果只由 seed + 坐标 + 世界配置决定，与探索顺序、线程无关；逻辑区域（默认 4096×4096）只是缓存单位，不是计算边界。城市生成若要与地形同步按需生成，也应遵守同样原则
- 多版本：1.20.1 为主版本（Fabric、Forge），另有 1.20.4/1.21.1/1.21.4（Fabric、NeoForge）和 26.3；按版本分模块 `versions/<版本>/{common,fabric,...}`，Architectury Loom 1.17
- 村庄已被限制在平坦地形（`villageRadius`/`villageMaxRelief`，`ChunkGeneratorMixin`）；原版村庄与本 mod 城市的关系需考虑

**MTR Advanced**（Architectury，Fabric + Forge，JDK 17）
- **只构建 Minecraft 1.20.1**
- 服务端核心数据在 `common/src/main/java/mtr/data/`（每维度一个 `RailwayData`：车站、线路、车厂等）；新运营规则放在纯 Java 8 的 `transit-sim/`
- 其数据类以 msgpack 存档、`FriendlyByteBuf` 包、`update(key, packet)` 三种方式读写；本 mod 若创建车站/线路，应走 MTR 现有的数据与同步路径，而不是直接改存档

## 已定与待定

- 已采用（2026-11-19 搭骨架时按默认值）：Minecraft 1.20.1（MTR 只构建 1.20.1，以后随 MTM_worldgen 的版本链扩展）；Fabric + Forge；对兄弟 mod 软依赖；mod id `citybuilder`、包 `com.mtm.citybuilder`、显示名 City Builder
- 【待决定】许可证（元数据暂写 MIT，与 MTM_worldgen 一致；仓库里还没有 LICENSE 文件）
- 【待决定】接入兄弟 mod 的方式（编译期依赖它们的 jar，还是反射/接口；MTM_worldgen 的 Amidst 是靠反射调用 `TerrainLogic`）

## 建筑素材的授权

素材来自 MIT、CC 等协议，发布前必须能证明每一件素材的授权：
- 每件导入的素材都记录：来源 URL、作者、协议及版本、是否修改过；（建议集中放在一个清单文件，如 `assets/ATTRIBUTION`/`LICENSES` 目录，随 jar 一起发布）
- CC BY / BY-SA 需署名，BY-SA 的衍生素材须沿用同一协议；CC **NC**（非商业）、**ND**（禁止演绎）与 mod 的分发和转换方式可能冲突——（【待决定】默认不收录 NC、ND 素材，遇到时先问用户）
- 来源或协议不明的素材不要加入仓库
