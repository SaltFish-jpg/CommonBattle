package com.findpath.findPathPainter.ui;

import com.findpath.findPathPainter.constants.GridPainterTypeEnum;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import javax.swing.event.MouseInputAdapter;
import java.awt.*;
import java.awt.event.MouseEvent;

@Slf4j
public class GeoGridLabel extends JLabel {
	public final static String HTML_FORMAT = "<html><body><table><tr><td>%s</td><td>%s</td><td>%s</td></tr><tr><td>%s</td><td></td><td>%s</td></tr><tr><td>%s</td><td>%s</td><td>%s</td></tr></table></body></html>";

	private MapPanel mapPanel;
	private int xIndex;
	private int yIndex;
	private GridPainterTypeEnum type;
	private int pathType = -1;

	public GeoGridLabel(MapPanel mapPanel, int xIndex, int yIndex) {
		super();
		this.mapPanel = mapPanel;
		this.xIndex = xIndex;
		this.yIndex = yIndex;
		this.type = GridPainterTypeEnum.Normal;
		setForeground(Color.BLACK);
		setOpaque(true);
		setBorder(BorderFactory.createLineBorder(Color.BLACK));
		setBackground(type.getColor());
		setHorizontalAlignment(JLabel.CENTER);
		addMouseListener(new MouseInputAdapter() {
			@Override
			public void mouseEntered(MouseEvent e) {
				super.mouseEntered(e);
				if (mapPanel.isPainting()) {
					mapPanel.paint(xIndex, yIndex);
				}
			}

			@Override
			public void mousePressed(MouseEvent e) {
				super.mousePressed(e);
				mapPanel.setPainting(true);
				mapPanel.paint(xIndex, yIndex);
			}

			@Override
			public void mouseReleased(MouseEvent e) {
				super.mouseReleased(e);
				mapPanel.setPainting(false);
			}
		});
	}

	public MapPanel getMapPanel() {
		return mapPanel;
	}

	public void setMapPanel(MapPanel mapPanel) {
		this.mapPanel = mapPanel;
	}

	public int getxIndex() {
		return xIndex;
	}

	public void setxIndex(int xIndex) {
		this.xIndex = xIndex;
	}

	public int getyIndex() {
		return yIndex;
	}

	public void setyIndex(int yIndex) {
		this.yIndex = yIndex;
	}

	public GridPainterTypeEnum getType() {
		return type;
	}

	public void setType(GridPainterTypeEnum type) {
		this.type = type;
	}

	public int getPathType() {
		return pathType;
	}

	public void setPathType(int pathType) {
		this.pathType = pathType;
	}

    public void reset() {
		this.type = GridPainterTypeEnum.Normal;
        this.pathType = -1;
        this.setText("");
        setBackground(this.type.getColor());
    }
}
