package firefly520.fireflymc.ai.blueprint;

import java.lang.reflect.*;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;

/** Prevents an owner's held Ultimine key from expanding a separately approved native operation. */
public final class NativeChainScope implements AutoCloseable {
    private static final ThreadLocal<ServerPlayer> ACTIVE = new ThreadLocal<>();
    private static boolean registered;
    private final ServerPlayer owner;

    private NativeChainScope(ServerPlayer owner) {
        if (!owner.server.isSameThread() || ACTIVE.get() != null) throw new IllegalStateException("NATIVE_CHAIN_SCOPE_INVALID");
        install(); this.owner = owner; ACTIVE.set(owner);
    }
    public static NativeChainScope begin(ServerPlayer owner) { return new NativeChainScope(owner); }
    public static boolean allowsExpansion(Object player) { return ACTIVE.get() != player; }

    /** The public registry accepts late registrations; one handler survives server restarts in this JVM. */
    private static synchronized void install() {
        if (registered || !ModList.get().isLoaded("ftbultimine")) return;
        String version = ModList.get().getModContainerById("ftbultimine").orElseThrow().getModInfo().getVersion().toString();
        if (!version.equals("2101.1.15")) throw new IllegalStateException("ULTIMINE_VERSION_UNSUPPORTED");
        try {
            Class<?> contract = Class.forName("dev.ftb.mods.ftbultimine.api.restriction.RestrictionHandler");
            Class<?> registry = Class.forName("dev.ftb.mods.ftbultimine.RestrictionHandlerRegistry");
            Object handler = Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                    case "equals" -> proxy == args[0]; case "hashCode" -> System.identityHashCode(proxy); default -> "FireflyMC approved native operation scope";
                };
                if (method.getName().equals("canUltimine")) return allowsExpansion(args[0]);
                if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
                throw new IllegalStateException("ULTIMINE_RESTRICTION_CONTRACT_CHANGED");
            });
            registry.getMethod("register", contract).invoke(registry.getField("INSTANCE").get(null), handler);
            registered = true;
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new IllegalStateException("ULTIMINE_RESTRICTION_UNAVAILABLE", failure);
        }
    }
    @Override public void close() { if (ACTIVE.get() == owner) ACTIVE.remove(); }
}
