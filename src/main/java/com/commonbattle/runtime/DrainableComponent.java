package com.commonbattle.runtime;

/**
 * 可参与服务排水的运行时组件。
 * 运维入口在摘流时先调用 {@link #beginDrain()} 关闭新请求准入，再等待存量队列清空。
 */
public interface DrainableComponent {
    void beginDrain();

    void resumeAccepting();

    boolean isDraining();
}
