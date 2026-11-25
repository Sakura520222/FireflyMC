package firefly520.fireflymc.ai.integration.travel;

import com.google.gson.*;
import com.mojang.datafixers.util.Either;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.storage.AiJson;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.*;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

/** Exact-version reflection into the real native route, requirement and rich-result APIs. */
final class NativeWaystoneBridge {
    private static final String API = "net.blay09.mods.waystones.api.";
    private static final String CORE = "net.blay09.mods.waystones.core.";
    private static final String REQUIREMENTS = "net.blay09.mods.waystones.requirement.";
    record Route(Object source, Object target, Object context, WaystoneTravelPlan.Stone from,
            WaystoneTravelPlan.Stone to, List<Long> chunks, List<String> flags, String requirement,
            List<String> description, boolean affordable) {}
    record Outcome(JsonObject data, boolean success, boolean known, String reason) {}
    record Preparation(Object source, Object target, Object context, WaystoneTravelPlan.Stone from,
            WaystoneTargetPreparation.Target to, List<Long> chunks) {}
    private final MinecraftServer server;
    NativeWaystoneBridge(MinecraftServer server) { this.server = server; }

    void requireReady() throws ReflectiveOperationException {
        thread();
        if (!version("waystones").equals("21.1.37") || !version("balm").equals("21.0.63"))
            throw new IllegalStateException("WAYSTONE_VERSION_UNSUPPORTED");
        var marker = type(CORE + "WaystoneTeleportManager").getDeclaredMethod("fireflymc$travelGuardVersion");
        marker.setAccessible(true);
        if (!"waystones-21.1.37-fee-and-scope-v1".equals(marker.invoke(null))) throw new IllegalStateException("WAYSTONE_GUARD_UNAVAILABLE");
        type(API + "event.WaystoneTeleportEntityEvent$Pre").getMethod("setCanceled", boolean.class);
    }
    Route route(ServerPlayer player, UUID fromId, UUID targetId) throws ReflectiveOperationException {
        requireReady(); noAttachedEntities(player);
        Object source = activated(player, fromId), target = activated(player, targetId);
        if (fromId.equals(targetId)) throw new IllegalArgumentException("WAYSTONE_SAME_SOURCE_AND_TARGET");
        var from = stone(source); var to = stone(target);
        if (!player.serverLevel().dimension().location().toString().equals(from.dimension())
                || player.position().distanceToSqr(Vec3.atCenterOf(new BlockPos(from.x(), from.y(), from.z()))) > 64)
            throw new IllegalStateException("WAYSTONE_SOURCE_OUT_OF_RANGE");
        // API context validation reads target blocks. Refuse before invoking it if this would load/generate a chunk.
        var chunks = destinationChunks(to);
        var targetLevel = level(target);
        for (long packed : chunks) if (targetLevel.getChunkSource().getChunkNow(ChunkPos.getX(packed), ChunkPos.getZ(packed)) == null)
            throw new IllegalStateException("WAYSTONE_DESTINATION_NOT_LOADED");
        Object builder = type("net.blay09.mods.waystones.menu.WaystoneSelectionListBuilder").getConstructor(ServerPlayer.class).newInstance(player);
        // Use the native ordinary activated subset, not unrelated memorial/portal/linked-player routes.
        // The full withTargetsForWaystone path also queries linked players' feather inventories and
        // validates unrelated memorial chunks. Those mechanisms are outside this approved route.
        invoke(builder, "withSourceWaystone", new Class<?>[]{type(API + "Waystone")}, source);
        invoke(builder, "withActivatedWaystones", new Class<?>[0]);
        invoke(builder, "skipSortingIndexUpdate", new Class<?>[0]);
        Object built = invoke(builder, "build", new Class<?>[0]);
        if (!(built instanceof List<?> candidates) || candidates.size() > 4096) throw new IllegalStateException("WAYSTONE_CANDIDATE_LIMIT");
        Object selected = null;
        for (Object candidate : candidates) if (id(candidate).equals(targetId)) { selected = candidate; break; }
        if (selected == null) throw new SecurityException("WAYSTONE_NOT_A_NATIVE_SOURCE_TARGET");
        Object personalizedSource = field(builder, "sourceWaystone");
        Set<?> nativeFlags = (Set<?>) field(builder, "flags");
        if (nativeFlags.size() > 32) throw new IllegalStateException("WAYSTONE_FLAG_LIMIT");
        List<String> flags = nativeFlags.stream().map(String::valueOf).sorted().toList();
        Object finalSelected = selected;
        Consumer<Object> configure = context -> {
            try {
                invoke(context, "setFromWaystone", new Class<?>[]{type(API + "Waystone")}, personalizedSource);
                invoke(context, "addFlags", new Class<?>[]{Set.class}, nativeFlags);
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException("WAYSTONE_CONTEXT_FAILED", failure); }
        };
        var created = (Either<?, ?>) type(API + "WaystonesAPI").getMethod("createDefaultTeleportContext", Entity.class, type(API + "Waystone"), Consumer.class)
                .invoke(null, player, finalSelected, configure);
        if (created.left().isEmpty()) throw new IllegalStateException("WAYSTONE_NATIVE_CONTEXT_REFUSED");
        Object context = created.left().orElseThrow(); noAttachedContext(context);
        Object requirement = invoke(context, "getRequirements", new Class<?>[0]);
        var description = new ArrayList<Component>();
        type(API + "requirement.WarpRequirement").getMethod("appendHoverText", Player.class, List.class).invoke(requirement, player, description);
        if (description.size() > 32) throw new IllegalStateException("WAYSTONE_REQUIREMENT_LIMIT");
        return new Route(personalizedSource, selected, context, from, to, chunks, flags,
                AiJson.canonical(requirement(requirement, player, 0)), description.stream().map(component -> bounded(component.getString(), 1024)).toList(),
                affordable(requirement, player));
    }
    Preparation preparation(ServerPlayer player, UUID fromId, UUID targetId) throws ReflectiveOperationException {
        requireReady(); noAttachedEntities(player);
        Object source = activated(player, fromId), target = knownActivated(player, targetId);
        var from = stone(source); var to = targetMetadata(target);
        var targetLevel = level(target); var targetPos = new BlockPos(to.x(), to.y(), to.z());
        if (targetLevel.isOutsideBuildHeight(targetPos) || !targetLevel.getWorldBorder().isWithinBounds(targetPos))
            throw new SecurityException("WAYSTONE_DESTINATION_OUTSIDE_BOUNDS");
        if (from.id().equals(to.id()) || !player.serverLevel().dimension().location().toString().equals(from.dimension())
                || player.position().distanceToSqr(Vec3.atCenterOf(new BlockPos(from.x(), from.y(), from.z()))) > 64)
            throw new IllegalStateException("WAYSTONE_SOURCE_OUT_OF_RANGE");
        Object candidates = type(API + "WaystonesAPI").getMethod("getActivatedWaystones", Player.class).invoke(null, player);
        if (!(candidates instanceof Collection<?> values) || values.size() > 4096) throw new IllegalStateException("WAYSTONE_CANDIDATE_LIMIT");
        boolean present = false; for (Object value : values) if (id(value).equals(to.id())) { present = true; break; }
        if (!present) throw new SecurityException("WAYSTONE_NOT_A_NATIVE_SOURCE_TARGET");
        // This context is passed ONLY to the native loading method, never to a teleport or fee API.
        Object context = type(API + "WaystonesAPI").getMethod("createUnboundTeleportContext", Entity.class, type(API + "Waystone")).invoke(null, player, target);
        invoke(context, "setFromWaystone", new Class<?>[]{type(API + "Waystone")}, source);
        return new Preparation(source, target, context, from, to, destinationChunks(new WaystoneTravelPlan.Stone(to.id(), to.dimension(), to.x(), to.y(), to.z(), to.type(), to.name(), "METADATA_ONLY")));
    }
    @SuppressWarnings("unchecked") CompletableFuture<Object> prepareTarget(Object context, Object target) throws ReflectiveOperationException {
        var method = type(CORE + "WaystoneTeleportManager").getDeclaredMethod("loadDestinationChunks", MinecraftServer.class, ServerLevel.class, type(API + "WaystoneTeleportContext"));
        method.setAccessible(true); return (CompletableFuture<Object>) method.invoke(null, server, level(target), context);
    }
    boolean loadedTargetMatches(WaystoneTargetPreparation.Target expected) throws ReflectiveOperationException {
        ResourceKey<Level> key = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, net.minecraft.resources.ResourceLocation.parse(expected.dimension()));
        var level = server.getLevel(key); var pos = new BlockPos(expected.x(), expected.y(), expected.z());
        if (level == null || !level.hasChunkAt(pos)) return false;
        var value = (Optional<?>) type(API + "WaystonesAPI").getMethod("getWaystoneAt", ServerLevel.class, BlockPos.class).invoke(null, level, pos);
        var metadata = value.isEmpty() ? null : targetMetadata(value.get());
        return metadata != null && id(value.get()).equals(expected.id()) && metadata.dimension().equals(expected.dimension())
                && metadata.x() == expected.x() && metadata.y() == expected.y() && metadata.z() == expected.z() && metadata.type().equals(expected.type())
                && level.getBlockEntity(pos) != null && level.getBlockEntity(pos).getClass().getName().equals("net.blay09.mods.waystones.block.entity.WaystoneBlockEntity");
    }
    private WaystoneTargetPreparation.Target targetMetadata(Object value) throws ReflectiveOperationException {
        var key = (ResourceKey<?>) invoke(value, "getDimension", new Class<?>[0]); var pos = (BlockPos) invoke(value, "getPos", new Class<?>[0]);
        return new WaystoneTargetPreparation.Target(id(value), key.location().toString(), pos.getX(), pos.getY(), pos.getZ(),
                String.valueOf(invoke(value, "getWaystoneType", new Class<?>[0])), bounded(((Component) invoke(value, "getEffectiveName", new Class<?>[0])).getString(), 256));
    }
    private Object activated(ServerPlayer player, UUID selectedId) throws ReflectiveOperationException {
        Object found = knownActivated(player, selectedId);
        ServerLevel level = level(found); BlockPos pos = (BlockPos) invoke(found, "getPos", new Class<?>[0]);
        if (!level.hasChunkAt(pos)) throw new IllegalStateException("WAYSTONE_DESTINATION_NOT_LOADED");
        var current = (Optional<?>) type(API + "WaystonesAPI").getMethod("getWaystoneAt", ServerLevel.class, BlockPos.class).invoke(null, level, pos);
        if (current.isEmpty() || !id(current.get()).equals(selectedId) || level.getBlockEntity(pos) == null
                || !level.getBlockEntity(pos).getClass().getName().equals("net.blay09.mods.waystones.block.entity.WaystoneBlockEntity"))
            throw new IllegalStateException("WAYSTONE_BLOCK_IDENTITY_CHANGED");
        return current.get();
    }
    private Object knownActivated(ServerPlayer player, UUID selectedId) throws ReflectiveOperationException {
        Object values = type(API + "WaystonesAPI").getMethod("getActivatedWaystones", Player.class).invoke(null, player);
        if (!(values instanceof Collection<?> known) || known.size() > 4096) throw new IllegalStateException("WAYSTONE_ACTIVATION_LIMIT");
        Object found = null;
        for (Object stone : known) if (id(stone).equals(selectedId)) { found = stone; break; }
        if (found == null || !(boolean) type(API + "WaystonesAPI").getMethod("isWaystoneActivated", Player.class, type(API + "Waystone")).invoke(null, player, found))
            throw new SecurityException("WAYSTONE_NOT_ACTIVATED_BY_OWNER");
        if (!(boolean) invoke(found, "isValid", new Class<?>[0]) || (boolean) invoke(found, "isTransient", new Class<?>[0])
                || !String.valueOf(invoke(found, "getWaystoneType", new Class<?>[0])).equals("waystones:waystone"))
            throw new IllegalStateException("WAYSTONE_ROUTE_TYPE_UNSUPPORTED");
        return found;
    }
    WaystoneTravelPlan.Stone stone(Object value) throws ReflectiveOperationException {
        var level = level(value); var position = (BlockPos) invoke(value, "getPos", new Class<?>[0]);
        if (!level.hasChunkAt(position)) throw new IllegalStateException("WAYSTONE_DESTINATION_NOT_LOADED");
        return new WaystoneTravelPlan.Stone(id(value), level.dimension().location().toString(), position.getX(), position.getY(), position.getZ(),
                String.valueOf(invoke(value, "getWaystoneType", new Class<?>[0])), bounded(((Component) invoke(value, "getEffectiveName", new Class<?>[0])).getString(), 256),
                level.getBlockState(position).toString());
    }
    String currentRequirement(Object context, ServerPlayer player) throws ReflectiveOperationException {
        Object fresh = type(API + "WaystonesAPI").getMethod("resolveRequirements", type(API + "WaystoneTeleportContext")).invoke(null, context);
        return AiJson.canonical(requirement(fresh, player, 0));
    }
    String heldRequirement(Object context, ServerPlayer player) throws ReflectiveOperationException {
        return AiJson.canonical(requirement(invoke(context, "getRequirements", new Class<?>[0]), player, 0));
    }
    void verifyStones(ServerPlayer player, WaystoneTravelPlan plan) throws ReflectiveOperationException {
        if (!stone(activated(player, plan.source().id())).equals(plan.source()) || !stone(activated(player, plan.target().id())).equals(plan.target()))
            throw new IllegalStateException("WAYSTONE_BLOCK_IDENTITY_CHANGED");
    }
    void requireLanding(Object destination, WaystoneTravelPlan plan) throws ReflectiveOperationException {
        requireLanding((ServerLevel) invoke(destination, "level", new Class<?>[0]), (Vec3) invoke(destination, "location", new Class<?>[0]), plan);
    }
    void requireLanding(ServerLevel level, Vec3 location, WaystoneTravelPlan plan) {
        var pos = BlockPos.containing(location);
        if (!level.dimension().location().toString().equals(plan.target().dimension()) || !Double.isFinite(location.x)
                || !Double.isFinite(location.y) || !Double.isFinite(location.z) || !level.hasChunkAt(pos)
                || !plan.destinationChunks().contains(new ChunkPos(pos).toLong()) || !level.getWorldBorder().isWithinBounds(pos)
                || location.distanceToSqr(new Vec3(plan.target().x() + .5, plan.target().y(), plan.target().z() + .5)) > 144)
            throw new SecurityException("WAYSTONE_LANDING_OUTSIDE_APPROVED_SCOPE");
    }
    boolean affordable(Object requirement, ServerPlayer player) throws ReflectiveOperationException {
        return (boolean) type(API + "requirement.WarpRequirement").getMethod("canAfford", Player.class).invoke(requirement, player)
                || player.getAbilities().instabuild;
    }
    Object refusedResult() throws ReflectiveOperationException {
        Object error = type(API + "error.WaystoneTeleportError$CancelledByEvent").getConstructor().newInstance();
        return type(API + "WaystoneTeleportResult").getMethod("failed", type(API + "error.WaystoneTeleportError")).invoke(null, error);
    }
    Object cancelledPreparation() throws ReflectiveOperationException {
        return Either.right(type(API + "error.WaystoneTeleportError$CancelledByEvent").getConstructor().newInstance());
    }
    @SuppressWarnings("unchecked") CompletableFuture<Object> start(Object context) throws ReflectiveOperationException {
        return (CompletableFuture<Object>) type(CORE + "WaystoneTeleportManager").getMethod("tryTeleportAsync", type(API + "WaystoneTeleportContext")).invoke(null, context);
    }
    Outcome outcome(Object nativeResult, ServerPlayer player, WaystoneTravelPlan plan) throws ReflectiveOperationException {
        var data = new JsonObject(); var results = new JsonArray();
        if (nativeResult == null) return new Outcome(data, false, false, "WAYSTONE_NATIVE_RESULT_MISSING");
        Object values = invoke(nativeResult, "results", new Class<?>[0]);
        if (!(values instanceof List<?> list) || list.size() > 128) return new Outcome(data, false, false, "WAYSTONE_NATIVE_RESULT_LIMIT");
        boolean ownSuccess = false, known = list.size() <= 1;
        for (Object value : list) {
            Entity entity = (Entity) invoke(value, "entity", new Class<?>[0]); boolean success = (boolean) invoke(value, "isSuccessful", new Class<?>[0]);
            var row = new JsonObject(); row.addProperty("entity", entity.getUUID().toString()); row.addProperty("success", success);
            Object error = invoke(value, "error", new Class<?>[0]); row.addProperty("nativeErrorType", error == null ? "" : error.getClass().getSimpleName());
            row.add("originalDestination", destination(invoke(value, "originalDestination", new Class<?>[0])));
            row.add("resolvedDestination", destination(invoke(value, "resolvedDestination", new Class<?>[0])));
            results.add(row);
            if (!entity.getUUID().equals(player.getUUID())) known = false;
            else ownSuccess = success;
        }
        var error = (Optional<?>) invoke(nativeResult, "teleportError", new Class<?>[0]);
        data.add("entities", results); data.addProperty("teleportErrorType", error.map(value -> value.getClass().getSimpleName()).orElse(""));
        boolean success = ownSuccess && error.isEmpty() && (boolean) invoke(nativeResult, "isSuccessful", new Class<?>[0]);
        if (success && (!player.serverLevel().dimension().location().toString().equals(plan.target().dimension())
                || !plan.destinationChunks().contains(player.chunkPosition().toLong())
                || player.position().distanceToSqr(new Vec3(plan.target().x() + .5, plan.target().y(), plan.target().z() + .5)) > 144)) known = false;
        return new Outcome(data, success && known, known, success && known ? "" : "WAYSTONE_NATIVE_REFUSED_OR_PARTIAL");
    }
    private JsonObject destination(Object destination) throws ReflectiveOperationException {
        var data = new JsonObject(); if (destination == null) return data;
        var level = (Level) invoke(destination, "level", new Class<?>[0]); var location = (Vec3) invoke(destination, "location", new Class<?>[0]);
        data.addProperty("dimension", level.dimension().location().toString()); data.addProperty("x", location.x); data.addProperty("y", location.y); data.addProperty("z", location.z); return data;
    }
    JsonObject playerSnapshot(ServerPlayer player) {
        var data = new JsonObject(); data.addProperty("player", player.getUUID().toString());
        data.addProperty("dimension", player.serverLevel().dimension().location().toString());
        data.addProperty("x", player.getX()); data.addProperty("y", player.getY()); data.addProperty("z", player.getZ());
        data.addProperty("levels", player.experienceLevel); data.addProperty("totalExperience", player.totalExperience); data.addProperty("experienceProgress", player.experienceProgress);
        var slots = new JsonArray();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) slots.add(AiJson.GSON.toJsonTree(ItemSnapshotCodec.capture(player.getInventory().getItem(slot), player.registryAccess())));
        data.add("inventory", slots); return data;
    }
    private JsonObject requirement(Object value, ServerPlayer player, int depth) throws ReflectiveOperationException {
        if (depth > 16) throw new IllegalStateException("WAYSTONE_REQUIREMENT_DEPTH");
        String name = value.getClass().getName(); var data = new JsonObject(); data.addProperty("type", name);
        if (!name.startsWith(REQUIREMENTS)) throw new IllegalStateException("WAYSTONE_REQUIREMENT_UNSUPPORTED");
        switch (name.substring(REQUIREMENTS.length())) {
            case "NoRequirement", "DismountRequirement" -> {}
            case "CombinedRequirement" -> {
                var values = (Collection<?>) invoke(value, "getRequirements", new Class<?>[0]);
                if (values.size() > 64) throw new IllegalStateException("WAYSTONE_REQUIREMENT_LIMIT");
                var children = new JsonArray(); for (Object child : values) children.add(requirement(child, player, depth + 1)); data.add("requirements", children);
            }
            case "ExperienceLevelRequirement" -> data.addProperty("levels", (int) invoke(value, "getLevels", new Class<?>[0]));
            case "ExperiencePointsRequirement" -> data.addProperty("points", (int) invoke(value, "getPoints", new Class<?>[0]));
            case "CooldownRequirement", "SoftCooldownRequirement" -> {
                data.addProperty("key", String.valueOf(invoke(value, "getCooldownKey", new Class<?>[0])));
                data.addProperty("seconds", (int) invoke(value, "getCooldownSeconds", new Class<?>[0]));
            }
            case "ItemRequirement" -> {
                var resolver = type(REQUIREMENTS + "InventoryItemResolver"); var handlers = (List<?>) resolver.getField("NESTED_COMPONENT_HANDLERS").get(null);
                if (handlers.size() > 8 || handlers.stream().anyMatch(handler -> !handler.getClass().getName().startsWith(REQUIREMENTS + "InventoryItemResolver")))
                    throw new IllegalStateException("WAYSTONE_ITEM_FEE_STORAGE_UNSUPPORTED");
                data.add("item", AiJson.GSON.toJsonTree(ItemSnapshotCodec.capture((ItemStack) invoke(value, "getItemStack", new Class<?>[0]), player.registryAccess())));
                data.addProperty("count", (int) invoke(value, "getCount", new Class<?>[0]));
            }
            case "RefuseRequirement" -> data.addProperty("message", Component.Serializer.toJson((Component) invoke(value, "getMessage", new Class<?>[0]), player.registryAccess()));
            default -> throw new IllegalStateException("WAYSTONE_REQUIREMENT_UNSUPPORTED");
        }
        return data;
    }
    void noAttachedContext(Object context) throws ReflectiveOperationException {
        if (!((List<?>) invoke(context, "getAdditionalEntities", new Class<?>[0])).isEmpty()
                || !((List<?>) invoke(context, "getLeashedEntities", new Class<?>[0])).isEmpty()) throw new IllegalStateException("WAYSTONE_ADDITIONAL_ENTITIES_NEED_ADAPTER");
    }
    static void noAttachedEntities(ServerPlayer player) {
        if (player.isPassenger() || player.isVehicle() || !player.isAlive() || player.isSleeping()) throw new IllegalStateException("WAYSTONE_PLAYER_STATE_UNSUPPORTED");
    }
    static List<Long> destinationChunks(WaystoneTravelPlan.Stone stone) {
        var position = new BlockPos(stone.x(), stone.y(), stone.z()); var chunks = new LinkedHashSet<Long>();
        for (var offset : List.of(position, position.north(), position.south(), position.east(), position.west())) chunks.add(new ChunkPos(offset).toLong());
        return chunks.stream().sorted().toList();
    }
    private ServerLevel level(Object stone) throws ReflectiveOperationException {
        var key = (ResourceKey<Level>) invoke(stone, "getDimension", new Class<?>[0]); var level = server.getLevel(key);
        if (level == null) throw new IllegalStateException("WAYSTONE_DIMENSION_UNAVAILABLE"); return level;
    }
    private static UUID id(Object stone) throws ReflectiveOperationException { return (UUID) invoke(stone, "getWaystoneUid", new Class<?>[0]); }
    static Object invoke(Object target, String name, Class<?>[] signature, Object... args) throws ReflectiveOperationException {
        return target.getClass().getMethod(name, signature).invoke(target, args);
    }
    private static Object field(Object target, String name) throws ReflectiveOperationException { var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
    private static Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name); }
    private static String version(String id) { return ModList.get().getModContainerById(id).map(mod -> mod.getModInfo().getVersion().toString()).orElse(""); }
    private static String bounded(String value, int size) { return value.length() <= size ? value : value.substring(0, size); }
    private void thread() { if (!server.isDedicatedServer() || !server.isSameThread()) throw new IllegalStateException("DEDICATED_SERVER_THREAD_REQUIRED"); }
}
