package com.findpath.findPathPainter.model;


import com.findpath.findPathPainter.ui.FindPathPanel;
import com.findpath.jps.Grid;

import java.util.List;

public class EmptyPainter extends PathPainter {

    @Override
    public void clear(FindPathPanel findPathPanel) {
    }

    @Override
    public int getStepSize() {
        return 0;
    }

    @Override
    public void traverse(int x, int y) {
    }

    @Override
    public void jump(int x, int y) {
    }

    @Override
    public void cost(String name) {
    }

    @Override
    public void printCost() {
    }

    @Override
    public void startRecord() {
    }

    @Override
    public void paint(FindPathPanel findPathPanel) {

    }

    @Override
    public void path(List<Grid> gridList) {

    }
}
