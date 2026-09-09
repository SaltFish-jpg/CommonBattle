package com.commonbattle.game.player;

import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.game.session.PlayerCommand;
import com.commonbattle.game.session.PlayerCommandDispatcher;
import com.commonbattle.game.session.PlayerCommandResult;
import com.commonbattle.game.session.PlayerCommandStatus;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 玩家业务命令统一入口。
 * 本服玩家命令进入本地邮箱；远端玩家命令按 Agent owner 转发到目标 Game 服，调用方始终收到统一响应信封。
 */
public final class PlayerBusinessCommandGateway implements AutoCloseable {
    public static final Duration DEFAULT_RESPONSE_TIMEOUT = Duration.ofSeconds(5);

    private final PlayerCommandDispatcher dispatcher;
    private final RpcGateway rpc;
    private final PlayerBusinessResponseHub responses;
    private final Duration responseTimeout;
    private final ScheduledExecutorService timeoutScheduler;
    private final boolean ownsTimeoutScheduler;

    public PlayerBusinessCommandGateway(
            PlayerCommandDispatcher dispatcher,
            RpcGateway rpc,
            PlayerBusinessResponseHub responses
    ) {
        this(dispatcher, rpc, responses, DEFAULT_RESPONSE_TIMEOUT);
    }

    public PlayerBusinessCommandGateway(
            PlayerCommandDispatcher dispatcher,
            RpcGateway rpc,
            PlayerBusinessResponseHub responses,
            Duration responseTimeout
    ) {
        this(
                dispatcher,
                rpc,
                responses,
                responseTimeout,
                Executors.newSingleThreadScheduledExecutor(new TimeoutThreadFactory()),
                true
        );
    }

    public PlayerBusinessCommandGateway(
            PlayerCommandDispatcher dispatcher,
            RpcGateway rpc,
            PlayerBusinessResponseHub responses,
            Duration responseTimeout,
            ScheduledExecutorService timeoutScheduler
    ) {
        this(dispatcher, rpc, responses, responseTimeout, timeoutScheduler, false);
    }

    private PlayerBusinessCommandGateway(
            PlayerCommandDispatcher dispatcher,
            RpcGateway rpc,
            PlayerBusinessResponseHub responses,
            Duration responseTimeout,
            ScheduledExecutorService timeoutScheduler,
            boolean ownsTimeoutScheduler
    ) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.rpc = Objects.requireNonNull(rpc, "rpc");
        this.responses = Objects.requireNonNull(responses, "responses");
        this.responseTimeout = Objects.requireNonNull(responseTimeout, "responseTimeout");
        this.timeoutScheduler = Objects.requireNonNull(timeoutScheduler, "timeoutScheduler");
        this.ownsTimeoutScheduler = ownsTimeoutScheduler;
        if (responseTimeout.isNegative() || responseTimeout.isZero()) {
            throw new IllegalArgumentException("responseTimeout must be positive");
        }
    }

    public void submit(PlayerCommand command, RpcCallback<PlayerBusinessResponse> callback) {
        dispatch(command, callback, true);
    }

    public void submitLocalOnly(PlayerCommand command, RpcCallback<PlayerBusinessResponse> callback) {
        dispatch(command, callback, false);
    }

    private void dispatch(
            PlayerCommand command,
            RpcCallback<PlayerBusinessResponse> callback,
            boolean allowRemoteForward
    ) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(callback, "callback");
        PlayerBusinessResponseRegistration registration;
        AtomicReference<ScheduledFuture<?>> timeoutFuture = new AtomicReference<>();
        try {
            registration = responses.expect(command, response -> {
                cancel(timeoutFuture.get());
                callback.success(response);
            });
        } catch (RuntimeException e) {
            callback.failure(e);
            return;
        }
        timeoutFuture.set(scheduleTimeout(command));
        PlayerCommandResult result;
        try {
            result = dispatcher.dispatch(command);
        } catch (RuntimeException e) {
            registration.cancel();
            cancel(timeoutFuture.get());
            callback.failure(e);
            return;
        }
        if (result.status() == PlayerCommandStatus.ACCEPTED || result.waitForExistingResponse()) {
            return;
        }
        registration.cancel();
        cancel(timeoutFuture.get());
        if (result.status() == PlayerCommandStatus.ROUTED_REMOTE && allowRemoteForward) {
            try {
                forwardRemote(command, result, callback);
            } catch (RuntimeException e) {
                callback.failure(e);
            }
            return;
        }
        callback.success(PlayerBusinessResponse.failure(command, new PlayerCommandDispatchException(result)));
    }

    private void forwardRemote(
            PlayerCommand command,
            PlayerCommandResult result,
            RpcCallback<PlayerBusinessResponse> callback
    ) {
        AgentLocation location = result.route()
                .flatMap(route -> route.location())
                .orElseThrow(() -> new IllegalStateException("remote player command has no target location"));
        rpc.call(
                RpcRequest.toService(
                        location.serviceId(),
                        PlayerBusinessRpcOperations.DISPATCH,
                        command,
                        PlayerBusinessResponse.class
                ),
                callback
        );
    }

    @Override
    public void close() {
        if (ownsTimeoutScheduler) {
            timeoutScheduler.shutdownNow();
        }
    }

    private ScheduledFuture<?> scheduleTimeout(PlayerCommand command) {
        return timeoutScheduler.schedule(
                () -> responses.timeout(command, responseTimeout),
                responseTimeout.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    private static void cancel(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }

    private static final class TimeoutThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "common-battle-player-business-timeout");
            thread.setDaemon(true);
            return thread;
        }
    }
}
