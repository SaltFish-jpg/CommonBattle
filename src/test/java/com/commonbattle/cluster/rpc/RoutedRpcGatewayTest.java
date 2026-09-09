package com.commonbattle.cluster.rpc;

import com.commonbattle.actor.rpc.RpcCallback;
import com.commonbattle.actor.rpc.RpcRequest;
import com.commonbattle.cluster.ServiceMetadata;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoutedRpcGatewayTest {
    @Test
    void callAppliesPolicyOptionsBeforeDelegating() {
        RecordingOptionedGateway delegate = new RecordingOptionedGateway();
        RoutedRpcGateway gateway = new RoutedRpcGateway(
                delegate,
                RpcCallOptions.of(Duration.ofSeconds(2)).withIdempotencyKey("idem-1"),
                (request, baseOptions) -> baseOptions.withRequiredTargetMetadata(ServiceMetadata.ROUTE_TAG, "gray")
        );
        RecordingCallback callback = new RecordingCallback();

        gateway.call(new RpcRequest<>("SCENE", "scene.enter", "payload", String.class), callback);

        assertEquals("ok", callback.response.get());
        assertEquals("idem-1", delegate.options.get().idempotencyKey());
        assertEquals("gray", delegate.options.get().requiredTargetMetadata().get(ServiceMetadata.ROUTE_TAG));
        assertEquals(1, gateway.stats().calls());
        assertEquals(1, gateway.stats().routedCalls());
        assertEquals(1, gateway.stats().routeTagCalls().get("gray"));
    }

    @Test
    void statsCountsUntouchedCalls() {
        RecordingOptionedGateway delegate = new RecordingOptionedGateway();
        RoutedRpcGateway gateway = new RoutedRpcGateway(
                delegate,
                RpcCallOptions.of(Duration.ofSeconds(2)),
                RpcRoutePolicy.none()
        );

        gateway.call(new RpcRequest<>("SCENE", "scene.leave", "payload", String.class), new RecordingCallback());

        assertEquals(1, gateway.stats().calls());
        assertEquals(0, gateway.stats().routedCalls());
        assertEquals(1, gateway.stats().unroutedCalls());
    }

    private static final class RecordingOptionedGateway implements OptionedRpcGateway {
        private final AtomicReference<RpcCallOptions> options = new AtomicReference<>();

        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback) {
            call(request, callback, RpcCallOptions.of(Duration.ofSeconds(1)));
        }

        @Override
        public <T> void call(RpcRequest<T> request, RpcCallback<T> callback, RpcCallOptions options) {
            this.options.set(options);
            callback.success(request.responseType().cast("ok"));
        }
    }

    private static final class RecordingCallback implements RpcCallback<String> {
        private final AtomicReference<String> response = new AtomicReference<>();

        @Override
        public void success(String response) {
            this.response.set(response);
        }

        @Override
        public void failure(Throwable error) {
            throw new AssertionError(error);
        }
    }
}
