package com.commonbattle.game.shop;

import com.commonbattle.actor.ActorContext;

/**
 * 在目标 Actor 邮箱中执行的库存回调。
 * 实现方可以安全读取和修改该 Actor 拥有的玩家或业务实体状态。
 */
public interface ActorShopStockCallback<T> {
    void success(ActorContext context, T response);

    void failure(ActorContext context, Throwable error);
}
