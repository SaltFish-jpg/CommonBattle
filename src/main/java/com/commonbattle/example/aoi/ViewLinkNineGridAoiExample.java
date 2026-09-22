package com.commonbattle.example.aoi;



import com.commonbattle.battle.state.EntityId;
import com.aoi.AoiPoint;
import com.aoi.NineGridAoiIndex;
import com.aoi.viewlink.ViewLinkChangeSet;
import com.aoi.viewlink.ViewLinkManager;

import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * 演示九宫格 AOI 与文档版视野关系十字链的组合方式。
 * 九宫格负责快速找附近候选对象，ViewLinkManager 负责计算 enter/leave 并维护双向可见关系。
 */
public final class ViewLinkNineGridAoiExample {
    private static final int CELL_SIZE = 5;
    private static final int VIEW_RANGE = 4;
    private static final int MAX_VISIBLE_COUNT = 2;

    private ViewLinkNineGridAoiExample() {
    }

    public static void main(String[] args) {
        NineGridAoiIndex mapAoi = NineGridAoiIndex.twoDimensional(CELL_SIZE);
        ViewLinkManager viewLinks = new ViewLinkManager(16);

        EntityId me = new EntityId("me");
        EntityId teammate = new EntityId("teammate");
        EntityId monster = new EntityId("monster");
        EntityId resource = new EntityId("resource");

        register(mapAoi, viewLinks, me, 0, AoiPoint.of2d(0, 0));
        register(mapAoi, viewLinks, teammate, 1, AoiPoint.of2d(10, 0));
        register(mapAoi, viewLinks, monster, 2, AoiPoint.of2d(3, 1));
        register(mapAoi, viewLinks, resource, 3, AoiPoint.of2d(7, 0));

        Map<EntityId, Integer> priorities = Map.of(
                me, 0,
                teammate, 1,
                resource, 5,
                monster, 10
        );
        ToIntFunction<EntityId> priorityOf = priorities::get;

        System.out.println("== 初始刷新 ==");
        refreshAndPrint(mapAoi, viewLinks, me, priorityOf);

        System.out.println("== me 移动到 resource 附近 ==");
        mapAoi.move(me, AoiPoint.of2d(5, 0));
        refreshAndPrint(mapAoi, viewLinks, me, priorityOf);

        System.out.println("== teammate 进入九宫格候选范围，优先级挤掉 monster ==");
        mapAoi.move(teammate, AoiPoint.of2d(6, 1));
        refreshAndPrint(mapAoi, viewLinks, me, priorityOf);
    }

    private static void register(NineGridAoiIndex mapAoi, ViewLinkManager viewLinks,
                                 EntityId id, int objectIndex, AoiPoint position) {
        mapAoi.add(id, position);
        viewLinks.add(id, objectIndex);
    }

    private static void refreshAndPrint(NineGridAoiIndex mapAoi, ViewLinkManager viewLinks,
                                        EntityId viewerId, ToIntFunction<EntityId> priorityOf) {
        AoiPoint position = mapAoi.positionOf(viewerId).orElseThrow();
        List<EntityId> candidates = mapAoi.queryAround2d(position, VIEW_RANGE);

        // 九宫格只给出空间候选集；迷雾、阵营、层级等玩法过滤应在 refresh 前完成。
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
