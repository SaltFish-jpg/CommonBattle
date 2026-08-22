package com.findpath.findPathPainter.constants;

import java.awt.*;

public enum GridTypeEnum {
    /**
     * 普通
     */
    Normal(0, Color.WHITE),
    /**
     * 阻挡
     */
    Block(1, Color.BLACK),
    /**
     * 起点
     */
    Start(3, Color.GREEN),
    /**
     * 终点
     */
    End(4, Color.RED),

    ///
    ;

    private final int id;
    private final Color color;

    GridTypeEnum(int id, Color color) {
        this.id = id;
        this.color = color;
    }

    public int getId() {
        return id;
    }

    public Color getColor() {
        return color;
    }
}
