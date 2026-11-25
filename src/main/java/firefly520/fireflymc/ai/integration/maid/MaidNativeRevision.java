package firefly520.fireflymc.ai.integration.maid;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.TamableAnimal;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Detects native control changes and human takeovers, including changes subsequently reversed. */
public final class MaidNativeRevision {
    public interface Carrier {
        long fireflymc$maidRevision();
        void fireflymc$markMaidControl();
    }
    private static final ConcurrentHashMap<String, Method> GETTERS = new ConcurrentHashMap<>();
    private MaidNativeRevision() {}
    public static long revision(Object entity) {
        if (!(entity instanceof Carrier marker)) throw new IllegalStateException("MAID_CONTROL_REVISION_HOOK_UNAVAILABLE");
        long revision = marker.fireflymc$maidRevision();
        if (revision == Long.MAX_VALUE) throw new IllegalStateException("MAID_CONTROL_REVISION_EXHAUSTED");
        return revision;
    }
    /** Native setters keep normal behavior; only actual field changes count here. */
    public static void changed(Object entity, String getter, Object next) {
        if (!(entity instanceof Carrier marker)) return;
        try {
            String key = entity.getClass().getName() + "#" + getter;
            Method method = GETTERS.get(key);
            if (method == null) { method = entity.getClass().getMethod(getter); GETTERS.put(key, method); }
            if (!Objects.equals(method.invoke(entity), next)) marker.fireflymc$markMaidControl();
        } catch (ReflectiveOperationException | RuntimeException unavailable) { marker.fireflymc$markMaidControl(); }
    }
    /** Runs inside the original queued native server handler, not on its network thread. */
    public static void manualPacket(IPayloadContext context, Object message) {
        if (!(context.player() instanceof ServerPlayer player) || !player.server.isDedicatedServer() || !player.server.isSameThread()) return;
        try {
            String accessor = message.getClass().getName().equals("com.github.tartaricacid.touhoulittlemaid.network.message.OpenMaidGuiPackage") ? "entityId" : "id";
            int id = (int) message.getClass().getMethod(accessor).invoke(message);
            if (player.serverLevel().getEntity(id) instanceof TamableAnimal maid && maid instanceof Carrier marker
                    && maid.isTame() && maid.isOwnedBy(player) && player.getUUID().equals(maid.getOwnerUUID())) marker.fireflymc$markMaidControl();
        } catch (ReflectiveOperationException | RuntimeException ignored) { /* Original native handling is unchanged. */ }
    }
    public static void manualInteraction(Object event) {
        try {
            Object playerValue = event.getClass().getMethod("getPlayer").invoke(event);
            if (!(playerValue instanceof ServerPlayer player) || !player.server.isDedicatedServer() || !player.server.isSameThread()) return;
            Object target = event.getClass().getMethod("getMaid").invoke(event);
            if (target instanceof TamableAnimal maid && maid instanceof Carrier marker && maid.isTame() && maid.isOwnedBy(player)
                    && player.getUUID().equals(maid.getOwnerUUID())) marker.fireflymc$markMaidControl();
        } catch (ReflectiveOperationException | RuntimeException ignored) { /* Original native interaction is unchanged. */ }
    }
}
