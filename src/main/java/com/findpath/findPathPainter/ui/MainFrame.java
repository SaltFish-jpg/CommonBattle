package com.findpath.findPathPainter.ui;


import com.findpath.findPathPainter.constants.UIConstants;

import javax.swing.*;

public class MainFrame extends JFrame {

    public MainFrame() {
        // 添加地图面板
        this.add(new FindPathPanel());
        this.setBounds(UIConstants.INIT_X, UIConstants.INIT_Y, UIConstants.MAIN_FRAME_WIDTH,
                UIConstants.MAIN_FRAME_HEIGHT);
        this.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        this.setVisible(true);
    }

    public static void main(String[] args) {
        new MainFrame();
    }
}
