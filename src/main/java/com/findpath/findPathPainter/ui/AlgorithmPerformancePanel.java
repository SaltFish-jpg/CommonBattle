package com.findpath.findPathPainter.ui;


import com.findpath.findPathPainter.constants.FindPathPainterAlgorithmEnum;
import com.findpath.findPathPainter.constants.GridPainterTypeEnum;
import com.findpath.findPathPainter.constants.UIConstants;
import com.findpath.findPathPainter.model.EmptyPainter;
import com.findpath.findPathPainter.utils.TimeUtility;
import com.findpath.jps.Grid;
import com.findpath.jps.GridQuery;
import com.findpath.jps.Terrain2D;
import com.utility.RandomUtility;

import javax.swing.*;
import java.awt.*;
import java.util.List;

public class AlgorithmPerformancePanel extends JPanel {

    private final JTextArea info;

    public AlgorithmPerformancePanel(FindPathPanel findPathPanel) {
        setLayout(new BorderLayout());

        setPreferredSize(new Dimension(UIConstants.ALGORITHM_WIDTH,
                UIConstants.MAIN_FRAME_HEIGHT - UIConstants.ALGORITHM_HEIGHT * FindPathPainterAlgorithmEnum.values().length));

        JPanel settingPanel = new JPanel();
        settingPanel.setLayout(new GridBagLayout());

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JLabel rangeLabel = new JLabel("随机范围:");
        JTextField rangeField = new JTextField("10", 10);
        JLabel countLabel = new JLabel("执行次数:");
        JTextField countField = new JTextField("1000", 10);
        JButton startBtn = new JButton("开始测试");
        JLabel infoLabel = new JLabel();
		this.info = new JTextArea(15, 10);

        startBtn.addActionListener(e -> {
            try {
                MapPanel mapPanel = findPathPanel.getMapPanel();
                Grid startGrid = mapPanel.getStartGrid();
                Grid endGrid = mapPanel.getEndGrid();

				info.setText("");
                infoLabel.setText("测试中");
                Integer count = Integer.valueOf(countField.getText());
                Integer range = Integer.valueOf(rangeField.getText());
                runTest(startGrid, endGrid, mapPanel.generateGeo(), range, mapPanel.getSettingPanel().getGeoWidth(),
                        count);
                infoLabel.setText("测试完成");
            } catch (Exception ex) {
                infoLabel.setText("测试出现异常");
                ex.printStackTrace();
            }
        });
        gbc.gridx = 0;
        gbc.gridy = 0;
        settingPanel.add(rangeLabel, gbc);
        gbc.gridx = 1;
        settingPanel.add(rangeField, gbc);
        gbc.gridx = 0;
        gbc.gridy = 1;
        settingPanel.add(countLabel, gbc);
        gbc.gridx = 1;
        settingPanel.add(countField, gbc);
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.gridwidth = 2;
        settingPanel.add(startBtn, gbc);
        gbc.gridy = 3;
        settingPanel.add(infoLabel, gbc);

        add(settingPanel, BorderLayout.NORTH);

		add(new JScrollPane(this.info), BorderLayout.CENTER);
    }

    public JTextArea getInfo() {
        return info;
    }

    private Grid randomGrid(int x1, int x2, int y1, int y2, Terrain2D terrain) {
        int type, x, y;
        do {
            x = RandomUtility.nextInt(x1, x2);
            y = RandomUtility.nextInt(y1, y2);
            type = terrain.getType(x, y);
		} while (type == GridPainterTypeEnum.Block.getId());

        return Grid.valueOf(x, y, type);
    }

    private void runTest(Grid startGrid, Grid endGrid, Terrain2D terrain, Integer range, int width, Integer count) {
        StringBuilder builder = new StringBuilder();
        int startXRange1 = Math.max(startGrid.getX() - range / 2, 0);
        int startXRange2 = Math.min(startGrid.getX() + range / 2, width);
        int startYRange1 = Math.max(startGrid.getY() - range / 2, 0);
        int startYRange2 = Math.min(startGrid.getY() + range / 2, width);
        int endXRange1 = Math.max(endGrid.getX() - range / 2, 0);
        int endXRange2 = Math.min(endGrid.getX() + range / 2, width);
        int endYRange1 = Math.max(endGrid.getY() - range / 2, 0);
        int endYRange2 = Math.min(endGrid.getY() + range / 2, width);

		long i, totalCost, failCount, aStarCost = 0;
        Grid start, end;
        long now;
        for (FindPathPainterAlgorithmEnum alg : FindPathPainterAlgorithmEnum.values()) {
            failCount = 0;
            // 预热
			GridQuery query = alg.createQuery(terrain);
            for (i = 0; i < count; ++i) {
                start = randomGrid(startXRange1, startXRange2, startYRange1, startYRange2, terrain);
                end = randomGrid(endXRange1, endXRange2, endYRange1, endYRange2, terrain);
				java.util.List<Grid> path = alg.findPath(start, end, query, new EmptyPainter(), null);
                if (path == null || path.size() <= 0) {
                    failCount++;
                }
            }

            // 统计耗时
            totalCost = 0;
            failCount = 0;
            for (i = 0; i < count; ++i) {
                start = randomGrid(startXRange1, startXRange2, startYRange1, startYRange2, terrain);
                end = randomGrid(endXRange1, endXRange2, endYRange1, endYRange2, terrain);

                now = System.nanoTime();
				List<Grid> path = alg.findPath(start, end, query, new EmptyPainter(), null);
                totalCost += System.nanoTime() - now;

                if (path == null || path.size() <= 0) {
                    failCount++;
                }
            }

			long avg = totalCost / count;
            builder.append("~~~~~~~~算法：").append(alg.getName()).append("~~~~~~~~~\n总耗时：")
                    .append(TimeUtility.nanoToCN(totalCost)).append(",平均耗时：").append(TimeUtility.nanoToCN(avg))
                    .append("\n失败次数：").append(failCount).append("\n");
            if (alg == FindPathPainterAlgorithmEnum.AStar) {
                aStarCost = avg;
            } else if (aStarCost != 0) {
                builder.append("比A*快：").append(aStarCost / avg).append("倍\n");
            }

        }

        this.info.append(builder.toString());
    }
}
