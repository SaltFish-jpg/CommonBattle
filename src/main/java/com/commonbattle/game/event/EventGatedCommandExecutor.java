package com.commonbattle.game.event;

import java.time.Clock;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 事件门控命令执行模板。
 * RPC/Netty 入口只负责调用本模板提交命令，业务执行函数必须继续把真实逻辑投递到目标 Actor mailbox。
 */
public final class EventGatedCommandExecutor {
    private final EventGatedCommandAuditSink audits;
    private final Clock clock;

    public EventGatedCommandExecutor(EventGatedCommandAuditSink audits, Clock clock) {
        this.audits = Objects.requireNonNull(audits, "audits");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static EventGatedCommandExecutor noop() {
        return new EventGatedCommandExecutor(EventGatedCommandAuditSink.noop(), Clock.systemUTC());
    }

    public <C extends EventGatedBusinessCommand<?, ?>, R> void execute(
            String operation,
            C command,
            BiConsumer<C, Consumer<R>> submit,
            Function<R, String> resultLabel,
            Consumer<R> callback
    ) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(submit, "submit");
        Objects.requireNonNull(resultLabel, "resultLabel");
        Objects.requireNonNull(callback, "callback");
        record(operation, command, "submitted", "");
        try {
            submit.accept(command, result -> {
                record(operation, command, "completed", resultLabel.apply(result));
                callback.accept(result);
            });
        } catch (RuntimeException | Error error) {
            record(operation, command, "failed", error.getClass().getName());
            throw error;
        }
    }

    private void record(
            String operation,
            EventGatedBusinessCommand<?, ?> command,
            String outcome,
            String result
    ) {
        audits.record(new EventGatedCommandAuditRecord(
                operation,
                command.command().getClass().getName(),
                command.boundary(),
                outcome,
                result,
                clock.instant()
        ));
    }
}
