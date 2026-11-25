package firefly520.fireflymc.ai.integration.exploration;

import firefly520.fireflymc.ai.material.ItemSnapshotCodec;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.mixin.CompassStructureStateAccess;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.neoforged.fml.ModList;

/** Server-only exact installed compass APIs. No teleport, network packet handlers or caller-provided origin is invoked. */
final class CompassNativeAccess {
    record Configuration(String fingerprint, int radius, int maxSamples, int costLevels, boolean visible) {}
    private final CompassSearchPlan.Kind kind;
    private final Class<?> item, utility, worker, configClass;
    private final Method state, broken, visible, target, foundX, foundZ, inactive, search, stop;
    private final Field nativeWorker, workers, finished, damageComponent;
    private final Field stateComponent;
    private final Field sampleX, sampleZ;
    private final Method damageGetter;
    private final Object config;
    CompassNativeAccess(CompassSearchPlan.Kind kind) throws ReflectiveOperationException {
        this.kind = kind;
        String installed = ModList.get().getModContainerById(kind.modId()).map(mod -> mod.getModInfo().getVersion().toString()).orElse("");
        if (!CompassSearchPlan.Kind.VERSION.equals(installed)) throw new IllegalStateException(installed.isEmpty() ? "COMPASS_MOD_ABSENT" : "COMPASS_VERSION_UNSUPPORTED");
        item = type(kind.itemClass()); utility = type(kind.prefix() + ".util." + (kind == CompassSearchPlan.Kind.BIOME ? "BiomeUtils" : "StructureUtils"));
        worker = type(kind.prefix() + (kind == CompassSearchPlan.Kind.BIOME ? ".util.BiomeSearchWorker" : ".worker.StructureSearchWorker"));
        configClass = type(kind.prefix() + ".config.ConfigHandler$General");
        config = type(kind.prefix() + ".config.ConfigHandler").getField("GENERAL").get(null);
        stateComponent = type(kind.modClass()).getField(kind == CompassSearchPlan.Kind.BIOME
                ? "COMPASS_STATE" : "COMPASS_STATE_COMPONENT");
        state = item.getMethod("getState", ItemStack.class); broken = item.getMethod("isBroken", ItemStack.class);
        visible = item.getMethod("shouldDisplayCoordinates", ItemStack.class);
        String noun = kind == CompassSearchPlan.Kind.BIOME ? "Biome" : "Structure";
        target = item.getMethod("get" + noun + "Key", ItemStack.class);
        foundX = item.getMethod("getFound" + noun + "X", ItemStack.class); foundZ = item.getMethod("getFound" + noun + "Z", ItemStack.class);
        inactive = item.getMethod("setInactive", ItemStack.class, Player.class); stop = worker.getMethod("stop");
        finished = field(worker, "finished");
        if (kind == CompassSearchPlan.Kind.BIOME) {
            nativeWorker = field(item, "worker"); workers = null;
            search = item.getMethod("searchForBiome", ServerLevel.class, Player.class, ResourceLocation.class, BlockPos.class, ItemStack.class);
            damageComponent = type(kind.modClass()).getField("DAMAGE"); damageGetter = null;
            sampleX = field(worker, "x"); sampleZ = field(worker, "z");
            requireHooks(worker);
        } else {
            nativeWorker = field(item, "workerManager"); workers = field(type(kind.prefix() + ".worker.SearchWorkerManager"), "workers");
            search = item.getMethod("searchForStructure", Level.class, Player.class, ResourceLocation.class, List.class, BlockPos.class, ItemStack.class, boolean.class);
            damageGetter = item.getMethod("getDamage", ItemStack.class); damageComponent = null;
            sampleX = null; sampleZ = null;
            for (String name : List.of("RandomSpreadSearchWorker", "ConcentricRingsSearchWorker", "GenericSearchWorker")) requireHooks(type(kind.prefix() + ".worker." + name));
        }
    }
    private static void requireHooks(Class<?> type) {
        if (!CompassScopedWorker.class.isAssignableFrom(type) || !CompassRegisteredWorker.class.isAssignableFrom(type)
                || !CompassSampleGuardedWorker.class.isAssignableFrom(type))
            throw new IllegalStateException("COMPASS_BUDGET_HOOKS_UNAVAILABLE");
    }
    static Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name, false, CompassNativeAccess.class.getClassLoader()); }
    static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        var field = owner.getDeclaredField(name); if (!field.trySetAccessible()) throw new IllegalAccessException("COMPASS_FIELD_UNAVAILABLE"); return field;
    }
    static Object call(Method method, Object owner, Object... args) throws ReflectiveOperationException {
        try { return method.invoke(owner, args); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException stopped) throw stopped;
            throw new ReflectiveOperationException("COMPASS_NATIVE_INVOCATION_FAILED", failure.getCause());
        }
    }
    private Object configValue(String name) throws ReflectiveOperationException {
        Object value = configClass.getField(name).get(config);
        return call(value.getClass().getMethod("get"), value);
    }
    /** Reads only the loaded server config scalar; no world/player/item access belongs to export projection. */
    static boolean structureCoordinatesConfigured() {
        try {
            Object config = type(CompassSearchPlan.Kind.STRUCTURE.prefix() + ".config.ConfigHandler").getField("GENERAL").get(null);
            Object setting = config.getClass().getField("displayCoordinates").get(config);
            return Boolean.TRUE.equals(call(setting.getClass().getMethod("get"), setting));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return false; }
    }
    Configuration configuration(ServerPlayer player, ResourceLocation target) throws ReflectiveOperationException {
        var values = new TreeMap<String, Object>();
        var names = kind == CompassSearchPlan.Kind.BIOME
                ? List.of("radiusModifier", "sampleSpaceModifier", "maxSamples", "compassDurability", "displayCoordinates", "biomeBlacklist", "defaultXpLevels", "perBiomeXpLevels")
                : List.of("maxRadius", "maxSamples", "compassDurability", "displayCoordinates", "structureBlacklist", "defaultXpLevels", "xpLevelOverrides");
        for (var name : names) values.put(name, configValue(name));
        String serialized = AiJson.canonical(values);
        if (serialized.length() > 65536) throw new IllegalStateException("COMPASS_CONFIG_LIMIT");
        int radius = ((Number) values.get(kind == CompassSearchPlan.Kind.BIOME ? "radiusModifier" : "maxRadius")).intValue();
        if (kind == CompassSearchPlan.Kind.BIOME) radius = Math.multiplyExact(radius, (int) call(utility.getMethod("getBiomeSize", Level.class), null, player.serverLevel()));
        int samples = ((Number) values.get("maxSamples")).intValue();
        int levels = kind == CompassSearchPlan.Kind.BIOME
                ? (int) call(utility.getMethod("getXpLevelsForBiome", ResourceLocation.class), null, target)
                : (int) call(utility.getMethod("getXpLevelsForStructure", ServerLevel.class, ResourceLocation.class), null, player.serverLevel(), target);
        if (radius < 1 || samples < 1) throw new IllegalStateException("COMPASS_NATIVE_SEARCH_DISABLED");
        return new Configuration(AiJson.digest(serialized), radius, samples, player.hasInfiniteMaterials() ? 0 : Math.max(0, levels), (boolean) values.get("displayCoordinates"));
    }
    void requireTarget(ServerPlayer player, ResourceLocation id) throws ReflectiveOperationException {
        if (kind == CompassSearchPlan.Kind.BIOME) {
            var registry = player.registryAccess().registryOrThrow(Registries.BIOME);
            var biome = registry.get(id); if (biome == null) throw new IllegalArgumentException("COMPASS_TARGET_NOT_REGISTERED");
            if ((boolean) call(utility.getMethod("biomeKeyIsBlacklisted", Level.class, ResourceLocation.class), null, player.serverLevel(), id)
                    || (boolean) call(utility.getMethod("biomeKeyIsHidden", Level.class, ResourceLocation.class), null, player.serverLevel(), id))
                throw new SecurityException("COMPASS_TARGET_NOT_ALLOWED");
            if (player.serverLevel().getChunkSource().getGenerator().getBiomeSource().possibleBiomes().stream().noneMatch(value -> value.value() == biome))
                throw new IllegalArgumentException("COMPASS_TARGET_OTHER_DIMENSION");
        } else {
            var structure = player.registryAccess().registryOrThrow(Registries.STRUCTURE).get(id);
            if (structure == null) throw new IllegalArgumentException("COMPASS_TARGET_NOT_REGISTERED");
            if ((boolean) call(utility.getMethod("structureIsBlacklisted", ServerLevel.class, Structure.class), null, player.serverLevel(), structure)
                    || (boolean) call(utility.getMethod("structureIsHidden", ServerLevel.class, Structure.class), null, player.serverLevel(), structure))
                throw new SecurityException("COMPASS_TARGET_NOT_ALLOWED");
            var state = player.serverLevel().getChunkSource().getGeneratorState();
            if (state.possibleStructureSets().size() > 4096) throw new IllegalStateException("COMPASS_STRUCTURE_TABLE_LIMIT");
            var holder = player.registryAccess().registryOrThrow(Registries.STRUCTURE).wrapAsHolder(structure);
            var placements = state.getPlacementsForStructure(holder);
            if (placements.isEmpty()) throw new IllegalStateException("COMPASS_TARGET_OTHER_DIMENSION");
            if (placements.size() > 16) throw new IllegalStateException("COMPASS_PLACEMENT_LIMIT");
            for (var placement : placements) if (placement instanceof ConcentricRingsStructurePlacement ring) {
                if (!(state instanceof CompassStructureStateAccess access)) throw new IllegalStateException("COMPASS_RING_GUARD_UNAVAILABLE");
                var future = access.fireflymc$ringPositions().get(ring);
                if (future == null || !future.isDone() || future.isCompletedExceptionally() || future.isCancelled())
                    throw new IllegalStateException("COMPASS_RING_PREPARATION_PENDING");
                if (future.resultNow().size() > 2048) throw new IllegalStateException("COMPASS_RING_PREPARATION_LIMIT");
            }
        }
    }
    boolean accepts(ItemStack stack) { return !stack.isEmpty() && stack.getCount() == 1 && stack.getItem().getClass() == item; }
    String fingerprint(ServerPlayer player, ItemStack stack) { return AiJson.digest(AiJson.canonical(ItemSnapshotCodec.capture(stack, player.registryAccess()))); }
    @SuppressWarnings("unchecked")
    CompassSearchJournal.Observation observe(ServerPlayer player, ItemStack stack) throws ReflectiveOperationException {
        int damage = damageGetter != null ? (int) call(damageGetter, stack.getItem(), stack)
                : stack.getOrDefault((DataComponentType<Integer>) damageComponent.get(null), 0);
        Object nativeState = call(state, stack.getItem(), stack);
        // These exact native getters return null when a real new compass has no COMPASS_STATE component.
        // Preserve that absence; do not mutate it to INACTIVE during a read-only preview.
        String stateName = nativeState == null ? stack.has((DataComponentType<?>) stateComponent.get(null)) ? "UNKNOWN_NATIVE_STATE" : "UNINITIALIZED" : ((Enum<?>) nativeState).name();
        return new CompassSearchJournal.Observation(fingerprint(player, stack), stateName, damage,
                (boolean) call(broken, stack.getItem(), stack), (boolean) call(visible, stack.getItem(), stack));
    }
    List<?> workers(ItemStack stack) throws ReflectiveOperationException {
        Object value = nativeWorker.get(stack.getItem());
        if (value == null) return List.of();
        if (kind == CompassSearchPlan.Kind.BIOME) return List.of(value);
        var result = (List<?>) workers.get(value);
        if (result.size() > 64) throw new IllegalStateException("COMPASS_NATIVE_WORKER_LIMIT"); return List.copyOf(result);
    }
    boolean busy(ItemStack stack) throws ReflectiveOperationException {
        for (var value : workers(stack)) if (!(boolean) finished.get(value)) return true;
        return false;
    }
    boolean stillRegistered(ItemStack stack, Set<Object> owned) throws ReflectiveOperationException {
        var current = workers(stack);
        return !current.isEmpty() && current.stream().allMatch(owned::contains);
    }
    boolean ownsWorker(Object value, ServerPlayer player, ItemStack stack) throws ReflectiveOperationException {
        return worker.isInstance(value) && field(worker, "player").get(value) == player && field(worker, "stack").get(value) == stack
                && field(worker, "level").get(value) == player.serverLevel();
    }
    void requireWorkerArguments(Object value, CompassSearchPlan plan) throws ReflectiveOperationException {
        String targetField = kind == CompassSearchPlan.Kind.BIOME ? "biomeKey" : "structureOrGroupId";
        var target = field(worker, targetField).get(value);
        var origin = field(worker, "startPos").get(value);
        if (!ResourceLocation.parse(plan.targetId()).equals(target) || !new BlockPos(plan.originX(), plan.originY(), plan.originZ()).equals(origin)
                || kind == CompassSearchPlan.Kind.STRUCTURE && field(worker, "isGroup").getBoolean(value)) {
            stopWorker(value); throw new IllegalStateException("COMPASS_NATIVE_ARGUMENT_CHANGED");
        }
    }
    void start(ServerPlayer player, ItemStack stack, ResourceLocation target) throws ReflectiveOperationException {
        if (kind == CompassSearchPlan.Kind.BIOME) call(search, stack.getItem(), player.serverLevel(), player, target, player.blockPosition(), stack);
        else call(search, stack.getItem(), player.serverLevel(), player, target, List.of(target), player.blockPosition(), stack, false);
    }
    record Cursor(Object worker, Map<Field, Object> fields) { }
    Cursor cursor(Object value) throws ReflectiveOperationException {
        if (kind != CompassSearchPlan.Kind.STRUCTURE || !worker.isInstance(value)) throw new IllegalStateException("COMPASS_STRUCTURE_CURSOR_REQUIRED");
        String type = value.getClass().getSimpleName();
        var own = switch (type) {
            case "RandomSpreadSearchWorker" -> List.of("x", "z", "length");
            case "ConcentricRingsSearchWorker" -> List.of("chunkIndex", "minDistance", "closest");
            case "GenericSearchWorker" -> List.of("chunkX", "chunkZ", "length", "nextLength", "direction");
            default -> throw new IllegalStateException("COMPASS_WORKER_CURSOR_UNSUPPORTED");
        };
        var values = new LinkedHashMap<Field, Object>();
        for (String name : own) { var field = field(value.getClass(), name); values.put(field, field.get(value)); }
        // Native progress notifications may already have run before candidate admission. Their
        // threshold is deliberately not rolled back; only the uncommitted candidate cursor is.
        for (String name : List.of("currentPos", "samples")) { var field = field(worker, name); values.put(field, field.get(value)); }
        return new Cursor(value, Collections.unmodifiableMap(values));
    }
    void restoreCursor(Cursor cursor, Object value) throws ReflectiveOperationException {
        if (cursor.worker() != value) throw new IllegalStateException("COMPASS_WORKER_CURSOR_CHANGED");
        for (var entry : cursor.fields().entrySet()) entry.getKey().set(value, entry.getValue());
    }
    boolean candidateUncommitted(Cursor cursor, Object value) throws ReflectiveOperationException {
        if (cursor.worker() != value) return false;
        for (var entry : cursor.fields().entrySet()) if (entry.getKey().getName().equals("samples"))
            return Objects.equals(entry.getValue(), entry.getKey().get(value));
        return false;
    }
    void stopWorker(Object value) throws ReflectiveOperationException { if (worker.isInstance(value)) call(stop, value); }
    void clearOnlyOwnedManager(ItemStack stack, Set<Object> owned) throws ReflectiveOperationException {
        if (kind != CompassSearchPlan.Kind.STRUCTURE) return;
        Object manager = nativeWorker.get(stack.getItem()); if (manager == null) return;
        var current = (List<?>) workers.get(manager);
        if (current.size() <= 64 && current.stream().allMatch(owned::contains)) call(manager.getClass().getMethod("stop"), manager);
    }
    void inactive(ServerPlayer player, ItemStack stack) throws ReflectiveOperationException { call(inactive, stack.getItem(), stack, player); }
    String target(ItemStack stack) throws ReflectiveOperationException {
        var value = call(target, stack.getItem(), stack); return value == null ? "" : value.toString();
    }
    BlockPos found(ItemStack stack) throws ReflectiveOperationException { return new BlockPos((int) call(foundX, stack.getItem(), stack), 0, (int) call(foundZ, stack.getItem(), stack)); }
    BlockPos samplePosition(Object value, int sampleY) throws ReflectiveOperationException {
        if (kind != CompassSearchPlan.Kind.BIOME || !worker.isInstance(value)) throw new IllegalStateException("COMPASS_SAMPLE_WORKER_CHANGED");
        return new BlockPos(sampleX.getInt(value), sampleY, sampleZ.getInt(value));
    }
    BlockPos biomeSample(Object value) throws ReflectiveOperationException {
        if (kind != CompassSearchPlan.Kind.BIOME || !worker.isInstance(value)) return null;
        var positions = (List<?>) field(worker, "prevPos").get(value);
        return positions.isEmpty() || positions.size() > 128 ? null : ((BlockPos) positions.getLast()).immutable();
    }
}
