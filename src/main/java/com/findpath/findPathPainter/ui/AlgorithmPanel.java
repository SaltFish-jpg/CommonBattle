package com.findpath.findPathPainter.ui;

import javax.swing.*;
import java.awt.*;

public class AlgorithmPanel extends JPanel {

    private final FindPathPanel findPathPanel;
    private final AlgorithmListPanel listPanel;
    private AlgorithmPerformancePanel performancePanel;

    public AlgorithmPanel(FindPathPanel findPathPanel) {
        this.findPathPanel = findPathPanel;

        setLayout(new BorderLayout());

        this.listPanel = new AlgorithmListPanel(findPathPanel);
        this.performancePanel = new AlgorithmPerformancePanel(findPathPanel);
        add(listPanel, BorderLayout.CENTER);
        add(performancePanel, BorderLayout.SOUTH);

        setBorder(BorderFactory.createEtchedBorder());
    }

    public AlgorithmListPanel getListPanel() {
        return listPanel;
    }

    public AlgorithmPerformancePanel getPerformancePanel() {
        return performancePanel;
    }
}
