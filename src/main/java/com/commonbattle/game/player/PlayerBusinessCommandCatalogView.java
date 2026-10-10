package com.commonbattle.game.player;

/**
 * 玩家业务命令目录观测接口。
 * 启动层注册该视图后，健康探针可以暴露当前进程可处理的玩家业务命令面。
 */
public interface PlayerBusinessCommandCatalogView {
    PlayerBusinessCommandCatalogStats commandCatalogStats();
}
