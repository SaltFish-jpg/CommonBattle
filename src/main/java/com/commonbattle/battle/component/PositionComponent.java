package com.commonbattle.battle.component;



import com.commonbattle.battle.state.Component;
/**
 * 实体在战场上的通用二维坐标。
 * 棋盘、格子地图或站位系统可以基于该组件扩展自己的合法性规则。
 */
public final class PositionComponent implements Component {
    private int x;
    private int y;

    public PositionComponent(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public void moveTo(int x, int y) {
        this.x = x;
        this.y = y;
    }
}
