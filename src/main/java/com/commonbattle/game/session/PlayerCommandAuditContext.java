package com.commonbattle.game.session;

/**
 * 玩家命令邮箱执行期的审计上下文。
 * 业务处理器可在生成回包时写入稳定结果码，分发器在同一条邮箱任务结束时落审计记录。
 */
public final class PlayerCommandAuditContext {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private PlayerCommandAuditContext() {
    }

    public static Scope open() {
        Scope scope = new Scope(CURRENT.get());
        CURRENT.set(scope);
        return scope;
    }

    public static void recordResultCode(String code) {
        Scope scope = CURRENT.get();
        if (scope != null && code != null && !code.isBlank()) {
            scope.resultCode = code;
        }
    }

    public static final class Scope implements AutoCloseable {
        private final Scope previous;
        private String resultCode = "";
        private boolean closed;

        private Scope(Scope previous) {
            this.previous = previous;
        }

        public String resultCode() {
            return resultCode;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
