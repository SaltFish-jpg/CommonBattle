package com.commonbattle.game.player;

/**
 * 玩家业务命令网关观测视图。
 * 运维健康探针通过它聚合跨服玩家命令转发的成功、失败、超时和迟到回包。
 */
public interface PlayerBusinessCommandGatewayView {
    PlayerBusinessCommandGatewayStats commandGatewayStats();
}
