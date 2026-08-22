package com.findpath.findPathPainter.ui;

import javax.swing.*;
import java.awt.*;

public class FindPathPanel extends JPanel {
    private AlgorithmPanel algorithmPanel;
    private MapPanel mapPanel;

    public FindPathPanel() {
        setLayout(new BorderLayout());

        this.algorithmPanel = new AlgorithmPanel(this);
        this.mapPanel = new MapPanel();

        add(this.algorithmPanel, BorderLayout.WEST);
        add(this.mapPanel, BorderLayout.CENTER);
    }

    public AlgorithmPanel getAlgorithmPanel() {
        return algorithmPanel;
    }

    public void setAlgorithmPanel(AlgorithmPanel algorithmPanel) {
        this.algorithmPanel = algorithmPanel;
    }

    public MapPanel getMapPanel() {
        return mapPanel;
    }

    public void setMapPanel(MapPanel mapPanel) {
        this.mapPanel = mapPanel;
    }
}
