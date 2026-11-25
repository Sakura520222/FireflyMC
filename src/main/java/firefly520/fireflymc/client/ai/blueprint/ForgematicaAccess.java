package firefly520.fireflymc.client.ai.blueprint;

import firefly520.fireflymc.ai.blueprint.BlueprintDocument;
import firefly520.fireflymc.ai.blueprint.BlueprintTransform;
import firefly520.fireflymc.ai.blueprint.LitematicSourceValidator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BiConsumer;

/** Version-specific reflective boundary. No optional mod type appears in a field or method signature. */
final class ForgematicaAccess {
    private final Method manager, selected, add, remove, select;
    private final Method schematic, origin, rotation, mirror, enabled, regions, hash, saved;
    private final Method areaSizes, areaPositions, container, file, metadata, modified, totalBlocks;
    private final Method regionName, regionPos, regionRotation, regionMirror, regionEnabled;
    private final Method get, set, containerSize, empty, create, saveFlag;
    private final Constructor<?> selectionConstructor, boxConstructor;
    private final Method selectionName, selectionOrigin, addBox;
    private final Class<?> listenerType, flagType;
    private final Method eventInstance, register;
    private Object retainedListener;

    ForgematicaAccess() throws ReflectiveOperationException {
        Class<?> dm = type("data.DataManager"), pm = type("schematic.placement.SchematicPlacementManager");
        Class<?> p = type("schematic.placement.SchematicPlacement"), s = type("schematic.LitematicaSchematic");
        Class<?> r = type("schematic.placement.SubRegionPlacement"), c = type("schematic.container.LitematicaBlockStateContainer");
        Class<?> a = type("selection.AreaSelection"), b = type("selection.Box"), m = type("schematic.SchematicMetadata");
        manager = method(dm, "getSchematicPlacementManager", true, pm);
        selected = method(pm, "getSelectedSchematicPlacement", false, p);
        add = method(pm, "addSchematicPlacement", false, void.class, p, boolean.class);
        remove = method(pm, "removeSchematicPlacement", false, boolean.class, p, boolean.class);
        select = method(pm, "setSelectedSchematicPlacement", false, void.class, p);
        schematic = method(p, "getSchematic", false, s);
        origin = method(p, "getOrigin", false, BlockPos.class);
        rotation = method(p, "getRotation", false, Rotation.class);
        mirror = method(p, "getMirror", false, Mirror.class);
        enabled = method(p, "isEnabled", false, boolean.class);
        regions = method(p, "getAllSubRegionsPlacements", false, Collection.class);
        hash = method(p, "getHashId", false, UUID.class);
        saved = method(p, "shouldBeSaved", false, boolean.class);
        areaSizes = method(s, "getAreaSizes", false, Map.class);
        areaPositions = method(s, "getAreaPositions", false, Map.class);
        container = method(s, "getSubRegionContainer", false, c, String.class);
        file = method(s, "getFile", false, File.class);
        metadata = method(s, "getMetadata", false, m);
        modified = method(m, "wasModifiedSinceSaved", false, boolean.class);
        totalBlocks = method(m, "setTotalBlocks", false, void.class, int.class);
        regionName = method(r, "getName", false, String.class);
        regionPos = method(r, "getPos", false, BlockPos.class);
        regionRotation = method(r, "getRotation", false, Rotation.class);
        regionMirror = method(r, "getMirror", false, Mirror.class);
        regionEnabled = method(r, "isEnabled", false, boolean.class);
        get = method(c, "get", false, BlockState.class, int.class, int.class, int.class);
        set = method(c, "set", false, void.class, int.class, int.class, int.class, BlockState.class);
        containerSize = method(c, "getSize", false, Vec3i.class);
        empty = method(s, "createEmptySchematic", true, s, a, String.class);
        create = method(p, "createFor", true, p, s, BlockPos.class, String.class, boolean.class, boolean.class, UUID.class);
        saveFlag = method(p, "setShouldBeSaved", false, void.class, boolean.class);
        selectionConstructor = a.getConstructor();
        boxConstructor = b.getConstructor(BlockPos.class, BlockPos.class, String.class);
        selectionName = method(a, "setName", false, void.class, String.class);
        selectionOrigin = method(a, "setExplicitOrigin", false, void.class, BlockPos.class);
        addBox = method(a, "addSubRegionBox", false, boolean.class, b, boolean.class);
        Class<?> events = type("schematic.placement.SchematicPlacementEventHandler");
        listenerType = type("interfaces.ISchematicPlacementEventListener");
        flagType = type("schematic.placement.SchematicPlacementEventFlag");
        eventInstance = method(events, "getInstance", true, events);
        register = method(events, "registerSchematicPlacementEventListener", false, void.class, listenerType, List.class);
    }

    record Region(Object nativeRegion, Object nativeContainer, String name, BlockPos originalPosition,
                  BlockPos signedSize, BlockPos position, Rotation rotation, Mirror mirror, boolean enabled,
                  BlueprintTransform transform) {
        boolean same(Region other) {
            return nativeRegion == other.nativeRegion && nativeContainer == other.nativeContainer && name.equals(other.name)
                    && originalPosition.equals(other.originalPosition) && signedSize.equals(other.signedSize)
                    && position.equals(other.position) && rotation == other.rotation && mirror == other.mirror && enabled == other.enabled;
        }
    }
    record Snapshot(Object manager, Object placement, Object schematic, UUID hash, BlockPos origin,
                    Rotation rotation, Mirror mirror, boolean enabled, boolean modified, Path source,
                    Map<String, BlockPos> originalPositions, Map<String, BlockPos> sizes, List<Region> regions) {
        boolean same(Snapshot other) {
            if (manager != other.manager || placement != other.placement || schematic != other.schematic
                    || !hash.equals(other.hash) || !origin.equals(other.origin) || rotation != other.rotation
                    || mirror != other.mirror || enabled != other.enabled || modified != other.modified
                    || !Objects.equals(source, other.source) || !originalPositions.equals(other.originalPositions)
                    || !sizes.equals(other.sizes) || regions.size() != other.regions.size()) return false;
            for (int i = 0; i < regions.size(); i++) if (!regions.get(i).same(other.regions.get(i))) return false;
            return true;
        }
    }

    Object manager() throws ReflectiveOperationException { return nonnull(call(manager, null)); }
    Object selected(Object owner) throws ReflectiveOperationException { return call(selected, owner); }

    Snapshot capture(Object owner, Object placement) throws ReflectiveOperationException {
        Object s = nonnull(call(schematic, placement));
        BlockPos o = ((BlockPos) nonnull(call(origin, placement))).immutable();
        Rotation rot = (Rotation) nonnull(call(rotation, placement));
        Mirror mir = (Mirror) nonnull(call(mirror, placement));
        BlueprintDocument.checkWorld(position(o));
        Map<String, BlockPos> sizes = positions(call(areaSizes, s));
        Map<String, BlockPos> originals = positions(call(areaPositions, s));
        if (!sizes.keySet().equals(originals.keySet())) throw invalid("SOURCE_REGION_MISMATCH");
        Collection<?> raw = (Collection<?>) nonnull(call(regions, placement));
        if (raw.size() > LitematicSourceValidator.MAX_REGIONS) throw invalid("SOURCE_REGION_LIMIT");
        List<Region> result = new ArrayList<>();
        long volume = 0;
        for (Object sub : raw) {
            String name = (String) nonnull(call(regionName, sub));
            BlockPos size = sizes.get(name), initial = originals.get(name);
            if (size == null || initial == null) throw invalid("SOURCE_REGION_MISMATCH");
            for (int n : new int[] {size.getX(), size.getY(), size.getZ()})
                if (n == 0 || Math.abs((long) n) > LitematicSourceValidator.MAX_AXIS) throw invalid("SOURCE_AXIS_LIMIT");
            BlockPos pos = ((BlockPos) nonnull(call(regionPos, sub))).immutable();
            Rotation sr = (Rotation) nonnull(call(regionRotation, sub));
            Mirror sm = (Mirror) nonnull(call(regionMirror, sub));
            var transform = new BlueprintTransform(size, pos, o, rot, mir, sr, sm);
            volume += transform.volume();
            if (volume > BlueprintDocument.MAX_BLOCKS) throw invalid("SOURCE_CELL_LIMIT");
            Object cells = nonnull(call(container, s, name));
            if (!transform.containerSize().equals(call(containerSize, cells))) throw invalid("SOURCE_CONTAINER_SIZE_MISMATCH");
            result.add(new Region(sub, cells, name, initial, size, pos, sr, sm,
                    (boolean) call(regionEnabled, sub), transform));
        }
        if (result.size() != sizes.size() || result.stream().map(Region::name).distinct().count() != result.size())
            throw invalid("SOURCE_REGION_MISMATCH");
        result.sort(Comparator.comparing(Region::name));
        File source = (File) call(file, s);
        return new Snapshot(owner, placement, s, (UUID) nonnull(call(hash, placement)), o, rot, mir,
                (boolean) call(enabled, placement), (boolean) call(modified, nonnull(call(metadata, s))),
                source == null ? null : source.toPath().toAbsolutePath().normalize(), originals, sizes, List.copyOf(result));
    }

    BlockState get(Object cells, int x, int y, int z) throws ReflectiveOperationException {
        return (BlockState) nonnull(call(get, cells, x, y, z));
    }
    void set(Object cells, int x, int y, int z, BlockState state) throws ReflectiveOperationException {
        call(set, cells, x, y, z, state);
    }
    Object empty(String name, Vec3i size) throws ReflectiveOperationException {
        Object area = selectionConstructor.newInstance();
        call(selectionName, area, name);
        call(selectionOrigin, area, BlockPos.ZERO);
        Object box = boxConstructor.newInstance(BlockPos.ZERO,
                new BlockPos(size.getX() - 1, size.getY() - 1, size.getZ() - 1), "fireflymc_task");
        if (!(boolean) call(addBox, area, box, false)) throw invalid("PREVIEW_REGION_CREATE_FAILED");
        return nonnull(call(empty, null, area, "FireflyMC"));
    }
    Object container(Object s) throws ReflectiveOperationException { return nonnull(call(container, s, "fireflymc_task")); }
    Object placement(Object s, BlockPos pos, String name, UUID id, int count) throws ReflectiveOperationException {
        call(totalBlocks, nonnull(call(metadata, s)), count);
        Object result = nonnull(call(create, null, s, pos, name, true, true, id));
        call(saveFlag, result, false);
        if ((boolean) call(saved, result)) throw invalid("PREVIEW_PERSISTENCE_NOT_DISABLED");
        return result;
    }
    void add(Object owner, Object p) throws ReflectiveOperationException { call(add, owner, p, false); }
    void remove(Object owner, Object p) throws ReflectiveOperationException { call(remove, owner, p, true); }
    void select(Object owner, Object p) throws ReflectiveOperationException { call(select, owner, p); }

    void listen(BiConsumer<String, Object> callback) throws ReflectiveOperationException {
        if (retainedListener != null) return;
        Object proxy = Proxy.newProxyInstance(listenerType.getClassLoader(), new Class<?>[] {listenerType},
                (self, method, args) -> {
                    if (method.getName().equals("hashCode")) return System.identityHashCode(self);
                    if (method.getName().equals("equals")) return args != null && self == args[0];
                    if (method.getName().equals("toString")) return "FireflyMC blueprint listener";
                    if (args != null && args.length > 0
                            && (method.getName().equals("onPlacementUpdated") || method.getName().equals("onPlacementRemoved")))
                        callback.accept(method.getName(), args[0]);
                    return null;
                });
        Object updated = flagType.getField("ON_UPDATED").get(null), removed = flagType.getField("ON_REMOVED").get(null);
        call(register, nonnull(call(eventInstance, null)), proxy, List.of(updated, removed));
        retainedListener = proxy;
    }

    private static Map<String, BlockPos> positions(Object raw) {
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty() || map.size() > LitematicSourceValidator.MAX_REGIONS)
            throw invalid("SOURCE_REGION_LIMIT");
        Map<String, BlockPos> result = new TreeMap<>();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String name) || name.isBlank() || name.length() > 128
                    || !(entry.getValue() instanceof BlockPos pos)) throw invalid("SOURCE_REGION_INVALID");
            result.put(name, pos.immutable());
        }
        return java.util.Collections.unmodifiableMap(result);
    }
    static BlueprintDocument.Position position(Vec3i pos) {
        return new BlueprintDocument.Position(pos.getX(), pos.getY(), pos.getZ());
    }
    private static Class<?> type(String suffix) throws ClassNotFoundException {
        return Class.forName("fi.dy.masa.litematica." + suffix, false, ForgematicaAccess.class.getClassLoader());
    }
    private static Method method(Class<?> owner, String name, boolean isStatic, Class<?> returns, Class<?>... args)
            throws NoSuchMethodException {
        Method method = owner.getMethod(name, args);
        if (Modifier.isStatic(method.getModifiers()) != isStatic || method.getReturnType() != returns)
            throw new NoSuchMethodException(owner.getName() + "." + name);
        return method;
    }
    private static Object call(Method method, Object target, Object... args) throws ReflectiveOperationException {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Error error) throw error;
            throw exception;
        }
    }
    private static Object nonnull(Object value) { return Objects.requireNonNull(value, "OPTIONAL_API_NULL"); }
    private static IllegalArgumentException invalid(String code) { return new IllegalArgumentException(code); }
}
