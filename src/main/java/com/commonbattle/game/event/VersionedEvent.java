package com.commonbattle.game.event;

/**
 * 由状态 owner 发布的版本事件。
 * 订阅方用 ownerKey + revision 做幂等、乱序和跳号检测。
 */
public interface VersionedEvent {
    String topic();

    String ownerKey();

    long revision();
}
