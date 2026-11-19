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
- **地形接入（直接依赖 jar，用户 2026-11-19 选定）**：编译时直接引用 MTM_worldgen 构建好的 jar（`build.gradle` 的 `worldgenJar(loader)`，从 `gradle.properties` 的 `mtm_worldgen_dir` 下 `versions/1.20.1/<加载器>/build/libs` 取最新修改的 `mtm_worldgen-<加载器>-*-1.20.1.jar`，不绑定版本号；运行时也接受任何版本；找不到 jar 时构建报错，先在 MTM_worldgen 里 `gradlew :1.20.1:fabric:build :1.20.1:forge:build`）。common 用 `modCompileOnly`（不打进 jar），fabric/forge 用 `modLocalRuntime`（开发运行时带上），core 的测试用 `testImplementation`
  - 城市逻辑只通过 core 的 `core.terrain.TerrainView` 读地形（地表高度、河湖水面、坡度、地貌、群系），不直接用 `TerrainLogic`，这样没装 MTM_worldgen 时也能加载；core 的**测试**可以直接用 `TerrainLogic.forWorld(seed, WorldgenSettings.DEFAULT)` 在真实地形上跑
  - 唯一引用 MTM_worldgen 类的是 `compat.worldgen.WorldgenTerrain`（包装 `TerrainLogic`，经 `MtmGenerators.terrainLogic(level)` 取得某个世界的实例），只能经 `terrain.Terrains.of(level)` 在 `isModLoaded` 判断之后加载；它返回 null 表示没装 MTM_worldgen 或该维度不是 MTM 地形
  - 不看版本号，真正的约束是用到的方法签名（MTR 同理，见下条）：`TerrainLogic`（`surfaceHeight`、`sampleWaterLevel`、`sampleGrade`、`sampleLandformKind`、`sampleBiome`、`forWorld`）、`TerrainLogic.Landform` 的取值、`MtmGenerators.terrainLogic(ServerLevel)`、`WorldgenSettings.DEFAULT`。它们改名或改签名时这里编译失败；已发布的 citybuilder 遇到这样的 MTM_worldgen 会在运行时报 `NoSuchMethodError`
- **MTR 接入（直接依赖 jar，用户 2026-11-19 选定）**：取 `gradle.properties` 的 `mtr_jar_dir`（默认 `P:/mtr-ante/test-jars/merged`，MTR 构建流程 `round.sh` 把 jar 复制到这里）里最新修改的 `MTR-merged-<加载器>-1.20.1-MTM_Logic-*.jar`（不含 `-server`），不绑定版本。common `modCompileOnly` 逻辑 jar；fabric/forge 的开发运行带上逻辑 jar、`MTM_Assets` jar 和 MTR 需要的 Architectury API（`architectury_api_version`，与 MTR 构建所用相同）
  - 城市逻辑通过 core 的 `core.transit.TransitView` 读线网，目前只有车站（`StationInfo`：id、名称、颜色、各矩形区域及高度范围；区域与高度取自 MTR 分支的 `Station.getRectangles()`/`getHeight(rect)`）。返回的是快照，可在任意线程使用
  - 唯一引用 MTR 类的是 `compat.mtr.MtrTransit`（经 `RailwayData.getInstance(level)`），只能经 `transit.Transits.of(level)` 加载；MTR 的数据由服务端线程修改，`stations()` 要在服务端线程调用
  - 依赖的 MTR 签名：`RailwayData.getInstance(Level)`、`RailwayData.stations`、`Station.getRectangles()`、`Station.getHeight(int[])`、`NameColorDataBase` 的 `id`/`name`/`color` 字段
- 开发运行（`runClient`/`runServer`）带上两个兄弟 mod 和 Architectury API 的配置能解析，但还没实际启动验证过
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

**MTR Advanced**（mod id `mtr`，Architectury，Fabric + Forge，JDK 17）
- **只构建 Minecraft 1.20.1**；运行时需要 Architectury API
- 服务端核心数据在 `common/src/main/java/mtr/data/`（每维度一个 `RailwayData`：车站、线路、车厂等）；新运营规则放在纯 Java 8 的 `transit-sim/`
- 其数据类以 msgpack 存档、`FriendlyByteBuf` 包、`update(key, packet)` 三种方式读写；本 mod 若创建车站/线路，应走 MTR 现有的数据与同步路径，而不是直接改存档

## 城市逻辑设计

构思稿在 [docs/city-logic-design.md](docs/city-logic-design.md)（尚未实现）。核心原则照搬 MTM_worldgen：城市规划是 seed + 坐标 + 配置 + 地形的纯函数，从大尺度到小尺度分层（适宜度 → 文化区 → 选址 → 范围 → 乡村 → 城际道路 → 骨架 → 街道 → 地块 → 建筑），区域只是缓存单位；随时间变化的只有每座城的发展值和已建地块记录，规划本身不变。改动设计时同步更新这份文档。

已和用户定下的（2026-11-19）：
- 两种风格，亚洲（东亚现代）和欧洲（苏联/东欧），各占一半面积，**不在同一座城里混用**；风格决定城市形态，L5–L8 每种风格一套模块
- 文化区按地形划分，边界尽量落在山脊和海峡上；落在平原上时用无建筑的边境农业带隔开
- 城际道路有纵断面，翻山时按代价自动选山口、盘山或隧道
- 欧洲风格建筑用 kogtyv-TAV 的部件（MIT/CC0，已拆成部件）；亚洲风格以程序化生成为主
- 建筑多样性靠拆部件拼装、换材质、加细节，并以"开发项目"为单位安排重复
- **尺度：1 格 = 1 米，按现实尺寸，以 MTR 为参照，不以原版村庄为参照**（层高、楼高、占地、路宽、街区、密度的取值范围见设计文档第 6 节）；欧洲风格住宅层高照用 TAV 的 4 格，亚洲 3 格
- 亚洲风格第一版以京都为原型：城市内没有高架道路和立交，有平面、地下（地铁走大路下方）和高架铁路车站；高架铁路参考 JR 嵯峨野线，城市只生成桥墩、桥面和高架站，轨道由玩家铺
- 城市生成车站建筑和设施，但**不写 MTR 的数据**；站台和设施只用原版方块和本 mod 的装饰性方块，不用 MTR 的方块。公交是 MTR 列车加公交车模型，城市只预留站位
- 城市之间生成**城际铁路走廊**的结构（L4b）：只连大都市和城市、沿途镇设站，干线复线、支线单线；不能盘山，沿河谷走、打长隧道；桥梁按跨度分混凝土高架桥和铁桥（钢桁架、钢板梁）；开局就建好，轨道由玩家铺
- 铁路线形**按接近现实的半径**（干线最小 600、支线 300、城市地铁和高架 160，站台直线优先），带缓和曲线；曲线起终点放"曲线标"方块，标出半径和缓和曲线长度，让玩家照着放 MTR 节点
- **高速线预留**：第一版不建结构，但线位和站区从第一版起就规划并空出（城市里做绿带或空地），以后在空着的走廊里补建
- **第一版以日本风格（京都原型）为目标**，欧洲风格之后再做。自然化的原则是"有原因的不规则"：三层时代层叠（古道 → 老城与规划方格 → 铁路与现代改造）、暗渠路、楼高跟路宽挂钩（道路斜线）、表通り和裏通り，不在结果上加随机扰动；用 OSM 统计的真实城市指标（街道朝向熵、路口类型比例等）校准（设计文档第 8 节）
- **山麓渐变**：L3 给出连续的城市强度（到中心的代价、坡度、高出盆地底面的高度），城市到山脚逐步变稀、变矮、变绿：盆地市街 → 山裾（寺社、低层住宅）→ 谷口聚落 → 山坡（墓地、梯田）→ 山林；城市沿谷伸进去，山脊保持森林
- **规划算法要按世界锁定版本**：区块不会重新生成，规划一改，已生成和未生成的区块就对不上；改变规划的修改必须让旧世界继续用旧算法
- 以后做高架、立交时，**借 MTR 分支的几何算法**（缓和曲线、斜面填缝、桥面铺设）自己生成方块，不写 MTR 数据；移植的代码保留 MTR 的 MIT 声明并注明出处
- 看得见的交通是**客户端车流**：车道网由服务端下发（不向客户端泄露种子），车辆只在客户端存在

## 已定与待定

- 已采用（2026-11-19 搭骨架时按默认值）：Minecraft 1.20.1（MTR 只构建 1.20.1，以后随 MTM_worldgen 的版本链扩展）；Fabric + Forge；对兄弟 mod 软依赖；mod id `citybuilder`、包 `com.mtm.citybuilder`、显示名 City Builder
- 【待决定】许可证（元数据暂写 MIT，与 MTM_worldgen 一致；仓库里还没有 LICENSE 文件）
- 地形与 MTR 接入：都直接依赖对方构建好的 jar，不绑定版本（见"代码结构"）

## 建筑素材的授权

素材来自 MIT、CC 等协议，发布前必须能证明每一件素材的授权：
- 每件导入的素材都记录：来源 URL、作者、协议及版本、是否修改过；（建议集中放在一个清单文件，如 `assets/ATTRIBUTION`/`LICENSES` 目录，随 jar 一起发布）
- CC BY / BY-SA 需署名，BY-SA 的衍生素材须沿用同一协议；CC **NC**（非商业）、**ND**（禁止演绎）与 mod 的分发和转换方式可能冲突——（【待决定】默认不收录 NC、ND 素材，遇到时先问用户）
- 来源或协议不明的素材不要加入仓库
