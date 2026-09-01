package com.commonbattle.game.profile;

import java.util.Objects;

/**
 * 玩家所属联盟的展示摘要。
 * 这是玩家资料中的冗余展示数据，联盟成员关系最终仍由联盟 owner 裁决。
 */
public record AllianceBrief(long allianceId, String name, String badge) {
    public AllianceBrief() {
        this(0, "", "");
    }

    public AllianceBrief {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(badge, "badge");
    }

    public static AllianceBrief none() {
        return new AllianceBrief(0, "", "");
    }
}
