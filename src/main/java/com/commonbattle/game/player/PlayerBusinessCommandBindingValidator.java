package com.commonbattle.game.player;

import com.commonbattle.game.session.PlayerCommandDispatcher;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 玩家业务命令绑定校验器。
 * 启动阶段使用它确认目录、codec 覆盖和 dispatcher 处理面没有漂移，避免灰度进程遗漏业务操作。
 */
public final class PlayerBusinessCommandBindingValidator {
    private PlayerBusinessCommandBindingValidator() {
    }

    public static void validateExamples(PlayerCommandDispatcher dispatcher) {
        validate(PlayerBusinessCommandCatalog.operations(), dispatcher.registeredOperations());
    }

    public static void validate(List<String> catalogOperations, Set<String> registeredOperations) {
        Objects.requireNonNull(catalogOperations, "catalogOperations");
        Objects.requireNonNull(registeredOperations, "registeredOperations");
        Set<String> expected = new LinkedHashSet<>(catalogOperations);
        Set<String> missing = new LinkedHashSet<>(expected);
        missing.removeAll(registeredOperations);
        Set<String> unexpected = new LinkedHashSet<>(registeredOperations);
        unexpected.removeAll(expected);
        if (!missing.isEmpty() || !unexpected.isEmpty()) {
            throw new IllegalStateException("Player business command binding mismatch, missing="
                    + missing + ", unexpected=" + unexpected);
        }
    }
}
