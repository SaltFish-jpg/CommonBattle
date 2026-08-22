package com.findpath.findPathPainter.model;

import com.findpath.findPathPainter.ui.FindPathPanel;


public interface PainterStep {
    /**
     * 绘制步骤
     *
     * @param findPathPanel
     */
    void paintStep(FindPathPanel findPathPanel);

    /**
     * 清理
     *
     * @param findPathPanel
     */
    void clear(FindPathPanel findPathPanel);
}
