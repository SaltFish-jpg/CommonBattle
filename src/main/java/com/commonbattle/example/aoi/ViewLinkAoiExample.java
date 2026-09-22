package com.commonbattle.example.aoi;



import com.commonbattle.battle.state.EntityId;
import com.aoi.AoiPoint;
import com.aoi.CrossLinkedListAoiIndex;
import com.aoi.viewlink.ViewLinkChangeSet;
import com.aoi.viewlink.ViewLinkManager;

import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * 演示地图十字链 AOI 与文档版视野关系十字链的组合方式。
 * 地图 AOI 只负责按坐标找候选对象，ViewLinkManager 负责维护 enter/leave 和双向可见关系。
 */
public final class ViewLinkAoiExample {
    private static final int VIEW_RANGE = 4;
    private static final int MAX_VISIBLE_COUNT = 2;

    private ViewLinkAoiExample() {
    }

    public static void main(String[] args) {
        CrossLinkedListAoiIndex mapAoi = new CrossLinkedListAoiIndex();
        ViewLinkManager viewLinks = new ViewLinkManager(16);

        EntityId me = new EntityId("me");
        EntityId teammate = new EntityId("teammate");
        EntityId monster = new EntityId("monster");
        EntityId farEnemy = new EntityId("far-enemy");

        register(mapAoi, viewLinks, me, 0, AoiPoint.of2d(0, 0));
        register(mapAoi, viewLinks, teammate, 1, AoiPoint.of2d(12, 0));
        register(mapAoi, viewLinks, monster, 2, AoiPoint.of2d(3, 1));
        register(mapAoi, viewLinks, farEnemy, 3, AoiPoint.of2d(8, 0));

        Map<EntityId, Integer> priorities = Map.of(
                me, 0,
                teammate, 1,
                farEnemy, 5,
                monster, 10
        );
        ToIntFunction<EntityId> priorityOf = priorities::get;

        System.out.println("== 初始刷新 ==");
        refreshAndPrint(mapAoi, viewLinks, me, priorityOf);

        System.out.println("== me 移动到 far-enemy 附近 ==");
        mapAoi.move(me, AoiPoint.of2d(6, 0));
        refreshAndPrint(mapAoi, viewLinks, me, priorityOf);

        System.out.println("== teammate 进入范围，优先级挤掉 monster ==");
        mapAoi.move(teammate, AoiPoint.of2d(6, 1));
        mapAoi.move(monster, AoiPoint.of2d(7, 1));
        refreshAndPrint(mapAoi, viewLinks, me, priorityOf);
    }

    private static void register(CrossLinkedListAoiIndex mapAoi, ViewLinkManager viewLinks,
                                 EntityId id, int objectIndex, AoiPoint position) {
        mapAoi.add(id, position);
        viewLinks.add(id, objectIndex);
    }

    private static void refreshAndPrint(CrossLinkedListAoiIndex mapAoi, ViewLinkManager viewLinks,
                                        EntityId viewerId, ToIntFunction<EntityId> priorityOf) {
        AoiPoint position = mapAoi.positionOf(viewerId).orElseThrow();
        List<EntityId> candidates = mapAoi.queryAround2d(position, VIEW_RANGE);

        // 候选集来自地图十字链；真正的视野变化由关系链计算，避免重复下发稳定可见对象。
        ViewLinkChangeSet changes = viewLinks.refresh(viewerId, candidates, MAX_VISIBLE_COUNT, priorityOf);

        System.out.println("候选对象: " + values(candidates));
        System.out.println("进入视野: " + values(changes.enter()));
        System.out.println("离开视野: " + values(changes.leave()));
        System.out.println("当前视野: " + values(viewLinks.visibleTargets(viewerId)));
        System.out.println();
    }

    private static List<String> values(List<EntityId> ids) {
        return ids.stream()
                .map(EntityId::value)
                .toList();
    }
}
