# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# citybuilder

本项目是 **MTM_worldgen**（`F:\MC\MTM_worldgen`，真实地貌地形生成）与 **MTR Advanced**（`P:\Minecraft-Transit-Railway-391ce5d1800fbc70b7f565571c692287828867fa`，MTR 3.2.2 的运营模拟分支）的共同配套 mod：在大地形尺度上自然地生成城市，并让城市按规则逐步扩展。建筑模型素材取自网络上 MIT、CC 等开放协议授权的作品。

> 当前状态（2026-11-20）：实现顺序的第 1–4、7 步，第 5、6 步的规划部分，以及第 8 步的 8a（城市路网骨架）、8b（支路和小巷，按 OSM 指标校准）、8c（桥、暗渠、坡道、台阶、参道）以及第 8 步其余的规划（功能分区、主站、城内铁路、车站用地、公交站、高速线改线），第 9 步的地块（L7）第一版已完成（L2 已改为按规划块规划、存盘）：`CityLogic`、`CitySettings`、L1 适宜度场、L1.5 文化区、外来区域的排除、L2 聚落选址与分级、L3 城市范围和强度、L3.5 土地用途、L4 城际道路、L4b 城际铁路走廊，以及它们的调试图（`cityMap`）。区块层第一版已接进游戏（铺路面、标线、人行道和路缘、林荫大道、桥面），在开发服务器和测试客户端上自动测试过（见下文"游戏内自动测试"），Forge 还没在游戏里跑过；建筑还没放进城里，但建筑库在做（2026-11-21：PLATEAU 体素化的地标，程序化生成的寺社、灯台和竞技场，以及地标用的本 mod 方块：跑道、草皮、内野土及其标线、看台座椅、菲涅耳透镜、LED 面板，在游戏里验证过）。下文标 **【待决定】** 的是尚未确定的事项，括号内为建议默认值；确定后删除标记。

## 常用命令

在 Claude Code 沙箱里 Gradle 要在沙箱外运行，并带上 `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=F:\MC\citybuilder\.gradle\uds`（该目录需存在），否则报 `Unable to establish loopback connection`（与 MTM_worldgen 相同）。

```
gradlew build                                   # 构建全部并跑 core 单元测试，mod jar 在 fabric/build/libs、forge/build/libs
gradlew :core:test                              # 单元测试
gradlew :core:test --tests '*SuitabilityLayerTest.regionBordersMeet'   # 单个测试
gradlew :core:cityMap -Pseed=12345 -Px=0 -Pz=0 -Pscale=32   # 在 MTM 地形上渲染 L1 适宜度调试图（默认配置），输出到 core/build/debug/
gradlew :core:cityMap -Players=culture -Pseed=12345 -Px=0 -Pz=0 -Pscale=128   # L1.5 文化区，约 1–2 分钟
gradlew :core:cityMap -Players=settlements -Pseed=12345 -Px=-10600 -Pz=-10000 -Pscale=16   # L2 聚落叠在 L1 上，冷启动约 40 秒；32 格/像素以上只画大都市和城市
gradlew :core:cityMap -Players=extent -Pseed=12345 -Px=-10232 -Pz=-10272 -Pscale=4   # L3 城市强度和 L3.5 用地，冷启动数分钟（主要是周围的 L2）
gradlew :core:cityMap -Players=roads -Pseed=12345 -Px=-10600 -Pz=-10000 -Pscale=32   # L4 城际道路，冷启动十几分钟（周围几十公里的 L2）
gradlew :core:cityMap -Players=streets -Pseed=12345 -Px=-10232 -Pz=-10272 -Pscale=8   # L5/L6 离该点最近的镇以上聚落的路网，打印各类长度、桥/暗渠/坡道/台阶、分区比例和 OSM 指标，约 50 秒；-Players=districts 同样的图，底色换成功能分区
gradlew :core:cityMap -Players=plots -Pseed=12345 -Px=-10400 -Pz=-10150 -Pscale=1 -Psize=700   # L7 地块，打印面积比例和各片区面宽进深，约 70 秒
gradlew :core:osmStats -Pin=F:/MC/citybuilder/core/build/osm/kyoto.osm -Pbox="34.975 135.735 35.035 135.785"   # OSM 街道统计（路径要绝对路径，相对路径按 core 目录解析；数据从 Overpass 下载要先问用户）
python -I tools/assets/plateau_buildings.py F:/MC/citybuilder/core/build/plateau/26100_kyoto-shi_city_2025_citygml_1_op.zip F:/MC/citybuilder/core/build/plateau/kyoto-buildings.csv   # PLATEAU 建筑属性和 LOD2 屋顶形式 → CSV，全京都约 1 分钟（多进程）
python -I tools/assets/plateau_stats.py F:/MC/citybuilder/core/build/plateau/kyoto-buildings.csv [--box 34.975,135.735,35.035,135.785 --streets F:/MC/citybuilder/core/build/osm/kyoto.osm]   # 层高、占地、屋顶、圆环、按临街道路等级的层数（设计文档第 6 节"京都实测"）
python -I tools/assets/plateau_voxel.py extract F:/MC/citybuilder/core/build/plateau/26100_kyoto-shi_city_2025_citygml_1_op.zip F:/MC/citybuilder/core/build/plateau/kyoto-buildings.csv F:/MC/citybuilder/core/build/plateau/library   # 取出 LANDMARKS 里大型和特殊建筑的 LOD2 面，约半分钟
python -I tools/assets/plateau_voxel.py specs F:/MC/citybuilder/core/build/plateau/library   # 寺社的生成参数（TEMPLES）写成 temple_specs.json
gradlew :core:templeModels -Pin=F:/MC/citybuilder/core/build/plateau/library/temple_specs.json -Pout=F:/MC/citybuilder/core/build/plateau/library/models   # 按参数生成寺社（core.building.temple）和灯台、竞技场（core.building.landmark），在 worktree 里要加 -Pmtm_worldgen_dir=F:/MC/MTM_worldgen
python -I tools/assets/plateau_voxel.py build F:/MC/citybuilder/core/build/plateau/kyoto-buildings.csv F:/MC/citybuilder/core/build/plateau/library   # 体素化成原版结构模板 .nbt、等轴预览 .png、library.json、preview.html（一页看全部），十几秒
python tools/textures/landmark_textures.py; python tools/textures/landmark_models.py   # 地标方块的贴图，再写方块状态、模型、物品模型、掉落表、语言（道路方块同理是 road_textures.py、road_models.py），在仓库根目录跑
python -I tools/assets/osm_buildings.py F:/MC/citybuilder/core/build/osm/kanazawa-bld.json --streets F:/MC/citybuilder/core/build/osm/kanazawa.osm   # OSM 建筑统计（Overpass 的 out geom JSON）
python -I tools/assets/nbt_survey.py <目录或 .nbt>   # 原版结构模板（如 kogtyv-TAV）的尺寸、DataVersion、1.20.1 没有的方块、拼图池、层高估计
gradlew :fabric:runClient / :forge:runClient    # 开发环境客户端（未验证）
```

- 建筑数据（2026-11-20）：PLATEAU 京都市 2025 年度 CityGML 在 `F:\MC\citybuilder\core\build\plateau\`（2.7 GB，用户同意下载；授权见设计文档"素材来源"），OSM 建筑在 `core/build/osm/*-bld.json`；都不进仓库，只把统计结果写进设计文档。脚本在 `tools/assets/`，纯 Python 标准库，用 `python -I` 运行；只有 `plateau_voxel.py` 要 Pillow（`pip install --user pillow`，脚本自己把用户 site-packages 加进路径，因为 `-I` 不带它），还从 Loom 缓存里的 1.20.1 客户端 jar 读方块贴图算平均色（只在本地用，缓存成 `block_colours.json`，不复制贴图）
- 性能测量（2026-11-20 第一轮优化用的）：`gradlew :core:cityMap -Players=spawn -Px=-10232 -Pz=-10272` 冷启动计时新世界第一个区块要的全部规划（逐层计时、统计碰到的 worldgen 区域数），`-Pjfr=<文件>` 记录 Flight Recorder 采样（`jfr print --events jdk.ExecutionSample`），`-PworldgenCache=<N>` 改 worldgen 的区域缓存大小做对照、`-Pthreads=<N>` 限制公共线程池；`gradlew :core:terrainCost` 计时 worldgen 各查询第一次进入一个区域和之后每点的开销
- `cityMap` 还接受 `-Psize=<像素>`、`-Pout=<文件>`；它会打印统计：L1 是每个逻辑区域的耗时、各地貌的平均适宜度和海拔；L1.5 是每个文化单元的耗时、亚洲风格的面积比例、边界落在各地貌上的比例、洪泛未到达的边界节点比例。调参后先出图看效果，再看测试
- 只要统计、不要大图时，用小尺寸覆盖同样的范围，比如 `-Pscale=2048 -Psize=64`
- 地图很大时注意 worldgen 的区域缓存：调试图按 `debug.PixelBlocks` 分块并行渲染，每块约一个 worldgen 区域；按整行并行会让各线程同时扫过几十个区域，把缓存挤爆（文化区图曾因此从 26 秒变成 400 秒）
- 调参时常用的对照：种子 12345 的 `-10600 -10000`（平原、河口），种子 707121638 的 `0 0`（平原、高原、湖）

## 代码结构

单一 Minecraft 版本（1.20.1）的 Architectury 布局，Gradle 9.6.0、Architectury Loom 1.17（`loom-remap`，Mojang 映射）、Architectury 插件 3.5、Shadow 9.6.1，加载器版本与 MTM_worldgen 的 1.20.1 相同（`gradle.properties`）。JDK 17。

- `core/`：城市逻辑，纯 Java，**不得引用 Minecraft 类**；不单独出 jar，作为源码目录编进 `common`，单元测试只在这里跑。三个源码集：
  - `src/main`：入口 `CityLogic.forWorld(seed, CitySettings, TerrainView)`（每个世界一份）；`CitySettings`（JSON，带 `planVersion`，Gson 只在内部类 `Json` 里用）；`cache.RegionCache`（按区域的 LRU，同一区域只算一次）；`noise.Hash`（splitmix64，所有随机选择的来源）；`noise.ValueNoise`（平滑噪声，文化区的风格场）；`layer.SuitabilityLayer`/`SuitabilityRegion`（L1）；`culture.CultureLayer`/`CultureRegion`/`CultureSample`/`Style`（L1.5）；`settlement.SettlementLayer`/`Settlement`/`SettlementTier`/`SettlementBlock`（L2，`SettlementBlock` 是一个规划块的结果和它的存储编码）；`plan.PlanStore`/`MemoryPlanStore`/`FilePlanStore`（规划块的存储）；`settlement.SitePreference`（镇和村的选址偏好）；`extent.ExtentLayer`/`CityExtent`/`UrbanRegion`/`UrbanSample`/`LandUse`（L3、L3.5）；`road.RoadLayer`/`RoadEdge`/`RoadRoute`/`RoadKind`/`RoadClass`（L4）；`rail.RailLayer`/`RailEdge`/`RailRoute`/`RailCurve`/`RailStation`/`RailKind`/`RailClass`（L4b）、`rail.HighSpeedLayer`/`HighSpeedLine`/`SpineNode`（高速线预留），两者共用 `rail.Alignment`（带朝向寻路、圆弧拟合、纵断面），`rail.Approach` 把它开放给 L5 规划城际线的城内段；`street.StreetLayer`/`StreetPlan`/`StreetSegment`/`StreetStructure`/`District`/`Districts`/`CityRailway`/`UrbanLine`/`TransitMode`/`StationSite`/`Embankment`/`Canal`/`Formation`/`Anchor`/`Moat`/`StreetMetrics`（L5、L6 城市路网：干道、支路、小巷（伸进街区、穿通并连成网）、护城河、老城的用水和沿渠的路，每段带桥、暗渠、坡道、台阶等结构，以及沿河道路和护岸、功能分区、主站和城际线的城内段、城市自己的铁路（建法地下/高架/地面，地面段两侧有沿线道路，`transit.build` 三档只决定区块层建多少）；街道层的水面按 16 格细网格判断；`StreetMetrics` 在生成的路网上算 OSM 同一套指标）；`building.temple.Hall`/`Pagoda`/`Gate`/`Belfry`/`Honden`/`Pavilion`/`Torii`/`ToriiTunnel`/`Roof`/`Parts`/`Model`/`TemplePalette`/`RoofType`/`SacredBuilding`（寺社的程序化生成：佛堂（寄栋、切妻、入母屋，可带裳阶）、塔、门（单层的八脚门/四脚门，双层的二重门/三门；通路沿 z；`GateStyle.ZEN` 是南禅寺式禅宗样三门，可带两端的山廊；`ROMON` 是楼门，可带两侧回廊）、神社的流造本殿（`Honden`，偏心屋脊的长前坡）、春日造/八幡造/神明造本殿（`Sanctuary`）、回廊（`Corridor`，可 L 形）、石灯笼/狛犬/手水舍（`Furnishing`）、整座境内（`Precinct`：神社或寺院，回廊围庭、中轴布置）、舞殿/神乐殿（`Pavilion`）、鸟居和千本鸟居、钟楼（吹放し或袴腰），按檐的外廓和檐、脊的高度给参数，输出方块状态字符串的稀疏模型；`SacredBuilding` 标注寺院还是神社及在境内的角色，库里每件寺社都带它，以后组装境内时复用）；`building.landmark.Lighthouse`/`Stadium`/`Ballpark`/`AthleticsGround`/`Arena`/`Landmark`（PLATEAU 覆盖不好的地标的程序化生成：岬的灯台（逐渐收细的白塔、回廊、灯笼室、可带退息所）、防波堤灯台（红/白）、球技场（105 × 68 球场、四面看台、主看台顶棚、照明塔）、野球场（两翼 98、中堅 122、内野看台和外野芝生席）、陆上竞技场（400 m 跑道、小主看台、芝生斜面）；看台下有大厅（コンコース）、外墙门洞和每 32 格一个出入口（台阶加隧道）；y = 0 是代替地表的那一层；同样经 `templeModels` 输出，`Landmark` 是它们在建筑库里的角色）；`plot.PlotLayer`/`TownPlots`/`Plot`（L7 地块：每点归边缘最近的街，宽街优先，沿街按片区面宽切开，过小的并入邻块或身后的地块，见设计文档 L7）；`terrain.BroadTerrain`（去掉河湖的地形，见下）；`terrain.ForeignMask`（外来区域的范围）；`debug.SuitabilityMap`、`debug.CultureMap`、`debug.SettlementMap`、`debug.ExtentMap`、`debug.RoadMap`、`debug.RailMap`、`debug.HighSpeedMap`、`debug.StreetMap`、`debug.PlotMap`（调试图渲染）
  - `src/worldgen`：`core.terrain.WorldgenTerrainView`，把 MTM_worldgen 的 `TerrainLogic` 包成 `TerrainView`。纯 Java，但引用 worldgen，所以不在 `src/main`；common 也编译它（`common/build.gradle` 的 `srcDir`），测试和工具在 core 里直接用
  - `src/tools`：`tools.CityMap`（`cityMap` 任务）、`tools.OsmStats`（`osmStats` 任务，OSM 街道统计，目标值在设计文档第 8 节）、`tools.TerrainCost`（`terrainCost` 任务），都不进 jar
- **L1 的约定**：每个网格节点的值只取决于节点及周围有限范围（`waterReach`、`landformBlend`）的地形，所以任何区域算出的同一节点相同、区域边界两侧连续（`SuitabilityLayerTest.regionBordersMeet`）。水面节点不存值（NaN），插值时不参与，河岸不会被拉低成台阶。离散的地貌类型分数在周围平均后再用，避免硬边。只用 `TerrainView` 的便宜查询
- **L1.5 的约定**：风格由低频风格场决定（大片区域、各一半），只在场值 0.5 等值线两侧各 `borderHalfWidth` 格的交界带里由地形决定：两种风格从交界带外侧按代价洪泛，交界带里的点归代价低的一方，边界因此落在山脊和海峡上。代价上限 `maxCost` 必须足以穿过整条交界带（哪怕全是山），否则边界会落在某一方能到达的最远处，形成直线（实测踩过）。区域无关性的论证：代价有上限、每格代价至少 1，所以路径不离开窗口；一方到不了时到达的一方获胜，两方都到不了时由风格场决定，都只取决于节点自己。不要回到"文化核心"的做法（为什么见设计文档 L1.5）
- **外来区域不参与计算**：`TerrainView.foreign(x, z)` 为真的地方（从其他世界拼进来的地图及其缝合带）在每一层都被排除：L1 不存值、不计入邻居；L1.5 洪泛不能穿过。以后各层同样如此。worldgen 不公开缝合带的实际范围，`WorldgenTerrainView` 用 `ForeignMask` 按最大缝合宽度保守排除
- **L2 的约定：一次规划、存入存档（用户 2026-11-20 选定）**：城市不要求由种子完全复现。L2（以后 L3、L4 同样）按每级的规划块（`Tier.planBlock`）第一次被需要时规划，存进 `core.plan.PlanStore`，以后只读不改。规划一个块前先取得（必要时规划）影响它的上级块；同级相邻块只读已存在的，不触发规划；块内按腹地贪心放置。每级一把锁，锁的顺序只从下级到上级。结果与相邻块的规划顺序有关，但后规划的总看得见先规划的，所以不会对不上；给定已存的相邻块，块内结果仍由种子决定。存档里的规划块不能删。L1、L1.5、L5–L8 仍是纯函数、与顺序无关
- **L3 的约定**：每座城的范围（`CityExtent`）只由已存的 L2 聚落和地形决定，不存盘，按聚落 id 缓存；大城市的中心可能在湖上（L2 不读湖），这时洪泛免费越过这片湖、目标面积 ×1.5，实际中心是岸上最先到达的陆地（`coreX/coreZ`），下游一律用它而不是聚落的 x、z；不超过目标面积一半的小湖不论在哪都免费越过，湖面面积补给陆地；区域（`UrbanRegion`）收集所有可能伸进来的聚落，按等级取各自的最大伸展范围和农田范围，重叠处取强度高的。L3 读完整 L1 和精细地形（`surfaceHeight`），只用于玩家附近
- **高速线预留的约定**：骨架只读 `TerrainView.continentHeight`（不生成区域，可以看几百公里），挂站和线形才读局部；不要改成"先规划大都市再连线"，那要规划几百公里内的大都市
- **L4b 的约定**：寻路状态带朝向，步长由最小半径决定，所以路线一定拟合得出合格的曲线；改步长或方向集时要保持"切线长不超过两侧直线的一半"（`RailLayerTest` 检查半径和坡度）；L4b 路线终点在城市中心，城内那段以 L5 的 `StreetPlan.railways()` 为准（从进城点到主站），不要直接用 L4b 路线在城里的部分
- **L4 的约定**：路图和路线只由已存的 L2 和地形决定，不存盘。寻路先用不含河湖的 L1，再只在路线两侧的走廊里查河湖重新寻路，避免在整个窗口里触发水系计算
- **规划阶段不读精细地形**：worldgen 的 `surfaceHeight`/`waterLevel`/`grade` 要算侵蚀，每点约 2 ms（平滑高度约 18 µs），所以选址偏好、城市范围、道路纵断面都只读平滑高度，水面用湖面和河流查询；精细地形留给区块层。大都市和城市在 256 格的粗网格上判断（`settlements.broadGrid`）。所有层经 `GridCachedTerrain` 读 64 格网格点上的平滑高度、地貌和湖面，避免 worldgen 反复重算
- **worldgen 的区域缓存**：`general.regionCacheSize` 默认 64；MTM_worldgen `7062ab4`/`472f671`（2026-11-20，用户让本会话改并推送的，后者按湖缓存湖形状；用户定的内存预算是 worldgen 所有缓存合计 1 GB 以内）起地形区域是它的 128 倍（8192）、水系湖泊河道 16 倍（1024），侵蚀仍 64，合计至多约 700 MB。早于它的 MTM_worldgen 缓存小得多：这时我们这边并行预取反而更慢（实测：预取完整 L1 使冷启动超过 10 分钟，几十个线程在等水系），不要再加。`-PworldgenCache=<N>` 可做对照
- **远处的判断不要触发 worldgen 的水系计算**：完整 L1 要查河流、湖泊，会让 worldgen 计算水系路由（每片第一次 1 秒以上）。大尺度的判断（大都市、城市，以后的城际路线这类看几十公里外的东西）读 `CityLogic.broadSuitability`（基于 `BroadTerrain`，去掉河湖）；只有局部的判断才读完整 L1。`CityLogic.culture` 在交界带以外直接由风格场回答，不读洪泛结果，原因相同。性能问题先用 `cityMap` 的分步计时和 `jstack` 抓线程栈定位，不要猜（曾猜缓存太小，实测无效）
- `common/`：Minecraft 侧代码。入口 `CityBuilder.init()`（由两个加载器入口调用）；`platform.Platform` 是加载器服务接口，各加载器经 `META-INF/services` 注册实现。common 里不能用 Fabric API 类
- `fabric/`、`forge/`：只做注册与平台适配，不写逻辑。`shadowJar` 打包 common，`remapJar` 产出正式 jar
- 对两个兄弟 mod 是软依赖（`fabric.mod.json` 的 `suggests`、`mods.toml` 的 `mandatory = false`），用 `Platform.isModLoaded(CityBuilder.WORLDGEN_MOD_ID / MTR_MOD_ID)` 判断是否存在
- **地形接入（直接依赖 jar，用户 2026-11-19 选定）**：编译时直接引用 MTM_worldgen 构建好的 jar（`build.gradle` 的 `worldgenJar(loader)`，从 `gradle.properties` 的 `mtm_worldgen_dir` 下 `versions/1.20.1/<加载器>/build/libs` 取最新修改的 `mtm_worldgen-<加载器>-*-1.20.1.jar`，不绑定版本号；运行时也接受任何版本；找不到 jar 时构建报错，先在 MTM_worldgen 里 `gradlew :1.20.1:fabric:build :1.20.1:forge:build`）。common 用 `modCompileOnly`（不打进 jar），fabric/forge 用 `modLocalRuntime`（开发运行时带上），core 的测试用 `testImplementation`
  - 城市逻辑只通过 core 的 `core.terrain.TerrainView` 读地形，不直接用 `TerrainLogic`，这样没装 MTM_worldgen 时也能加载；core 的**测试和工具**用 `new WorldgenTerrainView(TerrainLogic.forWorld(seed, WorldgenSettings.DEFAULT))` 在真实地形上跑
  - **`TerrainView` 的查询分两档**：便宜的（`smoothHeight`、`landform`、`nearestRiver`、`lakeLevel`）不需要侵蚀，适合在粗网格上整片采样；精细的（`surfaceHeight`、`waterLevel`、`grade`）第一次用到时会算整个区域的侵蚀（约 0.5–1.5 秒），只留给放置东西的步骤。worldgen 的 `sampleRiver` 读的是拟合到侵蚀地面的河，所以 `nearestRiver` 改用 `riverRegion(rx, rz).nearest(x, z)`（按水系路由的原始河道，不需要侵蚀）
  - 游戏里取得地形的入口是 `terrain.Terrains.of(level)`：在 `isModLoaded` 判断之后才加载 `compat.worldgen.WorldgenTerrain`（经 `MtmGenerators.terrainLogic(level)` 取得该世界的 `TerrainLogic`，再包成 `WorldgenTerrainView`）；返回 null 表示没装 MTM_worldgen 或该维度不是 MTM 地形
  - 不看版本号，真正的约束是用到的方法签名（MTR 同理，见下条）：`TerrainLogic`（`sampleContinentHeight`、`surfaceHeight`、`sampleWaterLevel`、`sampleGrade`、`sampleLandformKind`、`sampleBiome`、`sampleSmoothLand`、`riverRegion`、`sampleLakeLevel`、`settings`、`forWorld`、`foreign`）、`RiverRegion.nearest` 和 `Hit` 的 `distance`/`width`、`ForeignMap` 的 `isEmpty`/`chunks` 及其键的编码（`chunkX << 32 | chunkZ`）、`TerrainLogic.Landform` 的取值、`MtmGenerators.terrainLogic(ServerLevel)`、`WorldgenSettings.DEFAULT` 和 `general.regionSize`/`general.seaLevel`/`foreign.maxWidth`/`foreign.upgradeMaxWidth`。它们改名或改签名时这里编译失败；已发布的 citybuilder 遇到这样的 MTM_worldgen 会在运行时报 `NoSuchMethodError`
- **MTR 接入（直接依赖 jar，用户 2026-11-19 选定）**：取 `gradle.properties` 的 `mtr_jar_dir`（默认 `P:/mtr-ante/test-jars/merged`，MTR 构建流程 `round.sh` 把 jar 复制到这里）里最新修改的 `MTR-merged-<加载器>-1.20.1-MTM_Logic-*.jar`（不含 `-server`），不绑定版本。common `modCompileOnly` 逻辑 jar；fabric/forge 的开发运行带上逻辑 jar、`MTM_Assets` jar 和 MTR 需要的 Architectury API（`architectury_api_version`，与 MTR 构建所用相同）
  - 城市逻辑通过 core 的 `core.transit.TransitView` 读线网，目前只有车站（`StationInfo`：id、名称、颜色、各矩形区域及高度范围；区域与高度取自 MTR 分支的 `Station.getRectangles()`/`getHeight(rect)`）。返回的是快照，可在任意线程使用
  - 唯一引用 MTR 类的是 `compat.mtr.MtrTransit`（经 `RailwayData.getInstance(level)`），只能经 `transit.Transits.of(level)` 加载；MTR 的数据由服务端线程修改，`stations()` 要在服务端线程调用
  - 依赖的 MTR 签名：`RailwayData.getInstance(Level)`、`RailwayData.stations`、`Station.getRectangles()`、`Station.getHeight(int[])`、`NameColorDataBase` 的 `id`/`name`/`color` 字段
- 开发运行（`runClient`/`runServer`）带上两个兄弟 mod 和 Architectury API 的配置能解析，但还没实际启动验证过
- **区块层（第一版，2026-11-20）**：`mixin.ChunkGeneratorMixin` 挂在原版 `ChunkGenerator.applyBiomeDecoration`：开头调 `world.CityChunks.pave`（装饰前铺路，树和草就不会长在路上），结尾调 `clear`（清掉从路边长到路面上方的东西，只清找得到路面方块的列）。每列铺什么由 core 的 `chunk.ChunkLayer` 给出（纯 Java、有测试）：`columns(chunkX, chunkZ)` 按区块中心和四角的 L3 归属找城镇、读它的 `StreetPlan`（按区块分桶的索引缓存 16 个城），每列给出道路等级、是否桥面和桥面高度（每座桥取两岸较高的地面 +1，整座桥水平）、离中心线的偏移、方向、沿线位置、路宽；`paving(column)` 按日本道路给出铺装（干道 14 格以上：两侧人行道 3 格、20 格以上 4 格，中央黄线，边缘白实线，两车道时白色虚线 5 格画 5 格空；次干道 10–14 格：人行道 2 格、白色中线和边线；林荫大道 30 格：中央 4 格草方块绿化带、两侧白线、每向 3 车道；带人行道的道路车道下沉成 13/16 高（`PavingBlock.LOW`），人行道整块、侧面是路缘石，高出 3/16；两条干道或次干道交叉处只铺沥青、不画线；支路和古道只铺沥青；小巷人行道砖）。每个 `ServerLevel` 一份 `CityLogic`（`world.CityWorlds`，规划存在 `<维度目录>/citybuilder/plan`）；城市逻辑出错时记一次日志、本次会话不再建城，不影响世界生成。同一座城多线程同时请求只规划一次（`StreetLayer.plan` 用 future）。
- **本 mod 的方块**（`block.CityBlocks`，经 `Platform.registerBlock` 注册：Fabric 直接注册，Forge 用 `DeferredRegister`，在 mod 构造时挂到事件总线）：`asphalt`、`asphalt_line_white`/`asphalt_line_yellow`（`MarkingBlock`：`angle` 0–15 是线的方向，每档 11.25°，`offset` 0–7 是线在格子里沿法线的位置，由 `ChunkLayer.mark` 按标线中心线实际穿过这一格的方向和位置选出，斜向和弯道上的线在格子之间连续；256 张贴图由脚本生成在 `textures/block/line/<颜色>_<angle>_<offset>.png`）、`sidewalk`。贴图由 `tools/textures/road_textures.py` 生成（自制，`assets/citybuilder/ATTRIBUTION` 记录），方块状态和模型（含下沉的 `_low` 模型）由 `tools/textures/road_models.py` 生成，改了改脚本后依次重跑。；地标用的方块（2026-11-21）：`track`（全天候跑道）、`turf`/`turf_dark`（球场草皮两种修剪条纹）、`soil`（内野黑土），各带 `_line_white` 标线版（同样的 `angle`/`offset`，`low` 不用，线宽 2 像素；生成器里由 `core.building.landmark.Markings` 按线的真实位置选状态，线相交处按优先级取一条）、`seat_<颜色>`（`SeatBlock`，`facing` 是靠背一侧，六色）、`fresnel_lens`（亮度 15）、`led_panel`（记分牌）；贴图由 `tools/textures/landmark_textures.py` 生成、方块状态/模型/物品模型/掉落表/语言由 `landmark_models.py` 生成
- Mixin 配置 `citybuilder.mixins.json`：照搬 MTM_worldgen 的 1.20.1 refmap 处理（Fabric 的 `shadowJar` 给 jar 里的配置补上 `refmap`，源码里不写；Forge 用 `loom.forge.mixinConfig`），两个加载器的 `remapJar` 都检查 jar 里的配置写了 refmap 且 refmap 在 jar 里
- **开发服务器冒烟测试**（用户 2026-11-20 同意接受 EULA）：`fabric/run`（已在 .gitignore）里有 `eula.txt` 和 `server.properties`（`level-type=mtm_worldgen\:realistic`、种子 12345、世界名 `smoke`、RCON 端口 25575 密码 `citybuilder`）。后台跑 `gradlew :fabric:runServer`，等日志出现 `Done (`，再用 `python tools/devserver/rcon.py "forceload add x1 z1 x2 z2" ...` 生成区块；数方块用 `fill ... minecraft:air replace citybuilder:<方块>`（同种方块替换自身只计改变了状态的，数不出来；替换成空气会删掉它们，只在测试世界里这样做），`rcon.py stop` 关服
- **游戏内自动测试**（2026-11-20，仿 MTR 的 `MTM_TestKit` + `autotest`）：
  - TestKit 在 fabric 模块的 `src/testkit` 源码集（mod id `citybuilder_testkit`，只在开发服务器的运行里，不进 jar）：服务器启动后在 127.0.0.1 随机端口开 HTTP，端口、令牌、PID 写进 `fabric/run/testkit/endpoint-server.json`；请求要带 `X-TestKit-Token`，带 `Origin` 的拒绝；接口 `/command`（以控制台执行、返回输出）、`/generate?x1&z1&x2&z2`（加 FORCED 票据异步生成，在 HTTP 线程上等全部 FULL，返回耗时和期间最慢 tick；不能用 `forceload`，它同步加载、卡主线程）、`/blocks`（每列从上往下第一个非空气非树叶方块的统计、本 mod 路面所在高度、路面上方 4 格内有东西的列数）、`/tick`、`/plan-around?x&z`、`/canal?x&z`（离该点最近的用水上的一点，同样取这个世界的规划）、`/street?x&z&class`（这个世界实际规划的、离该点最近的某等级道路段，取在一段路中间的，`end=1` 取一段路的端头；L2 按需要的顺序规划，游戏里的城可能和 `cityMap` 算的略有不同，所以截图位置要从这里取）、`/stop`。HTTP 在服务器关闭时停掉（JDK 的分发线程不是守护线程，不停进程不退出）
  - 运行器 `autotest/`（Node + `@playwright/test` 1.56.1；1.49 在 Node 24 上加载测试文件时挂起）：`cd autotest && npm install && node run.mjs`。写 `server.properties`（世界 `autotest`、种子 12345、只听 127.0.0.1、不开 RCON），删旧世界，后台 `gradlew :fabric:runServer`（输出到 `fabric/run/autotest-server.log`），等 endpoint，跑 `npx playwright test`（`cases/*.spec.mjs`），再 `/stop`、一分钟后按 PID 结束；服务器日志里新增的 ERROR 或异常（除 `KNOWN` 里兄弟 mod 的噪声）算失败
  - 用例 `cases/streets.spec.mjs`：城下町（-10232, -10272）先预规划再生成 8 × 8 区块，期间最慢 tick < 2 秒；路面、标线、人行道数量够，路面上方 4 格内被盖住的列 < 1%。实测：规划线程 40 秒规划完，生成 48 秒，最慢 tick 1.2 秒；沥青 3022、白线 940、黄线 399、人行道砖 1766，盖住 0 列
  - **测试客户端**（`node run.mjs --client`）：同一个 TestKit 的 `TestKitClient`（客户端入口），开发客户端在 `fabric/run-client`（`options.txt` 关掉失焦暂停、无障碍引导、多人警告，静音），启动后写 `testkit/endpoint-client.json`；接口 `/state`、`/join?address`、`/look?yaw&pitch`、`/screenshot?file`、`/quit`。客户端窗口会出现在桌面上。用例 `cases/view.spec.mjs` 连上测试服、观察者模式、正午晴天，在城下町上空、斜上方、街道上各截一张图，再按 `/street` 找到林荫大道，从上方、端头上方、沿路和人行道边上（看路缘）各截一张，再按 `/canal` 截用水的俯视和渠边平视，存 `autotest/test-results/screens/` 并附进报告（第一次的三张复制在 `docs/screens/`）。没开客户端时这个用例跳过；`cases/landmark-blocks.spec.mjs` 把 `core/build/landmarks/models` 里生成的陆上竞技场弯道和球技场主看台的一段逐块 `setblock` 到出生点上空 y 250（160 会埋进山里），检查游戏接受每个方块状态并截图（`docs/screens/landmark-*.png`），要先跑 `templeModels`，worktree 里跑要设 `ORG_GRADLE_PROJECT_mtm_worldgen_dir`
- **后台规划**（`world.CityPlanner`，2026-11-20）：每个服务器一条低优先级守护线程；`MinecraftServerMixin` 每 100 tick 把每个玩家的位置交给它，它规划建成区可能伸到玩家 2.5 km 以内的城（近的先）。区块生成线程遇到没规划好的城仍然等它（玩家跑过规划线程、传送时）
- 冒烟测试实测（2026-11-20）：服务器 47 秒启动；在种子 12345 的城下町中心强制加载 8 × 8 区块，第一次规划这座城让主线程卡住 53 秒（"Can't keep up! 52899ms"），之后相邻区块几秒内生成；中心 64 个区块铺了沥青 2879、白线 955、黄线 399、人行道砖 1804 块，平地上的路面都在同一高度（y 66）。没有报错（这里卡主线程的是 `forceload` 命令本身同步加载区块）

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

构思稿在 [docs/city-logic-design.md](docs/city-logic-design.md)（尚未实现）。核心原则照搬 MTM_worldgen：L1、L1.5 和城市尺度的细节是 seed + 坐标 + 配置 + 地形的纯函数，L2–L4 按规划块一次规划、存入存档，从大尺度到小尺度分层（适宜度 → 文化区 → 选址 → 范围 → 乡村 → 城际道路 → 骨架 → 街道 → 地块 → 建筑），区域只是缓存单位；随时间变化的只有每座城的发展值和已建地块记录，规划本身不变。改动设计时同步更新这份文档。

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
- **高原可以建城但不以大城市为主**：L1 适宜度不压低高原；L2 计算大都市和城市的腹地时对高原打折（建议 ×0.3），镇和村不打折
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
- CC BY / BY-SA 需署名，BY-SA 的衍生素材须沿用同一协议。**NC（非商业）素材可以收录**（用户 2026-11-20：本项目不是商业项目，Minecraft 的使用准则本来就不许 mod 商业化）：本 mod 须免费分发、不以含这些素材收费，收录或改动过的 NC-SA 素材本身沿用 CC BY-NC-SA 4.0，署名和来源记进清单；代码仍按 MIT。ND（禁止演绎）素材不能改动，遇到时先问用户
- **Yuushya Townscape**（`F:\MC\yuushya-townscape-master`，代码 MIT、美术资源 CC BY-NC-SA 4.0，使用协议见其 `Terms_yuushya_user.md`：可随免费作品分发，须链接创作者页面并标明作品名，不得收费）：有沥青、道路标线（含箭头、匝道标线、人行横道）、电线杆和电线（`CableBlock` 按到电杆的距离算下垂）、路灯、交通标志和信号灯、方向和指引牌、排水沟、消防栓、自动售货机、各种栏杆。方块由 `data/yuushya/register/*.json` 数据驱动注册，id 形如 `yuushya:road_marking_0`。**用法（用户 2026-11-20 选定）**：软依赖，装了才用、按注册 id 在运行时查找，不打包它的素材；但为了可靠，能在我们这边实现的（标线、人行横道、箭头、电线杆和电线、路灯等）都做成本 mod 自己的方块，Yuushya 只补我们没有的东西，没装时一切照常
- 来源或协议不明的素材不要加入仓库
