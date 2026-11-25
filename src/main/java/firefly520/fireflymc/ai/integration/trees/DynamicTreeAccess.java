package firefly520.fireflymc.ai.integration.trees;

import firefly520.fireflymc.ai.blueprint.BuildRegionStore;
import firefly520.fireflymc.ai.blueprint.BlueprintDocument;
import firefly520.fireflymc.ai.storage.AiJson;
import java.lang.reflect.*;
import java.lang.invoke.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Exact shipped DT 1.7.2 / Plus 1.3.2 contracts. Discovery never runs a destroyer inspector. */
public final class DynamicTreeAccess {
    private static final String BASE = "com.dtteam.dynamictrees.";
    public static final String FALLING_TREE = BASE + "entity.FallingTreeEntity";
    private static final Set<String> BRANCHES = Set.of(BASE + "block.branch.BasicBranchBlock", BASE + "block.branch.ThickBranchBlock",
            BASE + "block.branch.PalmBranchBlock", "com.dtteam.dynamictreesplus.block.CactusBranchBlock",
            "com.dtteam.dynamictreesplus.block.mushroom.MushroomBranchBlock");
    public record Survey(String species, String family, String branchClass, BlockPos root, Set<BlockPos> branches,
                         Map<BlockPos, BlockState> footprint, String configuration) {
        public Survey { root = root.immutable(); branches = Set.copyOf(branches); footprint = Map.copyOf(footprint); }
    }
    /** Read-only discovery budget, not a selected region and never a world-write authorization. */
    public static BuildRegionStore.Bounds candidateBounds(BlockPos anchor) {
        return BuildRegionStore.bounds(new BlueprintDocument.Position(anchor.getX() - 10, anchor.getY() - 10, anchor.getZ() - 10),
                new BlueprintDocument.Position(anchor.getX() + 10, anchor.getY() + 26, anchor.getZ() + 10));
    }
    public static void requireVersion() {
        version("dynamictrees", "1.7.2", true); version("dynamictreesplus", "1.3.2", false);
        try { Class.forName(BASE + "systems.FutureBreak"); Class.forName(FALLING_TREE); Class.forName(BASE + "entity.animation.FalloverAnimationHandler"); Class.forName("net.minecraft.world.entity.item.ItemEntity"); }
        catch (ClassNotFoundException | LinkageError unavailable) { throw new IllegalStateException("TREE_NATIVE_TYPES_UNAVAILABLE", unavailable); }
        if (!DynamicTreeNativeScope.hooksAvailable()) throw new IllegalStateException("TREE_NATIVE_HOOKS_UNAVAILABLE");
    }
    private static void version(String id, String supported, boolean required) {
        var mod = ModList.get().getModContainerById(id);
        if (mod.isEmpty()) { if (required) throw new IllegalStateException("DYNAMIC_TREES_NOT_INSTALLED"); return; }
        if (!supported.equals(mod.get().getModInfo().getVersion().toString())) throw new IllegalStateException("TREE_VERSION_UNSUPPORTED");
    }
    public Survey survey(ServerPlayer player, BlockPos anchor, BuildRegionStore.Bounds region) throws ReflectiveOperationException {
        thread(player); requireVersion(); var level = player.serverLevel();
        // The native recursive mapper reads its adjacent node before calling inspectors. The complete
        // selection plus a chunk border must already be loaded; discovery never generates a chunk.
        var min = nativePos(region.minimum()); var max = nativePos(region.maximum());
        for (int x = (min.getX() - 16) >> 4; x <= (max.getX() + 16) >> 4; x++)
            for (int z = (min.getZ() - 16) >> 4; z <= (max.getZ() + 16) >> 4; z++)
                if (!level.hasChunk(x, z)) throw new IllegalStateException("TREE_SURVEY_UNLOADED");
        if (!contains(region, anchor)) throw new SecurityException("TREE_ANCHOR_OUTSIDE_REGION");
        var state = level.getBlockState(anchor); Object block = state.getBlock();
        if (!BRANCHES.contains(block.getClass().getName())) throw new IllegalStateException("TREE_BRANCH_CLASS_UNSUPPORTED");
        Class<?> inspector = type("api.network.NodeInspector"), signalType = type("api.network.MapSignal");
        var nodes = new LinkedHashSet<BlockPos>();
        Object collector = Proxy.newProxyInstance(inspector.getClassLoader(), new Class<?>[]{inspector}, (proxy, method, arguments) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "equals" -> proxy == arguments[0]; case "hashCode" -> System.identityHashCode(proxy); default -> "FireflyMC bounded read-only tree survey";
            };
            if (!Set.of("run", "returnRun").contains(method.getName())) throw new IllegalStateException("TREE_INSPECTOR_CONTRACT_CHANGED");
            if (method.getName().equals("returnRun")) return false;
            BlockPos pos = ((BlockPos) arguments[2]).immutable();
            if (!contains(region, pos) || nodes.size() >= 4096) throw new SecurityException("TREE_NETWORK_OUTSIDE_REGION_OR_LIMIT");
            if (!nodes.add(pos)) throw new IllegalStateException("TREE_NETWORK_CYCLE_UNSUPPORTED");
            return false;
        });
        Object speciesNode = type("systems.nodemapper.SpeciesNode").getConstructor().newInstance();
        Object inspectors = Array.newInstance(inspector, 2); Array.set(inspectors, 0, collector); Array.set(inspectors, 1, speciesNode);
        Object signal = signalType.getConstructor(inspectors.getClass()).newInstance(inspectors);
        // MapSignal defaults destroyLoopedNodes=true. A read-only survey must explicitly disable it.
        signalType.getField("destroyLoopedNodes").setBoolean(signal, false);
        signalType.getField("trackVisited").setBoolean(signal, false);
        block.getClass().getMethod("analyse", BlockState.class, LevelAccessor.class, BlockPos.class, Direction.class, signalType)
                .invoke(block, state, level, anchor, null, signal);
        if (signalType.getField("overflow").getBoolean(signal) || signalType.getField("multiroot").getBoolean(signal)
                || !signalType.getField("foundRoot").getBoolean(signal)) throw new IllegalStateException("TREE_NETWORK_INCOMPLETE_OR_MULTIROOT");
        var root = ((BlockPos) signalType.getField("root").get(signal)).immutable();
        Object species = call(speciesNode, "getSpecies"), family = call(species, "getFamily");
        String speciesId = String.valueOf(call(species, "getRegistryName")), familyId = String.valueOf(call(family, "getRegistryName"));
        if (speciesId.endsWith(":null") || !speciesId.contains(":")) throw new IllegalStateException("TREE_SPECIES_UNKNOWN");
        // Native destroyLeaves scans the family's expanded bounds twice. Include the complete read /
        // mutation envelope, trunk shells and neighbor notifications, not only the clicked trunk.
        Class<?> boundsType = type("api.voxmap.BlockPosBounds");
        Object bounds = boundsType.getConstructor(List.class).newInstance(new ArrayList<>(nodes));
        Method expand = family.getClass().getMethod("expandLeavesBlockBounds", boundsType);
        bounds = expand.invoke(family, expand.invoke(family, bounds));
        BlockPos leafMin = ((BlockPos) call(bounds, "getMin")).offset(-1, -1, -1);
        BlockPos leafMax = ((BlockPos) call(bounds, "getMax")).offset(1, 1, 1);
        if (!contains(region, leafMin) || !contains(region, leafMax) || !contains(region, root))
            throw new IllegalStateException("TREE_FULL_IMPACT_OUTSIDE_REGION");
        long volume = (long) (leafMax.getX() - leafMin.getX() + 1) * (leafMax.getY() - leafMin.getY() + 1) * (leafMax.getZ() - leafMin.getZ() + 1);
        if (volume > 16384) throw new IllegalStateException("TREE_IMPACT_LIMIT");
        var footprint = new LinkedHashMap<BlockPos, BlockState>();
        for (BlockPos mutable : BlockPos.betweenClosed(leafMin, leafMax)) {
            BlockPos pos = mutable.immutable(); footprint.put(pos, level.getBlockState(pos));
        }
        footprint.put(root, level.getBlockState(root));
        return new Survey(speciesId, familyId, block.getClass().getName(), root, nodes, footprint, configuration());
    }
    public static String configuration() throws ReflectiveOperationException {
        var values = new TreeMap<String, String>();
        Class<?> configs = type("config.DTConfigs");
        for (String section : List.of("SERVER", "COMMON")) {
            Object config = configs.getField(section).get(null);
            for (Field field : configs.getFields()) if (ModConfigSpec.ConfigValue.class.isAssignableFrom(field.getType())) {
                Object current = field.get(config); if (current instanceof ModConfigSpec.ConfigValue<?> value)
                    values.put(section + "." + field.getName(), String.valueOf(value.get()));
            }
        }
        return AiJson.digest(AiJson.canonical(values));
    }
    public static boolean nativeSpeciesStore(BlockState state, net.minecraft.world.level.block.entity.BlockEntity entity) {
        if (entity == null || !entity.getClass().getName().equals(BASE + "block.soil.SpeciesBlockEntity")) return false;
        try { return type("block.soil.SoilBlock").isInstance(state.getBlock()); }
        catch (ClassNotFoundException | LinkageError unavailable) { return false; }
    }
    public static void requireRootSpecies(ServerPlayer owner, Survey survey) throws ReflectiveOperationException {
        Object block = owner.serverLevel().getBlockState(survey.root()).getBlock();
        Class<?> soil = type("block.soil.SoilBlock");
        if (!soil.isInstance(block)) throw new IllegalStateException("TREE_ROOT_CHANGED");
        // SoilBlock also declares colorMultiplier(BlockColors,...). Class.getMethod enumerates
        // that client-only signature on a dedicated server; resolve only this audited descriptor.
        Object species = invokeVirtual(soil, "getSpecies", type("tree.species.Species"), block,
                new Class<?>[]{BlockState.class, LevelAccessor.class, BlockPos.class},
                owner.serverLevel().getBlockState(survey.root()), owner.serverLevel(), survey.root());
        if (!survey.species().equals(String.valueOf(call(species, "getRegistryName")))) throw new IllegalStateException("TREE_ROOT_SPECIES_CHANGED");
    }
    static Object invokeVirtual(Class<?> contract, String name, Class<?> result, Object receiver,
                                Class<?>[] parameters, Object... arguments) throws ReflectiveOperationException {
        try {
            return MethodHandles.publicLookup().findVirtual(contract, name, MethodType.methodType(result, parameters))
                    .bindTo(receiver).invokeWithArguments(arguments);
        } catch (ReflectiveOperationException missing) { throw missing; }
        catch (RuntimeException | Error nativeFailure) { throw nativeFailure; }
        catch (Throwable nativeFailure) { throw new InvocationTargetException(nativeFailure); }
    }
    public static void requireSafeFallingSweep(ServerPlayer player, Survey survey, BlockPos anchor) throws ReflectiveOperationException {
        // The actual FalloverAnimationHandler collision list is checked before its first
        // target velocity/hurt call. A sphere rejects the nearby owner even when the native
        // falling geometry never touches them, and cannot substitute for a live collision gate.
        thread(player); requireVersion();
    }
    public static Object call(Object receiver, String name) throws ReflectiveOperationException { return receiver.getClass().getMethod(name).invoke(receiver); }
    static Class<?> type(String name) throws ClassNotFoundException { return Class.forName(BASE + name); }
    static void thread(ServerPlayer player) { if (!player.server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED"); }
    static boolean contains(BuildRegionStore.Bounds bounds, BlockPos pos) { return bounds.contains(new BlueprintDocument.Position(pos.getX(), pos.getY(), pos.getZ())); }
    static BlockPos nativePos(BlueprintDocument.Position pos) { return new BlockPos(pos.x(), pos.y(), pos.z()); }
}
