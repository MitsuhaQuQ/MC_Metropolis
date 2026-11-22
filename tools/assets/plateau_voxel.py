"""Turn selected PLATEAU LOD2 buildings into Minecraft structure templates (the building library).

Two steps, both from the CityGML zip and the CSV written by plateau_buildings.py:

  extract   reads the LOD2 surfaces (roof, wall, ground ...) of the chosen buildings out of the
            zip, one mesh file per process, into <out>/geom/<id>.json (slow: the mesh files are
            300 MB each)
  build     voxelizes each extracted building at 1 block = 1 m, picks blocks from a palette,
            and writes <out>/<slug>.nbt (vanilla structure template, 1.20.1), <out>/<slug>.png
            (isometric preview from the south-east), <out>/library.json and <out>/preview.html

Voxelizing: the building is turned so its walls run along the axes (dominant wall direction,
modulo 90 degrees), every surface polygon is sampled every 0.25 m by scanlines, columns are filled by
parity (a vertical ray through each column centre crosses the closed LOD2 solid at every
non-vertical face; filling each column to its top would fill under overhangs), and only the shell plus one floor per
storey is kept (interior air is not stored). Surfaces outside the ground footprint stay one block
thick, so eaves overhang. Pitched roofs get stairs facing uphill where the slope is steep enough
and slabs where the surface sits in the lower half of a block.

Usage:
    python -I tools/assets/plateau_voxel.py extract <citygml.zip> <buildings.csv> <out> [--id GMLID ...]
    python -I tools/assets/plateau_voxel.py specs <out>          (then gradlew :core:templeModels
                                                                    -Pin=<out>/temple_specs.json -Pout=<out>/models)
    python -I tools/assets/plateau_voxel.py build <buildings.csv> <out> [--id GMLID ...] [--no-textures]
                                                  [--mc-jar <1.20.1 client jar>]

Without --id the built-in lists LANDMARKS (Kyoto) and MORE_LANDMARKS (other cities) are used. `extract` runs once
per city, with that city's zip and CSV, and takes the buildings of the lists found in that CSV; `build` takes the CSVs
of all the cities, comma separated, e.g.
    python -I tools/assets/plateau_voxel.py build <dir>/kyoto-buildings.csv,<dir>/himeji-buildings.csv,
        <dir>/kanazawa-buildings.csv,<dir>/taito-buildings.csv,<dir>/chuo-buildings.csv <out>
Source: 3D city models of Project PLATEAU (MLIT; copyright of each city or ward, CITIES), PLATEAU site policy
(compatible with CC BY 4.0): ATTRIBUTION, written next to the templates with every city used, must ship with them.
"""
import base64
import csv
import gzip
import json
import math
import multiprocessing
import os
import struct
import sys
import xml.etree.ElementTree as ET
import zipfile
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from osm_buildings import project  # noqa: E402

try:
    from PIL import Image
except ImportError:  # python -I leaves out the user site-packages, where pip --user puts Pillow
    import site
    sys.path.append(site.getusersitepackages())
    from PIL import Image

MC_JAR = os.path.expanduser("~/.gradle/caches/fabric-loom/1.20.1/minecraft-client.jar")

# (gml id, slug, name, palette or None for automatic). Names are what stands at these coordinates
# in central Kyoto; the PLATEAU data itself carries no names.
LANDMARKS = [
    ("bldg_715decd8-65ed-44b0-a98f-a2a3e87c8c7f", "kyoto_station", "京都駅ビル", "glass"),
    ("bldg_e337ac33-306a-49a6-b1ba-a85f0f173d5d", "kyoto_tower", "京都タワー", "white"),
    ("bldg_05e0f78c-9115-4472-babb-0ba715460f02", "toji_pagoda", "東寺 五重塔", "temple"),
    ("bldg_324e7112-55ff-49aa-90f8-e6a40c039892", "toji_kondo", "東寺 金堂", "temple"),
    ("bldg_f1daae71-2f47-426c-84e1-5105ac280c70", "toji_kodo", "東寺 講堂", "temple"),
    ("bldg_9119aef5-ef73-4486-9936-08d6487d80c2", "higashi_honganji_complex", "東本願寺 伽藍群", "temple"),
    ("bldg_2dabc819-9afb-4b97-bdc1-5d525730277e", "higashi_honganji_annex", "東本願寺 付属棟", "temple"),
    ("bldg_0ba99961-de54-4d42-9f8b-d848844d32de", "nishi_honganji_goeido", "西本願寺 御影堂", "temple"),
    ("bldg_1f1d7e7b-4735-4f44-9f57-0218fcc2f49e", "nishi_honganji_amidado", "西本願寺 阿弥陀堂", "temple"),
    ("bldg_572af0ac-c579-44be-aa12-d9eb0616f76e", "nishi_honganji_hall", "西本願寺 寄棟の堂", "temple"),
    ("bldg_28a9a7fc-417a-4f7a-aa7c-1a4d8db69c88", "nijo_ninomaru", "二条城 二の丸御殿", "temple"),
    ("bldg_d93b1456-d906-4e15-8635-d32bc211cc03", "temple_hall_karasuma", "烏丸の寺院本堂", "temple"),
    ("bldg_0ae8336e-dcec-43f9-a494-b71b0295ad64", "minamiza", "南座", "temple"),
    ("bldg_aa7e1082-f9c0-4d5e-bb1b-0e0b7394cd2e", "pref_office_old", "京都府庁旧本館", "brick"),
    ("bldg_47f88f29-94bf-4cf9-8b1f-e5ddc674d930", "city_hall", "京都市役所", "brick"),
    ("bldg_3978ea79-6a7b-4cd5-bf8b-5c479ee49bda", "takashimaya", "髙島屋 京都店", None),
    ("bldg_3313947c-c2d9-4b7c-9692-b8a4568ec600", "daimaru", "大丸 京都店", None),
    ("bldg_f8004f1e-5170-4c7b-94ec-a210f5e8a438", "hotel_okura", "ホテルオークラ京都", None),
    ("bldg_e75c0582-8eae-4342-99ea-d6999f5570fe", "westin_miyako", "ウェスティン都ホテル京都", None),
    ("bldg_32cfd39f-b99c-435d-9737-e7fb9094c5e3", "aeon_mall", "イオンモールKYOTO", None),
    ("bldg_b1b5e06c-47c2-4e90-91f5-d921b3875401", "aquarium", "京都水族館", None),
    ("bldg_d118eb3a-f9be-49e8-b223-e53f755ac502", "railway_museum", "京都鉄道博物館", None),
    ("bldg_34c21757-8a98-4dc8-b7d0-ea85209a07f1", "umekoji_depot", "梅小路の大型倉庫", None),
    ("bldg_ce127903-06cf-494d-aeaa-12a932fcb11f", "higashi_honganji_goeidomon", "東本願寺 御影堂門", "temple"),
    ("bldg_8ff4cd35-4d98-4664-8b70-8265b187f269", "nishi_honganji_goeidomon", "西本願寺 御影堂門", "temple"),
    ("bldg_088dbe5d-5014-44a6-93f1-993e4701d889", "nanzenji_sanmon", "南禅寺 三門", "temple"),
    ("bldg_cecd5425-2c83-4cec-934d-7007ba1f20a3", "yasaka_honden", "八坂神社 本殿", "temple"),
    ("bldg_a142fc0b-88ff-47c5-9073-4a3bf547eb30", "yasaka_buden", "八坂神社 舞殿", "temple"),
    ("bldg_70a2c83b-e0ad-4027-8422-72520fc2620a", "inari_romon", "伏見稲荷大社 楼門", "temple"),
    ("bldg_a42ada8b-afff-438c-ab50-b0d6bc9d047a", "inari_gaihaiden", "伏見稲荷大社 外拝殿", "temple"),
    ("bldg_143a1271-fbe6-4704-aae8-e78634da2291", "inari_honden", "伏見稲荷大社 本殿", "temple"),
]

# The cities the templates come from: the dataset on G空間情報センター and who made the data (the city, or for the
# Tokyo wards sometimes the metropolis). Kyoto's ids are in LANDMARKS; the others are in MORE_LANDMARKS.
CITIES = {
    "kyoto": ("京都市", "Kyoto City", 2025, "plateau-26100-kyoto-shi-2025"),
    "himeji": ("姫路市", "Himeji City", 2023, "plateau-28201-himeji-shi-2023"),
    "kanazawa": ("金沢市", "Kanazawa City", 2024, "plateau-17201-kanazawa-shi-2024"),
    "taito": ("台東区", "Taito City (Tokyo)", 2025, "plateau-13106-taito-ku-2025"),
    "chuo": ("中央区", "Chuo City (Tokyo)", 2025, "plateau-13102-chuo-ku-2025"),
    "yokohama": ("横浜市", "Yokohama City", 2024, "plateau-14100-yokohama-shi-2024"),
    "saitama": ("さいたま市", "Saitama City", 2025, "plateau-11100-saitama-shi-2025"),
}

# (gml id, slug, name, palette, city) from the partial city zips fetched by plateau_fetch.py (no textures there, so
# the palette is all there is). Names are what stands at these coordinates (OpenStreetMap); where nothing is named
# there the name says what and where.
MORE_LANDMARKS = [
    # Himeji Castle: the main keep on its stone base (LOD2 joins some of the smaller keeps to it), and the turrets
    # (yagura) and corridor turrets round it, named by where they stand from the keep; the stone walls they stand on
    # are part of each model.
    ("bldg_7b639b68-9776-4747-b144-19cf05cc27b2", "himeji_keep", "姫路城 大天守（連立する小天守の一部を含む）", "castle", "himeji"),
    ("bldg_02977f63-4916-4ff8-8b3f-a679f13232cf", "himeji_watari_yagura", "姫路城 渡櫓（天守の北東、折れ曲がり）", "castle", "himeji"),
    ("bldg_b6404c13-90e0-4a38-843a-465570e42ee6", "himeji_yagura_east", "姫路城 櫓（天守の東）", "castle", "himeji"),
    ("bldg_ea5dd0a8-7150-41dd-8687-f31710e11ffe", "himeji_yagura_southeast", "姫路城 櫓（天守の南東）", "castle", "himeji"),
    ("bldg_f38b8b93-41a1-486b-921a-b99aa13ea530", "himeji_yagura_southwest", "姫路城 櫓（天守の南西、L 字）", "castle", "himeji"),
    ("bldg_237aeeca-24f3-439d-b3c0-18276c8d6ca3", "himeji_yagura_small", "姫路城 小櫓（天守の南東）", "castle", "himeji"),
    ("bldg_7f52078b-2172-4231-9bd2-0775ea03a675", "himeji_nishinomaru", "姫路城 西の丸の渡櫓（百間廊下）", "castle", "himeji"),
    # Department stores, old to new.
    ("bldg_6c22d3d1-7bbf-40c8-a814-b0f2a77855c7", "mitsukoshi_nihonbashi", "三越 日本橋本店", "stone", "chuo"),
    ("bldg_c720caed-f9af-4ec8-9dda-c4ce6287d259", "takashimaya_nihonbashi", "髙島屋 日本橋店", "stone", "chuo"),
    ("bldg_0831aa2a-3a2d-4fae-a537-4a3d585396f4", "matsuya_ginza", "松屋 銀座", "modern", "chuo"),
    ("bldg_7de66f35-b725-44e5-bbf5-0925ba25142e", "mitsukoshi_ginza", "銀座三越", "glass", "chuo"),
    ("bldg_0c7a5b4d-b552-4e5c-b207-dc9b019df49a", "atrio_korinbo", "香林坊アトリオ（香林坊大和）", "modern", "kanazawa"),
    ("bldg_225af865-6a62-4e74-ad92-e60a7fecb48f", "sanyo_himeji", "山陽百貨店（姫路駅前）", "modern", "himeji"),
    # Schools: classroom blocks and gyms of municipal schools, suburban (Kanazawa) and inner-city (Taito).
    ("bldg_58f823a3-9ee0-43e9-aebf-5faeade2862c", "school_shikindai_classrooms", "紫錦台中学校 校舎", "school", "kanazawa"),
    ("bldg_0502618b-6b69-4ba9-8f70-9f90ffc6c5b9", "school_shikindai_annex", "紫錦台中学校 別棟（鉄骨 2 階）", "school", "kanazawa"),
    ("bldg_520c721f-0b93-40ed-8b6b-1f0bb87ff587", "school_shikindai_gym", "紫錦台中学校 体育館（寄棟）", "school", "kanazawa"),
    ("bldg_889b19aa-91b0-4d24-b44c-6d0897ec56fb", "school_chuo_classrooms", "中央小学校 校舎", "school", "kanazawa"),
    ("bldg_e9937263-eba7-499b-8e39-a939c5f3d3fa", "school_chuo_wing", "中央小学校 校舎（鉄骨 2 階、コの字）", "school", "kanazawa"),
    ("bldg_32daae02-3590-4d19-b1d4-1ede56d62318", "school_kenroku_classrooms", "兼六小学校 校舎", "school", "kanazawa"),
    ("bldg_be11df60-0458-42f1-bd85-9f94caf4e55d", "school_kenroku_gym", "兼六小学校 体育館（切妻）", "school", "kanazawa"),
    ("bldg_b8489c38-cc17-4eb2-9782-8ce3db739495", "school_kinryu", "金竜小学校（都心の一体型校舎）", "school", "taito"),
    ("bldg_5650eb42-a950-4c02-98e3-1ce16d604d2a", "school_negishi", "根岸小学校（都心の一体型校舎）", "school", "taito"),
    # Industry: sheds, works and warehouses.
    ("bldg_64e31921-4e86-441a-a173-e7942e6ab7cb", "factory_shed", "町工場（切妻、鉄骨造、金沢）", "industrial", "kanazawa"),
    ("bldg_f5b9c918-22e9-40e3-9692-c08312624387", "factory_works_1953", "工場（1953 年、鉄筋コンクリート造、金沢 石引）", "industrial", "kanazawa"),
    ("bldg_76714911-79e7-475a-ba10-70b14b66fef7", "factory_small_tsukuda", "町工場（切妻、佃）", "industrial", "chuo"),
    ("bldg_1ee0acc8-b7ca-45b6-8572-80ceedece25d", "factory_building", "工場ビル（墨田区横網、台東区のデータ）", "modern", "taito"),
    ("bldg_34f0a69b-920f-4f8f-b834-678329ca789f", "warehouse_kachidoki", "倉庫（切妻、勝どき）", "industrial", "chuo"),
    ("bldg_1aaa241b-98f1-447f-9379-0ccf819bf795", "warehouse_harumi", "大型倉庫（晴海）", "industrial", "chuo"),
    ("bldg_ce9c458f-e370-4f49-80d5-a5863aac02a5", "warehouse_multistorey", "多層倉庫（中央区）", "industrial", "chuo"),
    # Arenas (the Yokohama Arena's mesh is LOD1 only, so not here).
    ("bldg_5fc20e0c-e93b-4032-b60d-5f3e29205eb1", "saitama_super_arena", "さいたまスーパーアリーナ", "glass", "saitama"),
    ("bldg_c2a8fdb7-36bb-4c35-86e6-7afd672dbd66", "yokohama_buntai", "横浜BUNTAI（関内）", "modern", "yokohama"),
    ("bldg_be0df25a-55be-467a-b406-35fbc0ef631c", "yokohama_budokan", "横浜武道館", "industrial", "yokohama"),
]
ALL_LANDMARKS = [(g, s, n, p, "kyoto") for g, s, n, p in LANDMARKS] + MORE_LANDMARKS

# Buildings whose LOD2 shape is too coarse to voxelize (a pagoda is a box with a hip roof, halls lose their eaves
# and pent roofs): they are built by the temple generators in core (core.building.temple) from these specs, sizes
# taken from the LOD2 surfaces (eave outlines, eave and ridge heights, gable insets), rounded to whole blocks.
# `specs` writes them for `gradlew :core:templeModels`, whose models `build` then uses instead of voxels.
TEMPLES = {
    # LOD2: first eave outline 18.5 m square, top eaves 35.6 m, top roof 40.2 m, finial to 55 m.
    "toji_pagoda": dict(kind="pagoda", role="PAGODA", storeys=5, span=19, topSpan=15, body=9, topBody=7, firstEave=10.5,
                        topEave=35.6, apex=40.2, height=55, platform=2, pitch=0.42),
    # LOD2: pent roof (裳階) eaves 44.4 x 29.1 m at 11.4 m; main eaves 37.9 x 22.4 m at 18.2 m, irimoya ridge
    # 24.9 m with the gable walls 3.2 m in from the hipped ends.
    "toji_kondo": dict(kind="hall", role="KONDO", width=37, depth=23, eave=18.2, ridge=24.9, roof="IRIMOYA", gableInset=3.2,
                       overhang=4, mokoshiWidth=45, mokoshiDepth=29, mokoshiEave=11.4, mokoshiOverhang=3,
                       platform=2, bay=4.5),
    # LOD2: eaves 45.6 x 26.1 m at 6.9 m, irimoya ridge 17.7 m, gable walls 4.8 m in.
    "toji_kodo": dict(kind="hall", role="KODO", width=45, depth=27, eave=6.9, ridge=17.7, roof="IRIMOYA", gableInset=4.8,
                      overhang=4, platform=2, bay=4.5),
    # LOD2: a two-storey gate (三門); lower roof eaves 25.5 x 33.3 m at 11.8 m, upper eaves 23.3 x 30.9 m at
    # 21.3 m, irimoya ridge 29.2 m along the long side, gable walls 3.8 m in. Five bays, three ways through.
    # (Generated with the way through along z, so turned a quarter from the real one, which faces east.)
    "higashi_honganji_goeidomon": dict(kind="gate", role="TEMPLE_GATE", width=31, depth=23, eave=21.3, ridge=29.2,
                                       roof="IRIMOYA", gableInset=3.8, overhang=4, bays=5, passages=3,
                                       lowerWidth=33, lowerDepth=25, lowerEave=11.8, lowerOverhang=3, platform=2),
    # LOD2: 13.9 x 13.6 m, eaves 9.0 m, gable ridge 14.2 m: a single-storey gate (四脚門).
    "nishi_honganji_goeidomon": dict(kind="gate", role="TEMPLE_GATE", width=13, depth=13, eave=9.0, ridge=14.2,
                                     roof="GABLE", overhang=3, bays=3, passages=1, platform=1),
    # LOD1 only (east of the LOD2 area): 47.3 x 19.2 m with the stair houses (山廊) at both ends, 21.9 m high. A
    # Zen three-gate, five bays and three ways through; the storey heights and the parts after photographs
    # (columns on stones, doors opened back, boarded side bays, deep brackets, unpainted weathered wood).
    "nanzenji_sanmon": dict(kind="gate", role="TEMPLE_GATE", width=29, depth=17, eave=16.0, ridge=21.9,
                            roof="IRIMOYA", gableInset=3.5, overhang=4, bays=5, passages=3, lowerWidth=31,
                            lowerDepth=19, lowerEave=10.0, lowerOverhang=3, platform=2, style="ZEN", sanro=8,
                            palette="WEATHERED"),
    # LOD1 only: 43.0 x 30.9 m, 14.6 m high. Gion-zukuri (祇園造): main sanctuary and worship hall under one
    # cypress-bark irimoya roof, a lower roof (孫庇) all round, vermilion and white, black lattice, a railed
    # veranda; the eave heights after photographs.
    "yasaka_honden": dict(kind="hall", role="HONDEN", width=37, depth=25, eave=9.5, ridge=14.6, roof="IRIMOYA",
                          gableInset=4, overhang=4, mokoshiWidth=43, mokoshiDepth=31, mokoshiEave=6.5,
                          mokoshiOverhang=3, platform=1, bay=4.0, veranda=True, palette="HIWADA"),
    # LOD1 only: 14.0 x 12.9 m, 10.1 m high, just south of the main hall. The dance hall (舞殿): open on a raised
    # floor with a railing, dark wood, lanterns between the columns.
    "yasaka_buden": dict(kind="pavilion", role="KAGURADEN", width=15, depth=13, eave=6.0, ridge=10.1,
                         roof="IRIMOYA", gableInset=2.5, overhang=2, floor=2, lanterns=True, palette="DARK"),
    # Fushimi Inari, all LOD1 only; the parts after photographs. The romon: 30.5 x 11.8 m with its corridors,
    # 13.8 m high; two storeys under one cypress-bark irimoya roof, a railed balcony between, corridors both sides.
    "inari_romon": dict(kind="gate", role="SHRINE_GATE", width=15, depth=11, eave=10.0, ridge=13.8, roof="IRIMOYA",
                        gableInset=2.5, overhang=3, bays=3, passages=1, platform=1, style="ROMON", sanro=7,
                        palette="INARI"),
    # The outer worship hall (外拝殿): 17.6 x 12.5 m, 9.8 m high; open on a raised floor, white panels under the
    # beams, lanterns, a vermilion fence round the foot.
    "inari_gaihaiden": dict(kind="pavilion", role="HAIDEN", width=17, depth=13, eave=7.5, ridge=9.8,
                            roof="IRIMOYA", gableInset=3, overhang=2, floor=1, lanterns=True, panels=True,
                            fence=True, palette="INARI"),
    # The main sanctuary with its inner worship hall: 31.2 x 22.9 m, 9.1 m high; a five-bay nagare-zukuri.
    "inari_honden": dict(kind="honden", role="HONDEN", width=31, bodyWidth=23, bodyDepth=11, porch=6, eave=6.0,
                         ridge=9.1, floor=2, palette="INARI"),
    "inari_otorii": dict(kind="torii", role="TORII", span=10, height=13, palette="INARI"),
    "inari_senbon_torii": dict(kind="torii_tunnel", role="TORII", length=40, span=3, height=7, spacing=2,
                               palette="INARI"),
    # Not from PLATEAU: the same generators in the painted styles (Senso-ji's vermilion pagoda, a shrine hall
    # under a copper roof), to check the palettes.
    "demo_pagoda_vermilion": dict(kind="pagoda", role="PAGODA", storeys=5, span=19, topSpan=15, body=9, topBody=7, firstEave=10.5,
                                  topEave=35.6, apex=40.2, height=55, platform=2, pitch=0.42, palette="VERMILION"),
    "demo_gate_vermilion": dict(kind="gate", role="TEMPLE_GATE", width=25, depth=15, eave=16.0, ridge=22.7,
                                roof="IRIMOYA", gableInset=3, overhang=4, bays=3, passages=1, lowerWidth=25,
                                lowerDepth=15, lowerEave=9.0, lowerOverhang=3, platform=1, palette="VERMILION"),
    "demo_belfry_open": dict(kind="belfry", role="BELFRY", width=11, depth=9, eave=7.5, ridge=11.5, roof="IRIMOYA",
                             gableInset=2, overhang=2, platform=2),
    "demo_belfry_hakamagoshi": dict(kind="belfry", role="BELFRY", width=11, depth=11, eave=12.0, ridge=16.0,
                                    roof="IRIMOYA", gableInset=2, overhang=2, skirt=True, platform=1),
    "demo_honden_nagare": dict(kind="honden", role="HONDEN", width=11, bodyWidth=7, bodyDepth=5, porch=4,
                               eave=7.5, ridge=11.0, floor=2, palette="HIWADA"),
    "demo_honden_chigi": dict(kind="honden", role="HONDEN", width=11, bodyWidth=7, bodyDepth=5, porch=3,
                              eave=7.5, ridge=11.5, floor=2, chigi=True, palette="WEATHERED"),
    "demo_torii": dict(kind="torii", role="TORII", span=6, height=8, palette="VERMILION"),
    "demo_otorii": dict(kind="torii", role="TORII", span=12, height=15, palette="VERMILION"),
    "demo_kasuga": dict(kind="sanctuary", role="HONDEN", style="KASUGA", bodyWidth=5, bodyDepth=5, overhang=2,
                        eave=7.5, ridge=11.0, floor=2, palette="VERMILION"),
    "demo_hachiman": dict(kind="sanctuary", role="HONDEN", style="HACHIMAN", bodyWidth=15, bodyDepth=11, overhang=2,
                          eave=8.5, ridge=13.0, floor=2, palette="HIWADA"),
    "demo_shinmei": dict(kind="sanctuary", role="HONDEN", style="SHINMEI", bodyWidth=9, bodyDepth=5, overhang=2,
                         eave=8.0, ridge=12.5, floor=3, palette="SHINMEI"),
    "demo_kairo": dict(kind="corridor", role="KAIRO", lengthA=25, lengthB=17, depth=5, eave=5.5, ridge=8.0,
                       palette="INARI"),
    "demo_toro_kasuga": dict(kind="lantern", role="TORO", height=7, palette="NATURAL"),
    "demo_toro_small": dict(kind="lantern", role="TORO", height=4, palette="NATURAL"),
    "demo_komainu": dict(kind="guardians", role="KOMAINU", spacing=6, palette="NATURAL"),
    "demo_kitsune": dict(kind="guardians", role="KOMAINU", spacing=6, fox=True, palette="INARI"),
    "demo_temizuya": dict(kind="temizuya", role="TEMIZUYA", width=9, depth=7, eave=6.0, ridge=9.0, roof="IRIMOYA",
                          palette="COPPER"),
    # Precincts planned on a plot (rank, scale, seeded choices, terraces on the slope, forest); showGround puts the
    # natural ground in for the preview only.
    "demo_precinct_shrine_small_hill": dict(kind="precinct", role="KEIDAI", precinct="SHRINE", width=33, depth=45,
                                            seed=3, ground="worldgen", worldSeed=12345, x=-10232, z=-10272, radius=3000, minRise=6, maxRise=16,
                                            showGround=True, palette="VERMILION"),
    "demo_precinct_shrine_medium_hill": dict(kind="precinct", role="KEIDAI", precinct="SHRINE", width=51, depth=75,
                                             seed=5, ground="worldgen", worldSeed=12345, x=-10232, z=-10272, radius=3000, minRise=10, maxRise=25,
                                             showGround=True, palette="HIWADA"),
    "demo_precinct_shrine_large": dict(kind="precinct", role="KEIDAI", precinct="SHRINE", width=75, depth=105, seed=9,
                                       ground="slope", perX=0.03, perZ=-0.06, showGround=True, palette="INARI"),
    "demo_precinct_temple_medium_hill": dict(kind="precinct", role="GARAN", precinct="TEMPLE", width=55, depth=81,
                                             seed=4, ground="worldgen", worldSeed=12345, x=-10232, z=-10272, radius=3000, minRise=10, maxRise=25,
                                             showGround=True, palette="NATURAL"),
    "demo_precinct_temple_large": dict(kind="precinct", role="GARAN", precinct="TEMPLE", width=89, depth=121, seed=8,
                                       ground="slope", perX=0, perZ=-0.05, showGround=True, palette="NATURAL"),
    "demo_hall_copper": dict(kind="hall", role="HAIDEN", width=29, depth=21, eave=8.0, ridge=17.0, roof="IRIMOYA", gableInset=4,
                             overhang=4, platform=2, bay=4.0, palette="COPPER"),
    # Landmarks PLATEAU does not cover (core.building.landmark), self-made at real Japanese sizes. Lighthouses: a
    # cape light like Inubōsaki (31.6 m to the top, white round tower, gallery, lantern room, keeper's house) and a
    # smaller one like Shionomisaki (22.8 m); breakwater lights, red (right side entering port) and white, 11–12 m.
    "lighthouse_cape": dict(kind="lighthouse", role="LIGHTHOUSE", type="CAPE", tower=23, baseRadius=4.5, topRadius=3.2,
                            keeperHouse=True),
    "lighthouse_cape_small": dict(kind="lighthouse", role="LIGHTHOUSE", type="CAPE", tower=14, baseRadius=3.2,
                                  topRadius=2.6),
    "harbour_light_red": dict(kind="lighthouse", role="HARBOUR_LIGHT", type="HARBOUR", tower=7, baseRadius=2.2,
                              topRadius=1.5, colour="RED"),
    "harbour_light_white": dict(kind="lighthouse", role="HARBOUR_LIGHT", type="HARBOUR", tower=8, baseRadius=2.2,
                                topRadius=1.5, colour="WHITE"),
    # Sports grounds for a sports park (a circle of radius 130–180): a football stadium (105 x 68 pitch, four stands,
    # roofed main stand, floodlights), a municipal ballpark (両翼 98, 中堅 122) and a 400 m athletics ground.
    "stadium_football": dict(kind="stadium", role="STADIUM", mainDepth=32, backDepth=24, endDepth=20, backRoof=False,
                             floodlights=40),
    "stadium_baseball": dict(kind="ballpark", role="BALLPARK", lines=98, centre=122, foul=16, standDepth=26,
                             floodlights=36),
    "athletics_ground": dict(kind="athletics", role="ATHLETICS_GROUND", standDepth=20, roofLength=60),
    # Civic and roadside buildings (core.building.landmark), self-made at Japanese sizes: a mid-sized city's hall
    # (office block 48 x 22, 8 storeys, a council wing), a smaller town's (30 x 16, 5 storeys), a general hospital
    # (outpatient block 50 wide, 8-storey wards, helipad), a municipal library (44 x 30, two storeys) and a branch
    # library, filling stations with three and two islands.
    "city_hall_office": dict(kind="cityhall", role="CITY_HALL", width=48, depth=22, storeys=8, council=22, seed=3),
    "town_hall": dict(kind="cityhall", role="CITY_HALL", width=30, depth=16, storeys=5, council=0, seed=5),
    "hospital_general": dict(kind="hospital", role="HOSPITAL", width=50, wards=8, seed=2),
    "hospital_small": dict(kind="hospital", role="HOSPITAL", width=34, wards=5, seed=6),
    "library_city": dict(kind="library", role="LIBRARY", width=44, depth=30, seed=4),
    "library_branch": dict(kind="library", role="LIBRARY", width=24, depth=18, seed=9),
    "gas_station": dict(kind="gas_station", role="GAS_STATION", islands=3, carWash=True, seed=1),
    "gas_station_small": dict(kind="gas_station", role="GAS_STATION", islands=2, carWash=False, seed=2),
    # The hillside demos stand on MTM_worldgen's real terrain (seed 12345, round the castle town): each finds the
    # mountainside site nearest the town whose ground climbs as much as asked from the street to the back.
    # A shrine high on a mountainside: the approach climbs 30-60 m from the street.
    "demo_precinct_shrine_mountain": dict(kind="precinct", role="KEIDAI", precinct="SHRINE", width=41, depth=121,
                                          seed=12, ground="worldgen", worldSeed=12345, x=-10232, z=-10272, radius=3000, minRise=30, maxRise=60,
                                          showGround=True, palette="VERMILION"),
    # The torii tunnel up a mountain path (Inariyama), climbing 18-40 m.
    "demo_senbon_torii_mountain": dict(kind="torii_tunnel", role="TORII", length=60, span=3, height=7, spacing=2,
                                       ground="worldgen", worldSeed=12345, x=-10232, z=-10272, radius=3000, minRise=18, maxRise=40, showGround=True,
                                       palette="INARI"),
    "demo_karesansui": dict(kind="garden", role="TEIEN", style="karesansui", width=25, depth=15, seed=7),
    "demo_moss_garden": dict(kind="garden", role="TEIEN", style="moss", width=15, depth=17, seed=7),
    "demo_shoin": dict(kind="hall", role="QUARTERS", width=27, depth=21, eave=5.5, ridge=11.0, roof="IRIMOYA",
                       gableInset=3, overhang=3, platform=1, bay=3.0, veranda=True),
}

# What each library entry is, for reuse: a category for every building; for sacred buildings also temple (寺院) or
# shrine (神社) and the role (core.building.temple.SacredBuilding; None where the entry is several buildings or the
# role is not known).
TEMPLE, SHRINE, BOTH = "TEMPLE", "SHRINE", "BOTH"
LABELS = {
    "kyoto_station": ("station",), "kyoto_tower": ("tower",),
    "toji_pagoda": ("sacred", TEMPLE, "PAGODA"), "toji_kondo": ("sacred", TEMPLE, "KONDO"),
    "toji_kodo": ("sacred", TEMPLE, "KODO"),
    "higashi_honganji_complex": ("sacred", TEMPLE, None), "higashi_honganji_annex": ("sacred", TEMPLE, None),
    "nishi_honganji_goeido": ("sacred", TEMPLE, "MIEIDO"), "nishi_honganji_amidado": ("sacred", TEMPLE, "AMIDADO"),
    "nishi_honganji_hall": ("sacred", TEMPLE, None), "temple_hall_karasuma": ("sacred", TEMPLE, "HONDO"),
    "nijo_ninomaru": ("palace",), "minamiza": ("theatre",),
    "pref_office_old": ("government",), "city_hall": ("government",),
    "takashimaya": ("store",), "daimaru": ("store",), "aeon_mall": ("store",),
    "hotel_okura": ("hotel",), "westin_miyako": ("hotel",),
    "aquarium": ("museum",), "railway_museum": ("museum",), "umekoji_depot": ("warehouse",),
    "higashi_honganji_goeidomon": ("sacred", TEMPLE, "TEMPLE_GATE"),
    "nishi_honganji_goeidomon": ("sacred", TEMPLE, "TEMPLE_GATE"),
    "nanzenji_sanmon": ("sacred", TEMPLE, "TEMPLE_GATE"),
    "yasaka_honden": ("sacred", SHRINE, "HONDEN"), "yasaka_buden": ("sacred", SHRINE, "KAGURADEN"),
    "inari_romon": ("sacred", SHRINE, "SHRINE_GATE"), "inari_gaihaiden": ("sacred", SHRINE, "HAIDEN"),
    "inari_honden": ("sacred", SHRINE, "HONDEN"), "inari_otorii": ("sacred", SHRINE, "TORII"),
    "inari_senbon_torii": ("sacred", SHRINE, "TORII"),
    "demo_honden_nagare": ("sacred", SHRINE, "HONDEN"), "demo_honden_chigi": ("sacred", SHRINE, "HONDEN"),
    "demo_torii": ("sacred", SHRINE, "TORII"), "demo_otorii": ("sacred", SHRINE, "TORII"),
    "demo_gate_vermilion": ("sacred", TEMPLE, "TEMPLE_GATE"), "demo_belfry_open": ("sacred", TEMPLE, "BELFRY"),
    "demo_belfry_hakamagoshi": ("sacred", TEMPLE, "BELFRY"),
    "demo_pagoda_vermilion": ("sacred", TEMPLE, "PAGODA"),
    "demo_kasuga": ("sacred", SHRINE, "HONDEN"), "demo_hachiman": ("sacred", SHRINE, "HONDEN"),
    "demo_shinmei": ("sacred", SHRINE, "HONDEN"), "demo_kairo": ("sacred", BOTH, "KAIRO"),
    "demo_toro_kasuga": ("sacred", BOTH, "TORO"), "demo_toro_small": ("sacred", BOTH, "TORO"),
    "demo_komainu": ("sacred", SHRINE, "KOMAINU"), "demo_kitsune": ("sacred", SHRINE, "KOMAINU"),
    "demo_temizuya": ("sacred", BOTH, "TEMIZUYA"),
    "himeji_keep": ("castle", None, "CASTLE_KEEP"), "himeji_watari_yagura": ("castle", None, "WATARI_YAGURA"),
    "himeji_yagura_east": ("castle", None, "YAGURA"), "himeji_yagura_southeast": ("castle", None, "YAGURA"),
    "himeji_yagura_southwest": ("castle", None, "YAGURA"), "himeji_yagura_small": ("castle", None, "YAGURA"),
    "himeji_nishinomaru": ("castle", None, "WATARI_YAGURA"),
    "mitsukoshi_nihonbashi": ("store", None, "DEPARTMENT_STORE"), "takashimaya_nihonbashi": ("store", None, "DEPARTMENT_STORE"),
    "matsuya_ginza": ("store", None, "DEPARTMENT_STORE"), "mitsukoshi_ginza": ("store", None, "DEPARTMENT_STORE"),
    "atrio_korinbo": ("store", None, "DEPARTMENT_STORE"), "sanyo_himeji": ("store", None, "DEPARTMENT_STORE"),
    "school_shikindai_classrooms": ("school", None, "CLASSROOMS"), "school_shikindai_gym": ("school", None, "GYM"),
    "school_shikindai_annex": ("school", None, "CLASSROOMS"), "school_chuo_classrooms": ("school", None, "CLASSROOMS"),
    "school_chuo_wing": ("school", None, "CLASSROOMS"), "school_kenroku_classrooms": ("school", None, "CLASSROOMS"),
    "school_kenroku_gym": ("school", None, "GYM"), "school_kinryu": ("school", None, "SCHOOL_COMPACT"),
    "school_negishi": ("school", None, "SCHOOL_COMPACT"),
    "factory_shed": ("industry", None, "FACTORY"), "factory_works_1953": ("industry", None, "FACTORY"),
    "factory_small_tsukuda": ("industry", None, "FACTORY"), "factory_building": ("industry", None, "FACTORY"),
    "warehouse_kachidoki": ("industry", None, "WAREHOUSE"), "warehouse_harumi": ("industry", None, "WAREHOUSE"),
    "warehouse_multistorey": ("industry", None, "WAREHOUSE"),
    "saitama_super_arena": ("sports", None, "ARENA"), "yokohama_buntai": ("sports", None, "ARENA"),
    "yokohama_budokan": ("sports", None, "ARENA"),
    "demo_precinct_shrine": ("sacred", SHRINE, "KEIDAI"), "demo_precinct_temple": ("sacred", TEMPLE, "GARAN"), "demo_hall_copper": ("sacred", SHRINE, "HAIDEN"),
    # Landmarks: no religion, the role is a core.building.landmark.Landmark.
    "lighthouse_cape": ("lighthouse", None, "LIGHTHOUSE"), "lighthouse_cape_small": ("lighthouse", None, "LIGHTHOUSE"),
    "harbour_light_red": ("lighthouse", None, "HARBOUR_LIGHT"),
    "harbour_light_white": ("lighthouse", None, "HARBOUR_LIGHT"),
    "stadium_football": ("sports", None, "STADIUM"), "stadium_baseball": ("sports", None, "BALLPARK"),
    "athletics_ground": ("sports", None, "ATHLETICS_GROUND"),
    "city_hall_office": ("civic", None, "CITY_HALL"), "town_hall": ("civic", None, "CITY_HALL"),
    "hospital_general": ("civic", None, "HOSPITAL"), "hospital_small": ("civic", None, "HOSPITAL"),
    "library_city": ("civic", None, "LIBRARY"), "library_branch": ("civic", None, "LIBRARY"),
    "gas_station": ("roadside", None, "GAS_STATION"), "gas_station_small": ("roadside", None, "GAS_STATION"),
    "demo_precinct_shrine_small_hill": ("sacred", SHRINE, "KEIDAI"),
    "demo_precinct_shrine_medium_hill": ("sacred", SHRINE, "KEIDAI"),
    "demo_precinct_shrine_large": ("sacred", SHRINE, "KEIDAI"),
    "demo_precinct_temple_medium_hill": ("sacred", TEMPLE, "GARAN"),
    "demo_precinct_temple_large": ("sacred", TEMPLE, "GARAN"), "demo_hall_copper": ("sacred", SHRINE, "HAIDEN"),
    "demo_shoin": ("sacred", TEMPLE, "QUARTERS"),
    "demo_precinct_shrine_mountain": ("sacred", SHRINE, "KEIDAI"),
    "demo_senbon_torii_mountain": ("sacred", SHRINE, "TORII"),
    "demo_karesansui": ("sacred", TEMPLE, "TEIEN"), "demo_moss_garden": ("sacred", BOTH, "TEIEN"),
}
DEMO_NAMES = {"lighthouse_cape": "灯台（岬、犬吠埼風、退息所付き）", "lighthouse_cape_small": "灯台（岬、潮岬風）",
              "harbour_light_red": "防波堤灯台（赤）", "harbour_light_white": "防波堤灯台（白）",
              "stadium_football": "球技場（サッカー）", "stadium_baseball": "野球場", "athletics_ground": "陸上競技場",
              "city_hall_office": "市役所（行政棟・議会棟）", "town_hall": "町役場", "hospital_general": "総合病院（ヘリポート付き）",
              "hospital_small": "病院（中規模）", "library_city": "市立図書館", "library_branch": "図書館（分館）",
              "gas_station": "ガソリンスタンド（洗車機付き）", "gas_station_small": "ガソリンスタンド（小）",
              "demo_precinct_shrine": "神社の境内（稲荷風）", "demo_precinct_temple": "寺院の伽藍", "demo_kasuga": "本殿（春日造）", "demo_hachiman": "本殿（八幡造）", "demo_shinmei": "本殿（神明造）",
              "demo_precinct_shrine_small_hill": "小さな神社（山の斜面）", "demo_precinct_shrine_medium_hill": "中規模の神社（山麓）",
              "demo_precinct_shrine_large": "大きな神社（稲荷風、緩い斜面）", "demo_precinct_temple_medium_hill": "中規模の寺院（山麓）",
              "demo_precinct_temple_large": "大きな寺院の伽藍（緩い斜面）", "demo_kasuga": "本殿（春日造）", "demo_hachiman": "本殿（八幡造）", "demo_shinmei": "本殿（神明造）",
              "demo_kairo": "回廊（L 字、角）", "demo_toro_kasuga": "石灯籠（春日灯籠）", "demo_toro_small": "石灯籠（小）",
              "demo_komainu": "狛犬", "demo_kitsune": "狐（稲荷）", "demo_temizuya": "手水舎", "inari_otorii": "伏見稲荷大社 大鳥居", "inari_senbon_torii": "伏見稲荷大社 千本鳥居", "demo_honden_nagare": "本殿（流造、檜皮葺）", "demo_honden_chigi": "本殿（千木・鰹木）",
              "demo_torii": "鳥居（明神鳥居）", "demo_otorii": "大鳥居", "demo_gate_vermilion": "二重門（朱塗り、宝蔵門風）", "demo_belfry_open": "鐘楼（吹放し）",
              "demo_belfry_hakamagoshi": "鐘楼（袴腰）","demo_pagoda_vermilion": "五重塔（朱塗り、浅草寺風）", "demo_hall_copper": "拝殿（銅板葺、神社）",
              "demo_shoin": "書院・庫裏（和室）", "demo_karesansui": "枯山水（砂紋、石組）",
              "demo_precinct_shrine_mountain": "山上の神社（石段の参道、踊り場に鳥居）",
              "demo_senbon_torii_mountain": "千本鳥居（山道、石段）",
              "demo_moss_garden": "苔庭（飛石、灯籠、紅葉、建仁寺垣）"}


def label(slug):
    lab = LABELS.get(slug, ("other",))
    return {"category": lab[0], "religion": lab[1] if len(lab) > 1 else None,
            "role": lab[2] if len(lab) > 2 else None}


def attribution(cities):
    """The credit for the cities the templates were made from (Kyoto always: the generated ones take its sizes)."""
    keys = [k for k in CITIES if k == "kyoto" or k in cities]
    ja = "、".join(f"{CITIES[k][0]}（{CITIES[k][2]}年度）" for k in keys)
    lines = [f"出典：3D都市モデル（Project PLATEAU）{ja}"]
    lines += [f"Source: 3D city model (Project PLATEAU), {CITIES[k][1]} (FY{CITIES[k][2]}), "
              f"https://www.geospatial.jp/ckan/dataset/{CITIES[k][3]}" for k in keys]
    return "\n".join(lines) + """
PLATEAU Site Policy「３．著作権について」に基づき利用。

これらの構造物テンプレートは上記データを加工して作成したものです（ボクセル化、方向の補正、
ブロックの割り当て）。国土交通省および各地方公共団体が作成したものではありません。
These structure templates were made by processing the data above (voxelized at 1 m, turned to the
grid, blocks assigned). They were not made by MLIT or the local governments.

palette "generated" の項目（寺社、灯台、競技場）は core.building の生成器が作ったもので、外部の素材を使っていません
（寸法は PLATEAU の計測値や実在の施設の公表値を参考にした自作の形状）。本 mod のライセンスに従います。
Entries with palette "generated" (temples, shrines, lighthouses, sports grounds) are built by the generators in
core.building from parameters, with no outside material (sizes taken from PLATEAU measurements or published
dimensions of real facilities); self-made shapes under the mod's own licence.
"""



GML_ID = "{http://www.opengis.net/gml}id"
SURFACES = {"RoofSurface": "roof", "OuterFloorSurface": "roof", "WallSurface": "wall",
            "OuterCeilingSurface": "wall", "ClosureSurface": "wall", "GroundSurface": "ground",
            "BuildingInstallation": "wall"}
STEP = 0.25


def local(tag):
    return tag.rsplit("}", 1)[-1]


# ---------------------------------------------------------------- extract

def surfaces(building):
    """[class, exterior ring, [interior rings], polygon id] per LOD2 polygon."""
    out = []

    def ring(e):
        for pl in e.iter():
            if local(pl.tag) == "posList" and pl.text:
                v = [float(x) for x in pl.text.split()]
                return [[v[i], v[i + 1], v[i + 2]] for i in range(0, len(v) - 2, 3)]
        return None

    def walk(e, cls):
        t = local(e.tag)
        if t.startswith(("lod0", "lod1", "lod3", "lod4")) or t == "Opening":
            return
        cls = SURFACES.get(t, cls)
        if t == "Polygon" and cls:
            ext, holes = None, []
            for c in e:
                if local(c.tag) == "exterior":
                    ext = ring(c)
                elif local(c.tag) == "interior":
                    r = ring(c)
                    if r:
                        holes.append(r)
            if ext:
                out.append([cls, ext, holes, e.get(GML_ID)])
            return
        for c in e:
            walk(c, cls)

    walk(building, None)
    return out


def extract_mesh(args):
    """Surfaces of the wanted buildings, and the texture (atlas image, texture coordinates of the
    exterior ring) of each of their polygons; the appearance section follows the buildings."""
    zpath, name, ids = args
    found = {}
    owner = {}  # polygon id -> building id
    with zipfile.ZipFile(zpath) as z, z.open(name) as fh:
        depth = 0
        for ev, el in ET.iterparse(fh, events=("start", "end")):
            t = local(el.tag)
            if t == "ParameterizedTexture" and ev == "end":
                image = None
                for c in el:
                    if local(c.tag) == "imageURI":
                        image = (c.text or "").strip()
                    elif local(c.tag) == "target":
                        pid = (c.get("uri") or "").lstrip("#")
                        if pid in owner and image:
                            for tc in c.iter():
                                if local(tc.tag) == "textureCoordinates" and tc.text:
                                    found[owner[pid]]["tex"][pid] = [image, [float(x) for x in tc.text.split()]]
                                    break
                el.clear()
                continue
            if t != "Building":
                continue
            if ev == "start":
                depth += 1
                continue
            depth -= 1
            if depth:
                continue
            gid = el.get(GML_ID)
            if gid in ids:
                surf = surfaces(el)
                found[gid] = {"surfaces": surf, "tex": {}}
                for sf in surf:
                    if sf[3]:
                        owner[sf[3]] = gid
            el.clear()
    return name, found


def extract(zpath, rows, out, ids):
    os.makedirs(os.path.join(out, "geom"), exist_ok=True)
    by_mesh = {}
    for gid in ids:
        if os.path.exists(os.path.join(out, "geom", gid + ".json")):
            continue
        by_mesh.setdefault(rows[gid]["mesh"], set()).add(gid)
    if not by_mesh:
        print("all extracted")
        return
    jobs = [(zpath, f"udx/bldg/{m}_bldg_6697_op.gml", s) for m, s in sorted(by_mesh.items())]
    print(f"{sum(len(s) for s in by_mesh.values())} buildings in {len(jobs)} mesh files", flush=True)
    images = set()
    with multiprocessing.Pool(min(len(jobs), max(1, (os.cpu_count() or 2) - 1))) as pool:
        for name, found in pool.imap_unordered(extract_mesh, jobs):
            for gid, b in found.items():
                with open(os.path.join(out, "geom", gid + ".json"), "w", encoding="utf-8") as fh:
                    json.dump({"id": gid, **b}, fh)
                images |= {im for im, _ in b["tex"].values()}
            print(f"{name}: {len(found)} buildings, "
                  f"{sum(len(b['tex']) for b in found.values())} textured polygons", flush=True)
    missing = 0
    with zipfile.ZipFile(zpath) as z:  # the atlases (aerial photographs) those polygons use
        have = set(z.namelist())
        for im in sorted(images):
            if "udx/bldg/" + im not in have:  # partial zips from plateau_fetch.py have no appearance folders
                missing += 1
                continue
            dst = os.path.join(out, "tex", im)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            with open(dst, "wb") as fh:
                fh.write(z.read("udx/bldg/" + im))
    print(f"{len(images) - missing} texture atlases" + (f", {missing} not in the zip" if missing else ""), flush=True)


# ---------------------------------------------------------------- voxelize

def newell(pts):
    nx = ny = nz = 0.0
    for (x1, y1, z1), (x2, y2, z2) in zip(pts, pts[1:] + pts[:1]):
        nx += (y1 - y2) * (z1 + z2)
        ny += (z1 - z2) * (x1 + x2)
        nz += (x1 - x2) * (y1 + y2)
    return nx, ny, nz


def open_ring(r):
    return r[:-1] if len(r) > 1 and r[0] == r[-1] else r


def sample_polygon(pts, holes=(), step=STEP):
    """(x, y, z, inner) every `step` over a planar polygon with holes (any shape) and along its
    edges; edge points are not inner (their texture may bleed into the atlas background)."""
    pts = open_ring(pts)
    holes = [open_ring(h) for h in holes if len(open_ring(h)) >= 3]
    if len(pts) < 3:
        return
    for p, q in zip(pts, pts[1:] + pts[:1]):
        n = max(1, int(math.dist(p, q) / step))
        for i in range(n):
            t = i / n
            yield tuple(p[k] + (q[k] - p[k]) * t for k in range(3)) + (False,)
    n = newell(pts)
    k = max(range(3), key=lambda i: abs(n[i]))
    if abs(n[k]) < 1e-9:
        return
    a, b = [i for i in range(3) if i != k]
    d = sum(n[i] * pts[0][i] for i in range(3))
    edges = [((p[a], p[b]), (q[a], q[b])) for r in [pts] + holes for p, q in zip(r, r[1:] + r[:1])]
    lo, hi = min(p[b] for p in pts), max(p[b] for p in pts)
    t = lo + step / 2
    while t < hi:
        xs = sorted(a1 + (t - b1) * (a2 - a1) / (b2 - b1)
                    for (a1, b1), (a2, b2) in edges if (b1 <= t < b2) or (b2 <= t < b1))
        for i in range(0, len(xs) - 1, 2):
            s = xs[i] + step / 2
            while s < xs[i + 1]:
                p = [0.0, 0.0, 0.0]
                p[a], p[b] = s, t
                p[k] = (d - n[a] * s - n[b] * t) / n[k]
                yield (p[0], p[1], p[2], True)
                s += step
        t += step


def fill_columns(ring, holes=()):
    """Columns (x, z) whose centre lies inside a 2D ring and outside its holes."""
    out = set()
    lo, hi = math.floor(min(p[1] for p in ring)), math.ceil(max(p[1] for p in ring))
    edges = [e for r in [ring] + list(holes) for e in zip(r, r[1:] + r[:1])]
    for z in range(lo, hi + 1):
        t = z + 0.5
        xs = sorted(a1 + (t - b1) * (a2 - a1) / (b2 - b1)
                    for (a1, b1), (a2, b2) in edges if (b1 <= t < b2) or (b2 <= t < b1))
        for i in range(0, len(xs) - 1, 2):
            for x in range(math.ceil(xs[i] - 0.5), math.floor(xs[i + 1] - 0.5) + 1):
                out.add((x, z))
    return out


def to_local(surf):
    """Lat/lon/height -> metres (x east, y up, z south), turned so walls follow the axes."""
    allp = [p for _, ring, _, _ in surf for p in ring]
    lat0 = sum(p[0] for p in allp) / len(allp)
    lon0 = sum(p[1] for p in allp) / len(allp)
    proj = project(lat0, lon0)

    def loc(ring):
        return [(e, h, -n) for (e, n), h in ((proj(a, b), h) for a, b, h in ring)]
    rings = [(cls, loc(ring), [loc(h) for h in holes], pid) for cls, ring, holes, pid in surf]
    # Dominant wall direction modulo 90 degrees, weighted by area.
    c4 = s4 = 0.0
    for cls, ring, _, _ in rings:
        if cls != "wall":
            continue
        nx, _, nz = newell(ring)
        w = math.hypot(nx, nz)
        if w > 1e-6:
            a = math.atan2(nz, nx)
            c4 += w * math.cos(4 * a)
            s4 += w * math.sin(4 * a)
    theta = math.atan2(s4, c4) / 4 if (c4 or s4) else 0.0
    c, s = math.cos(-theta), math.sin(-theta)
    def turn(r):
        return [(x * c - z * s, y, x * s + z * c) for x, y, z in r]
    rings = [(cls, turn(r), [turn(h) for h in hs], pid) for cls, r, hs, pid in rings]
    grounds = [p[1] for cls, ring, _, _ in rings if cls == "ground" for p in ring]
    y0 = min(grounds) if grounds else min(p[1] for _, ring, _, _ in rings for p in ring)
    x0 = min(p[0] for _, ring, _, _ in rings for p in ring)
    z0 = min(p[2] for _, ring, _, _ in rings for p in ring)

    def shift(r):
        return [(x - x0, y - y0, z - z0) for x, y, z in r]
    rings = [(cls, shift(r), [shift(h) for h in hs], pid) for cls, r, hs, pid in rings]
    return rings, math.degrees(theta), (lat0, lon0)


def texture_map(ring, uv):
    """Affine map from a point on the polygon's plane to texture coordinates, fitted to its vertices."""
    image, coords = uv
    pts = list(ring)
    pairs = [(coords[i], coords[i + 1]) for i in range(0, len(coords) - 1, 2)]
    m = min(len(pts), len(pairs))
    if m < 3:
        return None
    n = newell(open_ring(pts))
    k = max(range(3), key=lambda i: abs(n[i]))
    a, b = [i for i in range(3) if i != k]
    S = [[0.0] * 3 for _ in range(3)]
    ru, rv = [0.0] * 3, [0.0] * 3
    for p, (u, v) in zip(pts[:m], pairs[:m]):
        f = (1.0, p[a], p[b])
        for i in range(3):
            for j in range(3):
                S[i][j] += f[i] * f[j]
            ru[i] += f[i] * u
            rv[i] += f[i] * v
    cu, cv = solve3(S, ru), solve3(S, rv)
    if cu is None or cv is None:
        return None
    return image, a, b, cu, cv


def solve3(A, r):
    M = [row[:] + [r[i]] for i, row in enumerate(A)]
    for c in range(3):
        piv = max(range(c, 3), key=lambda i: abs(M[i][c]))
        if abs(M[piv][c]) < 1e-12:
            return None
        M[c], M[piv] = M[piv], M[c]
        for i in range(3):
            if i != c:
                f = M[i][c] / M[c][c]
                M[i] = [x - f * y for x, y in zip(M[i], M[c])]
    return [M[i][3] / M[i][i] for i in range(3)]


ATLASES = {}


def atlas(path):
    im = ATLASES.get(path)
    if im is None:
        img = Image.open(path).convert("RGB")
        im = ATLASES[path] = (img.load(), img.size[0], img.size[1])
    return im


def voxelize(rings, tex=None, texdir=None):
    """Solid voxels, the class of each surface voxel, the roof height per column and, with
    textures, up to 32 colour samples per surface voxel and class."""
    surf = {}  # voxel -> class
    height = {}  # column -> highest roof point
    colours = {}  # (voxel, class) -> [rgb]
    owners = {}  # wall voxel -> {polygon id: samples}
    columns = set()
    prio = {"ground": 0, "wall": 1, "roof": 2}
    for cls, ring, holes, pid in rings:
        if cls == "ground":
            columns |= fill_columns([(p[0], p[2]) for p in ring], [[(p[0], p[2]) for p in h] for h in holes])
        tm = texture_map(ring, tex[pid]) if tex and pid in tex and cls != "ground" else None
        if tm and not os.path.exists(os.path.join(texdir, tm[0])):  # an atlas not extracted (not in the zip)
            tm = None
        if tm:
            pix, W, H = atlas(os.path.join(texdir, tm[0]))
            _, ta, tb, cu, cv = tm
        for x, y, z, inner in sample_polygon(ring, holes):
            v = (math.floor(x), max(0, math.floor(y - 0.01)), math.floor(z))
            old = surf.get(v)
            if old is None or prio[cls] > prio[old]:
                surf[v] = cls
            if tm and inner:
                p = (x, y, z)
                u = cu[0] + cu[1] * p[ta] + cu[2] * p[tb]
                w = cv[0] + cv[1] * p[ta] + cv[2] * p[tb]
                px, py = int(u * W), int((1 - w) * H)
                if 0 <= px < W and 0 <= py < H:
                    lst = colours.setdefault((v, cls), [])
                    if len(lst) < 32:
                        lst.append(pix[px, py])
                    if cls == "wall":
                        o = owners.setdefault(v, {})
                        o[pid] = o.get(pid, 0) + 1
            if cls == "roof":
                col = (v[0], v[2])
                if y > height.get(col, -1e9):
                    height[col] = y
    # Inside by parity: a vertical ray through each column centre crosses the closed LOD2 solid
    # at every non-vertical face (ground, roofs, undersides of overhangs); fill between pairs.
    # Filling each column up to its highest face would fill everything under an overhang.
    hits = {}
    for cls, ring, holes, _ in rings:
        r, hs = open_ring(ring), [open_ring(h) for h in holes]
        if len(r) < 3:
            continue
        nx, ny, nz = newell(r)
        if abs(ny) < 1e-3 * math.sqrt(nx * nx + ny * ny + nz * nz) + 1e-9:
            continue  # vertical
        d = nx * r[0][0] + ny * r[0][1] + nz * r[0][2]
        for cx, cz in fill_columns([(p[0], p[2]) for p in r], [[(p[0], p[2]) for p in h] for h in hs if len(h) >= 3]):
            hits.setdefault((cx, cz), []).append((d - nx * (cx + 0.5) - nz * (cz + 0.5)) / ny)
    solid = set(surf)
    odd = 0
    for col, ys in hits.items():
        ys.sort()
        merged = []
        for y in ys:  # a centre on an edge shared by two faces counts once
            if merged and y - merged[-1] < 0.05:
                continue
            merged.append(y)
        if len(merged) % 2:  # not closed here (a seam or a tangent face): keep only the surfaces
            odd += 1
            continue
        for lo, hi in zip(merged[::2], merged[1::2]):
            for y in range(max(0, math.floor(lo + 0.5)), max(0, math.floor(hi - 0.01)) + 1):
                solid.add((col[0], y, col[1]))
    if odd:
        print(f"  {odd} of {len(hits)} columns cross the surface an odd number of times", flush=True)
    return solid, surf, height, colours, owners


# ---------------------------------------------------------------- palettes

PALETTES = {
    # roof full / stairs / slab, wall, accent (pillars, sills), window, floor, flat roof
    "temple": dict(roof="deepslate_tiles", stairs="deepslate_tile_stairs", slab="deepslate_tile_slab",
                   wall="calcite", lower="dark_oak_planks", pillar="dark_oak_log", band="dark_oak_planks",
                   window=None, floor="spruce_planks", flat="deepslate_tiles"),
    "brick": dict(roof="deepslate_tiles", stairs="deepslate_tile_stairs", slab="deepslate_tile_slab",
                  wall="bricks", lower="stone_bricks", pillar="stone_bricks", band="smooth_stone",
                  window="gray_stained_glass", floor="spruce_planks", flat="smooth_stone"),
    "modern": dict(roof="gray_concrete", stairs="polished_andesite_stairs", slab="polished_andesite_slab",
                   wall="light_gray_concrete", lower="polished_andesite", pillar="light_gray_concrete",
                   band="light_gray_concrete", window="light_gray_stained_glass", floor="smooth_stone",
                   flat="smooth_stone"),
    "white": dict(roof="white_concrete", stairs="quartz_stairs", slab="quartz_slab",
                  wall="white_concrete", lower="white_concrete", pillar="white_concrete", band="white_concrete",
                  window="light_gray_stained_glass", floor="smooth_stone", flat="white_concrete"),
    "glass": dict(roof="gray_concrete", stairs="polished_andesite_stairs", slab="polished_andesite_slab",
                  wall="light_gray_concrete", lower="polished_andesite", pillar="gray_concrete",
                  band="light_gray_concrete", window="light_blue_stained_glass", floor="smooth_stone",
                  flat="smooth_stone", glassy=True),
    "house": dict(roof="deepslate_tiles", stairs="deepslate_tile_stairs", slab="deepslate_tile_slab",
                  wall="white_terracotta", lower="white_terracotta", pillar="stripped_dark_oak_log",
                  band="white_terracotta", window="light_gray_stained_glass", floor="spruce_planks",
                  flat="smooth_stone"),
    # White plaster over a stone base, grey tiles, dark windows. LOD2 models a keep or turret with its stone wall
    # (the walls start at the foot of the wall), so the base is everything one storey below the eaves (the 10th
    # percentile of the roof heights: a few low roofs of gates and corridors do not count).
    "castle": dict(roof="deepslate_tiles", stairs="deepslate_tile_stairs", slab="deepslate_tile_slab",
                   wall="white_concrete", lower="stone_bricks", pillar="white_concrete", band="smooth_quartz",
                   window="dark_oak_planks", floor="spruce_planks", flat="deepslate_tiles", base="eave", bay=5, panes=(2,)),
    # Stone-faced pre-war department stores (Mitsukoshi, Takashimaya Nihonbashi).
    "stone": dict(roof="smooth_stone", stairs="smooth_sandstone_stairs", slab="smooth_sandstone_slab",
                  wall="smooth_sandstone", lower="polished_granite", pillar="cut_sandstone", band="cut_sandstone",
                  window="gray_stained_glass", floor="smooth_stone", flat="smooth_stone", bay=3, panes=(1,)),
    # Schools: off-white concrete, a band of windows along each classroom floor.
    "school": dict(roof="light_gray_concrete", stairs="polished_andesite_stairs", slab="polished_andesite_slab",
                   wall="white_concrete", lower="light_gray_concrete", pillar="white_concrete", band="white_concrete",
                   window="glass", floor="smooth_stone", flat="light_gray_concrete", bay=4, panes=(1, 2, 3)),
    # Sheds and warehouses: metal-clad walls with few windows, low-pitched metal roofs.
    "industrial": dict(roof="light_gray_concrete", stairs="andesite_stairs", slab="andesite_slab",
                       wall="light_gray_concrete", lower="gray_concrete", pillar="gray_concrete",
                       band="gray_concrete", window="gray_stained_glass", floor="smooth_stone", flat="gray_concrete",
                       bay=8, panes=(3, 4)),
}


def choose_palette(row):
    shape, pitch = row.get("roof_shape") or "", float(row.get("roof_pitch") or 0)
    pitched = shape and not shape.startswith("flat") and pitch >= 12
    if pitched and (row.get("usage") == "文教厚生施設" or row.get("structure") == "木造・土蔵造"):
        return "temple"
    return "house" if pitched else "modern"


def storey_height(row):
    try:
        h, n = float(row["height"]), int(row["storeys"])
        if n > 0:
            return max(3, min(5, round(h / n)))
    except (KeyError, ValueError):
        pass
    return 4


def assign_blocks(solid, surf, height, pal, sh):
    """Block state per kept voxel: (name, properties)."""
    P = PALETTES[pal]
    pitched_roofs = pal in ("temple", "brick", "house", "castle", "school", "industrial")
    bay, panes, base = P.get("bay", 4), P.get("panes", (1, 2)), P.get("base", 0)
    if base == "eave":
        eaves = sorted(v[1] for v, c in surf.items() if c == "roof" and v[1] > 2)
        base = max(0, eaves[len(eaves) // 10] - sh) if eaves else 0
    blocks = {}
    dirs = [(1, 0, 0), (-1, 0, 0), (0, 0, 1), (0, 0, -1), (0, 1, 0), (0, -1, 0)]
    for v in solid:
        x, y, z = v
        open_ = [(dx, dy, dz) for dx, dy, dz in dirs if (x + dx, y + dy, z + dz) not in solid]
        if not open_:
            if y % sh == 0:
                blocks[v] = (P["floor"], None)
            continue
        up = (0, 1, 0) in open_
        side = [d for d in open_ if d[1] == 0]
        if up and (surf.get(v) == "roof" or not side or (x, z) in height):
            blocks[v] = roof_block(v, height, P, pitched_roofs)
            continue
        if not side:  # only open below: an overhang's underside
            blocks[v] = (P["band"], None)
            continue
        corner = len({abs(d[0]) for d in side}) > 1 and len(side) >= 2 and any(d[0] for d in side) and any(d[2] for d in side)
        along = z if side[0][0] else x  # coordinate along the wall
        level = y % sh
        roof_above = (x, y + 1, z) in solid and blocks_is_roof(x, y + 1, z, solid, height)
        if y < base:
            blocks[v] = (P["lower"], None)
        elif corner or (pal in ("temple",) and along % 4 == 0):
            blocks[v] = (P["pillar"], {"axis": "y"} if P["pillar"].endswith("_log") else None)
        elif roof_above:
            blocks[v] = (P["band"], None)
        elif pal == "temple":
            blocks[v] = (P["lower"] if y < 3 else P["wall"], None)
        elif y == 0:
            blocks[v] = (P["lower"], None)
        elif P.get("glassy") and level != 0:
            blocks[v] = (P["window"] if along % 6 else P["pillar"], None)
        elif P["window"] and 0 < level < sh - 1 and along % bay in panes:
            blocks[v] = (P["window"], None)
        else:
            blocks[v] = (P["wall"], None)
    return blocks


def blocks_is_roof(x, y, z, solid, height):
    return (x, y + 1, z) not in solid and (x, z) in height


def roof_block(v, height, P, pitched):
    x, y, z = v
    h = height.get((x, z))
    if h is None or not pitched:
        return (P["flat"], None)

    def H(dx, dz):
        return height.get((x + dx, z + dz))
    gx = [g for g in ((H(1, 0) - h) if H(1, 0) is not None else None,
                      (h - H(-1, 0)) if H(-1, 0) is not None else None) if g is not None]
    gz = [g for g in ((H(0, 1) - h) if H(0, 1) is not None else None,
                      (h - H(0, -1)) if H(0, -1) is not None else None) if g is not None]
    gx = sum(gx) / len(gx) if gx else 0.0
    gz = sum(gz) / len(gz) if gz else 0.0
    if math.floor(h - 0.01) != y:  # a step of a steep roof, below the surface
        return (P["roof"], None)
    if max(abs(gx), abs(gz)) >= 0.3:
        if abs(gx) >= abs(gz):
            facing = "east" if gx > 0 else "west"
        else:
            facing = "south" if gz > 0 else "north"
        return (P["stairs"], {"facing": facing, "half": "bottom", "shape": "straight", "waterlogged": "false"})
    if h - y <= 0.5:
        return (P["slab"], {"type": "bottom", "waterlogged": "false"})
    return (P["roof"], None)


# ---------------------------------------------------------------- materials from the textures

def _family(full, stem=None, tex=None):
    stem = stem or full
    return full, stem + "_stairs", stem + "_slab", tex or full


# Roof materials: full block, stairs, slab, texture of the full block.
ROOF_FAMILIES = [_family(*f) for f in [
    ("deepslate_tiles", "deepslate_tile"), ("deepslate_bricks", "deepslate_brick"), ("polished_deepslate",),
    ("cobbled_deepslate",), ("polished_blackstone_bricks", "polished_blackstone_brick"), ("polished_blackstone",),
    ("blackstone",), ("stone_bricks", "stone_brick"), ("mossy_stone_bricks", "mossy_stone_brick"), ("stone",),
    ("cobblestone",), ("andesite",), ("polished_andesite",), ("diorite",), ("polished_diorite",), ("granite",),
    ("polished_granite",), ("smooth_quartz", None, "quartz_block_bottom"),
    ("smooth_sandstone", None, "sandstone_top"), ("smooth_red_sandstone", None, "red_sandstone_top"),
    ("bricks", "brick"), ("mud_bricks", "mud_brick"), ("prismarine",), ("prismarine_bricks", "prismarine_brick"),
    ("dark_prismarine",), ("cut_copper",), ("exposed_cut_copper",), ("weathered_cut_copper",),
    ("oxidized_cut_copper",), ("end_stone_bricks", "end_stone_brick"), ("nether_bricks", "nether_brick"),
    ("red_nether_bricks", "red_nether_brick"),
]] + [_family(w + "_planks", w) for w in
      ("oak", "spruce", "birch", "dark_oak", "jungle", "acacia", "mangrove", "cherry", "crimson", "warped")]
COLOURS16 = ["white", "light_gray", "gray", "black", "brown", "red", "orange", "yellow", "lime", "green",
             "cyan", "light_blue", "blue", "purple", "magenta", "pink"]
# Flat roofs may also be these (no stairs; smooth stone has a slab).
FLAT_EXTRA = [c + "_concrete" for c in COLOURS16] + ["smooth_stone"]
WALL_BLOCKS = ([c + "_concrete" for c in COLOURS16] + ["terracotta"] + [c + "_terracotta" for c in COLOURS16]
               + [f[0] for f in ROOF_FAMILIES] + ["quartz_block", "calcite", "smooth_stone", "iron_block",
                                                 "packed_mud", "stripped_oak_log", "stripped_spruce_log",
                                                 "stripped_dark_oak_log", "stripped_birch_log"])
GLASS_BLOCKS = ["glass"] + [c + "_stained_glass" for c in
                            ("white", "light_gray", "gray", "black", "light_blue", "cyan", "blue", "brown", "green")]
TEXTURE = {f[0]: f[3] for f in ROOF_FAMILIES}
TEXTURE.update({"quartz_block": "quartz_block_side", "smooth_stone": "smooth_stone"})
STAIRS = {f[0]: f[1] for f in ROOF_FAMILIES}
SLAB = {f[0]: f[2] for f in ROOF_FAMILIES}
SLAB["smooth_stone"] = "smooth_stone_slab"
FULL_OF = {v: k for k, v in STAIRS.items()}
FULL_OF.update({v: k for k, v in SLAB.items()})


def block_colours(jar, cache):
    """Average colour (alpha-weighted, first animation frame) of each candidate block's texture in
    the vanilla client jar. Read locally to pick blocks; nothing from the jar is written out
    except these averages, cached in `cache`."""
    names = sorted(set(WALL_BLOCKS + GLASS_BLOCKS + FLAT_EXTRA + [f[0] for f in ROOF_FAMILIES] + ["gold_block"]
                       + [n for P in PALETTES.values() for k, n in P.items()  # the palettes' full blocks too
                          if k not in ("stairs", "slab") and isinstance(n, str) and n != "eave"]))
    if os.path.exists(cache):
        with open(cache, encoding="utf-8") as fh:
            got = json.load(fh)
        if all(n in got for n in names):
            return {k: tuple(v) for k, v in got.items()}
    import io
    out = {}
    with zipfile.ZipFile(jar) as z:
        for n in names:
            img = Image.open(io.BytesIO(z.read(f"assets/minecraft/textures/block/{TEXTURE.get(n, n)}.png"))).convert("RGBA")
            w = img.size[0]
            img = img.crop((0, 0, w, min(w, img.size[1])))
            tot = [0.0, 0.0, 0.0, 0.0]
            for r, g, b, a in img.get_flattened_data():
                tot[0] += r * a
                tot[1] += g * a
                tot[2] += b * a
                tot[3] += a
            out[n] = tuple(round(c / tot[3]) for c in tot[:3])
    with open(cache, "w", encoding="utf-8") as fh:
        json.dump(out, fh, indent=0)
    return out


def lab(rgb):
    def lin(c):
        c /= 255
        return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = (lin(c) for c in rgb)
    x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.9505
    y = 0.2126 * r + 0.7152 * g + 0.0722 * b
    z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.089

    def f(t):
        return t ** (1 / 3) if t > 0.008856 else 7.787 * t + 16 / 116
    fx, fy, fz = f(x), f(y), f(z)
    return 116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)


def median_rgb(samples):
    n = len(samples)
    return tuple(sorted(c[i] for c in samples)[n // 2] for i in range(3))


class Matcher:
    def __init__(self, bc, names):
        self.items = [(n, lab(bc[n])) for n in names]
        self.cache = {}

    def __call__(self, rgb):
        key = (rgb[0] >> 2, rgb[1] >> 2, rgb[2] >> 2)
        hit = self.cache.get(key)
        if hit is None:
            L = lab(rgb)
            hit = self.cache[key] = min(self.items, key=lambda it: (it[1][0] - L[0]) ** 2
                                        + (it[1][1] - L[1]) ** 2 + (it[1][2] - L[2]) ** 2)[0]
        return hit


def luminance(c):
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]


def kmeans(points, k, rounds=10):
    """Deterministic k-means in Lab: starts at evenly spaced lightness quantiles."""
    if len(points) <= k:
        return list(points), list(range(len(points)))
    order = sorted(points)
    centres = [order[int((i + 0.5) * len(order) / k)] for i in range(k)]
    assign = [0] * len(points)
    for _ in range(rounds):
        for i, q in enumerate(points):
            assign[i] = min(range(k), key=lambda c: (centres[c][0] - q[0]) ** 2 + (centres[c][1] - q[1]) ** 2
                            + (centres[c][2] - q[2]) ** 2)
        sums = [[0.0, 0.0, 0.0, 0] for _ in range(k)]
        for q, c in zip(points, assign):
            s_ = sums[c]
            s_[0] += q[0]
            s_[1] += q[1]
            s_[2] += q[2]
            s_[3] += 1
        centres = [(s_[0] / s_[3], s_[1] / s_[3], s_[2] / s_[3]) if s_[3] else centres[i]
                   for i, s_ in enumerate(sums)]
    return centres, assign


class LabMatcher:
    def __init__(self, bc, names):
        self.items = [(n, lab(bc[n])) for n in names]

    def __call__(self, L):
        return min(self.items, key=lambda it: (it[1][0] - L[0]) ** 2 + (it[1][1] - L[1]) ** 2
                   + (it[1][2] - L[2]) ** 2)[0]


WALL_CLUSTERS, ROOF_CLUSTERS = 8, 5


def recolour(blocks, solid, colours, pal, bc):
    """Give every outside block whose surface has texture samples a material from the photographs,
    keeping its shape (stairs and slabs stay stairs and slabs of the matched material).

    The photographs are lit from one side, and streets shade the lower floors: each face direction
    (walls facing each way in 4 m bands, roof slopes facing each way, flat roofs) is brought to the
    same median brightness (walls only lifted, roofs both ways), then the whole building
    is lifted so the bright walls are near white. Colours are clustered per building (walls 8,
    roofs 5) and each cluster becomes one block, so a facade reads as a few materials rather than
    speckle; a majority filter among neighbours cleans up what is left."""
    if not colours:
        return 0
    found = []  # (voxel, role, shape, orientation, rgb)
    for v, (name, props) in blocks.items():
        x, y, z = v
        sides = [d for d in ((1, 0), (-1, 0), (0, 1), (0, -1)) if (x + d[0], y, z + d[1]) not in solid]
        top = (x, y + 1, z) not in solid
        if not top and not sides:
            continue  # not on the outside
        shape = "stairs" if props and "facing" in props else "slab" if props and "type" in props else "full"
        role = "roof" if shape != "full" or (top and (v, "roof") in colours) else "wall"
        lst = colours.get((v, role)) or colours.get((v, "wall" if role == "roof" else "roof"))
        if not lst:
            continue
        if role == "roof":
            orient = props["facing"] if shape == "stairs" else "up"
        else:
            orient = (sides[0], y // 4) if sides else "under"  # walls: per direction and 4 m band
        found.append((v, role, shape, orient, median_rgb(lst)))
    if not found:
        return 0
    groups = {}
    for v, role, shape, orient, rgb in found:
        groups.setdefault((role, orient), []).append(luminance(rgb))
    target = {}
    for role in ("wall", "roof"):
        allv = sorted(l for (r, _), ls in groups.items() if r == role for l in ls)
        if allv:
            target[role] = allv[len(allv) // 2]
    scale = {}
    for key, ls in groups.items():
        ls.sort()
        med = max(ls[len(ls) // 2], 8)
        lo = 0.5 if key[0] == "roof" else 1.0  # roofs: one material, glare and shade both evened out; walls: lift shade only
        scale[key] = max(lo, min(3.0, target[key[0]] / med)) if len(ls) >= 20 else 1.0
    adj = [tuple(min(255.0, c * scale[(role, orient)]) for c in rgb) for _, role, _, orient, rgb in found]
    walls_l = sorted(luminance(c) for c, f in zip(adj, found) if f[1] == "wall")
    gain = max(1.0, min(1.8, 215 / max(walls_l[int(len(walls_l) * 0.9)], 1))) if len(walls_l) >= 50 else 1.0
    labs = [lab(tuple(min(255.0, c * gain) for c in rgb)) for rgb in adj]
    walls = LabMatcher(bc, WALL_BLOCKS + ([] if pal == "temple" else GLASS_BLOCKS))
    roofs = {"full": LabMatcher(bc, [f[0] for f in ROOF_FAMILIES] + FLAT_EXTRA),
             "stairs": LabMatcher(bc, [f[0] for f in ROOF_FAMILIES]),
             "slab": LabMatcher(bc, [f[0] for f in ROOF_FAMILIES] + ["smooth_stone"])}
    chosen = {}
    for role, k in (("wall", WALL_CLUSTERS), ("roof", ROOF_CLUSTERS)):
        idx = [i for i, f in enumerate(found) if f[1] == role]
        if not idx:
            continue
        centres, assign = kmeans([labs[i] for i in idx], k)
        for i, c in zip(idx, assign):
            v, _, shape, _, _ = found[i]
            m = walls(centres[c]) if role == "wall" else roofs[shape](centres[c])
            chosen[v] = (role, shape, m)
    final = {}
    for v, (role, shape, mat) in chosen.items():
        x, y, z = v
        votes = {mat: 2}
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for dz in (-1, 0, 1):
                    o = chosen.get((x + dx, y + dy, z + dz))
                    if o and o[0] == role and o[1] == shape and (dx or dy or dz):
                        votes[o[2]] = votes.get(o[2], 0) + 1
        final[v] = max(votes, key=votes.get)
    for v, mat in final.items():
        name, props = blocks[v]
        role, shape, _ = chosen[v]
        if shape == "stairs":
            blocks[v] = (STAIRS[mat], props)
        elif shape == "slab":
            blocks[v] = (SLAB[mat], props)
        else:
            blocks[v] = (mat, None)
    return len(final)


# ---------------------------------------------------------------- windows from the textures

def _glass_for(rgb):
    """The stained glass whose tint a window's colour suggests: blue, teal, grey or dark."""
    r, g, b = rgb
    if b > r + 18 and b >= g - 5:
        return "light_blue" if b > 120 else "blue"
    if g > r + 12 and b > r + 8:
        return "cyan"
    return "gray" if luminance(rgb) < 85 else "light_gray"


def _smooth(vals, w):
    """Moving average over a window of 2w+1, ignoring None."""
    out = []
    for i in range(len(vals)):
        seg = [v for v in vals[max(0, i - w):i + w + 1] if v is not None]
        out.append(sum(seg) / len(seg) if seg else None)
    return out


def _period(profile, lo, hi):
    """The lag in [lo, hi] at which a profile (None for no data) best matches itself, and that correlation."""
    xs = [v for v in profile if v is not None]
    if len(xs) < 2 * hi:
        return None, 0.0
    mu = sum(xs) / len(xs)
    var = sum((v - mu) ** 2 for v in xs) / len(xs)
    if var < 1e-6:
        return None, 0.0
    best = (None, 0.0)
    for lag in range(lo, hi + 1):
        pairs = [(profile[i] - mu, profile[i + lag] - mu) for i in range(len(profile) - lag)
                 if profile[i] is not None and profile[i + lag] is not None]
        if len(pairs) < lag:
            continue
        c = sum(x * y for x, y in pairs) / len(pairs) / var
        if c > best[1]:
            best = (lag, c)
    return best


def _fold(profile, period):
    bins = [[] for _ in range(period)]
    for i, v in enumerate(profile):
        if v is not None:
            bins[i % period].append(v)
    return [sum(b) / len(b) if b else None for b in bins]


STEP_F = 0.25  # metres between samples on a facade


def facade_patterns(rings, tex, texdir):
    """For each textured wall polygon tall enough to hold storeys, its window pattern read off the photograph.

    The polygon is sampled every 25 cm in its own frame (along the wall, up); each sample's brightness is divided by
    the mean of its 6 m neighbourhood, which takes out shade and the sun's gradient. The vertical profile's period
    (2.8 to 5 m, by autocorrelation) is the storey; folded by it, the darker part is the window band. Within that
    band the horizontal profile's period (1.25 to 8 m) and its brighter part are the piers, if it has one clearly.
    Returns {polygon id: dict(origin, u, base, storey, band, pier, piers, glass, wall rgb)}."""
    out = {}
    for cls, ring, holes, pid in rings:
        if cls != "wall" or not tex or pid not in tex:
            continue
        pts = open_ring(ring)
        if len(pts) < 3:
            continue
        n = newell(pts)
        nh = math.hypot(n[0], n[2])
        if nh < 1e-6 or abs(n[1]) > 0.2 * nh:
            continue  # not a vertical wall
        ux, uz = -n[2] / nh, n[0] / nh
        ox, oz = pts[0][0], pts[0][2]
        A = [(p[0] - ox) * ux + (p[2] - oz) * uz for p in pts]
        H = [p[1] for p in pts]
        a0, a1, h0, h1 = min(A), max(A), min(H), max(H)
        if h1 - h0 < 8 or a1 - a0 < 4:
            continue
        tm = texture_map(ring, tex[pid])
        if not tm:
            continue
        pix, W, Hh = atlas(os.path.join(texdir, tm[0]))
        _, ta, tb, cu, cv = tm
        poly2 = list(zip(A, H))
        na, nhh = int((a1 - a0) / STEP_F), int((h1 - h0) / STEP_F)
        grid = [[None] * na for _ in range(nhh)]
        rgbs = [[None] * na for _ in range(nhh)]
        for j in range(nhh):
            h = h0 + (j + 0.5) * STEP_F
            for i in range(na):
                a = a0 + (i + 0.5) * STEP_F
                if not inside(a, h, poly2):
                    continue
                p3 = (ox + a * ux, h, oz + a * uz)
                u = cu[0] + cu[1] * p3[ta] + cu[2] * p3[tb]
                w = cv[0] + cv[1] * p3[ta] + cv[2] * p3[tb]
                px, py = int(u * W), int((1 - w) * Hh)
                if 0 <= px < W and 0 <= py < Hh:
                    c = pix[px, py]
                    grid[j][i] = luminance(c)
                    rgbs[j][i] = c
        # Take out the shading: divide by the 6 m neighbourhood (rows, then columns, of the moving average).
        k = int(3 / STEP_F)
        rows_sm = [_smooth(r, k) for r in grid]
        cols_sm = [[None] * na for _ in range(nhh)]
        for i in range(na):
            col = _smooth([rows_sm[j][i] for j in range(nhh)], k)
            for j in range(nhh):
                cols_sm[j][i] = col[j]
        ratio = [[grid[j][i] / cols_sm[j][i] if grid[j][i] is not None and cols_sm[j][i] else None
                  for i in range(na)] for j in range(nhh)]
        vert = []
        for j in range(nhh):
            r = [v for v in ratio[j] if v is not None]
            vert.append(sum(r) / len(r) if len(r) > na * 0.3 else None)
        storey, corr = _period(vert, int(2.8 / STEP_F), int(5.0 / STEP_F))
        if not storey or corr < 0.3:
            continue
        folded = _fold(vert, storey)
        if any(v is None for v in folded) or max(folded) - min(folded) < 0.08:
            continue
        mid = (max(folded) + min(folded)) / 2
        band = [v < mid for v in folded]
        if all(band) or not any(band):
            continue
        horiz = []
        for i in range(na):
            r = [ratio[j][i] for j in range(nhh) if band[j % storey] and ratio[j][i] is not None]
            horiz.append(sum(r) / len(r) if len(r) > 2 else None)
        pier, pc = _period(horiz, int(1.25 / STEP_F), int(8 / STEP_F))
        piers = None
        if pier and pc > 0.35:
            fh = _fold(horiz, pier)
            if all(v is not None for v in fh) and max(fh) - min(fh) > 0.08:
                hmid = (max(fh) + min(fh)) / 2
                piers = [v >= hmid for v in fh]
                if all(piers) or not any(piers):
                    piers = None
        win, wall = [], []
        for j in range(nhh):
            for i in range(na):
                c = rgbs[j][i]
                if c is None:
                    continue
                is_win = band[j % storey] and not (piers and piers[i % pier])
                (win if is_win else wall).append(c)
        if not win or not wall:
            continue
        med = lambda cs: tuple(sorted(c[t] for c in cs)[len(cs) // 2] for t in range(3))
        out[pid] = dict(origin=(ox, oz), u=(ux, uz), a0=a0, h0=h0, storey=storey, band=band, pier=pier,
                        piers=piers, glass=_glass_for(med(win)), wall=med(wall))
    return out


def windows(blocks, solid, owners, patterns, bc):
    """Puts the windows read off the photographs into the walls: each outer wall block takes the pattern of the
    polygon that gave it most of its samples, and is a window if most of its metre lies in the window band and off
    the piers. Windows become stained glass panes (set back in the wall), the rest of that polygon's wall one block,
    the commonest the colours gave it, or the nearest to its wall colour."""
    by_pid = {}
    for v, (name, props) in blocks.items():
        x, y, z = v
        if props is not None or (x, y + 1, z) not in solid or v not in owners:
            continue
        sides = [d for d in ((1, 0), (-1, 0), (0, 1), (0, -1)) if (x + d[0], y, z + d[1]) not in solid]
        if len(sides) != 1:
            continue
        pid = max(owners[v], key=owners[v].get)
        if pid in patterns:
            by_pid.setdefault(pid, []).append((v, sides[0]))
    walls = Matcher(bc, [b for b in WALL_BLOCKS])
    changed = 0
    for pid, cells in by_pid.items():
        pt = patterns[pid]
        ox, oz = pt["origin"]
        ux, uz = pt["u"]
        st, band, pier, piers = pt["storey"], pt["band"], pt["pier"], pt["piers"]

        def window_at(x, y, z):
            hits = 0
            for dy in (0.125, 0.375, 0.625, 0.875):
                for da in (-0.375, -0.125, 0.125, 0.375):
                    a = (x + 0.5 - ox) * ux + (z + 0.5 - oz) * uz + da
                    jj = int((y + dy - pt["h0"]) / STEP_F)
                    ii = int((a - pt["a0"]) / STEP_F)
                    if jj < 0 or ii < 0:
                        continue
                    if band[jj % st] and not (piers and piers[ii % pier]):
                        hits += 1
            return hits >= 8
        count = {}
        flags = []
        for v, d in cells:
            w = window_at(*v)
            flags.append((v, d, w))
            n = blocks[v][0]
            if not w and "glass" not in n:
                count[n] = count.get(n, 0) + 1
        wall = max(count, key=count.get) if count else walls(pt["wall"])
        pane = pt["glass"] + "_stained_glass_pane"
        for v, d, w in flags:
            if w:
                blocks[v] = (pane, {"east": "true", "west": "true"} if d[0] == 0 else {"north": "true", "south": "true"})
            else:
                blocks[v] = (wall, None)
            changed += 1
    return changed


# ---------------------------------------------------------------- shaded lower floors

def _block_lum(name, bc):
    n = name[:-5] if name.endswith("_pane") else name
    c = bc.get(FULL_OF.get(n, PREVIEW_ALIAS.get(n, n)))
    return luminance(c) if c else None


def lift_shaded_floors(blocks, solid, bc, storey):
    """The aerial photographs see a facade's lower floors in the street's shadow, or hidden by the buildings across
    it, so they come out dark. Facade by facade (outer wall blocks facing one way in one plane), the rows from the
    ground up that are much darker than the lit upper half are given the upper floors' materials, column by column
    (each column keeps the block it has most often above, so piers and window bands carry down); the ground floor,
    where it was dark, becomes a shopfront: glass panes between piers of the facade's wall. Facades whose windows
    were read already, or without lit floors above, are left alone."""
    facades = {}
    for v, (name, props) in blocks.items():
        x, y, z = v
        if (x, y + 1, z) not in solid or (props is not None and not name.endswith("_pane")):
            continue
        sides = [d for d in ((1, 0), (-1, 0), (0, 1), (0, -1)) if (x + d[0], y, z + d[1]) not in solid]
        if len(sides) != 1:
            continue
        d = sides[0]
        facades.setdefault((d, x if d[0] else z), {})[(z if d[0] else x, y)] = v
    changed = 0
    # The building's lit facades set how bright its walls really are: the upper quartile of facade medians.
    fac_med = {}
    for key, cells in facades.items():
        ls = sorted(l for l in (_block_lum(blocks[v][0], bc) for v in cells.values()) if l is not None)
        if len(ls) >= 20:
            fac_med[key] = ls[len(ls) // 2]
    meds = sorted(fac_med.values())
    lit = meds[len(meds) * 3 // 4] if meds else None
    # The lit facades' commonest wall block: what the building's walls are made of where the photograph saw them.
    lit_count = {}
    for key, cells in facades.items():
        if lit and fac_med.get(key, 0) >= 0.85 * lit:
            for v in cells.values():
                n = blocks[v]
                if n[1] is None and "glass" not in n[0]:
                    lit_count[n] = lit_count.get(n, 0) + 1
    lit_wall = max(lit_count, key=lit_count.get) if lit_count else None
    for key, cells in list(facades.items()):
        d = key[0]
        props = {"east": "true", "west": "true"} if d[0] == 0 else {"north": "true", "south": "true"}
        has_panes = any(blocks[v][0].endswith("_pane") for v in cells.values())
        if lit_wall and key in fac_med and fac_med[key] < 0.6 * lit:
            # In shade from top to foot: the lit facades' wall; where it was much darker than the rest of the
            # facade, a window (unless the windows were read already).
            for v in cells.values():
                n, pr = blocks[v]
                if pr is not None:
                    continue
                l = _block_lum(n, bc)
                if not has_panes and l is not None and l < 0.6 * fac_med[key]:
                    blocks[v] = ("gray_stained_glass_pane", dict(props))
                else:
                    blocks[v] = lit_wall
                changed += 1
            continue
        if has_panes:
            continue  # its windows were read: already in order
        ys = sorted({y for _, y in cells})
        y0, y1 = ys[0], ys[-1]
        if y1 - y0 < 8 or len(cells) < 40:
            continue
        rows = {}
        for (a, y), v in cells.items():
            l = _block_lum(blocks[v][0], bc)
            if l is not None:
                rows.setdefault(y, []).append(l)
        med = {y: sorted(ls)[len(ls) // 2] for y, ls in rows.items() if len(ls) >= 4}
        upper = [med[y] for y in med if y >= y0 + (y1 - y0) // 2]
        if not upper:
            continue
        ref = sorted(upper)[len(upper) // 2]
        # The dark rows, contiguous from the ground up.
        top_dark = None
        for y in range(y0, y1 + 1):
            if y in med and med[y] < 0.7 * ref:
                top_dark = y
            elif y in med:
                break
        if top_dark is None or top_dark >= y0 + (y1 - y0) * 2 // 3:
            continue  # nothing dark at the foot, or the whole facade is dark: no lit floors to borrow from
        # Each column's commonest block above the dark rows, and the facade's.
        col_mode, all_count = {}, {}
        for (a, y), v in cells.items():
            if y > top_dark:
                n = blocks[v]
                col_mode.setdefault(a, {})
                col_mode[a][n] = col_mode[a].get(n, 0) + 1
                all_count[n] = all_count.get(n, 0) + 1
        if not all_count:
            continue
        facade_mode = max(all_count, key=all_count.get)
        wall = max((n for n in all_count if "glass" not in n[0]), key=all_count.get, default=facade_mode)
        props = {"east": "true", "west": "true"} if d[0] == 0 else {"north": "true", "south": "true"}
        for (a, y), v in cells.items():
            if y > top_dark:
                continue
            if y <= y0 + min(3, storey):  # the ground floor: a shopfront
                blocks[v] = wall if (a % 4 == 0 or y == y0) else ("light_gray_stained_glass_pane", dict(props))
            else:
                m = col_mode.get(a)
                blocks[v] = max(m, key=m.get) if m else facade_mode
            changed += 1
    return changed


# ---------------------------------------------------------------- rooftops

def tidy_roofs(blocks, solid, parapets=True):
    """Flat roofs as the photographs show them are speckled with what stands on them (air conditioners, tanks,
    skylights, their shadows), one block each. Each flat roof plane (full blocks open to the sky, connected at one
    height) gets one material: its commonest block, keeping only patches of another block large enough to be a
    real surface (a roof garden, a rooflight; at least 5% of the plane and 25 blocks). Where a plane ends at a drop,
    a parapet one block high goes round it, of the wall below it. Pitched roofs (stairs, slabs) are left alone."""
    top = {v for v, (n, pr) in blocks.items() if pr is None and (v[0], v[1] + 1, v[2]) not in blocks
           and "glass" not in n}
    seen, planes = set(), []
    for v in top:
        if v in seen:
            continue
        plane, stack = [], [v]
        seen.add(v)
        while stack:
            c = stack.pop()
            plane.append(c)
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                n = (c[0] + dx, c[1], c[2] + dz)
                if n in top and n not in seen:
                    seen.add(n)
                    stack.append(n)
        if len(plane) >= 20:
            planes.append(plane)
    changed = 0
    for plane in planes:
        cells = set(plane)
        # Patches of one block, connected.
        patch_of, patches = {}, []
        for v in plane:
            if v in patch_of:
                continue
            name = blocks[v][0]
            comp, stack = [], [v]
            patch_of[v] = len(patches)
            while stack:
                c = stack.pop()
                comp.append(c)
                for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    n = (c[0] + dx, c[1], c[2] + dz)
                    if n in cells and n not in patch_of and blocks[n][0] == name:
                        patch_of[n] = len(patches)
                        stack.append(n)
            patches.append((name, comp))
        big = max(25, len(plane) // 20)
        count = {}
        for name, comp in patches:
            count[name] = count.get(name, 0) + len(comp)
        main = max(count, key=count.get)
        for name, comp in patches:
            if name != main and len(comp) < big:
                for c in comp:
                    blocks[c] = (main, None)
                    changed += 1
        if not parapets or len(plane) < 30:
            continue
        for v in plane:
            x, y, z = v
            for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                n = (x + dx, y, z + dz)
                if n not in solid and (x + dx, y - 1, z + dz) not in solid:
                    below = blocks.get((x, y - 1, z))
                    wall = below if below and below[1] is None and "glass" not in below[0] else (main, None)
                    if (x, y + 1, z) not in blocks:
                        blocks[(x, y + 1, z)] = wall
                        changed += 1
                    break
    return changed


# ---------------------------------------------------------------- NBT

def nbt_string(s):
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def nbt_compound(items):
    """items: list of (name, type id, payload bytes)."""
    out = b"".join(bytes([t]) + nbt_string(n) + p for n, t, p in items)
    return out + b"\x00"


def nbt_int_list(vals):
    return bytes([3]) + struct.pack(">i", len(vals)) + b"".join(struct.pack(">i", v) for v in vals)


def nbt_compound_list(payloads):
    return bytes([10 if payloads else 0]) + struct.pack(">i", len(payloads)) + b"".join(payloads)


def write_nbt(path, blocks, size):
    states, index = [], {}
    entries = []
    for (x, y, z), (name, props) in sorted(blocks.items(), key=lambda kv: (kv[0][1], kv[0][2], kv[0][0])):
        key = (name, tuple(sorted(props.items())) if props else ())
        if key not in index:
            index[key] = len(states)
            items = [("Name", 8, nbt_string(name if ":" in name else "minecraft:" + name))]
            if props:
                items.append(("Properties", 10, nbt_compound([(k, 8, nbt_string(v)) for k, v in sorted(props.items())])))
            states.append(nbt_compound(items))
        entries.append(nbt_compound([("pos", 9, nbt_int_list([x, y, z])), ("state", 3, struct.pack(">i", index[key]))]))
    root = nbt_compound([
        ("DataVersion", 3, struct.pack(">i", 3465)),  # 1.20.1
        ("size", 9, nbt_int_list(list(size))),
        ("palette", 9, nbt_compound_list(states)),
        ("blocks", 9, nbt_compound_list(entries)),
        ("entities", 9, nbt_compound_list([])),
    ])
    with gzip.open(path, "wb") as fh:
        fh.write(bytes([10]) + nbt_string("") + root)


# ---------------------------------------------------------------- preview

COLOURS = {
    "candle": 0xE6DEC8,
    "deepslate_tiles": 0x3A3A3E, "deepslate_tile_stairs": 0x3A3A3E, "deepslate_tile_slab": 0x3A3A3E,
    "calcite": 0xDFE0DC, "dark_oak_planks": 0x4A3220, "dark_oak_log": 0x3C2E1A, "spruce_planks": 0x725431,
    "bricks": 0x976253, "stone_bricks": 0x7A7A7A, "smooth_stone": 0x9E9E9E, "gray_stained_glass": 0x4C5258,
    "gray_concrete": 0x4A4E52, "polished_andesite_stairs": 0x848685, "polished_andesite_slab": 0x848685,
    "polished_andesite": 0x848685, "light_gray_concrete": 0x9D9D97, "light_gray_stained_glass": 0x6F8190,
    "white_concrete": 0xCFD5D6, "quartz_stairs": 0xEBE5DE, "quartz_slab": 0xEBE5DE,
    "light_blue_stained_glass": 0x7FB2D8, "white_terracotta": 0xD1B2A1, "stripped_dark_oak_log": 0x4A3A28,
    # Landmarks (lighthouses, sports grounds); grass as tinted in a temperate biome.
    "grass_block": 0x5E9D34, "moss_block": 0x4E7F2C, "coarse_dirt": 0x77553A, "dirt": 0x86603F, "sand": 0xDBCFA3,
    "glass_pane": 0xA8D0D8, "dark_oak_door": 0x4A3220, "spruce_door": 0x6B4F2F, "iron_door": 0xC8C8C8,
    "ladder": 0x7A5E36, "polished_deepslate_wall": 0x48484B, "quartz_pillar": 0xEBE5DE, "sea_lantern": 0xC4DCD2,
    "shroomlight": 0xF09246, "verdant_froglight": 0xC9E3B0, "blue_wool": 0x35399D, "brick_wall": 0x976253,
    "cherry_leaves": 0xE5ADC2, "cherry_log": 0x3B2433, "spruce_trapdoor": 0x6B4F2F, "stone_brick_wall": 0x7A7A7A,
    "white_stained_glass_pane": 0xF0F0F0, "yellow_banner": 0xF8C527,
}


PREVIEW_ALIAS = {"cyan_stained_glass": "cyan_stained_glass", "end_rod": "white_concrete", "brown_stained_glass": "brown_stained_glass", "grass_block": "lime_terracotta", "dirt": "brown_terracotta", "cobblestone": "andesite",
                 "mossy_cobblestone": "mossy_stone_bricks", "spruce_log": "spruce_planks", "dark_oak_log": "dark_oak_planks",
                 "oak_log": "oak_planks", "spruce_leaves": "green_terracotta", "jungle_leaves": "green_concrete",
                 "dark_oak_leaves": "green_terracotta", "oak_leaves": "lime_terracotta", "acacia_leaves": "orange_terracotta",
                 "stone_brick_stairs": "stone_bricks", "white_concrete_powder": "white_concrete", "andesite_wall": "andesite", "andesite_stairs": "andesite", "andesite_slab": "andesite",
                 "water_cauldron": "light_blue_concrete", "mud_brick_stairs": "mud_bricks", "mud_brick_slab": "mud_bricks",
                 "oak_fence": "oak_planks", "oak_stairs": "oak_planks", "spruce_stairs": "spruce_planks",
                 "chiseled_stone_bricks": "stone_bricks", "acacia_stairs": "acacia_planks", "green_terracotta": "green_terracotta", "lantern": "gold_block", "spruce_slab": "spruce_planks", "dark_oak_slab": "dark_oak_planks",
                 "yellow_terracotta": "yellow_terracotta", "waxed_weathered_copper": "weathered_cut_copper", "chain": "polished_blackstone",
                 "stripped_spruce_log": "spruce_planks", "dark_oak_fence": "dark_oak_planks", "mangrove_fence": "mangrove_planks",
                 "polished_blackstone_wall": "polished_blackstone", "iron_trapdoor": "iron_block",
                 "dark_oak_trapdoor": "dark_oak_planks", "iron_bars": "iron_block", "lightning_rod": "cut_copper",
                 "bell": "gold_block", "gilded_blackstone": "gold_block", "waxed_oxidized_cut_copper": "oxidized_cut_copper",
                 "waxed_oxidized_cut_copper_stairs": "oxidized_cut_copper",
                 "waxed_oxidized_cut_copper_slab": "oxidized_cut_copper", "waxed_oxidized_copper": "oxidized_cut_copper",
                 "stripped_oak_log": "oak_planks", "red_concrete": "red_concrete"}


MOD_TEXTURES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "common", "src", "main",
                            "resources", "assets", "citybuilder", "textures", "block")
_mod_colours = {}


_mod_files = []


def mod_texture(short):
    """The texture standing for one of this mod's blocks: its own name's at the top, else in a subfolder (fixtures,
    signs, walls), else the first whose name starts with it (a sign's characters, a fixture's faces)."""
    top = os.path.join(MOD_TEXTURES, short + ".png")
    if os.path.exists(top):
        return top
    # The block's own model's particle texture, through its block state file
    assets = os.path.dirname(os.path.dirname(MOD_TEXTURES))
    try:
        with open(os.path.join(assets, "blockstates", short + ".json"), encoding="utf-8") as fh:
            state = json.load(fh)
        if "variants" in state:
            first = next(iter(state["variants"].values()))
        else:
            first = state["multipart"][0]["apply"]
        first = first[0] if isinstance(first, list) else first
        model = first["model"].split(":", 1)[1]
        for _ in range(4):  # up through parents until a texture is named
            with open(os.path.join(assets, "models", model + ".json"), encoding="utf-8") as fh:
                m = json.load(fh)
            textures = m.get("textures", {})
            tex = textures.get("particle") or next((t for t in textures.values() if not t.startswith("#")), None)
            if tex and tex.startswith("citybuilder:"):
                path = os.path.join(assets, "textures", tex.split(":", 1)[1] + ".png")
                if os.path.exists(path):
                    return path
            parent = m.get("parent", "")
            if not parent.startswith("citybuilder:"):
                break
            model = parent.split(":", 1)[1]
    except (OSError, KeyError, StopIteration, ValueError):
        pass
    if not _mod_files:
        for root, _, files in os.walk(MOD_TEXTURES):
            _mod_files.extend(sorted(os.path.join(root, f) for f in files if f.endswith(".png")))
    for path in _mod_files:
        if os.path.basename(path) == short + ".png":
            return path
    for path in _mod_files:
        base = os.path.basename(path)[:-4]
        if base.startswith(short) or short.startswith(base + "_") and len(base) > 3:
            return path
    raise FileNotFoundError("no texture for citybuilder:" + short)


def colour_of(name, bc):
    """A block's preview colour: vanilla from the client jar's textures (or COLOURS), this mod's own blocks from their
    textures in the repository, painted lines lighter than their surface."""
    if name in bc:
        return bc[name]
    if name.startswith("citybuilder:"):
        if name not in _mod_colours:
            short = name.split(":", 1)[1]
            line = short.endswith("_line_white")
            img = Image.open(mod_texture(short[:-len("_line_white")] if line else short))
            px = list(img.convert("RGB").getdata())
            avg = [sum(p[i] for p in px) / len(px) for i in range(3)]
            if line:
                avg = [c + (235 - c) * 0.45 for c in avg]
            _mod_colours[name] = tuple(int(c) for c in avg)
        return _mod_colours[name]
    return bc.get(FULL_OF.get(name, PREVIEW_ALIAS.get(name, name))) or ((COLOURS[name] >> 16, (COLOURS[name] >> 8) & 255,
                                                                          COLOURS[name] & 255) if name in COLOURS else (255, 0, 255))


def render(blocks, size, path, bc, max_px=1800):
    blocks = {v: b for v, b in blocks.items() if b[0] != "air"}  # cuts in the hillside: nothing to draw
    sx, sy, sz = size
    s = 2
    while s < 8 and (sx + sz) * (s + 2) <= max_px and ((sx + sz) * (s + 2)) // 2 + sy * (s + 2) <= max_px:
        s += 2
    w = (sx + sz) * s + 2 * s + 8
    h = (sx + sz) * s // 2 + sy * s + 2 * s + 8
    img = bytearray(b"\xf4\xf1\xea" * (w * h))
    ox, oy = sz * s + s + 4, sy * s + s + 4

    def proj(x, y, z):
        return (x - z) * s, (x + z) * s / 2 - y * s

    def sprite(top, bottom=0.0, lo=0.0, hi=1.0):
        """A box [lo, hi] x [bottom, top] x [lo, hi] in the block: its top, east and south faces."""
        faces = {0: [(lo, top, lo), (hi, top, lo), (hi, top, hi), (lo, top, hi)],
                 1: [(hi, bottom, lo), (hi, top, lo), (hi, top, hi), (hi, bottom, hi)],
                 2: [(lo, bottom, hi), (hi, bottom, hi), (hi, top, hi), (lo, top, hi)]}
        pix = []
        for py in range(-s * 2, s * 2):
            for px in range(-s * 2, s * 2):
                cx, cy = px + 0.5, py + 0.5
                for f, quad in faces.items():
                    q = [proj(*p) for p in quad]
                    if inside(cx, cy, q):
                        pix.append((px, py, f))
                        break
        return pix

    full, half = sprite(1), sprite(0.5)
    shapes = {"plate_top": sprite(1, 0.8), "plate_bottom": sprite(0.2), "post": sprite(1, 0, 0.3, 0.7),
              "bell": sprite(0.9, 0.2, 0.25, 0.75), "slab_top": sprite(1, 0.5)}
    shade = (1.0, 0.78, 0.6)
    order = sorted(blocks, key=lambda v: (v[0] + v[1] + v[2], v[1]))
    for v in order:
        x, y, z = v
        name, props = blocks[v]
        if all(n in blocks and not thin(blocks[n][0]) for n in ((x + 1, y, z), (x, y, z + 1), (x, y + 1, z))):
            continue
        if name == "spruce_planks" and props is None and name not in bc:
            pass
        if name.endswith("_pane"):
            name = name[:-5]
        r, g, b = colour_of(name, bc)
        bx, by = proj(x, y, z)
        bx, by = int(bx) + ox, int(by) + oy
        if name.endswith("trapdoor"):
            spr = shapes["plate_top" if props and props.get("half") == "top" else "plate_bottom"]
        elif name.endswith(("_fence", "_wall", "iron_bars", "lightning_rod", "chain")):
            spr = shapes["post"]
        elif name in ("bell", "citybuilder:koro", "citybuilder:chochin"):
            spr = shapes["bell"]
        elif name == "citybuilder:zabuton":
            spr = shapes["plate_bottom"]
        elif name == "citybuilder:shimenawa":
            spr = shapes["plate_top"]
        elif props and props.get("type") == "top":
            spr = shapes["slab_top"]
        else:
            spr = half if props and props.get("type") == "bottom" or name.startswith("citybuilder:seat_") else full
        cols = [bytes((int(r * k), int(g * k), int(b * k))) for k in shade]
        for px, py, f in spr:
            X, Y = bx + px, by + py
            if 0 <= X < w and 0 <= Y < h:
                i = (Y * w + X) * 3
                img[i:i + 3] = cols[f]
    # Beside it, the front (south) elevation, flat-on: what stands nearest the viewer in each column of the
    # facade, darker the further back it stands; the proportions of walls, columns and eaves read here.
    front = {}
    for (x, y, z) in blocks:
        if z > front.get((x, y), -1):
            front[(x, y)] = z
    fs = max(2, s)
    fw, fh = (sx + 3) * fs, sy * fs  # three columns more on the left for a person, to judge the height by
    W, H = w + 16 + fw, max(h, fh + 8)
    out = bytearray(bytes((0xf4, 0xf1, 0xea)) * (W * H))
    for row in range(h):
        out[row * W * 3:(row * W + w) * 3] = img[row * w * 3:(row + 1) * w * 3]
    for (x, y), z in front.items():
        name, props = blocks[(x, y, z)]
        if name.endswith("_pane"):
            name = name[:-5]
        r, g, b = colour_of(name, bc)
        k = 0.95 - 0.45 * (sz - 1 - z) / max(1, sz - 1)
        col = bytes((int(r * k), int(g * k), int(b * k)))
        for py in range(fs):  # slabs and stairs drawn whole: a roof's steps would show the sky between them
            Y = H - 4 - (y + 1) * fs + py
            X0 = w + 16 + (x + 3) * fs
            if 0 <= Y < H:
                out[(Y * W + X0) * 3:(Y * W + X0 + fs) * 3] = col * fs
    # The person: 1.8 m, legs and body over two blocks, at the left on the ground line.
    for py in range(int(1.8 * fs)):
        Y = H - 4 - py - 1
        col = bytes((50, 60, 120)) if py < 0.9 * fs else bytes((70, 110, 70)) if py < 1.55 * fs else bytes((205, 165, 135))
        X0 = w + 16 + fs // 2
        if 0 <= Y < H:
            out[(Y * W + X0) * 3:(Y * W + X0 + max(1, fs // 2)) * 3] = col * max(1, fs // 2)
    write_png(path, W, H, out)


def thin(name):
    return name.endswith(("trapdoor", "_fence", "_wall", "iron_bars", "lightning_rod", "chain", "_slab", "_stairs")) \
        or name in ("bell", "citybuilder:koro", "citybuilder:chochin", "citybuilder:zabuton", "citybuilder:shimenawa",
                    "citybuilder:saisen_bako") or name.startswith("citybuilder:seat_")


def inside(x, y, poly):
    c = False
    for (x1, y1), (x2, y2) in zip(poly, poly[1:] + poly[:1]):
        if (y1 > y) != (y2 > y) and x < x1 + (y - y1) * (x2 - x1) / (y2 - y1):
            c = not c
    return c


def write_png(path, w, h, rgb):
    raw = b"".join(b"\x00" + bytes(rgb[y * w * 3:(y + 1) * w * 3]) for y in range(h))

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    with open(path, "wb") as fh:
        fh.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
                 + chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b""))


# ---------------------------------------------------------------- build

def parse_state(state):
    """'minecraft:x[a=b,c=d]' -> ('x', {'a': 'b', 'c': 'd'} or None)."""
    name, _, props = state.partition("[")
    if name.startswith("minecraft:"):
        name = name[len("minecraft:"):]  # vanilla blocks by their bare names; this mod's keep their namespace
    if not props:
        return name, None
    return name, dict(kv.split("=", 1) for kv in props.rstrip("]").split(","))


def own_top_texture(name, props):
    """The texture file of this mod's block's top face, and the quarter turns it is drawn with; None if not ours."""
    if not name.startswith("citybuilder:"):
        return None
    n = name.split(":", 1)[1]
    props = props or {}
    if n == "raked_gravel_ring":
        return f"raked_gravel_ring_{props['facing']}_{props['part']}", 0
    if n == "raked_gravel":
        return "raked_gravel", 1 if props.get("axis") == "z" else 0
    if n == "tatami":
        return "tatami", {"north": 0, "east": 1, "south": 2, "west": 3}[props.get("facing", "north")]
    return {"tobiishi": "tobiishi_stone", "fusuma": "fusuma_upper", "fusuma_gold": "fusuma_gold_upper",
            "saisen_bako": "saisen_bako_top", "koro": "koro_top", "chochin": "chochin_cap",
            "coffered_ceiling": "coffered_ceiling_side"}.get(n, n), 0


def render_plan(blocks, size, path, bc, px=8):
    """Seen from straight above, every column's top block drawn with its own texture where it is one of this mod's
    (scaled to px per block), else its average colour; higher blocks a little lighter. Shows raking, tatami and
    paving, which the averaged isometric view cannot."""
    sx, _, sz = size
    top = {}
    for (x, y, z), b in blocks.items():
        if b[0] != "air" and y > top.get((x, z), (-1, None))[0]:
            top[(x, z)] = (y, b)
    ymin = min(t[0] for t in top.values())
    img = Image.new("RGB", (sx * px, sz * px), (244, 241, 234))
    cache = {}
    for (x, z), (y, (name, props)) in top.items():
        tex = own_top_texture(name, props)
        k = 1 + 0.03 * (y - ymin)
        if tex:
            key = tex
            if key not in cache:
                t = Image.open(os.path.join(OWN_TEXTURES, tex[0] + ".png")).convert("RGB")
                for _ in range(tex[1]):
                    t = t.transpose(Image.Transpose.ROTATE_270)  # a quarter turn clockwise
                cache[key] = t.resize((px, px), Image.Resampling.BOX)
            tile = cache[key].point(lambda v: min(255, int(v * k)))
        else:
            n = name[:-5] if name.endswith("_pane") else name
            c = bc.get(FULL_OF.get(n, PREVIEW_ALIAS.get(n, n))) or ((COLOURS[n] >> 16, (COLOURS[n] >> 8) & 255,
                                                                      COLOURS[n] & 255) if n in COLOURS else (255, 0, 255))
            tile = Image.new("RGB", (px, px), tuple(min(255, int(v * k)) for v in c))
        img.paste(tile, (x * px, z * px))
    img.save(path)


def interior_cut(blocks, kind):
    """The height to cut a building at to show its interior: three above its lowest tatami (the fusuma and the beam
    over them, below the rooms' ceilings), or above a pagoda's ground floor; None without an interior. The images
    (gold) are kept whole above the cut."""
    if kind not in ("hall", "pagoda"):
        return None  # a precinct's hillside would be cut through too
    tatami = [y for (x, y, z), (n, _) in blocks.items() if n == "citybuilder:tatami"]
    if tatami:
        return min(tatami) + 3
    gold = [y for (x, y, z), (n, _) in blocks.items() if n == "gold_block"]
    return min(gold) + 2 if gold and kind == "pagoda" else None


def build_temple(out, gid, slug, name, row, bc):
    with open(os.path.join(out, "models", slug + ".json"), encoding="utf-8") as fh:
        model = json.load(fh)
    blocks = {(x, y, z): parse_state(st) for x, y, z, st in model["blocks"]}
    size = tuple(model["size"])
    write_nbt(os.path.join(out, slug + ".nbt"), blocks, size)
    render(blocks, size, os.path.join(out, slug + ".png"), bc)
    if TEMPLES[slug]["kind"] == "garden":
        render_plan(blocks, size, os.path.join(out, slug + "_plan.png"), bc, px=16)
    cut = interior_cut(blocks, TEMPLES[slug]["kind"])
    if cut is not None:  # a cutaway: everything above the rooms' fusuma taken off, the rooms seen from above
        floor = [v for v, b in blocks.items() if b[0] in ("citybuilder:tatami", "gold_block") and v[1] <= cut]
        x0, x1 = min(v[0] for v in floor), max(v[0] for v in floor)
        z0, z1 = min(v[2] for v in floor), max(v[2] for v in floor)
        kept = {v: b for v, b in blocks.items() if v[1] <= cut or b[0] in ("gold_block", "gilded_blackstone")
                and v[1] <= cut + 6 and x0 <= v[0] <= x1 and z0 <= v[2] <= z1}
        render(kept, (size[0], max(v[1] for v in kept) + 1, size[2]), os.path.join(out, slug + "_interior.png"), bc)
    elif os.path.exists(os.path.join(out, slug + "_interior.png")):
        os.remove(os.path.join(out, slug + "_interior.png"))
    counts = {}
    for n, _ in blocks.values():
        counts[n] = counts.get(n, 0) + 1
    return {"slug": slug, "name": name, "plateau_id": gid, "usage": row.get("usage"),
            "structure": row.get("structure"), "year": row.get("year"), "measured_height": row.get("height"),
            "storeys": row.get("storeys"), "roof_shape": row.get("roof_shape"), "roof_pitch": row.get("roof_pitch"),
            **label(slug), "palette": "generated", "spec": TEMPLES[slug], "size": list(size), "blocks": len(blocks),
            "surfaces": 0, "textured_polygons": 0, "recoloured_blocks": 0, "rotation_deg": 0.0,
            "block_counts": dict(sorted(counts.items(), key=lambda kv: -kv[1]))}


# ---------------------------------------------------------------- landmark fixes
#
# Where LOD2 and its photographs fall short of a landmark everyone knows, its upper parts are rebuilt by hand after
# photographs, keeping LOD2's heights and axis; the rest stays as voxelized and coloured from the textures.

def _ring(blocks, cx, cz, y, r_out, r_in, state, keep=None):
    """A ring (or a disc with r_in < 0) of `state` at height y round the axis (cx, cz), voxel centres."""
    R = int(r_out) + 1
    for x in range(int(cx) - R - 1, int(cx) + R + 2):
        for z in range(int(cz) - R - 1, int(cz) + R + 2):
            d = ((x + 0.5 - cx) ** 2 + (z + 0.5 - cz) ** 2) ** 0.5
            if r_in < d <= r_out and (keep is None or keep(x, z, d)):
                blocks[(x, y, z)] = state


def kyoto_tower(blocks):
    """Kyoto Tower above its building's roof, after a photograph: a golden glass bowl on the roof with light mullions,
    the white shaft narrowing upwards from a flared foot, the observation deck (an orange-red cone under it, a band of
    windows between white rims) inside a cage of red ribs bulging outwards, a white drum, the orange-and-white
    antenna and a red-and-white mast. Heights and axis from LOD2 (shaft 48-98, deck 99-104, drum 105-110, antenna
    111-122, mast to 130)."""
    shaft = [(x, z) for (x, y, z) in blocks if 60 <= y <= 90]
    cx = sum(x for x, _ in shaft) / len(shaft) + 0.5
    cz = sum(z for _, z in shaft) / len(shaft) + 0.5
    for v in [v for v in blocks if v[1] >= 36 and ((v[0] + 0.5 - cx) ** 2 + (v[2] + 0.5 - cz) ** 2) ** 0.5 <= 13.5]:
        del blocks[v]
    white, orange, glass = ("white_concrete", None), ("orange_concrete", None), ("brown_stained_glass", None)
    mullion, deckglass = ("smooth_sandstone", None), ("gray_stained_glass", None)
    # The bowl: narrow at the roof, opening upwards, glass with a light mullion every few degrees, a white lip and top.
    import math
    for y in range(36, 47):
        t = (y - 36) / 10
        r = 5.5 + 6.5 * t ** 0.8

        def stripe(x, z, d):
            return int(round(math.degrees(math.atan2(z + 0.5 - cz, x + 0.5 - cx)) / 15)) % 2 == 0
        _ring(blocks, cx, cz, y, r, r - 1.2, glass if y < 46 else white)
        if y < 46:
            _ring(blocks, cx, cz, y, r, r - 1.2, mullion, keep=lambda x, z, d: abs(math.degrees(math.atan2(z + 0.5 - cz, x + 0.5 - cx))) % 20 < 3)
    _ring(blocks, cx, cz, 46, 12.0, -1, white)
    _ring(blocks, cx, cz, 35, 5.5, -1, white)
    # The shaft, white, from a flared foot to 6.6 m under the deck.
    for y in range(47, 99):
        r = 3.3 + 1.2 * max(0.0, (75 - y) / 28) ** 1.5 + (1.2 if y < 49 else 0.5 if y < 51 else 0)
        _ring(blocks, cx, cz, y, r, r - 1.1, white)
    # The deck: the orange-red cone under it, white rims, a band of windows, the floor and roof.
    for y, r in ((95, 4.2), (96, 5.2), (97, 6.3), (98, 7.4), (99, 8.5)):
        _ring(blocks, cx, cz, y, r, -1, orange)
    _ring(blocks, cx, cz, 100, 9.3, -1, white)
    for y in (101, 102):
        _ring(blocks, cx, cz, y, 9.3, 8.2, deckglass)
    _ring(blocks, cx, cz, 103, 9.0, -1, white)
    _ring(blocks, cx, cz, 104, 6.0, -1, white)
    # The cage of red ribs round the deck, bulging out at its middle, every 30 degrees.
    for k in range(12):
        a = math.radians(k * 30 + 15)
        for y, r in ((99, 9.6), (100, 10.4), (101, 10.8), (102, 10.8), (103, 10.4), (104, 9.6), (105, 8.2)):
            x, z = int(math.floor(cx + r * math.cos(a))), int(math.floor(cz + r * math.sin(a)))
            blocks[(x, y, z)] = ("mangrove_fence", None)
    _ring(blocks, cx, cz, 105, 8.6, 7.6, ("mangrove_fence", None))
    # The drum, the antenna (orange, then white), the mast (red and white).
    for y in range(105, 111):
        _ring(blocks, cx, cz, y, 3.4, -1, white)
    for y in range(111, 123):
        _ring(blocks, cx, cz, y, 1.6, -1, orange if y < 115 else white)
    for y in range(123, 131):
        blocks[(int(cx), y, int(cz))] = ("lightning_rod", {"facing": "up"}) if y % 3 == 0 else ("end_rod", {"facing": "up"})
    # The Kyoto Tower Building under it, as in the photographs: continuous ribbon windows of teal glass floor by floor,
    # thin dark spandrel bands between, shopfronts at the street and a light parapet on top; in place of the shaded,
    # speckled colours from the aerial textures.
    for (x, y, z), b in list(blocks.items()):
        if y > 31 or b[1] is not None:
            continue
        sides = [d for d in ((1, 0), (-1, 0), (0, 1), (0, -1)) if (x + d[0], y, z + d[1]) not in blocks]
        if not sides or (x, y + 1, z) not in blocks:
            continue  # inside, or a roof
        along = z if sides[0][0] else x
        top = (x, y + 1, z) in blocks and (x, y + 2, z) not in blocks
        if y < 4:
            blocks[(x, y, z)] = ("glass", None) if 0 < y < 3 and along % 5 else ("gray_terracotta", None)
        elif top:
            blocks[(x, y, z)] = white  # the parapet
        elif y % 3 == 1:
            blocks[(x, y, z)] = ("gray_terracotta", None)  # spandrel
        else:
            blocks[(x, y, z)] = ("cyan_stained_glass", None)


def kyoto_station(blocks):
    """Kyoto Station's front, the north side facing the city, after photographs of its main entrance.

    Above 12 m every north wall is a curtain wall of blue reflecting glass on a grid of fine light grey mullions
    (every 3 blocks both ways), a grey band every 12. In the middle stretch of the tall front (the central concourse,
    x 240-335 in the model) the bottom 24 m is the great gate: grey tiled stone piers at its ends; between them a
    white steel truss whose top chord runs straight at 23 m and whose bottom chord rises in two arches from the
    piers and from an X-braced white post in the middle; the truss's web crossing diagonals in a row of X; under the
    arches the atrium, open and dark; glass doors at the street. Elsewhere below 12 m the colours stay as read off
    the photographs."""
    import math
    gx0, gx1, top = 240, 336, 23
    ix0, ix1 = gx0 + 8, gx1 - 8  # inside the piers
    xc = (ix0 + ix1) // 2  # the middle post: xc - 2 .. xc + 1
    foot, crown = 13, 19  # the arches' bottom chord: at the supports, at mid-span
    frame, band = ("light_gray_concrete", None), ("gray_concrete", None)
    stone, joint, white = ("polished_andesite", None), ("andesite", None), ("white_concrete", None)
    ew = {"east": "true", "west": "true"}
    dark = ("black_stained_glass_pane", dict(ew))

    def arch(x):
        """The bottom chord's height over x, in the half-span it is in."""
        a0, a1 = (ix0, xc - 2) if x < xc else (xc + 2, ix1)
        t = (x - a0 + 0.5) / max(1, a1 - a0)
        return foot + (crown - foot) * math.sin(math.pi * max(0.0, min(1.0, t)))

    def diagonal(x, y, period=6):
        k, j = (x - ix0) % period, (y - foot) % period
        return k == j or k == (period - j) % period

    for (x, y, z), b in list(blocks.items()):
        if y < 1 or (x, y, z - 1) in blocks:
            continue  # not facing north
        if (b[1] is not None and not b[0].endswith("_pane")) or (x, y + 1, z) not in blocks:
            continue  # stairs, slabs, roofs
        if gx0 <= x < gx1 and y <= top:
            if x < ix0 or x >= ix1:  # the piers, tiled
                blocks[(x, y, z)] = joint if y % 3 == 0 or (x - gx0) % 4 == 0 else stone
            elif xc - 2 <= x < xc + 2 and y < arch(xc - 3):  # the middle post, X-braced
                edge = x in (xc - 2, xc + 1)
                blocks[(x, y, z)] = white if edge or diagonal(x, y, 4) else dark
            elif y == top:
                blocks[(x, y, z)] = white  # the top chord
            elif y >= round(arch(x)):
                if y == round(arch(x)) or y == round(arch(x)) + 1:
                    blocks[(x, y, z)] = white  # the bottom chord, arched
                else:
                    blocks[(x, y, z)] = white if diagonal(x, y) else dark  # the web
            elif y < 4:
                blocks[(x, y, z)] = frame if x % 4 == 0 else ("light_gray_stained_glass_pane", dict(ew))
            else:
                blocks[(x, y, z)] = dark  # the atrium under the arches
        elif y >= 12:
            if y % 12 == 0:
                blocks[(x, y, z)] = band
            elif x % 3 == 0 or y % 3 == 0:
                blocks[(x, y, z)] = frame
            else:
                blocks[(x, y, z)] = ("light_blue_stained_glass_pane", dict(ew))


LANDMARK_FIXES = {"kyoto_tower": kyoto_tower, "kyoto_station": kyoto_station}
# Previews seen from another side: quarter turns of the model before drawing (the station's front faces north).
PREVIEW_TURN = {"kyoto_station": 2}


def build_one(args):
    out, gid, slug, name, pal, row, bc, textured, city = args
    e = _build_one(out, gid, slug, name, pal, row, bc, textured)
    if e:
        e["city"] = city
    return e


def _build_one(out, gid, slug, name, pal, row, bc, textured):
    if slug in TEMPLES and os.path.exists(os.path.join(out, "models", slug + ".json")):
        return build_temple(out, gid, slug, name, row, bc)
    with open(os.path.join(out, "geom", gid + ".json"), encoding="utf-8") as fh:
        geom = json.load(fh)
    surf = geom["surfaces"]
    if not surf:
        return None
    rings, angle, (lat, lon) = to_local(surf)
    tex = geom.get("tex") if textured else None
    solid, cls, height, colours, owners = voxelize(rings, tex, os.path.join(out, "tex"))
    pal = pal or choose_palette(row)
    sh = storey_height(row)
    blocks = assign_blocks(solid, cls, height, pal, sh)
    recoloured = recolour(blocks, solid, colours, pal, bc)
    glazed = windows(blocks, solid, owners, facade_patterns(rings, tex, os.path.join(out, "tex")), bc) if tex else 0
    lifted = lift_shaded_floors(blocks, solid, bc, sh) if tex else 0
    roofs = tidy_roofs(blocks, solid, parapets=pal in ("modern", "glass", "white", "brick")) if tex else 0
    if slug in LANDMARK_FIXES:
        LANDMARK_FIXES[slug](blocks)
    size = tuple(max(v[i] for v in blocks) + 1 for i in range(3))
    write_nbt(os.path.join(out, slug + ".nbt"), blocks, size)
    view = blocks
    for _ in range(PREVIEW_TURN.get(slug, 0)):  # quarter turns, clockwise seen from above, for the preview only
        sz_ = max(v[2] for v in view) + 1
        view = {(sz_ - 1 - z, y, x): b for (x, y, z), b in view.items()}
    render(view, tuple(max(v[i] for v in view) + 1 for i in range(3)), os.path.join(out, slug + ".png"), bc)
    counts = {}
    for n, _ in blocks.values():
        counts[n] = counts.get(n, 0) + 1
    return {"slug": slug, "name": name, **label(slug), "plateau_id": gid, "lat": round(lat, 6), "lon": round(lon, 6),
            "usage": row.get("usage"), "structure": row.get("structure"), "year": row.get("year"),
            "measured_height": row.get("height"), "storeys": row.get("storeys"),
            "roof_shape": row.get("roof_shape"), "roof_pitch": row.get("roof_pitch"),
            "palette": pal, "storey_height": sh, "rotation_deg": round(angle, 1),
            "size": list(size), "blocks": len(blocks), "surfaces": len(surf),
            "textured_polygons": len(geom.get("tex", {})), "recoloured_blocks": recoloured, "window_blocks": glazed, "lifted_blocks": lifted, "roof_blocks": roofs,
            "block_counts": dict(sorted(counts.items(), key=lambda kv: -kv[1]))}


# This mod's blocks: the texture whose colour stands for each in previews (the rest by their own names).
OWN_TEXTURE = {"fusuma": "fusuma_lower", "fusuma_gold": "fusuma_gold_lower", "raked_gravel_ring": "raked_gravel",
               "tobiishi": "tobiishi_stone", "saisen_bako": "saisen_bako_top"}
OWN_TEXTURES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "common", "src", "main",
                            "resources", "assets", "citybuilder", "textures", "block")


def own_colours():
    """Average colours of this mod's own block textures (drawn by tools/textures), keyed 'citybuilder:<id>'."""
    out = {}
    for f in os.listdir(OWN_TEXTURES):
        if not f.endswith(".png"):
            continue
        img = Image.open(os.path.join(OWN_TEXTURES, f)).convert("RGB")
        px = list(img.getdata())
        out[f[:-4]] = tuple(round(sum(c[i] for c in px) / len(px)) for i in range(3))
    named = {}
    for tex in out:
        named["citybuilder:" + tex] = out[tex]
    for block, tex in OWN_TEXTURE.items():
        named["citybuilder:" + block] = out[tex]
    return named


def build(rows, out, chosen, jar=MC_JAR, textured=True):
    bc = block_colours(jar, os.path.join(out, "block_colours.json"))
    bc.update(own_colours())
    jobs = [(out, gid, slug, name, pal, rows.get(gid, {}), bc, textured, city) for gid, slug, name, pal, city in chosen
            if os.path.exists(os.path.join(out, "geom", gid + ".json"))
            or slug in TEMPLES and os.path.exists(os.path.join(out, "models", slug + ".json"))]  # LOD1-only ones too
    if len(chosen) == len(ALL_LANDMARKS) or any(c[1].startswith("demo_") for c in chosen):
        jobs += [(out, None, slug, DEMO_NAMES.get(slug, slug), None, {}, bc, textured, None) for slug in TEMPLES
                 if slug not in {c[1] for c in ALL_LANDMARKS} and os.path.exists(os.path.join(out, "models", slug + ".json"))]
    entries = []
    with multiprocessing.Pool(max(1, min(len(jobs), (os.cpu_count() or 2) - 1))) as pool:
        for e in pool.imap(build_one, jobs):
            if e:
                entries.append(e)
                print(f"{e['slug']:24s} {e['name']:16s} {e['size'][0]:4d}x{e['size'][1]:3d}x{e['size'][2]:4d} "
                      f"{e['blocks']:7d} blocks  {e['palette']:6s} turned {e['rotation_deg']:+.1f}  "
                      f"{e['recoloured_blocks']} from textures, {e.get('window_blocks', 0)} facade blocks redone, {e.get('lifted_blocks', 0)} shaded lifted, {e.get('roof_blocks', 0)} roof tidied", flush=True)
    cities = {e["city"] for e in entries if e.get("city")}
    credit = attribution(cities)
    with open(os.path.join(out, "library.json"), "w", encoding="utf-8") as fh:
        json.dump({"source": "PLATEAU LOD2: " + ", ".join(f"{CITIES[k][1]} FY{CITIES[k][2]}" for k in CITIES
                                                       if k == "kyoto" or k in cities),
                   "attribution": "ATTRIBUTION", "buildings": entries}, fh, ensure_ascii=False, indent=1)
    with open(os.path.join(out, "ATTRIBUTION"), "w", encoding="utf-8") as fh:
        fh.write(credit)
    html = ["<!doctype html><meta charset=utf-8><title>PLATEAU building library</title>",
            "<style>body{font:14px sans-serif;background:#eee;margin:16px}figure{display:inline-block;"
            "vertical-align:top;margin:8px;background:#fff;padding:8px;max-width:100%}img{max-width:100%}"
            "figcaption{margin-top:4px}</style><h1>PLATEAU building library</h1><p>"
            + credit.replace("\n", "<br>") + "</p>"]
    for e in entries:
        with open(os.path.join(out, e["slug"] + ".png"), "rb") as fh:
            data = base64.b64encode(fh.read()).decode()
        plan = os.path.join(out, e["slug"] + "_plan.png")
        if os.path.exists(plan):
            with open(plan, "rb") as fh:
                html.append(f"<figure><img src='data:image/png;base64,{base64.b64encode(fh.read()).decode()}'>"
                            f"<figcaption><b>{e['name']}</b> — 俯瞰（实际纹理）</figcaption></figure>")
        cutaway = os.path.join(out, e["slug"] + "_interior.png")
        if os.path.exists(cutaway):
            with open(cutaway, "rb") as fh:
                html.append(f"<figure><img src='data:image/png;base64,{base64.b64encode(fh.read()).decode()}'>"
                            f"<figcaption><b>{e['name']}</b> — 内部（切断）</figcaption></figure>")
        kind = e["category"] + (f" / {e['religion']}" if e.get("religion") else "") + (f" {e['role']}" if e.get("role") else "")
        html.append(f"<figure><img src='data:image/png;base64,{data}'><figcaption><b>{e['name']}</b> [{kind}] "
                    f"{e['slug']} — {e['size'][0]}×{e['size'][1]}×{e['size'][2]}, {e['blocks']} blocks, "
                    f"{e['usage']}, {e['structure']}, {e['year']}, roof {e['roof_shape']} {e['roof_pitch']}°, "
                    f"palette {e['palette']}</figcaption></figure>")
    with open(os.path.join(out, "preview.html"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(html))


def main(argv):
    if len(argv) >= 2 and argv[0] == "specs":
        os.makedirs(argv[1], exist_ok=True)
        path = os.path.join(argv[1], "temple_specs.json")
        with open(path, "w", encoding="utf-8") as fh:
            json.dump([dict(slug=k, **v) for k, v in TEMPLES.items()], fh, indent=1)
        print(path)
        return 0
    if len(argv) < 3 or argv[0] not in ("extract", "build"):
        print(__doc__)
        return 2
    cmd = argv[0]
    if cmd == "extract":
        zpath, csvpath, out, rest = argv[1], argv[2], argv[3], argv[4:]
    else:
        zpath, csvpath, out, rest = None, argv[1], argv[2], argv[3:]
    ids = [rest[i + 1] for i, a in enumerate(rest) if a == "--id"]
    jar = next((rest[i + 1] for i, a in enumerate(rest) if a == "--mc-jar"), MC_JAR)
    textured = "--no-textures" not in rest
    chosen = [c for c in ALL_LANDMARKS if not ids or c[0] in ids]
    known = {c[0] for c in chosen}
    chosen += [(g, g.split("_")[1][:8], g, None, None) for g in ids if g not in known]
    want = {c[0] for c in chosen}
    rows = {}
    for path in csvpath.split(","):  # build takes the CSVs of all the cities, comma separated
        with open(path, encoding="utf-8") as fh:
            for r in csv.DictReader(fh):
                if r["id"] in want:
                    rows.setdefault(r["id"], r)
    missing = want - set(rows)
    if missing and (ids or cmd == "build"):
        print("not in the CSVs:", ", ".join(sorted(missing)))
    os.makedirs(out, exist_ok=True)
    if cmd == "extract":
        extract(zpath, rows, out, [g for g in want if g in rows])
    else:
        build(rows, out, chosen, jar, textured)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
