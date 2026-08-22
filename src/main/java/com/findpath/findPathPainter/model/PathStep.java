package com.findpath.findPathPainter.model;



import com.findpath.findPathPainter.constants.GridPainterTypeEnum;
import com.findpath.findPathPainter.constants.PathTypeEnum;
import com.findpath.findPathPainter.ui.FindPathPanel;
import com.findpath.findPathPainter.ui.GeoGridLabel;
import com.findpath.findPathPainter.ui.MapPanel;

import javax.swing.*;
import java.awt.*;


public class PathStep implements PainterStep {
    private int number;
    private int x;
    private int y;

    public static PathStep valueOf(int x, int y, int number) {
        PathStep obj = new PathStep();
        obj.x = x;
        obj.y = y;
        obj.number = number;
        return obj;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    @Override
    public void paintStep(FindPathPanel findPathPanel) {
        MapPanel mapPanel = findPathPanel.getMapPanel();
        GeoGridLabel[][] grids = mapPanel.getGrids();
        GeoGridLabel label = grids[y][x];

        label.setText(label.getText() + number + " ");
        label.setHorizontalTextPosition(JLabel.CENTER);
        label.setForeground(Color.WHITE);

		if (label.getType() == GridPainterTypeEnum.Normal) {
            label.setBackground(PathTypeEnum.Path.getColor());
        }
    }

    @Override
    public void clear(FindPathPanel findPathPanel) {
        GeoGridLabel[][] grids = findPathPanel.getMapPanel().getGrids();
        if (y >= grids.length || x >= grids[y].length) {
            return;
        }

        GeoGridLabel label = grids[y][x];

        label.setText("");
        label.setPathType(-1);
        label.setBackground(label.getType().getColor());
    }
}
