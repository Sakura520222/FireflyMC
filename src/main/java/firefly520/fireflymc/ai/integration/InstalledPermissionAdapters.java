package firefly520.fireflymc.ai.integration;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.fml.ModList;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Optional permission checks against the installed, statically inspected versions.
 * An allowed result is one permission layer, not approval to consume items or modify the world.
 * Call again on the server thread immediately before each operation; no allow decision is cached.
 */
public final class InstalledPermissionAdapters {
    public static final int PROTOCOL_VERSION = 1;
    public static final String OPAC_ID = "openpartiesandclaims";
    public static final String RANKS_ID = "ftbranks";
    public static final String OPAC_VERSION = "0.28.1";
    public static final String RANKS_VERSION = "2101.1.3";
    private static final String OPAC_EVIDENCE =
            "ev_463c8ad10ec22ec784b7b71ab2f403856be5352b5bc5cedafebe6c0459102a6e";
    private static final String RANKS_EVIDENCE =
            "ev_19b5c2dce3c16e6d796401569210d5ee8b9ba850d8aa5d2a825ad81ebb151d2b";
    private static final Pattern NODE = Pattern.compile("fireflymc\\.ai(?:\\.[a-z0-9_]+)*");

    /** RUNTIME_CALLABLE records an invocation, never completed gameplay acceptance. */
    public enum State { DISCOVERED, ABSENT, STATIC_API_MATCH, RUNTIME_CALLABLE, UNSUPPORTED_VERSION, FAILED }
    public enum Origin { VANILLA_BASELINE, MISSING_NODE, CONFIGURED, PROTECTION_API, ERROR }
    /** evidenceId identifies the inspected archive; it is not live server compatibility evidence. */
    public record AdapterStatus(String modId, String installedVersion, String inspectedVersion,
                                State state, String reason, String evidenceId,
                                int protocolVersion, boolean behaviorVerified) {
        public boolean callable() {
            return state == State.STATIC_API_MATCH || state == State.RUNTIME_CALLABLE;
        }
    }
    public record Verdict(boolean allowed, String code, Origin origin, AdapterStatus adapter) {}
    public record QuotaVerdict(boolean allowed, long value, String code, Origin origin, AdapterStatus adapter) {}
    public record Snapshot(AdapterStatus protection, AdapterStatus ranks) {}

    enum Action { PLACE, BREAK, CONTAINER, TRAVEL_DESTINATION }
    record WorldRequest(Action action, ServerPlayer player, ServerLevel level, BlockPos pos, Direction side) {}
    record PermissionReading(boolean missing, Boolean booleanValue, Number numberValue) {}

    /** Package-private injection seam; production always supplies MinecraftRuntime. */
    interface RuntimeAccess {
        String serverGuard();
        String actorGuard(ServerPlayer player, ServerLevel level, BlockPos pos, boolean worldOperation);
        default String travelDestinationGuard(ServerPlayer player, ServerLevel level, BlockPos pos) {
            return actorGuard(player, level, pos, true);
        }
        String installedVersion(String modId);
        ProtectionCalls bindProtection() throws ReflectiveOperationException;
        RankCalls bindRanks() throws ReflectiveOperationException;
    }
    interface ProtectionCalls {
        boolean administrativeMode(ServerPlayer player) throws ReflectiveOperationException;
        boolean denies(WorldRequest request) throws ReflectiveOperationException;
    }
    interface RankCalls {
        PermissionReading read(ServerPlayer player, String node) throws ReflectiveOperationException;
    }

    private final RuntimeAccess runtime;
    private volatile AdapterStatus protection = initial(OPAC_ID);
    private volatile AdapterStatus ranks = initial(RANKS_ID);
    private ProtectionCalls protectionCalls;
    private RankCalls rankCalls;

    public InstalledPermissionAdapters(MinecraftServer server) {
        this(new MinecraftRuntime(Objects.requireNonNull(server)));
    }

    InstalledPermissionAdapters(RuntimeAccess runtime) {
        this.runtime = Objects.requireNonNull(runtime);
    }

    /** Safe to display without touching Minecraft state or invoking optional mods. */
    public Snapshot snapshot() { return new Snapshot(protection, ranks); }

    /** Probe on the dedicated server thread after mods have initialized. */
    public Snapshot probe() {
        String blocked = runtime.serverGuard();
        if (blocked != null) throw new IllegalStateException(blocked);
        ensureProtection();
        ensureRanks();
        return snapshot();
    }

    public Verdict checkPlace(ServerPlayer player, ServerLevel level, BlockPos pos) {
        return checkWorld(new WorldRequest(Action.PLACE, player, level, pos, Direction.UP));
    }

    public Verdict checkBreak(ServerPlayer player, ServerLevel level, BlockPos pos, Direction face) {
        return checkWorld(new WorldRequest(Action.BREAK, player, level, pos, face));
    }

    /** Empty-hand container access; the caller must separately own an explicit source authorization. */
    public Verdict checkContainer(ServerPlayer player, ServerLevel level, BlockPos pos, Direction side) {
        return checkWorld(new WorldRequest(Action.CONTAINER, player, level, pos, side));
    }

    /** Protection query only: the real player stays in the source dimension until the native travel succeeds. */
    public Verdict checkTravelDestination(ServerPlayer player, ServerLevel level, BlockPos pos) {
        return checkWorld(new WorldRequest(Action.TRAVEL_DESTINATION, player, level, pos, Direction.UP));
    }

    private Verdict checkWorld(WorldRequest request) {
        String blocked = runtime.serverGuard();
        if (blocked == null) blocked = request.action() == Action.TRAVEL_DESTINATION
                ? runtime.travelDestinationGuard(request.player(), request.level(), request.pos())
                : runtime.actorGuard(request.player(), request.level(), request.pos(), true);
        if (blocked != null) return deny(blocked, protection);
        if (request.side() == null) return deny("INTERACTION_SIDE_REQUIRED", protection);
        ensureProtection();
        if (protection.state() == State.ABSENT)
            return new Verdict(true, "VANILLA_BASELINE", Origin.VANILLA_BASELINE, protection);
        if (!protection.callable()) return deny(protection.reason(), protection);
        try {
            if (protectionCalls.administrativeMode(request.player())) {
                return new Verdict(false, "CLAIMS_ADMIN_MODE_ACTIVE", Origin.PROTECTION_API, protection);
            }
            boolean denied = protectionCalls.denies(request);
            protection = called(protection);
            return new Verdict(!denied, denied ? "CLAIM_DENIED" : "CLAIM_ALLOWED",
                    Origin.PROTECTION_API, protection);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            protection = failed(protection, "PROTECTION_CALL_FAILED");
            return deny(protection.reason(), protection);
        }
    }

    /**
     * Missing values use only this explicit baseline. The caller must not derive it from OP level.
     * A configured false, wrong value type, or failed installed adapter always denies.
     */
    public Verdict checkBooleanPermission(ServerPlayer player, String node, boolean vanillaFallback) {
        String blocked = guard(player, null, null, false);
        if (blocked != null) return deny(blocked, ranks);
        if (!validNode(node)) return deny("PERMISSION_NODE_INVALID", ranks);
        ensureRanks();
        if (ranks.state() == State.ABSENT)
            return new Verdict(vanillaFallback, "VANILLA_BASELINE", Origin.VANILLA_BASELINE, ranks);
        if (!ranks.callable()) return deny(ranks.reason(), ranks);
        try {
            PermissionReading value = Objects.requireNonNull(rankCalls.read(player, node));
            ranks = called(ranks);
            if (value.missing())
                return new Verdict(vanillaFallback, "PERMISSION_MISSING", Origin.MISSING_NODE, ranks);
            if (value.booleanValue() == null) return deny("PERMISSION_TYPE_INVALID", ranks);
            return new Verdict(value.booleanValue(),
                    value.booleanValue() ? "PERMISSION_ALLOWED" : "PERMISSION_DENIED", Origin.CONFIGURED, ranks);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            ranks = failed(ranks, "PERMISSION_CALL_FAILED");
            return deny(ranks.reason(), ranks);
        }
    }

    public Verdict checkBooleanPermission(ServerPlayer player, String node) {
        return checkBooleanPermission(player, node, false);
    }

    /** Zero is a valid quota. Check both allowed() and the remaining amount before doing work. */
    public QuotaVerdict checkQuotaPermission(ServerPlayer player, String node, long fallback, long serverMaximum) {
        String blocked = guard(player, null, null, false);
        if (blocked != null) return quotaDenied(blocked);
        if (!validNode(node)) return quotaDenied("PERMISSION_NODE_INVALID");
        if (fallback < 0 || serverMaximum < 0) return quotaDenied("QUOTA_POLICY_INVALID");
        ensureRanks();
        long baseline = Math.min(fallback, serverMaximum);
        if (ranks.state() == State.ABSENT)
            return new QuotaVerdict(true, baseline, "VANILLA_BASELINE", Origin.VANILLA_BASELINE, ranks);
        if (!ranks.callable()) return quotaDenied(ranks.reason());
        try {
            PermissionReading value = Objects.requireNonNull(rankCalls.read(player, node));
            ranks = called(ranks);
            if (value.missing())
                return new QuotaVerdict(true, baseline, "PERMISSION_MISSING", Origin.MISSING_NODE, ranks);
            Long amount = exactNonnegativeInteger(value.numberValue());
            if (amount == null) return quotaDenied("QUOTA_TYPE_INVALID");
            return new QuotaVerdict(true, Math.min(amount, serverMaximum), "QUOTA_CONFIGURED", Origin.CONFIGURED, ranks);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            ranks = failed(ranks, "PERMISSION_CALL_FAILED");
            return quotaDenied(ranks.reason());
        }
    }

    private String guard(ServerPlayer player, ServerLevel level, BlockPos pos, boolean worldOperation) {
        String blocked = runtime.serverGuard();
        return blocked == null ? runtime.actorGuard(player, level, pos, worldOperation) : blocked;
    }

    private void ensureProtection() {
        if (protection.state() != State.DISCOVERED) return;
        try {
            protection = versionStatus(OPAC_ID, runtime.installedVersion(OPAC_ID));
            if (protection.state() != State.STATIC_API_MATCH) return;
            protectionCalls = Objects.requireNonNull(runtime.bindProtection());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            protection = failed(protection, "PROTECTION_SIGNATURE_MISMATCH");
        }
    }

    private void ensureRanks() {
        if (ranks.state() != State.DISCOVERED) return;
        try {
            ranks = versionStatus(RANKS_ID, runtime.installedVersion(RANKS_ID));
            if (ranks.state() != State.STATIC_API_MATCH) return;
            rankCalls = Objects.requireNonNull(runtime.bindRanks());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            ranks = failed(ranks, "PERMISSION_SIGNATURE_MISMATCH");
        }
    }

    private static AdapterStatus initial(String id) {
        return status(id, "", State.DISCOVERED, "NOT_PROBED");
    }

    private static AdapterStatus versionStatus(String id, String version) {
        if (version == null) return status(id, "", State.ABSENT, "MOD_ABSENT");
        String expected = id.equals(OPAC_ID) ? OPAC_VERSION : RANKS_VERSION;
        return expected.equals(version)
                ? status(id, version, State.STATIC_API_MATCH, "STATIC_API_MATCH")
                : status(id, version, State.UNSUPPORTED_VERSION, "UNSUPPORTED_VERSION");
    }

    private static AdapterStatus status(String id, String version, State state, String reason) {
        return new AdapterStatus(id, version, id.equals(OPAC_ID) ? OPAC_VERSION : RANKS_VERSION,
                state, reason, id.equals(OPAC_ID) ? OPAC_EVIDENCE : RANKS_EVIDENCE,
                PROTOCOL_VERSION, false);
    }

    private static AdapterStatus called(AdapterStatus status) {
        return status(status.modId(), status.installedVersion(), State.RUNTIME_CALLABLE, "CALL_SUCCEEDED_UNVERIFIED");
    }

    private static AdapterStatus failed(AdapterStatus status, String reason) {
        return status(status.modId(), status.installedVersion(), State.FAILED, reason);
    }

    private static Verdict deny(String code, AdapterStatus status) {
        return new Verdict(false, code, Origin.ERROR, status);
    }

    private QuotaVerdict quotaDenied(String code) {
        return new QuotaVerdict(false, 0, code, Origin.ERROR, ranks);
    }

    private static boolean validNode(String node) {
        return node != null && node.length() <= 128 && NODE.matcher(node).matches();
    }

    private static Long exactNonnegativeInteger(Number number) {
        if (number == null) return null;
        try {
            long value;
            if (number instanceof BigInteger integer) value = integer.longValueExact();
            else if (number instanceof BigDecimal decimal) value = decimal.longValueExact();
            else if (number instanceof Byte || number instanceof Short || number instanceof Integer || number instanceof Long)
                value = number.longValue();
            else if (number instanceof Double || number instanceof Float) {
                if (!Double.isFinite(number.doubleValue())) return null;
                value = new BigDecimal(number.toString()).longValueExact();
            } else return null;
            return value < 0 ? null : value;
        } catch (ArithmeticException | NumberFormatException invalid) {
            return null;
        }
    }

    private static final class MinecraftRuntime implements RuntimeAccess {
        private final MinecraftServer server;
        private MinecraftRuntime(MinecraftServer server) { this.server = server; }

        @Override public String serverGuard() {
            if (!server.isDedicatedServer()) return "AI_DEDICATED_SERVER_REQUIRED";
            return server.isSameThread() ? null : "SERVER_THREAD_REQUIRED";
        }

        @Override public String actorGuard(ServerPlayer player, ServerLevel level, BlockPos pos, boolean worldOperation) {
            if (player == null || player.server != server || player.isRemoved()
                    || server.getPlayerList().getPlayer(player.getUUID()) != player) return "PLAYER_NOT_ACTIVE";
            if (!worldOperation) return null;
            if (level == null || pos == null || level.getServer() != server || player.serverLevel() != level)
                return "WORLD_CONTEXT_MISMATCH";
            if (player.gameMode.getGameModeForPlayer() != GameType.SURVIVAL) return "SURVIVAL_REQUIRED";
            if (level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos))
                return "OUTSIDE_WORLD_BOUNDS";
            if (!level.hasChunkAt(pos)) return "WORLD_UNLOADED";
            if (!level.mayInteract(player, pos)) return "VANILLA_INTERACTION_DENIED";
            return null;
        }

        @Override public String travelDestinationGuard(ServerPlayer player, ServerLevel level, BlockPos pos) {
            String identity = actorGuard(player, null, null, false);
            if (identity != null) return identity;
            if (!player.isAlive()) return "PLAYER_NOT_ACTIVE";
            if (level == null || pos == null || level.getServer() != server || server.getLevel(level.dimension()) != level)
                return "WORLD_CONTEXT_MISMATCH";
            if (player.gameMode.getGameModeForPlayer() != GameType.SURVIVAL) return "SURVIVAL_REQUIRED";
            if (level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos)) return "OUTSIDE_WORLD_BOUNDS";
            if (!level.hasChunkAt(pos)) return "WORLD_UNLOADED";
            return level.mayInteract(player, pos) ? null : "VANILLA_INTERACTION_DENIED";
        }

        @Override public String installedVersion(String id) {
            return ModList.get().getModContainerById(id)
                    .map(mod -> mod.getModInfo().getVersion().toString()).orElse(null);
        }

        @Override public ProtectionCalls bindProtection() throws ReflectiveOperationException {
            Class<?> api = optionalClass("xaero.pac.common.server.api.OpenPACServerAPI");
            Class<?> protectionApi = optionalClass("xaero.pac.common.server.claims.protection.api.IChunkProtectionAPI");
            Class<?> playerDataApi = optionalClass("xaero.pac.common.server.player.data.api.ServerPlayerDataAPI");
            Method get = signature(api, "get", true, api, MinecraftServer.class);
            Method getProtection = signature(api, "getChunkProtection", false, protectionApi);
            Method place = signature(protectionApi, "onEntityPlaceBlock", false, boolean.class,
                    Entity.class, ServerLevel.class, BlockPos.class);
            Method interact = signature(protectionApi, "onBlockInteraction", false, boolean.class,
                    Entity.class, InteractionHand.class, ItemStack.class, ServerLevel.class,
                    BlockPos.class, Direction.class, boolean.class, boolean.class);
            Method from = signature(playerDataApi, "from", true, playerDataApi, ServerPlayer.class);
            Method adminMode = signature(playerDataApi, "isClaimsAdminMode", false, boolean.class);
            Method serverMode = signature(playerDataApi, "isClaimsServerMode", false, boolean.class);
            return new ProtectionCalls() {
                @Override public boolean administrativeMode(ServerPlayer player) throws ReflectiveOperationException {
                    Object data = Objects.requireNonNull(invoke(from, null, player));
                    return bool(invoke(adminMode, data)) || bool(invoke(serverMode, data));
                }

                @Override public boolean denies(WorldRequest request) throws ReflectiveOperationException {
                    Object apiInstance = Objects.requireNonNull(invoke(get, null, server));
                    Object checker = Objects.requireNonNull(invoke(getProtection, apiInstance));
                    if (request.action() == Action.PLACE)
                        return bool(invoke(place, checker, request.player(), request.level(), request.pos()));
                    Object item = request.action() == Action.CONTAINER || request.action() == Action.TRAVEL_DESTINATION ? ItemStack.EMPTY : null;
                    return bool(invoke(interact, checker, request.player(), InteractionHand.MAIN_HAND, item,
                            request.level(), request.pos(), request.side(), request.action() == Action.BREAK, false));
                }
            };
        }

        @Override public RankCalls bindRanks() throws ReflectiveOperationException {
            Class<?> api = optionalClass("dev.ftb.mods.ftbranks.api.FTBRanksAPI");
            Class<?> valueApi = optionalClass("dev.ftb.mods.ftbranks.api.PermissionValue");
            Method read = signature(api, "getPermissionValue", true, valueApi, ServerPlayer.class, String.class);
            Method empty = signature(valueApi, "isEmpty", false, boolean.class);
            Method asBoolean = signature(valueApi, "asBoolean", false, Optional.class);
            Method asNumber = signature(valueApi, "asNumber", false, Optional.class);
            return (player, node) -> {
                Object value = Objects.requireNonNull(invoke(read, null, player, node));
                if (bool(invoke(empty, value))) return new PermissionReading(true, null, null);
                Object booleanValue = optional(invoke(asBoolean, value)).orElse(null);
                Object numberValue = optional(invoke(asNumber, value)).orElse(null);
                if (booleanValue != null && !(booleanValue instanceof Boolean)
                        || numberValue != null && !(numberValue instanceof Number))
                    throw new IllegalStateException("PERMISSION_VALUE_SHAPE");
                return new PermissionReading(false, (Boolean) booleanValue, (Number) numberValue);
            };
        }
    }

    private static Class<?> optionalClass(String name) throws ClassNotFoundException {
        return Class.forName(name, false, InstalledPermissionAdapters.class.getClassLoader());
    }

    private static Method signature(Class<?> owner, String name, boolean isStatic, Class<?> returnType,
                                    Class<?>... arguments) throws NoSuchMethodException {
        Method method = owner.getMethod(name, arguments);
        if (method.getReturnType() != returnType || Modifier.isStatic(method.getModifiers()) != isStatic)
            throw new NoSuchMethodException(owner.getName() + "." + name);
        return method;
    }

    private static Object invoke(Method method, Object receiver, Object... arguments) throws ReflectiveOperationException {
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof Error error) throw error;
            if (wrapped.getCause() instanceof RuntimeException error) throw error;
            throw wrapped;
        }
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean result) return result;
        throw new IllegalStateException("BOOLEAN_RESULT_REQUIRED");
    }

    private static Optional<?> optional(Object value) {
        if (value instanceof Optional<?> result) return result;
        throw new IllegalStateException("OPTIONAL_RESULT_REQUIRED");
    }
}
