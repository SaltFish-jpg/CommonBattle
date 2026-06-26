package com.commonbattle.example.turn;

import com.commonbattle.core.Component;
import com.commonbattle.core.EntityId;

import java.util.List;

/**
 * Turn-based global state.
 * It keeps actor order and the current round without changing core battle state semantics.
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
