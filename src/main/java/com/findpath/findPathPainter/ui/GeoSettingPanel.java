package com.findpath.findPathPainter.ui;

import com.findpath.findPathPainter.constants.GridPainterTypeEnum;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.awt.*;

@Slf4j
public class GeoSettingPanel extends JPanel {
    private final JTextField widthText;
    private final ButtonGroup typeButtonGroup;
    private final JRadioButton normal;
    private final JRadioButton block;
    private final JRadioButton start;
    private final JRadioButton end;
    private final JTextField sleepField;

    public GeoSettingPanel(MapPanel mapPanel) {

        setLayout(new FlowLayout());

        JLabel sleepLabel = new JLabel("播放间隔ms：");
        this.sleepField = new JTextField("-1", 2);
        JLabel widthLabel = new JLabel("地形边长");
        this.widthText = new JTextField("20", 2);
        JLabel typeLabel = new JLabel("绘制内容");
        this.typeButtonGroup = new ButtonGroup();
        this.normal = new JRadioButton("空白");
        this.block = new JRadioButton("阻挡");
        this.start = new JRadioButton("起点");
        this.end = new JRadioButton("终点");
        JButton paintBtn = new JButton("绘制");
        JButton resetBtn = new JButton("重置");

        paintBtn.addActionListener(e -> mapPanel.paintGeo(getGeoWidth()));

        resetBtn.addActionListener(e -> {
            mapPanel.resetGrids();
        });

        normal.setSelected(true);
        typeButtonGroup.add(normal);
        typeButtonGroup.add(block);
        typeButtonGroup.add(start);
        typeButtonGroup.add(end);

        add(sleepLabel);
        add(sleepField);
        add(widthLabel);
        add(widthText);
        add(typeLabel);
        add(normal);
        add(block);
        add(start);
        add(end);
        add(paintBtn);
        add(resetBtn);

        setBorder(BorderFactory.createEtchedBorder());
    }

    public int getGeoWidth() {
        try {
            return Integer.parseInt(this.widthText.getText());
        } catch (Exception e) {
            log.error("Error number:{}", this.widthText.getText());
            return 0;
        }
    }

	public GridPainterTypeEnum getPaintingType() {
        if (block.isSelected()) {
			return GridPainterTypeEnum.Block;
        } else if (start.isSelected()) {
			return GridPainterTypeEnum.Start;
        } else if (end.isSelected()) {
			return GridPainterTypeEnum.End;
        } else {
			return GridPainterTypeEnum.Normal;
        }
    }

    public int getSleep() {
        return Integer.parseInt(this.sleepField.getText());
    }
}
