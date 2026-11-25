package firefly520.fireflymc.validation;

import firefly520.fireflymc.FireflyMCMod;
import firefly520.fireflymc.network.*;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;

/** Only in the opt-in validation mod: deliver a version reply for a mock network client through the real handler. */
public final class ValidationHandshake {
    private ValidationHandshake() {}
    public static void reply(ServerPlayer player) {
        if (!player.server.isSameThread()) throw new IllegalStateException("TEST_MAIN_THREAD_REQUIRED");
        ModPayloadHandler.handleHandshakeReply(new ModHandshakeReplyPayload(FireflyMCMod.VERSION), context(player));
        if (!ModPayloadHandler.VERIFIED_PLAYERS.getOrDefault(player.getUUID(), false)) throw new IllegalStateException("TEST_HANDSHAKE_FAILED");
    }
    /** Long-lived isolated fixture players complete the real existing password/rules flow, without disabling its watchdog. */
    public static void authenticateFixture(ServerPlayer player) {
        if (!player.server.isDedicatedServer() || !player.server.isSameThread()) throw new IllegalStateException("TEST_DEDICATED_MAIN_THREAD_REQUIRED");
        var passwords = firefly520.fireflymc.auth.PlayerPasswordManager.getInstance();
        if (firefly520.fireflymc.ServerConfig.SERVER.playerAuthEnabled.get()) {
            if (!passwords.isPendingVerification(player.getUUID()) || passwords.hasPassword(player)) throw new IllegalStateException("TEST_FRESH_PASSWORD_SESSION_REQUIRED");
            String secret = String.format(java.util.Locale.ROOT, "%019d", java.util.UUID.randomUUID().getLeastSignificantBits() & Long.MAX_VALUE);
            ModPayloadHandler.handlePasswordSubmit(new PasswordSubmitPayload(secret), context(player));
        }
        ModPayloadHandler.handleConfirmRules(new ConfirmRulesPayload(), context(player));
        if (!firefly520.fireflymc.ai.AiServerService.authenticated(player)) throw new IllegalStateException("TEST_REAL_AUTH_FLOW_FAILED");
    }
    private static IPayloadContext context(ServerPlayer player) {
        return (IPayloadContext) Proxy.newProxyInstance(IPayloadContext.class.getClassLoader(), new Class<?>[]{IPayloadContext.class}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "player" -> player;
                case "enqueueWork" -> {
                    try {
                        if (args[0] instanceof Runnable runnable) { runnable.run(); yield CompletableFuture.completedFuture(null); }
                        yield CompletableFuture.completedFuture(((java.util.function.Supplier<?>) args[0]).get());
                    } catch (Throwable error) { yield CompletableFuture.failedFuture(error); }
                }
                case "disconnect" -> { player.connection.disconnect((net.minecraft.network.chat.Component) args[0]); yield null; }
                case "toString" -> "ValidationHandshakeContext";
                default -> throw new UnsupportedOperationException("UNEXPECTED_VALIDATION_CONTEXT_METHOD:" + method.getName());
            };
        });
    }
}
