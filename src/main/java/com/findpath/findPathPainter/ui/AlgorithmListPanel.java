package com.findpath.findPathPainter.ui;

import com.findpath.findPathPainter.constants.FindPathPainterAlgorithmEnum;
import com.findpath.findPathPainter.constants.UIConstants;
import com.findpath.findPathPainter.model.PathPainter;
import com.findpath.findPathPainter.utils.TimeUtility;
import com.findpath.jps.Grid;
import com.findpath.jps.GridQuery;
import com.findpath.jps.Terrain2D;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.awt.*;
import java.util.List;

@Slf4j
public class AlgorithmListPanel extends JPanel {
    private static final String COST = "耗时：";

    private PathPainter painter = new PathPainter();

    public AlgorithmListPanel(FindPathPanel findPathPanel) {
        FindPathPainterAlgorithmEnum[] allAlg = FindPathPainterAlgorithmEnum.values();

        setLayout(new GridLayout(allAlg.length, 1));
        setPreferredSize(new Dimension(UIConstants.ALGORITHM_WIDTH, UIConstants.ALGORITHM_HEIGHT * allAlg.length));

        JButton runBtn;
        for (FindPathPainterAlgorithmEnum alg : allAlg) {
            JPanel algPanel = new JPanel();
            algPanel.setLayout(new FlowLayout());
            algPanel.setBorder(BorderFactory.createEtchedBorder());
            algPanel.add(new JLabel(alg.getName()));
            runBtn = new JButton("执行");
            JLabel costLabel = new JLabel(COST);

            runBtn.addActionListener(e -> {

                MapPanel mapPanel = findPathPanel.getMapPanel();
                Terrain2D terrain = mapPanel.generateGeo();
                if (terrain == null) {
                    return;
                }

                GeoGridLabel startLabel = mapPanel.getStart();
                GeoGridLabel endLabel = mapPanel.getEnd();

                if (startLabel == null || endLabel == null) {
                    return;
                }

                Grid start =
                        Grid.valueOf(startLabel.getxIndex(), startLabel.getyIndex(), startLabel.getType().getId());
                Grid end = Grid.valueOf(endLabel.getxIndex(), endLabel.getyIndex(), endLabel.getType().getId());

                log.info("start:({},{})  end:({},{})", start.getX(), start.getY(), end.getX(), end.getY());
                // FindPathUtility.printGeo(terrain);

                painter.clear(findPathPanel);
				GridQuery query = alg.createQuery(terrain);
                long now = System.nanoTime();
				List<Grid> path = alg.findPath(start, end, query, painter, findPathPanel);
                painter.printCost();
                long cost = System.nanoTime() - now;
                costLabel.setText(COST + TimeUtility.nanoToCN(cost));
                painter.path(path);
                new Thread(() -> {
                    try {
                        painter.paint(findPathPanel);
                    } catch (InterruptedException interruptedException) {
                        interruptedException.printStackTrace();
                    }
                }).start();

                log.info("step size:{}", painter.getStepSize());
            });
            algPanel.add(runBtn);
            algPanel.add(costLabel);
            add(algPanel);
        }
    }
}
