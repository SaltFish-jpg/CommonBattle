package com.commonbattle.game.player;

import com.commonbattle.cluster.protocol.PayloadCodecRegistry;
import com.commonbattle.game.session.PlayerCommand;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 玩家业务命令协议面校验器。
 * 启动或测试阶段使用它确认目录内命令、统一信封和响应结果都具备显式 protobuf codec。
 */
public final class PlayerBusinessCommandProtocolValidator {
    private PlayerBusinessCommandProtocolValidator() {
    }

    public static void validate(PayloadCodecRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        List<String> missing = new ArrayList<>();
        requireProtobuf(registry, PlayerCommand.class, missing);
        requireProtobuf(registry, PlayerBusinessResponse.class, missing);
        requireProtobuf(registry, PlayerBusinessAck.class, missing);
        for (PlayerBusinessCommandDescriptor descriptor : PlayerBusinessCommandCatalog.descriptors()) {
            requireProtobuf(registry, descriptor.commandType(), missing);
            requireProtobuf(registry, descriptor.responseType(), missing);
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing player business protobuf codecs: " + missing);
        }
    }

    private static void requireProtobuf(PayloadCodecRegistry registry, Class<?> type, List<String> missing) {
        if (!registry.hasProtobufCodec(type) && !missing.contains(type.getName())) {
            missing.add(type.getName());
        }
    }
}
