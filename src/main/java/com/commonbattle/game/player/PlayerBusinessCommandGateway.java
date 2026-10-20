package com.commonbattle.game.player;

import com.commonbattle.actor.agent.AgentLocation;
import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcGateway;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ServiceId;
import com.commonbattle.game.session.PlayerCommand;
import com.commonbattle.game.session.PlayerCommandDispatcher;
import com.commonbattle.game.session.PlayerCommandResult;
import com.commonbattle.game.session.PlayerCommandStatus;

import java.time.Duration;
import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 玩家业务命令统一入口。
 * 本服玩家命令进入本地邮箱；远端玩家命令按 Agent owner 转发到目标 Game 服，调用方始终收到统一响应信封。
 */
public final class PlayerBusinessCommandGateway implements PlayerBusinessCommandGatewayView, AutoCloseable {
    public static final Duration DEFAULT_RESPONSE_TIMEOUT = Duration.ofSeconds(5);

    private final PlayerCommandDispatcher dispatcher;
    private final RpcGateway rpc;
    private final PlayerBusinessResponseHub responses;
    private final Duration responseTimeout;
    private final ScheduledExecutorService timeoutScheduler;
    private final boolean ownsTimeoutScheduler;
    private final PlayerBusinessRedirectHandler redirectHandler;
    private final PlayerBusinessCommandGatewayMetrics metrics = new PlayerBusinessCommandGatewayMetrics();

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
        this(dispatcher, rpc, responses, responseTimeout, timeoutScheduler, PlayerBusinessRedirectHandler.NOOP);
    }

    public PlayerBusinessCommandGateway(
            PlayerCommandDispatcher dispatcher,
            RpcGateway rpc,
            PlayerBusinessResponseHub responses,
            Duration responseTimeout,
            PlayerBusinessRedirectHandler redirectHandler
    ) {
        this(
                dispatcher,
                rpc,
                responses,
                responseTimeout,
                Executors.newSingleThreadScheduledExecutor(new TimeoutThreadFactory()),
                true,
                redirectHandler
        );
    }

    public PlayerBusinessCommandGateway(
            PlayerCommandDispatcher dispatcher,
            RpcGateway rpc,
            PlayerBusinessResponseHub responses,
            Duration responseTimeout,
            ScheduledExecutorService timeoutScheduler,
            PlayerBusinessRedirectHandler redirectHandler
    ) {
        this(dispatcher, rpc, responses, responseTimeout, timeoutScheduler, false, redirectHandler);
    }

    private PlayerBusinessCommandGateway(
            PlayerCommandDispatcher dispatcher,
            RpcGateway rpc,
            PlayerBusinessResponseHub responses,
            Duration responseTimeout,
            ScheduledExecutorService timeoutScheduler,
            boolean ownsTimeoutScheduler
    ) {
        this(dispatcher, rpc, responses, responseTimeout, timeoutScheduler, ownsTimeoutScheduler,
                PlayerBusinessRedirectHandler.NOOP);
    }

    private PlayerBusinessCommandGateway(
            PlayerCommandDispatcher dispatcher,
            RpcGateway rpc,
            PlayerBusinessResponseHub responses,
            Duration responseTimeout,
            ScheduledExecutorService timeoutScheduler,
            boolean ownsTimeoutScheduler,
            PlayerBusinessRedirectHandler redirectHandler
    ) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.rpc = Objects.requireNonNull(rpc, "rpc");
        this.responses = Objects.requireNonNull(responses, "responses");
        this.responseTimeout = Objects.requireNonNull(responseTimeout, "responseTimeout");
        this.timeoutScheduler = Objects.requireNonNull(timeoutScheduler, "timeoutScheduler");
        this.ownsTimeoutScheduler = ownsTimeoutScheduler;
        this.redirectHandler = Objects.requireNonNull(redirectHandler, "redirectHandler");
        if (responseTimeout.isNegative() || responseTimeout.isZero()) {
            throw new IllegalArgumentException("responseTimeout must be positive");
        }
    }

    public void submit(PlayerCommand command, RpcCallback<PlayerBusinessResponse> callback) {
        Objects.requireNonNull(callback, "callback");
        submitDetailed(command, new PlayerBusinessResponseCallback() {
            @Override
            public void completed(PlayerBusinessResponse response) {
                callback.success(response);
            }
        });
    }

    public void submitLocalOnly(PlayerCommand command, RpcCallback<PlayerBusinessResponse> callback) {
        Objects.requireNonNull(callback, "callback");
        dispatch(command, new PlayerBusinessResponseCallback() {
            @Override
            public void completed(PlayerBusinessResponse response) {
                callback.success(response);
            }
        }, false);
    }

    public void submitDetailed(PlayerCommand command, PlayerBusinessResponseCallback callback) {
        dispatch(command, callback, true);
    }

    public Optional<PlayerBusinessResponse> cachedResponse(PlayerBusinessResponseQuery query) {
        return responses.cachedResponse(query);
    }

    private void dispatch(
            PlayerCommand command,
            PlayerBusinessResponseCallback callback,
            boolean allowRemoteForward
    ) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(callback, "callback");
        PlayerBusinessResponseRegistration registration;
        AtomicReference<ScheduledFuture<?>> timeoutFuture = new AtomicReference<>();
        AtomicBoolean completedSynchronously = new AtomicBoolean();
        try {
            registration = responses.expect(command, new PlayerBusinessResponseCallback() {
                @Override
                public void completed(PlayerBusinessResponse response) {
                    completed(response, false);
                }

                @Override
                public void completed(PlayerBusinessResponse response, boolean replayed) {
                    completedSynchronously.set(true);
                    cancel(timeoutFuture.get());
                    callback.completed(response, replayed);
                }
            });
        } catch (RuntimeException e) {
            callback.completed(PlayerBusinessResponse.failure(command, e));
            return;
        }
        if (completedSynchronously.get()) {
            return;
        }
        timeoutFuture.set(scheduleTimeout(command));
        PlayerCommandResult result;
        try {
            result = dispatcher.dispatch(command);
        } catch (RuntimeException e) {
            registration.cancel();
            cancel(timeoutFuture.get());
            callback.completed(PlayerBusinessResponse.failure(command, e));
            return;
        }
        if (result.status() == PlayerCommandStatus.ACCEPTED || result.waitForExistingResponse()) {
            return;
        }
        cancel(timeoutFuture.get());
        if (result.status() == PlayerCommandStatus.ROUTED_REMOTE && allowRemoteForward) {
            try {
                forwardRemote(command, result);
            } catch (RuntimeException e) {
                dispatcher.cancelRemote(command);
                responses.completedUncached(command, PlayerBusinessResponse.failure(command, e));
            }
            return;
        }
        registration.cancel();
        callback.completed(PlayerBusinessResponse.failure(command, new PlayerCommandDispatchException(result)));
    }

    private void forwardRemote(
            PlayerCommand command,
            PlayerCommandResult result
    ) {
        metrics.remoteForward();
        AgentLocation location = result.route()
                .flatMap(route -> route.location())
                .orElseThrow(() -> new IllegalStateException("remote player command has no target location"));
        AtomicReference<AgentLocation> currentOwner = new AtomicReference<>(location);
        AtomicBoolean completed = new AtomicBoolean();
        ScheduledFuture<?> timeoutFuture = scheduleRemoteForwardTimeout(command, currentOwner, completed);
        callRemote(command, location, currentOwner, completed, timeoutFuture, false);
    }

    private void callRemote(
            PlayerCommand command,
            AgentLocation location,
            AtomicReference<AgentLocation> currentOwner,
            AtomicBoolean completed,
            ScheduledFuture<?> timeoutFuture,
            boolean redirected
    ) {
        try {
            rpc.call(
                    RpcRequest.toService(
                            location.serviceId(),
                            PlayerBusinessRpcOperations.DISPATCH,
                            command,
                            PlayerBusinessResponse.class
                    ),
                    new RpcCallback<>() {
                        @Override
                        public void success(PlayerBusinessResponse response) {
                            if (!redirected && tryRedirect(command, location, response, currentOwner, completed,
                                    timeoutFuture)) {
                                return;
                            }
                            completeRemoteForward(
                                    completed,
                                    timeoutFuture,
                                    () -> recordRemoteForwardSuccessResponse(response),
                                    metrics::lateRemoteForwardResponse,
                                    () -> completeRemoteResponse(command, response)
                            );
                        }

                        @Override
                        public void failure(Throwable error) {
                            completeRemoteForward(
                                    completed,
                                    timeoutFuture,
                                    metrics::remoteForwardFailureResponse,
                                    metrics::lateRemoteForwardResponse,
                                    () -> failRemoteResponse(command, PlayerBusinessResponse.failure(command, error))
                            );
                        }
                    }
            );
        } catch (RuntimeException e) {
            completeRemoteForward(
                    completed,
                    timeoutFuture,
                    metrics::remoteForwardFailureResponse,
                    metrics::lateRemoteForwardResponse,
                    () -> failRemoteResponse(command, PlayerBusinessResponse.failure(command, e))
            );
        }
    }

    private boolean tryRedirect(
            PlayerCommand command,
            AgentLocation previousOwner,
            PlayerBusinessResponse response,
            AtomicReference<AgentLocation> currentOwner,
            AtomicBoolean completed,
            ScheduledFuture<?> timeoutFuture
    ) {
        if (completed.get()) {
            metrics.lateRemoteForwardResponse();
            return true;
        }
        if (!(response.payload() instanceof PlayerBusinessRedirect redirect)) {
            return false;
        }
        metrics.remoteForwardRedirectResponse();
        metrics.remoteForwardMigrationProtectedResponse();
        metrics.remoteForwardRedirectRetry();
        AgentLocation next = new AgentLocation(ServiceId.parse(redirect.targetService()),
                new com.commonbattle.actor.ActorRef(redirect.targetActor()));
        try {
            if (redirectHandler.redirected(command, previousOwner, redirect)) {
                metrics.remoteForwardRedirectRouteRefresh();
            }
        } catch (RuntimeException e) {
            metrics.remoteForwardRedirectRouteRefreshFailure();
        }
        currentOwner.set(next);
        callRemote(command, next, currentOwner, completed, timeoutFuture, true);
        return true;
    }

    @Override
    public PlayerBusinessCommandGatewayStats commandGatewayStats() {
        return metrics.snapshot();
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

    private ScheduledFuture<?> scheduleRemoteForwardTimeout(
            PlayerCommand command,
            AtomicReference<AgentLocation> currentOwner,
            AtomicBoolean completed
    ) {
        return timeoutScheduler.schedule(
                () -> recoverRemoteForwardTimeout(command, currentOwner.get(), completed),
                responseTimeout.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    private void recoverRemoteForwardTimeout(
            PlayerCommand command,
            AgentLocation owner,
            AtomicBoolean completed
    ) {
        if (completed.get()) {
            metrics.lateRemoteForwardResponse();
            return;
        }
        metrics.remoteForwardTimeoutRecoveryQuery();
        AtomicBoolean recoveryCompleted = new AtomicBoolean();
        ScheduledFuture<?> recoveryTimeout = timeoutScheduler.schedule(
                () -> {
                    if (recoveryCompleted.compareAndSet(false, true)) {
                        metrics.remoteForwardTimeoutRecoveryFailure();
                        completeRemoteTimeout(command, completed);
                    }
                },
                responseTimeout.toMillis(),
                TimeUnit.MILLISECONDS
        );
        try {
            rpc.call(
                    RpcRequest.toService(
                            owner.serviceId(),
                            PlayerBusinessRpcOperations.GET_RESULT,
                            PlayerBusinessResponseQuery.from(command),
                            PlayerBusinessResponse.class
                    ),
                    new RpcCallback<>() {
                        @Override
                        public void success(PlayerBusinessResponse response) {
                            if (!recoveryCompleted.compareAndSet(false, true)) {
                                metrics.lateRemoteForwardResponse();
                                return;
                            }
                            cancel(recoveryTimeout);
                            if (PlayerBusinessResponse.RESULT_NOT_FOUND.equals(response.code())) {
                                metrics.remoteForwardTimeoutRecoveryMiss();
                                completeRemoteTimeout(command, completed);
                                return;
                            }
                            metrics.remoteForwardTimeoutRecoveryHit();
                            completeRemoteForward(
                                    completed,
                                    null,
                                    () -> recordRemoteForwardSuccessResponse(response),
                                    metrics::lateRemoteForwardResponse,
                                    () -> completeRemoteResponse(command, response)
                            );
                        }

                        @Override
                        public void failure(Throwable error) {
                            if (!recoveryCompleted.compareAndSet(false, true)) {
                                metrics.lateRemoteForwardResponse();
                                return;
                            }
                            cancel(recoveryTimeout);
                            metrics.remoteForwardTimeoutRecoveryFailure();
                            completeRemoteTimeout(command, completed);
                        }
                    }
            );
        } catch (RuntimeException e) {
            if (recoveryCompleted.compareAndSet(false, true)) {
                cancel(recoveryTimeout);
                metrics.remoteForwardTimeoutRecoveryFailure();
                completeRemoteTimeout(command, completed);
            }
        }
    }

    private void completeRemoteTimeout(PlayerCommand command, AtomicBoolean completed) {
        if (!completed.compareAndSet(false, true)) {
            metrics.lateRemoteForwardResponse();
            return;
        }
        metrics.remoteForwardTimedOutResponse();
        dispatcher.cancelRemote(command);
        responses.completedUncached(command, PlayerBusinessResponse.failure(
                command,
                new PlayerBusinessResponseTimeoutException(command, responseTimeout)
        ));
    }

    private static void completeRemoteForward(
            AtomicBoolean completed,
            ScheduledFuture<?> timeoutFuture,
            Runnable completedMetric,
            Runnable lateMetric,
            Runnable action
    ) {
        if (!completed.compareAndSet(false, true)) {
            lateMetric.run();
            return;
        }
        completedMetric.run();
        cancel(timeoutFuture);
        action.run();
    }

    private static void cancel(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }

    private void recordRemoteForwardSuccessResponse(PlayerBusinessResponse response) {
        metrics.remoteForwardSuccessResponse();
        if (migrationProtected(response)) {
            metrics.remoteForwardMigrationProtectedResponse();
        }
        if (redirected(response)) {
            metrics.remoteForwardRedirectResponse();
        }
    }

    private void completeRemoteResponse(PlayerCommand command, PlayerBusinessResponse response) {
        if (remoteOwnerAccepted(response)) {
            dispatcher.completeRemote(command, PlayerCommandStatus.ACCEPTED);
            responses.completed(command, response);
            return;
        }
        failRemoteResponse(command, response);
    }

    private void failRemoteResponse(PlayerCommand command, PlayerBusinessResponse response) {
        dispatcher.cancelRemote(command);
        responses.completedUncached(command, response);
    }

    private static boolean remoteOwnerAccepted(PlayerBusinessResponse response) {
        if (response.status() == PlayerBusinessResponseStatus.SUCCESS) {
            return true;
        }
        try {
            PlayerCommandStatus.valueOf(response.code());
            return false;
        } catch (IllegalArgumentException ignored) {
            return true;
        }
    }

    private static boolean migrationProtected(PlayerBusinessResponse response) {
        if (response.status() != PlayerBusinessResponseStatus.FAILED) {
            return false;
        }
        return PlayerCommandStatus.AGENT_MIGRATING.name().equals(response.code())
                || PlayerCommandStatus.ROUTED_REMOTE.name().equals(response.code());
    }

    private static boolean redirected(PlayerBusinessResponse response) {
        return response.payload() instanceof PlayerBusinessRedirect;
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
