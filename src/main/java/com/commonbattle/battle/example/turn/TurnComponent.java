package com.commonbattle.battle.example.turn;




import com.commonbattle.battle.state.Component;
import com.commonbattle.battle.state.EntityId;
import java.util.List;

/**
 * 回合制全局状态。
 * 保存行动顺序和当前轮次，同时不改变核心战斗状态语义。
 */
public final class TurnComponent implements Component {
    private final List<EntityId> order;
    private int index;
    private int round = 1;

    public TurnComponent(List<EntityId> order) {
        if (order.isEmpty()) {
            throw new IllegalArgumentException("turn order cannot be empty");
        }
        this.order = List.copyOf(order);
    }

    public EntityId currentActor() {
        return order.get(index);
    }

    public int round() {
        return round;
    }

    public void advance() {
        index = (index + 1) % order.size();
        if (index == 0) {
            round++;
        }
    }
}
