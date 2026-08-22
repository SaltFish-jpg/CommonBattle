package com.findpath.findPathPainter.constants;

public enum WAWDirectionEnum {
    /**
     * 上
     */
    Up(2, 3, 1),
    /**
     * 下
     */
    Down(2, 3, 0),
    /**
     * 左
     */
    Left(0, 1, 3),
    /**
     * 右
     */
    Right(0, 1, 2),;

    private static WAWDirectionEnum[] values;

    static {
        values = WAWDirectionEnum.values();
    }

    private final int[] neighbours = new int[2];
    private final int backIndex;

    WAWDirectionEnum(int neighbourIndex1, int neighbourIndex2, int backIndex) {
        this.neighbours[0] = neighbourIndex1;
        this.neighbours[1] = neighbourIndex2;
        this.backIndex = backIndex;
    }

    public static WAWDirectionEnum valueOf(int ordinal) {
        return values[ordinal];
    }

    public int[] getNeighbours() {
        return neighbours;
    }

    public WAWDirectionEnum getBack() {
        return values[backIndex];
    }
}
