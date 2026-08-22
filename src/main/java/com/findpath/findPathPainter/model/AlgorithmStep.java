package com.findpath.findPathPainter.model;

import com.findpath.findPathPainter.constants.GridPainterTypeEnum;
import com.findpath.findPathPainter.constants.PathTypeEnum;
import com.findpath.findPathPainter.ui.FindPathPanel;
import com.findpath.findPathPainter.ui.GeoGridLabel;
import com.findpath.findPathPainter.ui.MapPanel;

import java.awt.*;


public class AlgorithmStep implements PainterStep {
    private int x;
    private int y;
    private PathTypeEnum type;

    public static AlgorithmStep valueOf(int x, int y, PathTypeEnum type) {
        AlgorithmStep obj = new AlgorithmStep();
        obj.x = x;
        obj.y = y;
        obj.type = type;
        return obj;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public PathTypeEnum getType() {
        return type;
    }

    @Override
    public void paintStep(FindPathPanel findPathPanel) {
        MapPanel mapPanel = findPathPanel.getMapPanel();
        GeoGridLabel[][] grids = mapPanel.getGrids();
        GeoGridLabel label = grids[y][x];
        if (label.getType() != GridPainterTypeEnum.Normal) {
            return;
        }

        if (label.getPathType() > type.ordinal()) {
            return;
        }

        Color color = type.getColor();
        if (type == PathTypeEnum.Traversed && label.getPathType() == type.ordinal()) {
            color = Color.DARK_GRAY;
        }

        label.setBackground(color);
        label.setPathType(type.ordinal());
    }

    @Override
    public void clear(FindPathPanel findPathPanel) {
        GeoGridLabel[][] grids = findPathPanel.getMapPanel().getGrids();
        if (y >= grids.length || x >= grids[y].length) {
            return;
        }

        GeoGridLabel label = grids[y][x];
        label.setBackground(label.getType().getColor());
        label.setPathType(-1);
    }
}
