package com.commonbattle.actor;

import java.util.Objects;

/**
 * Actor 邮箱中的一条业务消息。
 * 实现方把网络包、RPC 回包、定时器等外部输入封装成任务，交给目标 Actor 串行执行。
 */
@FunctionalInterface
public interface ActorTask {
    void run(ActorContext context);

    /**
     * 返回任务类别，调度器据此做分类背压和指标聚合。
     */
    default ActorTaskCategory category() {
        return ActorTaskCategory.DEFAULT;
    }

    /**
     * 把已有任务包装成指定类别，常用于网络包、RPC 回包和定时器入口统一打标。
     */
    static ActorTask categorized(ActorTaskCategory category, ActorTask delegate) {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(delegate, "delegate");
        return new ActorTask() {
            @Override
            public void run(ActorContext context) {
                delegate.run(context);
            }

            @Override
            public ActorTaskCategory category() {
                return category;
            }
        };
    }

    static ActorTask withDefaultCategory(ActorTaskCategory category, ActorTask delegate) {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(delegate, "delegate");
        ActorTaskCategory current = delegate.category();
        return current == null || current == ActorTaskCategory.DEFAULT ? categorized(category, delegate) : delegate;
    }
}
