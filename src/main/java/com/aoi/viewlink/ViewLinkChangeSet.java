package com.aoi.viewlink;




import com.commonbattle.battle.state.EntityId;
import java.util.List;

/**
 * 一次视野刷新产生的差量结果。
 * enter 表示新进入观察者视野的对象，leave 表示本轮刷新后离开视野的对象。
 */
public record ViewLinkChangeSet(List<EntityId> enter, List<EntityId> leave) {
    public ViewLinkChangeSet {
        enter = List.copyOf(enter);
        leave = List.copyOf(leave);
    }
}
