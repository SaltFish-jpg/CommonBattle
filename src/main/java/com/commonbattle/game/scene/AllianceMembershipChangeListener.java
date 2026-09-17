package com.commonbattle.game.scene;

import com.commonbattle.game.social.AllianceSnapshot;

/**
 * 联盟成员关系投影变化监听器。
 * 订阅方可把成员退出或快照修复结果投递给自己的业务 Actor 做派生状态收敛。
 */
public interface AllianceMembershipChangeListener {
    void onAllianceMemberLeft(long allianceId, long playerId, long revision);

    void onAllianceSnapshot(AllianceSnapshot snapshot);

    static AllianceMembershipChangeListener noop() {
        return Noop.INSTANCE;
    }

    enum Noop implements AllianceMembershipChangeListener {
        INSTANCE;

        @Override
        public void onAllianceMemberLeft(long allianceId, long playerId, long revision) {
        }

        @Override
        public void onAllianceSnapshot(AllianceSnapshot snapshot) {
        }
    }
}
