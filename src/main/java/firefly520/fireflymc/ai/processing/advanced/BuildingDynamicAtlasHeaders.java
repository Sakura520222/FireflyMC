package firefly520.fireflymc.ai.processing.advanced;

import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.mixin.AiSavedDataCacheAccess;
import java.lang.invoke.*;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.*;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.*;
import net.neoforged.fml.ModList;

/** Cache-only, header-only read view for the exact inspected atlas recipes. No pixels or map IDs are generated. */
public final class BuildingDynamicAtlasHeaders implements AutoCloseable {
    public static final int MAX_MAP_IDS = 64;
    private static final ThreadLocal<BuildingDynamicAtlasHeaders> ACTIVE = new ThreadLocal<>();
    public record Header(int id, int centerX, int centerZ, byte scale, String dimension, boolean locked) { }
    private record Bound(MapItemSavedData source, Header header, MapItemSavedData metadata, Object holder) { }
    private record CollectionView(Object source, Object isolated) { }
    private final ServerPlayer owner;
    private final ServerLevel level;
    private final Abi abi;
    private final Map<Integer, Bound> headers = new TreeMap<>();
    private final IdentityHashMap<ItemStack, CollectionView> collections = new IdentityHashMap<>();
    private boolean guarded;

    private BuildingDynamicAtlasHeaders(ServerPlayer owner) { this(owner, null); }
    private BuildingDynamicAtlasHeaders(ServerPlayer owner, Abi preparedAbi) {
        this.owner = Objects.requireNonNull(owner); level = owner.serverLevel();
        // This is a cache-only calculation, also used by the native recipe validation server.
        // AI service initialization, dedicated-server admission and authentication stay at the
        // service/tool boundary; this helper never initializes any of those services.
        if (!owner.server.isSameThread() || ACTIVE.get() != null)
            throw failure("ATLAS_HEADER_SCOPE_INVALID");
        String version = ModList.get().getModContainerById("map_atlases").map(mod -> mod.getModInfo().getVersion().toString()).orElse("");
        if (!"1.21-6.6.0".equals(version)) throw failure("ATLAS_RECIPE_VERSION_UNSUPPORTED");
        abi = preparedAbi == null ? new Abi() : preparedAbi;
        if (!BuildingDynamicAtlasHooks.ready()) throw failure("ATLAS_HEADER_HOOK_UNAVAILABLE");
        ACTIVE.set(this);
    }
    public static BuildingDynamicAtlasHeaders begin(ServerPlayer owner) { return new BuildingDynamicAtlasHeaders(owner); }
    /** Precomputed local headers can be reused inside a native effect scope without creating SavedData. */
    public static final class Prepared {
        private final ServerPlayer owner;
        private final ServerLevel level;
        private final Abi abi;
        private final Map<Integer, Bound> headers;
        private final String digest;
        private Prepared(BuildingDynamicAtlasHeaders scope) {
            owner = scope.owner; level = scope.level; abi = scope.abi; headers = Map.copyOf(scope.headers); digest = scope.digest();
        }
        public BuildingDynamicAtlasHeaders begin(ServerPlayer actor) {
            if (actor != owner || actor.serverLevel() != level) throw failure("ATLAS_HEADER_OWNER_CHANGED");
            var scope = new BuildingDynamicAtlasHeaders(actor, abi);
            try { scope.headers.putAll(headers); scope.guarded = true; scope.validate(); return scope; }
            catch (RuntimeException | Error invalid) { scope.close(); throw invalid; }
        }
        public String digest() { return digest; }
    }
    public static boolean active() { return ACTIVE.get() != null; }
    /** Must run before input cloning and before entering the actual crafted callback's native-effect scope. */
    public void guardRawInputs(List<ItemStack> inputs) {
        requireScope();
        if (guarded || inputs == null || inputs.size() > 81) throw failure("ATLAS_HEADER_INPUT_SCOPE");
        for (ItemStack stack : inputs) {
            if (stack == null) throw failure("ATLAS_HEADER_INPUT_SCOPE");
            if (stack.isEmpty()) continue;
            if (stack.getItem() == abi.atlas) {
                for (var entry : ids(stack).entrySet()) {
                    if (entry.getKey() != abi.vanilla && !entry.getValue().isEmpty()) throw failure("ATLAS_NON_VANILLA_MAP_UNSUPPORTED");
                    for (MapId id : entry.getValue()) bind(id, entry.getKey());
                }
            } else {
                Object type = call(abi.fromFilled, stack.getItem());
                if (type != null) {
                    if (type != abi.vanilla) throw failure("ATLAS_NON_VANILLA_MAP_UNSUPPORTED");
                    MapId id = (MapId) call(abi.getId, type, stack); bind(id, type);
                }
            }
        }
        guarded = true; validate();
    }
    public void bindInput(CraftingInput input) {
        requireGuarded();
        if (!abi.inputContext.isInstance(input)) throw failure("ATLAS_CRAFTING_CONTEXT_UNAVAILABLE");
        // Native cutting only reads this real owner's position. No menu is opened and no slots move.
        call(abi.setMenu, input, owner.inventoryMenu);
    }
    public void finalizeOutput(ItemStack output) {
        requireGuarded();
        if (!output.isEmpty() && output.getItem() == abi.atlas) {
            requireKnownIds(ids(output)); call(abi.validateSlices, output, level);
        }
    }
    public boolean hasFilledMapIds() { requireGuarded(); return !headers.isEmpty(); }
    public Prepared freeze() { requireGuarded(); validate(); return new Prepared(this); }
    public void validate() {
        requireScope();
        var cache = ((AiSavedDataCacheAccess) owner.server.overworld().getDataStorage()).fireflymc$existingStores();
        for (var entry : headers.entrySet()) {
            Bound bound = entry.getValue();
            if (cache.get(new MapId(entry.getKey()).key()) != bound.source()
                    || !header(new MapId(entry.getKey()), bound.source()).equals(bound.header())) throw failure("ATLAS_MAP_HEADER_CHANGED");
            if (!header(new MapId(entry.getKey()), bound.metadata()).equals(bound.header())) throw failure("ATLAS_LOCAL_HEADER_CHANGED");
            requireNoSlice(bound.source());
            requireNoSlice(bound.metadata());
        }
    }
    public String digest() {
        requireGuarded(); validate();
        return AiJson.digest(AiJson.canonical(Map.of("dimension", level.dimension().location().toString(),
                "headers", headers.values().stream().map(Bound::header).toList())));
    }
    public static Object find(MapId id, Object type, Level level) {
        var scope = required(); scope.requireLevel(level); scope.requireGuarded();
        if (type != scope.abi.vanilla || id == null) throw failure("ATLAS_NON_VANILLA_MAP_UNSUPPORTED");
        Bound bound = scope.headers.get(id.id());
        if (bound == null) throw failure("ATLAS_MAP_NOT_IN_INPUT_SCOPE");
        return bound.holder();
    }
    public static Object collection(ItemStack stack, Level level) {
        var scope = required(); scope.requireLevel(level); scope.requireGuarded();
        Object source = stack.get(scope.abi.component);
        var prior = scope.collections.get(stack);
        if (prior != null && prior.source() == source) return prior.isolated();
        Map<Object, List<MapId>> ids = scope.ids(stack); scope.requireKnownIds(ids);
        Object detached = call(scope.abi.collectionConstructor, ids);
        call(scope.abi.initialize, detached, level);
        scope.collections.put(stack, new CollectionView(source, detached));
        if (scope.collections.size() > 256) throw failure("ATLAS_COLLECTION_VIEW_LIMIT");
        return detached;
    }
    private void bind(MapId id, Object type) {
        if (type != abi.vanilla || id == null || id.id() < 0) throw failure("ATLAS_MAP_ID_INVALID");
        if (headers.containsKey(id.id())) return;
        if (headers.size() >= MAX_MAP_IDS) throw failure("ATLAS_HEADER_LIMIT");
        Object existing = ((AiSavedDataCacheAccess) owner.server.overworld().getDataStorage()).fireflymc$existingStores().get(id.key());
        if (existing == null) throw failure("ATLAS_MAP_HEADER_NOT_LOADED");
        if (existing.getClass() != MapItemSavedData.class) throw failure("ATLAS_MAP_HEADER_TYPE_UNSUPPORTED");
        var map = (MapItemSavedData) existing; requireNoSlice(map);
        Header header = header(id, map);
        if (header.scale() < 0 || header.scale() > 4) throw failure("ATLAS_MAP_HEADER_INVALID");
        // This local, unregistered metadata projection never copies colors, decorations or markers.
        var metadata = (MapItemSavedData) call(abi.mapConstructor, header.centerX(), header.centerZ(), header.scale(), false, false, header.locked(), map.dimension);
        Object holder = call(abi.holderConstructor, id, type, metadata);
        headers.put(id.id(), new Bound(map, header, metadata, holder));
    }
    @SuppressWarnings("unchecked")
    private Map<Object, List<MapId>> ids(ItemStack stack) {
        Object component = stack.get(abi.component);
        if (component == null || component.getClass() != abi.collectionType) throw failure("ATLAS_COLLECTION_COMPONENT_INVALID");
        Map<?, ?> raw = (Map<?, ?>) call(abi.ids, component);
        var result = new LinkedHashMap<Object, List<MapId>>(); int count = 0;
        for (var entry : raw.entrySet()) {
            if (!(entry.getValue() instanceof List<?> values)) throw failure("ATLAS_COLLECTION_COMPONENT_INVALID");
            if (values.size() > MAX_MAP_IDS || (count += values.size()) > MAX_MAP_IDS) throw failure("ATLAS_HEADER_LIMIT");
            var list = new ArrayList<MapId>();
            for (Object value : values) { if (!(value instanceof MapId id) || id.id() < 0) throw failure("ATLAS_MAP_ID_INVALID"); list.add(id); }
            result.put(entry.getKey(), List.copyOf(list));
        }
        return result;
    }
    private void requireKnownIds(Map<Object, List<MapId>> ids) {
        for (var entry : ids.entrySet()) {
            if (entry.getKey() != abi.vanilla && !entry.getValue().isEmpty()) throw failure("ATLAS_NON_VANILLA_MAP_UNSUPPORTED");
            for (MapId id : entry.getValue()) if (!headers.containsKey(id.id())) throw failure("ATLAS_MAP_NOT_IN_INPUT_SCOPE");
        }
    }
    private void requireNoSlice(MapItemSavedData map) {
        if (abi.depth != null) {
            Object result = call(abi.depth, map);
            if (!(result instanceof Optional<?> value) || value.isPresent()) throw failure("ATLAS_SLICED_METADATA_UNSUPPORTED");
        }
    }
    private static Header header(MapId id, MapItemSavedData map) { return new Header(id.id(), map.centerX, map.centerZ, map.scale, map.dimension.location().toString(), map.locked); }
    private void requireGuarded() { requireScope(); if (!guarded) throw failure("ATLAS_HEADER_INPUT_SCOPE"); }
    private void requireScope() { if (ACTIVE.get() != this || !owner.server.isSameThread() || owner.serverLevel() != level) throw failure("ATLAS_HEADER_SCOPE_CHANGED"); }
    private void requireLevel(Level supplied) { requireScope(); if (supplied != level) throw failure("ATLAS_HEADER_LEVEL_CHANGED"); }
    private static BuildingDynamicAtlasHeaders required() { var scope = ACTIVE.get(); if (scope == null) throw failure("ATLAS_HEADER_SCOPE_REQUIRED"); return scope; }
    @Override public void close() { if (ACTIVE.get() == this) ACTIVE.remove(); }
    private static IllegalStateException failure(String code) { return new IllegalStateException(code); }
    private static Object call(MethodHandle method, Object... arguments) {
        try { return method.invokeWithArguments(arguments); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("ATLAS_HEADER_CONTRACT_UNAVAILABLE"); }
    }
    private static final class Abi {
        final Class<?> collectionType = type("map_collection.MapCollection"), mapType = type("utils.MapType"), holderType = type("utils.MapDataHolder");
        final Class<?> inputContext = type("utils.ICraftingInputWithContext"), itemType = type("item.MapAtlasItem");
        final Object vanilla;
        final Item atlas;
        final DataComponentType<Object> component;
        final MethodHandle ids, collectionConstructor, initialize, holderConstructor, mapConstructor, fromFilled, getId, setMenu, validateSlices, depth;
        @SuppressWarnings("unchecked") Abi() {
            try {
                Class<?> mod = type("MapAtlasesMod"); var lookup = MethodHandles.publicLookup();
                vanilla = lookup.findStaticGetter(mapType, "VANILLA", mapType).invoke();
                atlas = (Item) ((Supplier<?>) lookup.findStaticGetter(mod, "MAP_ATLAS", Supplier.class).invoke()).get();
                component = (DataComponentType<Object>) ((Supplier<?>) lookup.findStaticGetter(mod, "MAP_COLLECTION", Supplier.class).invoke()).get();
                ids = lookup.findVirtual(collectionType, "getIdsCopy", MethodType.methodType(Map.class));
                initialize = lookup.findVirtual(collectionType, "initialize", MethodType.methodType(void.class, Level.class));
                collectionConstructor = MethodHandles.privateLookupIn(collectionType, MethodHandles.lookup()).findConstructor(collectionType, MethodType.methodType(void.class, Map.class));
                holderConstructor = lookup.findConstructor(holderType, MethodType.methodType(void.class, MapId.class, mapType, MapItemSavedData.class));
                mapConstructor = MethodHandles.privateLookupIn(MapItemSavedData.class, MethodHandles.lookup()).findConstructor(MapItemSavedData.class,
                        MethodType.methodType(void.class, int.class, int.class, byte.class, boolean.class, boolean.class, boolean.class, ResourceKey.class));
                fromFilled = lookup.findStatic(mapType, "fromFilledMap", MethodType.methodType(mapType, Item.class));
                getId = lookup.findVirtual(mapType, "getMapId", MethodType.methodType(MapId.class, ItemStack.class));
                setMenu = lookup.findVirtual(inputContext, "mapAtlases$setMenu", MethodType.methodType(void.class, AbstractContainerMenu.class));
                validateSlices = MethodHandles.privateLookupIn(itemType, MethodHandles.lookup()).findStatic(itemType, "validateSelectedSlices", MethodType.methodType(void.class, ItemStack.class, Level.class));
                depth = ModList.get().isLoaded("supplementaries") ? lookup.findStatic(Class.forName("net.mehvahdjukaar.supplementaries.common.misc.map_data.DepthDataHandler", false,
                        BuildingDynamicAtlasHeaders.class.getClassLoader()), "getMapHeight", MethodType.methodType(Optional.class, MapItemSavedData.class)) : null;
            } catch (Throwable invalid) { throw failure("ATLAS_HEADER_CONTRACT_UNAVAILABLE"); }
        }
        private static Class<?> type(String path) {
            try { return Class.forName("pepjebs.mapatlases." + path, false, BuildingDynamicAtlasHeaders.class.getClassLoader()); }
            catch (ClassNotFoundException unavailable) { throw failure("ATLAS_HEADER_CONTRACT_UNAVAILABLE"); }
        }
    }
}
