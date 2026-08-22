package com.findpath.findPathPainter.painter;


import com.findpath.findPathPainter.constants.WAWDirectionEnum;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TouchingWall implements Comparable<TouchingWall> {
    private int x;
    private int y;
    private WAWDirectionEnum dir;
    private WAWDirectionEnum wallDir;

    private double w;
    private double g;
    private boolean finish;
    private boolean dead;
    private boolean jump;

    public static TouchingWall valueOf(int x, int y, WAWDirectionEnum dir, WAWDirectionEnum wallDir) {
        TouchingWall obj = new TouchingWall();
        obj.x = x;
        obj.y = y;
        obj.dir = dir;
        obj.wallDir = wallDir;
        return obj;
    }

    @Override
    public int compareTo(TouchingWall o) {
        return Double.compare(w, o.w);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TouchingWall that = (TouchingWall) o;
        return x == that.x && y == that.y;
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }
}
