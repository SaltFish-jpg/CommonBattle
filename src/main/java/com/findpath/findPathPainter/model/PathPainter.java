package com.findpath.findPathPainter.model;


import com.findpath.findPathPainter.constants.PathTypeEnum;
import com.findpath.findPathPainter.ui.FindPathPanel;
import com.findpath.findPathPainter.ui.MapPanel;
import com.findpath.jps.Grid;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Slf4j
public class PathPainter {
    private List<PainterStep> paintStep = new ArrayList<>();
    private List<Grid> path;
    private Map<String, Long> methodCost = new HashMap<>();
    private Map<String, Integer> methodCount = new HashMap<>();
    private long now;

    public void clear(FindPathPanel findPathPanel) {
        if (CollectionUtils.isNotEmpty(this.paintStep)) {
            for (PainterStep step : paintStep) {
                step.clear(findPathPanel);
            }

            this.paintStep.clear();
        }

        methodCost.clear();
        methodCount.clear();
    }

    public int getStepSize() {
        return paintStep.size();
    }

    public void traverse(int x, int y) {
        paintStep.add(AlgorithmStep.valueOf(x, y, PathTypeEnum.Traversed));
    }

    public void open(int x, int y) {
        paintStep.add(AlgorithmStep.valueOf(x, y, PathTypeEnum.Open));
    }

	public void addJumpPoint(int x, int y) {
		paintStep.add(AlgorithmStep.valueOf(x, y, PathTypeEnum.JumpPoint));
	}

    public void paint(FindPathPanel findPathPanel) throws InterruptedException {
        MapPanel mapPanel = findPathPanel.getMapPanel();
        int sleep = mapPanel.getSettingPanel().getSleep();
        for (PainterStep step : paintStep) {
            step.paintStep(findPathPanel);
            if (sleep > 0) {
                Thread.sleep(sleep);
            }
        }
    }

    public void jump(int x, int y) {
        paintStep.add(AlgorithmStep.valueOf(x, y, PathTypeEnum.Jump));
    }

    public void path(List<Grid> gridList) {
        int i = 1;
        for (Grid grid : gridList) {
            paintStep.add(PathStep.valueOf(grid.getX(), grid.getY(), i++));
        }
    }

    public void info(String info) {
        paintStep.add(InfoStep.valueOf(info));
    }

    public void cost(String name) {
        long cost = System.nanoTime() - now;
        methodCost.put(name, methodCost.getOrDefault(name, 0L) + cost);
        methodCount.put(name, methodCount.getOrDefault(name, 0) + 1);
    }

    public void printCost() {
        for (Map.Entry<String, Long> entry : methodCost.entrySet()) {
            log.info("method:{}, cost:{}, call count:{}", entry.getKey(), entry.getValue(),
                    methodCount.get(entry.getKey()));
        }
    }

    public void startRecord() {
        this.now = System.nanoTime();
    }
}
