package com.commonbattle.game.player;

import com.commonbattle.actor.persistence.AgentStateRepository;

/**
 * 玩家业务状态仓库。
 */
public interface PlayerStateRepository extends AgentStateRepository<Long, PlayerStateSnapshot> {
}
