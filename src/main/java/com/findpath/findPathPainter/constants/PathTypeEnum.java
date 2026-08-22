package com.findpath.findPathPainter.constants;

import java.awt.*;

public enum PathTypeEnum {
    /**
     * 扫过的结点
     */
    Traversed(Color.GRAY),
    /**
     * openSet结点
     */
    Open(Color.ORANGE),
    /**
     * 跳点
     */
    Jump(Color.ORANGE),
    /**
     * 最终的路径
     */
    Path(Color.BLUE),
	JumpPoint(Color.BLUE),
    ///
    ;

    private final Color color;

    PathTypeEnum(Color color) {
        this.color = color;
    }

    public Color getColor() {
        return color;
    }
}
