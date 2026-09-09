package com.commonbattle.game.social;

import java.util.Set;

/**
 * 联盟成员只读快照。
 */
public record AllianceSnapshot(long allianceId, long revision, Set<Long> members) {
    public AllianceSnapshot {
        members = Set.copyOf(members);
    }

    public String ownerKey() {
        return AllianceOwnerKeyParser.ownerKey(allianceId);
    }
}
