package com.commonbattle.example.cross.scene;

import com.commonbattle.actor.agent.AgentIdentity;
import com.commonbattle.actor.backpressure.AdmissionDecision;
import com.commonbattle.actor.backpressure.InboundAdmissionController;
import com.commonbattle.example.cross.SceneOperations;

import java.util.Objects;

/**
 * Scene RPC 入口的邮箱压力门。
 * 网络线程只做落点选择和准入判断，真正的场景业务仍由 SceneServiceStrategy 后续落到对应 Actor 边界。
 */
public final class SceneMailboxPressureGate {
    private final SceneServiceStrategy scenes;
    private final InboundAdmissionController admissions;

    public SceneMailboxPressureGate(SceneServiceStrategy scenes, InboundAdmissionController admissions) {
        this.scenes = Objects.requireNonNull(scenes, "scenes");
        this.admissions = Objects.requireNonNull(admissions, "admissions");
    }

    public SceneAdmission admitEnter(String sceneId, int chunkX, int chunkY) {
        ScenePlacement placement = scenes.place(sceneId, chunkX, chunkY);
        AdmissionDecision decision = admissions.admit(sceneIdentity(placement), SceneOperations.ENTER);
        return new SceneAdmission(placement, decision);
    }

    public static String actorIdOf(AgentIdentity identity) {
        return identity.key();
    }

    private static AgentIdentity sceneIdentity(ScenePlacement placement) {
        return new AgentIdentity(AgentIdentity.SCENE, placement.actor().id());
    }

    public record SceneAdmission(ScenePlacement placement, AdmissionDecision decision) {
        public SceneAdmission {
            Objects.requireNonNull(placement, "placement");
            Objects.requireNonNull(decision, "decision");
        }

        public boolean accepted() {
            return decision.accepted();
        }
    }
}
