package firefly520.fireflymc.ai.blueprint;

import java.lang.reflect.Method;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.neoforged.fml.ModList;

/** Checks installed native break semantics before any accepted mining callback can change the world. */
public final class NativeRemovalProfiles {
    private record Klee(Method active, Method converter, Method doubled, Class<?> horizontal, Method single) {}
    private static Klee klee;
    private static final Map<net.minecraft.server.MinecraftServer, Boolean> EXECUTED = Collections.synchronizedMap(new WeakHashMap<>());
    private NativeRemovalProfiles() {}
    public static void require(ServerPlayer owner, BlockState state) {
        require(owner, BlockPos.ZERO, state, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
    }
    public static void require(ServerPlayer owner, BlockPos pos, BlockState before, BlockState target) {
        NativePlacementProfiles.require(before);
        var remaining = remaining(owner, pos, before, !target.isAir() && target.hasProperty(SlabBlock.TYPE) && target.getValue(SlabBlock.TYPE) == SlabType.TOP ? SlabType.TOP : SlabType.BOTTOM);
        if (remaining.isPresent() && !remaining.get().equals(target)) throw new IllegalStateException("REMOVAL_PARTIAL_SLAB_PLAN_REQUIRED");
        if (remaining.isEmpty() && !target.isAir()) throw new IllegalStateException("REMOVAL_PARTIAL_SLAB_MODE_CHANGED");
    }
    /** Actual native converter determines the fixed first phase; whole-cell removal starts with the upper half. */
    public static Optional<BlockState> remaining(ServerPlayer owner, BlockPos pos, BlockState state) {
        return remaining(owner, pos, state, SlabType.BOTTOM);
    }
    public static Optional<BlockState> remaining(ServerPlayer owner, BlockPos pos, BlockState state, SlabType keep) {
        if (keep != SlabType.BOTTOM && keep != SlabType.TOP) throw new IllegalArgumentException("REMOVAL_SLAB_PART_INVALID");
        NativePlacementProfiles.require(state);
        if (!ModList.get().isLoaded("kleeslabs")) return Optional.empty();
        String version = ModList.get().getModContainerById("kleeslabs").orElseThrow().getModInfo().getVersion().toString();
        if (!version.equals("21.1.11")) throw new IllegalStateException("KLEESLABS_VERSION_UNSUPPORTED");
        try {
            bind();
            Object converter = klee.converter().invoke(null, state);
            if (converter == null || !Boolean.TRUE.equals(klee.doubled().invoke(converter, state)) || !Boolean.TRUE.equals(klee.active().invoke(null, owner))) return Optional.empty();
            if (state.getBlock().getClass() != SlabBlock.class || !klee.horizontal().isInstance(converter)) throw new IllegalStateException("REMOVAL_SLAB_CONVERTER_UNSUPPORTED");
            var remaining = (BlockState) klee.single().invoke(converter, state, owner.serverLevel(), pos, owner, keep);
            var removed = (BlockState) klee.single().invoke(converter, state, owner.serverLevel(), pos, owner, keep == SlabType.BOTTOM ? SlabType.TOP : SlabType.BOTTOM);
            var original = BlueprintBlockStateCodec.describe(state);
            if (!RemovalTransitions.halfSlab(original, BlueprintBlockStateCodec.describe(remaining)) || !RemovalTransitions.halfSlab(original, BlueprintBlockStateCodec.describe(removed)))
                throw new IllegalStateException("REMOVAL_SLAB_CONVERTER_STATE_UNSUPPORTED");
            return Optional.of(remaining);
        } catch (ReflectiveOperationException | LinkageError failed) {
            throw new IllegalStateException("KLEESLABS_NATIVE_CONTRACT_UNAVAILABLE", failed);
        }
    }
    private static void bind() throws ReflectiveOperationException {
        if (klee == null) klee = new Klee(Class.forName("net.blay09.mods.kleeslabs.KleeSlabs").getMethod("isPlayerKleeSlabbing", Player.class),
                Class.forName("net.blay09.mods.kleeslabs.registry.SlabRegistry").getMethod("getSlabConverter", BlockState.class),
                Class.forName("net.blay09.mods.kleeslabs.converter.SlabConverter").getMethod("isDoubleSlab", BlockState.class),
                Class.forName("net.blay09.mods.kleeslabs.converter.HorizontalSlabConverter"),
                Class.forName("net.blay09.mods.kleeslabs.converter.HorizontalSlabConverter").getMethod("getSingleSlab", BlockState.class, Level.class, BlockPos.class, Player.class, SlabType.class));
    }
    static void nativeExecuted(net.minecraft.server.MinecraftServer server) { EXECUTED.put(server, true); }
    public static com.google.gson.JsonObject evidence(net.minecraft.server.MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        var data = new com.google.gson.JsonObject(); String loaded = ModList.get().getModContainerById("kleeslabs").map(c -> c.getModInfo().getVersion().toString()).orElse("");
        String state = loaded.isEmpty() ? "ABSENT" : !loaded.equals("21.1.11") ? "VERSION_UNSUPPORTED" : "INTERFACE_MATCHED";
        if (state.equals("INTERFACE_MATCHED")) {
            try { bind(); if (EXECUTED.containsKey(server)) state = "RUNTIME_READY"; }
            catch (ReflectiveOperationException | LinkageError unavailable) { state = "API_UNAVAILABLE"; }
        }
        data.addProperty("modId", "kleeslabs"); data.addProperty("loadedVersion", loaded); data.addProperty("inspectedVersion", "21.1.11"); data.addProperty("state", state);
        data.addProperty("capability", "水平双层半砖分阶段拆除"); data.addProperty("behaviorVerifiedHere", false);
        data.addProperty("evidence", "ev_e8048c9c6c233b75079f5b1025b59ad9674a727c8ec037c57426007179112d53");
        data.addProperty("scope", "已提供固定两阶段计划、真实掉落托管、暂停续办及撤销来源核验；按本服模式、工具和保护逐次检查。接口匹配不代表当前连接已完成全套行为验收，特殊或垂直转换器另行适配。");
        return data;
    }
}
