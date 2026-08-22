package com.findpath.findPathPainter.ui;

import com.findpath.findPathPainter.constants.GridPainterTypeEnum;
import com.findpath.jps.Grid;
import com.findpath.jps.Terrain2D;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;

import javax.swing.*;
import java.awt.*;

@Slf4j
public class MapPanel extends JPanel {
    private final JPanel geoPanel;
    private final GeoSettingPanel settingPanel;

    private boolean painting = false;
    private GeoGridLabel[][] grids;
    private GeoGridLabel start;
    private GeoGridLabel end;

    public MapPanel() {

        setLayout(new BorderLayout());
        this.settingPanel = new GeoSettingPanel(this);
        this.geoPanel = new JPanel();
        this.geoPanel.setBorder(BorderFactory.createEtchedBorder());

        add(settingPanel, BorderLayout.NORTH);
        add(geoPanel, BorderLayout.CENTER);

        setBorder(BorderFactory.createEtchedBorder());
    }

    public static Logger getLog() {
        return log;
    }

    public void paintGeo(int width) {
        if (width <= 0 || width > 100) {
            log.info("地图尺寸不合法");
            return;
        }

        this.geoPanel.removeAll();
        this.geoPanel.repaint();

        geoPanel.setLayout(new GridLayout(width, width));
        grids = new GeoGridLabel[width][width];
        for (int y = width - 1; y >= 0; --y) {
            for (int x = 0; x < width; ++x) {
                GeoGridLabel label = new GeoGridLabel(this, x, y);
                grids[y][x] = label;
                this.geoPanel.add(label);
            }
        }

        this.geoPanel.revalidate();
    }

    public void paint(int x, int y) {
        GeoGridLabel label = grids[y][x];

		GridPainterTypeEnum paintingType = settingPanel.getPaintingType();
        label.setType(paintingType);
        switch (paintingType) {
            case Normal:
                label.setBackground(paintingType.getColor());
                break;
            case Block:
                label.setBackground(paintingType.getColor());
                break;
            case Start:
                if (this.end == label) {
                    this.end = null;
                }

                if (this.start != null) {
                    this.start.reset();
                }

                label.setBackground(paintingType.getColor());
                this.start = label;
                break;
            case End:
                if (this.start == label) {
                    this.end = null;
                }

                if (this.end != null) {
                    this.end.reset();
                }

                label.setBackground(paintingType.getColor());
                this.end = label;
                break;
            default:
                log.error("error type");
        }
    }

    public JPanel getGeoPanel() {
        return geoPanel;
    }

    public boolean isPainting() {
        return painting;
    }

    public void setPainting(boolean painting) {
        this.painting = painting;
    }

    public GeoGridLabel[][] getGrids() {
        return grids;
    }

    public void setGrids(GeoGridLabel[][] grids) {
        this.grids = grids;
    }

    public GeoGridLabel getStart() {
        return start;
    }

    public void setStart(GeoGridLabel start) {
        this.start = start;
    }

    public GeoGridLabel getEnd() {
        return end;
    }

    public void setEnd(GeoGridLabel end) {
        this.end = end;
    }

    public Terrain2D generateGeo() {
        int width = grids.length;
        int[] geo = new int[width * width];

        GeoGridLabel geoGridLabel;
        for (int y = 0; y < width; ++y) {
            for (int x = 0; x < width; ++x) {
                geoGridLabel = grids[y][x];
                geo[y * width + x] = geoGridLabel.getType().getId();
            }
        }

        return new Terrain2D(width, width, geo);
    }

    public void resetGrids() {
        for (GeoGridLabel[] labelArray : grids) {
            for (GeoGridLabel label : labelArray) {
                label.reset();
            }
        }
    }

    public Grid getStartGrid() {
        return Grid.valueOf(start.getxIndex(), start.getyIndex(), start.getType().getId());
    }

    public Grid getEndGrid() {
        return Grid.valueOf(end.getxIndex(), end.getyIndex(), end.getType().getId());
    }

    public GeoSettingPanel getSettingPanel() {
        return settingPanel;
    }
}
