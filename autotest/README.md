# Codex 游戏内自动化测试方案

目标是让 Codex 能从玩家反馈定位到方块，在同一场景验证修复，并给出玩家视角的前后对照。首轮覆盖 Fabric 开发服。规划规则和尺寸依据见[设计文档第 6、7 节](../docs/city-logic-design.md)。

## 今天可运行的入口

在仓库根目录打开 PowerShell。构建需要 Java 17 和仓库配置的兄弟 mod 依赖，游戏测试还需要 Node、已安装的测试依赖和已接受 EULA 的开发服。以下命令彼此独立，均从根目录执行。

```powershell
# 首次准备测试依赖
npm --prefix autotest ci

# 只列出匹配用例，检查发现流程
npm --prefix autotest exec -- playwright test --config autotest/playwright.config.mjs --list

# 方块与生成回归，单次开服，串行跑相关用例
Push-Location autotest
try { node run.mjs streets.spec.mjs embankment.spec.mjs rails.spec.mjs subway.spec.mjs } finally { Pop-Location }

# 玩家视角与站前广场，会启动开发客户端
Push-Location autotest
try { node run.mjs --client buildings-view.spec.mjs station-square.spec.mjs } finally { Pop-Location }
```

[运行器](run.mjs)默认种子为 `12345`，启动时重写 `fabric/run/server.properties` 并删除旧的 `fabric/run/autotest`。每个用例先定位目标、生成所需区块，再通过[服务端 TestKit](lib/server.mjs)查询。只有需要截图的用例才启动[客户端](lib/client.mjs)。Playwright 使用一个 worker，共享同一服务端；同时运行两个启动器会争用世界和端口。

## Codex 如何选场景和判定结果

**首轮尺度验收**使用种子 12345。在 `autotest/` 下设置 `$env:AUTOTEST_LAYOUT='1'` 后运行 `node run.mjs --client residential-scale.spec.mjs station-square.spec.mjs`；待进程退出后换成 `'5'` 同机位重跑。版本 2 只扩大地块，版本 3 另加高住宅首层，版本 4 再调整商铺首层和主站前庭接坡，版本 5 增加通到上层站厅的低位入口。结束后用 `Remove-Item Env:AUTOTEST_LAYOUT` 清除选择。在同一目录运行 `node compare-scale.mjs`，复核种子、建筑 ID、固定机位、分辨率、净高增量与站前错台，输出配对报告 `build/debug/residential-scale/comparison.json`。

- **街面。** 固定人行道 `(-11213, -7430)`，分别取得住宅和商铺的已占用首层列，沿各自建筑轴投影出面宽、进深，并从临街边缘列取建筑顶部高度的中位数。住宅另查连续 2 × 2 的室内碰撞净空。报告区分名义地块、规划建筑尺寸和实测方块；前缘高度含屋面或女儿墙。输出在 `build/debug/residential-scale/layout-<版本>/`，有客户端时保存固定沿街与隔街图，有适合站立的门前机位时另存近景。已知旧存档断口的地表样本验证中断诊断会报告第一格草地。
- **主站。** 从前庭到站房预留地内侧沿 5 条平行采样线检查可站立面和头顶净空，中央及左右各 2 格在步行通道上，左右各 6 格检查坡肩（生成低位门厅时移到左右各 24 格，检查门厅以外的绿地）。版本 4 起断言相邻取样高差不超过 1 格、坡肩与通道高差不超过 2 格。数值及正面玩家视角在 `build/debug/station-scale/layout-<版本>/`；版本 5 还从广场低位入口沿大厅、楼梯、平台和上层连廊采样到真实站厅门内，记录每一步的地面和头顶碰撞体。单列 TestKit 剖面只读取已加载区块，一次最多 64 格高度。

| 改动范围 | 场景与实测断言 | 已有入口 |
| --- | --- | --- |
| 建筑网格与地块 | 在同一住宅街、老城商业街、站前干道分组统计。按建筑 ID 去重，分别记录名义地块和最终主体的面宽、进深、檐口高、首层净高及样本数。检查特殊建筑从实际门槛可走到人行道；检查斜切三角地上的主体能容纳入口和内部空间。单格外墙单独计。 | `buildings`、`buildings-view`、`facilities`、`thin-walls` |
| 广场与车站 | 从外部车道沿可行车铺面进入泊位，再从人行道沿玩家净空走到站门、站厅、楼梯和站台。记录首个堵点、梯段净宽、靠墙退距、站台绕行宽度、站厅最低梁底。用断面测量对照车型与站型的配置；普通站与换乘站分别取样。 | `station-square`、`rails`、`subway` |
| 道路与堤顶 | 在曾断开的干道两端验证跨接缝的车道连续；沿堤顶取纵断面，比较规划堤顶与已铺路面的高度。梁下按全车道宽度测最低净空。 | `streets`、`embankment`、`bridges` |
| 铁路连续性 | 沿每股道检查站端、引桥、坡段和区块边界的道床连续。地下交叉按站箱和隧道实际占用范围检测，含仅擦过站箱侧面、中心线不交的样本。 | `rails`、`subway` |
| 城址 | 有天守、无天守分别取样。在入口步行经过两段石阶抵达本丸，记录台地高差、石垣接缝及模型落位。 | `facilities`、`landmark-blocks` |

表中断言是待补齐的覆盖目标。现有 `station-square` 已检查找到车站并可截图，`embankment` 已检查护岸方块数量。截图存在、某类方块足够多，只能证明场景生成到了这一层。

## 样本和复现

- **生成器基线**用种子 `12345`、城下町附近 `(-10232, -10272)`。首次为住宅、商业、普通站、换乘站选样时，记录定位接口返回的实际坐标、设施类型、朝向和地面 Y。已固定的必测样本找不到应报告失败及最近候选；探索其他种子时可记为缺样。
- **玩家存档回归**用种子 `8689008927313558369`。干道断口中心约 `(3273, -5745)`，堤顶路约 `(3250, -6560)`，钢梁净空点 `(3260, -6538)`。样本包应含地形设置、规划库的一致性副本、建筑尺度版本、模组版本和相关原区块。原区块留作修复前证据；在另一个测试工作副本用相同规划重新生成目标及邻块，再测修复结果。`AUTOTEST_SEED` 只切换种子。设置 `AUTOTEST_REPLAY` 指向含 `level.dat` 和 `citybuilder/` 的只读快照，再用 `AUTOTEST_LAYOUT=8`（复现原存档当时的布局；验证布局 9 的修复时改设 9，见下文"布局 9 的街边与建筑回归"）运行 `node run.mjs saved-transport.spec.mjs`，会用 SQLite 在线备份 API 将规划复制到独立的 `fabric/run/autotest-replay`；尺度覆盖只写到该副本。原区块不复制，目标区块重新生成。实测结果保存在 `build/debug/transport-replay/columns.json`。
- **尺度升级对照**分别准备旧版和新版布局样本，在相同种子、区域和机位比较。记录建筑尺度版本；重启各自副本后再次生成邻块，检查地块边界接续。旧规划存档会保留旧尺度，不能用它代替新版样本。

## 街面视觉验收

为每个机位保存世界坐标、脚下实际路面 Y、朝向、俯仰、FOV、分辨率、资源包、光影、时间和天气。先在同侧人行道沿街看，再隔街看正立面，最后到门前或进入站厅。定位脚部时使用该列实际地面，由客户端自身提供眼高；高架下取道路层，避免把相机放到桥面。等待目标区块可见、画面稳定后截图，超时记取景失败。

前后图按相同机位配对。Codex 将图中差异与方块测量一起报告，例如“门前视角上方只剩两格净高”或“退界扩大导致街墙变远”。数值阈值来自设计或已确认的基线；“车站是否显得局促”等主观判断保留截图待评审。不同光影、视野的图片不作像素级回归。

客户端截图同时写入同名的 `.png.json`，记录截图时的玩家脚部与眼睛坐标、朝向、FOV 设置、分辨率、启用的资源包、时间和天气；安装 Iris 的实例还会记录可读取的光影配置。制作试玩副本时设置 `AUTOTEST_PREVIEW_SPAWN=castle`，带客户端运行 `castle-wards.spec.mjs`，验收通过后出生点位于本丸主门前的园路。

复查原有区块时，将 `AUTOTEST_BASELINE` 指向已停服的存档快照或解压的试玩世界。运行器把完整世界复制到独立的 `fabric/run/autotest-baseline`，使用复制后的区域文件。按原规划重新生成则选 `AUTOTEST_REPLAY`；两者分别运行。对解压的城址试玩世界，可同时设置 `AUTOTEST_PREVIEW_SPAWN=castle`，运行 `node run.mjs --client preview-import.spec.mjs` 核对玩家出生点和存档中的本丸石阶。

## 改进清单的版本 8 回归

这是布局 8 那一轮的回归（现在新世界默认布局 9，不设 `AUTOTEST_LAYOUT` 时按 9 跑）。固定 `AUTOTEST_LAYOUT=8`，在 `autotest/` 运行 `node run.mjs --client building-fit.spec.mjs castle-wards.spec.mjs rail-interfaces.spec.mjs rail-boundary.spec.mjs station-square.spec.mjs plaza-roads.spec.mjs station-circulation.spec.mjs rail-boxes.spec.mjs`。只有与故障规划输入配套的 `saved-transport.spec.mjs` 需要另设 `AUTOTEST_REPLAY`。开发服和客户端编译启动期间，应在同一代码版本下完成取样；运行中替换 class 会触发开发加载器热重载，不能充当干净生成的验证。

| 用例 | 检查对象与证据目录（仓库 `build/debug/` 下） |
| --- | --- |
| `rail-boundary` | 城际线进入城内线的交接点；沿正线逐格核对道床、轨面标高和四格净空，报告在 `rail-interfaces/urban-boundary.json` |
| `plaza-roads` | 站前路侧泊位两端接在近侧车道；沿原有大道、侧街及接收道路各车道逐列检查车行材质、四格净空和相邻高度，记录 `through-lanes.json` |
| `station-circulation` | 普通与换乘、高架与地下各取实站，逐排测量实际踏步净宽、头部净空、护栏外通道和连续站厅铺面 |
| `saved-transport` | `transport-replay/`，干道断口、沿堤设计标高、低位接入与梁下净空，并沿实际车道走通至可上堤的下一个接点 |
| `castle-wards` | `castle-wards/`，实际桥面与桥下水面、天守与无天守样本、桥头门、角橹及相连廊下，从外堀桥至本丸上院的逐格可行走路径 |
| `building-fit` | `building-fit/`，裁剪后重新落位的真实楔形地块，用实测的室内列检验主体；市役所、临街诊所的真实门扇与门前道路连通 |
| `rail-interfaces`、`rail-boxes` | `rail-interfaces/`，逐股道检查站端与引桥，检查两条独立地下线路与车站占用同一片平面时的上下净空 |
| `station-square` | `station-scale/layout-8/`，大道两侧车道到每一实际公交泊位、前庭到候车区的步行面，另测低位站厅入口与上层道路 |

## 实现顺序和交付物

1. **先跑通一个端到端样本。** 扩展 `buildings-view`，为住宅街增加固定人行道机位；使用开发 TestKit 有范围限制的逐列剖面查询，返回坐标、Y、方块状态和碰撞高度。交付同机位前后图、样本数和首层实测结果。先用已知一格厚主体或已知断口验证断言能报错。
2. **补交通与城址。** 共用同一剖面采样，再加车道连通、站内步行、逐股道和梁底净空断言。先收录已有精确坐标的断口及堤顶；尚无实例的故障先定位并固定样本。遇到多层结构时按目标 Y 取样，分别检查桥上、桥下和地下层。
3. **支持存档副本与结果归档。** 扩展运行器以区分新世界、旧区块基线和重放副本，临时世界限定在开发运行目录。运行器按启动时间和进程号分配独立结果目录。每次运行的 `run.json` 记录参数、布局版本、种子和运行状态；`tests.json` 记录每例的断言结果。运行期间新写入的 JSON 及 PNG 归入 `evidence/`，开发服与客户端日志保存在同一目录。复跑时使用 `run.json` 中的参数和环境设置。

Codex 完成一轮测试时应分别报出通过、失败、跳过及待视觉评审的场景。服务未启动、目标未加载、客户端未入服等环境失败单列，保留日志后只重跑受影响用例。必测场景必须实际命中；无客户端运行时只报告服务端覆盖。优先跑本次改动关联的用例，全量命令为在 `autotest/` 执行 `node run.mjs --client`。

站前路幅验收还沿两条实际来路分别测量左右路缘，记录多组垂直于中心线的横断面。车行检查沿真实并道方向从宽端进入窄端，并覆盖过路口后继续前进的内侧车道。

## 布局 9 的街边与建筑回归

在 `autotest/` 目录用 PowerShell 运行以下命令。种子 12345 使用默认地形和新世界；复现特定的已有规划时，可用 `AUTOTEST_REPLAY` 指向对应的存档快照。

```powershell
$env:AUTOTEST_LAYOUT = '9'
node run.mjs --client urban-frontage.spec.mjs plaza-roads.spec.mjs residential-scale.spec.mjs
```

| 场景 | 核对内容与输出 |
| --- | --- |
| 站前老街 | `urban-frontage` 在原草坎处测五个断面的人行道、相邻地表及地块归属，再检查画面内所有铺装列，相邻两列高差不超过 0.3 格。记录在 `build/debug/urban-frontage/street-edge.json` 和 `frontage-steps.json`；客户端同机位截图为 `frontage.png`。 |
| 并道与路缘 | `plaza-roads` 沿车道检查铺面、净空及高差，并横向测量路缘。记录在 `build/debug/station-scale/layout-9/through-lanes.json`。 |
| 住宅与店铺 | `residential-scale` 在固定街景内选取实际建筑，沿整栋占地测首层净高。布局 9 重切了地块，跨版本对照使用同一街景和建筑用途。记录在 `build/debug/residential-scale/layout-9/measurements.json`。 |

本轮住宅与店铺样本首层净高分别为 3 格和 4 格。另一次新旧存档对照检查了 22 列原先高出两格的草地，[同机位截图和结果](../docs/city-logic-design.md#布局-9-的街边地形和地块)见设计文档。

复核玩家种子 `8689008927313558369` 时，保持布局 9，设置 `AUTOTEST_REPLAY` 为该世界的规划快照，再运行 `node run.mjs saved-transport.spec.mjs`。该用例单独检查干道断口、堤脚接入和铁路梁下净空。
