# City Builder

Cities that grow out of the land, at real scale, for Minecraft. City Builder lays out towns and cities across a
[Realistic Landforms (MTM Worldgen)](https://github.com/MitsuhaQuQ/MTM_worldGen) world the way they would have grown there: where the plains,
rivers and coasts invite them, with old roads, castle towns and canals, railway stations and their station fronts,
shopping streets and back lanes, temples on the hills and ports on the sea. One block is one metre, as in the terrain
and in [Minecraft Transit Railway](https://github.com/MitsuhaQuQ/MTR_advanced) (the MTR Advanced fork), so the cities, their streets and their railways are the size of
real ones.

The first version is modelled on Japanese towns (Kyoto, Kanazawa, Himeji, Tokyo's old quarters), measured from real
data rather than guessed.

> **Early development.** The city logic is planned in full, but only the streets are built in the world so far:
> paving, road markings, pavements and kerbs, bridges, canals, footpaths and the airports. Buildings come when the
> building library is finished. Worlds made with one version may not match the next. Do not use it for worlds you want
> to keep yet.

中文简介见[下文](#中文简介)。

## What it plans

The city logic works from the largest scale down, each layer a function of the terrain and of what the layers above
decided:

- **Where cities can be.** A suitability field over the land (flat, dry, near water, sheltered), then settlements
  placed and ranked: metropolises on the great plains, cities, towns and villages between them, each with the
  hinterland it serves. Settlements are planned once, the first time they are needed, and kept in the world's save.
- **How far each city reaches.** A city spreads from its heart over the land it can take, thinning out towards the
  hills: basin streets, then the foot of the hills with temples and low houses, valley villages, terraced slopes,
  forest. Farmland lies around it.
- **What links them.** Intercity roads with their profiles, passes, tunnels and bridges; intercity railway corridors at
  real curve radii, with stations at the towns along them; reserved corridors for future high-speed lines; airports out
  of town, on flat land, on a hilltop or on an island filled out of the sea.
- **Each city's streets.** A formation by its history (castle town, temple town, port town, a capital's planned grid,
  post town, resort, village), its old roads, arterials and boulevards, streets, lanes running into the blocks, the
  castle's moats, irrigation canals with stone slab bridges, embankments and bank roads, culverts, ramps and steps on
  the slopes, the town's own railway lines (underground, elevated or at grade) and its stations, and footpaths along
  the rivers, the canals and the foot of the hills. The street network is calibrated against OpenStreetMap statistics
  of real Japanese towns (orientation, junction types, block sizes, lane density).
- **Districts and lots.** Downtown, the station front, the old town, houses, works and the port; lots cut along every
  street at frontages measured from PLATEAU's buildings, the insides of the blocks filled with back lots reached by
  lanes and flag lots, as real blocks are.
- **What the land is for.** Shopping, office, samurai and temple streets; schools, hospitals, parks, temples and shrines
  on whole blocks at real densities; clinics, police boxes and bathhouses on single lots; landmarks by chance (a castle
  keep, a pagoda, a tower, a department store, a lighthouse, a great torii); historic pleasure quarters (hanamachi) in
  Kyoto's or Kanazawa's style; temples and shrines on the hills out of town; container terminals, cargo piers, fishing
  harbours and ferry terminals on the coast.

Everything below the settlements is deterministic: the same plan gives the same city, on Fabric and Forge, in
whatever order the world is explored. The design is written up in [docs/city-logic-design.md](docs/city-logic-design.md)
(in Chinese).

## Requirements

- Minecraft 1.20.1, on Fabric or Forge.
- **Realistic Landforms (MTM Worldgen)** for the terrain: without it no cities are built. A soft dependency, read
  through a small interface, so the mod loads without it.
- **Minecraft Transit Railway**, the [MTR Advanced](https://github.com/MitsuhaQuQ/MTR_advanced) fork (optional): the
  cities read its stations. They never write its data; the railway
  structures are built for players to lay their own track on.
- Java 17.

## Building

The build compiles against the sibling mods' jars, taken from their build folders (set in `gradle.properties`):

- `mtm_worldgen_dir`: a checkout of MTM Worldgen, built first with
  `gradlew :1.20.1:fabric:build :1.20.1:forge:build`.
- `mtr_jar_dir`: a folder holding the MTR jars (`MTR-merged-<loader>-1.20.1-MTM_Logic-*.jar`).

Then:

```
gradlew build            # both loaders' jars in fabric/build/libs and forge/build/libs, and the unit tests
gradlew :core:test       # the city logic's unit tests only
```

The city logic lives in `core/`, plain Java with no Minecraft classes, so it can be tested and inspected on its own.

## Looking at the plans

`cityMap` renders any layer of the plan on the real terrain of a seed, and prints its statistics:

```
gradlew :core:cityMap -Players=settlements -Pseed=12345 -Px=-10600 -Pz=-10000 -Pscale=16
gradlew :core:cityMap -Players=streets -Pseed=12345 -Px=-10232 -Pz=-10272 -Pscale=8
gradlew :core:cityMap -Players=functions -Pseed=12345 -Px=-10232 -Pz=-10272 -Pscale=2 -Psize=1000
```

The layers: `suitability`, `culture`, `settlements`, `extent`, `roads`, `rail`, `highspeed`, `streets`, `districts`,
`plots`, `functions`, `airports`, `sanctuaries`, `ports`. Images are written to `core/build/debug/`.

`autotest/` runs a development server (and optionally a client) and checks the cities in the game itself: chunk
generation times, the paving laid, and screenshots of the streets (`cd autotest && npm install && node run.mjs`).

## Data and licences

The code is under the [MIT licence](LICENSE). Every asset shipped with the mod is listed, with its source and licence,
in [ATTRIBUTION](common/src/main/resources/assets/citybuilder/ATTRIBUTION); those made for the mod are MIT too. Assets
from elsewhere are only taken under licences that allow it (MIT, CC0, CC BY, CC BY-SA, and CC BY-NC-SA for a mod that is
free and never sold), credited there, and never when their source or licence is unclear.

The cities are calibrated against statistics measured from:

- [PLATEAU](https://www.mlit.go.jp/plateau/) (Japan's Ministry of Land, Infrastructure, Transport and Tourism, and the
  cities): buildings of Kyoto, Kanazawa, Himeji, and Tokyo's Taito and Chuo.
- [OpenStreetMap](https://www.openstreetmap.org/copyright) (© OpenStreetMap contributors, ODbL): streets of Kyoto,
  Kanazawa, Himeji, Takayama, Tokyo and other towns.

Only the statistics are used; none of either dataset is shipped. The scripts that measure them are in `tools/assets/`.

## 中文简介

City Builder 是 [Realistic Landforms（MTM Worldgen）](https://github.com/MitsuhaQuQ/MTM_worldGen) 和
[Minecraft Transit Railway（MTR Advanced 分支）](https://github.com/MitsuhaQuQ/MTR_advanced) 的配套 mod：在真实比例（1 格 = 1 米）的地形上，按城市在那里本来会怎样长出来的方式生成城镇。
第一版以日本城市为原型（京都、金泽、姬路、东京的下町），用真实数据校准。

- **规划了什么**：从大到小一层层决定。先是城市选址、等级和范围，然后是城际道路、城际铁路走廊、高速线预留和机场。
  - 城里的路网：按来历分城下町、门前町、港町、方格的都城等，有古道、干道、林荫大道、支路、小巷、护城河、用水和石板桥、步道，以及城市自己的铁路和车站。
  - 地块：沿街按实测面宽切开，街区内部再分后排地块和旗竿地。
  - 用地：商店街、学校、寺社、公园、地标、花街、山上的寺社、港口码头。
- **目前状态**：处于早期开发阶段。在游戏里只铺了路面、标线、人行道、桥、水渠、步道和机场，建筑要等建筑库完成。不同版本生成的世界可能对不上，暂时不要用在想保留的存档里。
- **依赖**：Minecraft 1.20.1，Fabric 或 Forge，Java 17。地形需要 MTM Worldgen（没装时不生成城市）；MTR 可选，只读取它的车站。
- **构建、调试图、数据来源和许可证**见上文英文部分。设计文档是中文的：[docs/city-logic-design.md](docs/city-logic-design.md)。
- **许可证**：代码采用 MIT；随 mod 发布的每件素材的来源和许可证都记在 ATTRIBUTION 里。PLATEAU 和 OpenStreetMap 的数据只用来做统计，不随 mod 发布。
