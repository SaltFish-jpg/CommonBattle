package com.commonbattle.game.player;

import com.commonbattle.actor.ActorRef;
import com.commonbattle.actor.agent.AgentDirectory;
import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.game.session.PlayerCommand;

import java.util.Objects;

/**
 * 基于 AgentDirectory 的玩家业务重定向处理器。
 * 它只做旧 owner 到新 owner 的 CAS 刷新，避免覆盖已经收敛到其他位置的目录。
 */
public final class AgentDirectoryPlayerBusinessRedirectHandler implements PlayerBusinessRedirectHandler {
    private final AgentDirectory directory;

    public AgentDirectoryPlayerBusinessRedirectHandler(AgentDirectory directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    @Override
    public boolean redirected(PlayerCommand command, AgentLocation previousOwner, PlayerBusinessRedirect redirect) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(previousOwner, "previousOwner");
        Objects.requireNonNull(redirect, "redirect");
        AgentLocation next = new AgentLocation(
                ServiceId.parse(redirect.targetService()),
                new ActorRef(redirect.targetActor())
        );
        if (previousOwner.equals(next)) {
            return false;
        }
        return directory.move(AgentIdentity.player(command.playerId()), previousOwner, next);
    }
}
