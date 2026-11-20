package com.mtm.citybuilder.core.building.temple;

/**
 * What a sacred building is: whether it belongs to a Buddhist temple (寺院) or a Shinto shrine (神社), and its role
 * in the precinct. Library entries and generated buildings carry this so a precinct can be assembled from the right
 * pieces (a temple: gate, main hall, pagoda, lecture hall; a shrine: torii, worship hall, main sanctuary), and so the
 * styles stay apart (pagodas, tiled roofs and bells belong to temples; copper or bark roofs, vermilion and chigi to
 * shrines, though both share much).
 */
public enum SacredBuilding {
    // Temples (寺院)
    /** 金堂: the main hall of an older temple, housing the principal image. */
    KONDO(Religion.TEMPLE, "金堂"),
    /** 本堂: the main hall (the later name for the kondō). */
    HONDO(Religion.TEMPLE, "本堂"),
    /** 講堂: the lecture hall. */
    KODO(Religion.TEMPLE, "講堂"),
    /** 御影堂 / 大師堂: the founder's hall. */
    MIEIDO(Religion.TEMPLE, "御影堂"),
    /** 阿弥陀堂: the Amida hall. */
    AMIDADO(Religion.TEMPLE, "阿弥陀堂"),
    /** 塔: a pagoda (three- or five-storeyed). */
    PAGODA(Religion.TEMPLE, "塔"),
    /** 山門 / 楼門: a temple gate. */
    TEMPLE_GATE(Religion.TEMPLE, "山門"),
    /** 鐘楼: the bell tower. */
    BELFRY(Religion.TEMPLE, "鐘楼"),
    /** 僧房 / 庫裏: the monks' quarters and kitchen. */
    QUARTERS(Religion.TEMPLE, "庫裏"),
    // Shrines (神社)
    /** 本殿: the main sanctuary, where the kami is enshrined. */
    HONDEN(Religion.SHRINE, "本殿"),
    /** 拝殿: the worship hall in front of it. */
    HAIDEN(Religion.SHRINE, "拝殿"),
    /** 幣殿: the offering hall between them. */
    HEIDEN(Religion.SHRINE, "幣殿"),
    /** 楼門 / 神門: a shrine gate (the torii is a separate structure). */
    SHRINE_GATE(Religion.SHRINE, "楼門"),
    /** 神楽殿 / 舞殿: the hall for sacred dances. */
    KAGURADEN(Religion.SHRINE, "神楽殿"),
    /** 社務所: the shrine office. */
    SHAMUSHO(Religion.SHRINE, "社務所");

    /** Buddhist temple or Shinto shrine. */
    public enum Religion { TEMPLE, SHRINE }

    private final Religion religion;
    private final String japanese;

    SacredBuilding(Religion religion, String japanese) {
        this.religion = religion;
        this.japanese = japanese;
    }

    public Religion religion() {
        return religion;
    }

    /** The Japanese name, for labels. */
    public String japanese() {
        return japanese;
    }
}
