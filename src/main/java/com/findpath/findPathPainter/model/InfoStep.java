package com.findpath.findPathPainter.model;


import com.findpath.findPathPainter.ui.FindPathPanel;
import javax.swing.*;


public class InfoStep implements PainterStep {
    private String info;

    public static InfoStep valueOf(String info) {
        InfoStep obj = new InfoStep();
        obj.info = info;
        return obj;
    }

    @Override
    public void paintStep(FindPathPanel findPathPanel) {
        JTextArea infoArea = findPathPanel.getAlgorithmPanel().getPerformancePanel().getInfo();
        infoArea.append(info + "\n");
    }

    @Override
    public void clear(FindPathPanel findPathPanel) {

    }
}
