package com.commonbattle.runtime;

/**
 * 可参与服务排水的运行时组件。
 * 运维入口会先执行对外公告阶段，再执行本地入口关闭和存量队列排空。
 */
public interface DrainableComponent {
    default DrainPhase phase() {
        return DrainPhase.LOCAL_INGRESS;
    }

    void beginDrain();

    void resumeAccepting();

    boolean isDraining();
}
