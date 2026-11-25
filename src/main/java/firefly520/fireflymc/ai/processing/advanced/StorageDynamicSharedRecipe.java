package firefly520.fireflymc.ai.processing.advanced;

import com.google.common.cache.Cache;
import firefly520.fireflymc.ai.blueprint.NativeConstructionSave;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.processing.RecipeInputIsolation;
import firefly520.fireflymc.ai.processing.VerifiedTransformation.SamplingEvidence;
import firefly520.fireflymc.mixin.AiSavedDataCacheAccess;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

/** Native shared-storage recipes: isolated selected-entry preview and a journal-owned native commit. */
final class StorageDynamicSharedRecipe {
    private static final String STORE = "net.p3pp3rf1y.sophisticatedstorage.block.ItemContentsStorage";
    private static final String WRAPPER = "net.p3pp3rf1y.sophisticatedstorage.item.StackStorageWrapper";
    private static final String ITEM = "net.p3pp3rf1y.sophisticatedstorage.item.StorageBlockItem";
    private static final String REPOSITORY = "net.p3pp3rf1y.sophisticatedcore.inventory.StorageWrapperRepository";
    private static final String STORE_ID = "sophisticatedstorage";
    private record NativeResult(ItemStack output, List<ItemStack> remainders) { }
    /** Immutable codecs and identifiers only; no world, player, ItemStack or native SavedData is retained. */
    private record Commit(UUID operation, String recipe, String nativeClass, List<MaterialStack> inputs, MaterialStack output, List<MaterialStack> remainders,
                          Set<UUID> sources, Map<String, String> before, Map<String, String> after, boolean vanillaMigration) {
        Commit {
            inputs = List.copyOf(inputs); remainders = List.copyOf(remainders); sources = Set.copyOf(sources); before = Map.copyOf(before); after = Map.copyOf(after);
        }
    }
    private StorageDynamicSharedRecipe() { }

    static AdvancedTransformation verify(RecipeHolder<?> holder, List<ItemStack> selected, ServerPlayer player,
                                          StorageDynamicRecipes.Profile profile) {
        AdvancedReflection.current(holder, player.serverLevel()); requireHooks();
        UUID operation = DynamicRecipeOperationScope.current();
        if (operation == null) throw AdvancedReflection.failure("STORAGE_DYNAMIC_OPERATION_SCOPE_REQUIRED");
        if (profile.input() != StorageDynamicRecipes.InputKind.GRID || selected.size() != 9
                || selected.stream().anyMatch(stack -> stack == null || stack.getCount() > 1))
            throw AdvancedReflection.failure("STORAGE_DYNAMIC_UNIT_INPUT_REQUIRED");
        var beforeInput = RecipeInputIsolation.copyAll(selected, player.registryAccess());
        var working = RecipeInputIsolation.copyAll(beforeInput, player.registryAccess());
        var sources = sources(beforeInput); UUID reserved = StorageDynamicNativeScope.reserved(operation);
        if (sources.contains(reserved)) throw AdvancedReflection.failure("STORAGE_DYNAMIC_OPERATION_UUID_CONFLICT");
        var template = holder.value().getResultItem(player.registryAccess());
        if (template.has(uuidComponent())) throw AdvancedReflection.failure("STORAGE_DYNAMIC_TEMPLATE_UUID_FORBIDDEN");
        capacity(template);
        SavedData real = realStore(player, !sources.isEmpty());
        var permitted = new LinkedHashSet<>(sources); permitted.add(reserved);
        var before = snapshot(real, permitted);
        if (!before.keySet().containsAll(sources.stream().map(UUID::toString).toList()))
            throw AdvancedReflection.failure("STORAGE_DYNAMIC_SOURCE_CONTENTS_MISSING");
        if (before.containsKey(reserved.toString())) throw AdvancedReflection.failure("STORAGE_DYNAMIC_OPERATION_UUID_ALREADY_PRESENT");
        SavedData shadow = shadow(before, player.registryAccess());
        if (!StorageDynamicNbt.digest(before).equals(StorageDynamicNbt.digest(snapshot(shadow, permitted))))
            throw AdvancedReflection.failure("STORAGE_DYNAMIC_LEGACY_CONTENTS_REVIEW_REQUIRED");
        boolean vanillaMigration = profile.className().endsWith("ShulkerBoxFromVanillaShapelessRecipe");
        NativeResult nativeResult; Map<String, String> after;
        try (var scope = StorageDynamicNativeScope.open(operation, shadow, true, sources)) {
            try {
                nativeResult = grid(holder, working, player);
                if (vanillaMigration) requireContainerConservation(working, nativeResult.output(), player);
                after = snapshot(shadow, permitted);
            } finally { cleanup(scope.detachedStacks()); }
        }
        for (int slot = 0; slot < 9; slot++) if (!AdvancedReflection.same(beforeInput.get(slot), working.get(slot), player.registryAccess()))
            throw AdvancedReflection.failure("STORAGE_DYNAMIC_INPUT_MUTATED");
        ItemStack output = RecipeInputIsolation.copy(nativeResult.output(), player.registryAccess());
        if (output.isEmpty() || output.getCount() != 1) throw AdvancedReflection.failure("STORAGE_DYNAMIC_SHARED_OUTPUT_INVALID");
        UUID outputId = output.get(uuidComponent());
        if (vanillaMigration && !reserved.equals(outputId) || !vanillaMigration && !sources.contains(outputId))
            throw AdvancedReflection.failure("STORAGE_DYNAMIC_NATIVE_UUID_NOT_PRESERVED");
        if (vanillaMigration && !after.containsKey(reserved.toString()))
            throw AdvancedReflection.failure("STORAGE_DYNAMIC_NATIVE_CONTENTS_NOT_CREATED");
        var data = new Commit(operation, holder.id().toString(), profile.className(),
                beforeInput.stream().map(stack -> ItemSnapshotCodec.capture(stack, player.registryAccess())).toList(),
                ItemSnapshotCodec.capture(output, player.registryAccess()),
                nativeResult.remainders().stream().map(stack -> ItemSnapshotCodec.capture(stack, player.registryAccess())).toList(), sources, before, after, vanillaMigration);
        var context = new LinkedHashMap<String, String>(); context.put("owner", player.getUUID().toString());
        context.put("profile", profile.className()); context.put("nativeBuildVersion", profile.buildVersion());
        context.put("operationId", operation.toString()); context.put("sharedStore", STORE_ID);
        context.put("sharedBeforeDigest", StorageDynamicNbt.digest(before)); context.put("sharedAfterDigest", StorageDynamicNbt.digest(after));
        context.put("sharedEntries", Integer.toString(after.size())); context.put("nativeOutputUUID", Objects.toString(outputId));
        context.put("sharedCommit", "native assemble after durable callback intent; canonical saved-data proof required");
        context.put("requiresNativeAssemblyCommit", "true");
        return new AdvancedTransformation(holder, "advanced:storage-shared-native-v1", beforeInput,
                beforeInput.stream().map(stack -> stack.isEmpty() ? 0 : 1).toList(), List.of(output), nativeResult.remainders(),
                SamplingEvidence.deterministic(), context, AdvancedTransformation.references(holder),
                List.of("native_shared_storage_assemble", "item_crafted", "neo_crafting_event", "recipe_award"),
                actor -> commit(data, actor), player.registryAccess());
    }

    private static void commit(Commit data, ServerPlayer player) {
        AdvancedReflection.thread(player.serverLevel()); requireHooks();
        var holder = player.server.getRecipeManager().byKey(ResourceLocation.parse(data.recipe()))
                .orElseThrow(() -> AdvancedReflection.failure("PROCESSING_RECIPE_STALE"));
        var profile = StorageDynamicRecipes.profile(holder.value());
        if (profile == null || !profile.className().equals(data.nativeClass())) throw AdvancedReflection.failure("STORAGE_DYNAMIC_COMMIT_PROFILE_CHANGED");
        var working = data.inputs().stream().map(snapshot -> ItemSnapshotCodec.restore(snapshot, player.registryAccess())).toList();
        SavedData real = realStore(player, true); var permitted = new LinkedHashSet<>(data.sources()); permitted.add(StorageDynamicNativeScope.reserved(data.operation()));
        var before = snapshot(real, permitted);
        if (!StorageDynamicNbt.digest(before).equals(StorageDynamicNbt.digest(data.before())))
            throw AdvancedReflection.failure("STORAGE_DYNAMIC_COMMIT_CONTENTS_CHANGED");
        NativeConstructionSave.allowCurrentLoadedSavedData(player.server.overworld(), STORE_ID, real);
        try (var operation = DynamicRecipeOperationScope.open(data.operation());
             var scope = StorageDynamicNativeScope.open(data.operation(), real, false, data.sources())) {
            try {
                var result = grid(holder, working, player);
                if (data.vanillaMigration()) requireContainerConservation(working, result.output(), player);
                if (!data.output().equals(ItemSnapshotCodec.capture(result.output(), player.registryAccess()))
                        || !data.remainders().equals(result.remainders().stream().map(stack -> ItemSnapshotCodec.capture(stack, player.registryAccess())).toList())
                        || !StorageDynamicNbt.digest(snapshot(real, permitted)).equals(StorageDynamicNbt.digest(data.after())))
                    throw AdvancedReflection.failure("STORAGE_DYNAMIC_NATIVE_COMMIT_DIFFERED");
                scope.endAssembly();
                AdvancedCraftingAdapter.crafted(holder, working, result.output(), player);
                if (!StorageDynamicNbt.digest(snapshot(real, permitted)).equals(StorageDynamicNbt.digest(data.after())))
                    throw AdvancedReflection.failure("STORAGE_DYNAMIC_CALLBACK_CONTENTS_CHANGED");
            } finally {
                // Native getters can initialise wrapper tags without dirtying their enclosing store.
                // Only an observed change to this approved entry slice schedules its real canonical write.
                if (!StorageDynamicNbt.digest(snapshot(real, permitted)).equals(StorageDynamicNbt.digest(before))) real.setDirty();
                cleanup(scope.detachedStacks());
            }
        }
    }

    private static NativeResult grid(RecipeHolder<?> holder, List<ItemStack> working, ServerPlayer player) {
        var positioned = CraftingInput.ofPositioned(3, 3, working); var input = positioned.input(); var recipe = (CraftingRecipe) holder.value();
        if (!recipe.canCraftInDimensions(3, 3) || !recipe.matches(input, player.serverLevel()))
            throw AdvancedReflection.failure("STORAGE_DYNAMIC_INPUT_MISMATCH");
        var output = recipe.assemble(input, player.registryAccess()); var remaining = recipe.getRemainingItems(input);
        if (remaining.size() != input.size()) throw AdvancedReflection.failure("STORAGE_DYNAMIC_REMAINDERS_INVALID");
        var mapped = AdvancedReflection.emptySlots(9);
        for (int y = 0; y < input.height(); y++) for (int x = 0; x < input.width(); x++)
            mapped.set((y + positioned.top()) * 3 + x + positioned.left(), RecipeInputIsolation.copy(remaining.get(y * input.width() + x), player.registryAccess()));
        return new NativeResult(output, List.copyOf(mapped));
    }
    private static void requireContainerConservation(List<ItemStack> input, ItemStack output, ServerPlayer player) {
        var source = input.stream().filter(stack -> stack.getItem() instanceof BlockItem block && block.getBlock() instanceof ShulkerBoxBlock).findFirst()
                .orElseThrow(() -> AdvancedReflection.failure("STORAGE_DYNAMIC_VANILLA_CONTAINER_REQUIRED"));
        if (!inventory(source, player).equals(inventory(output, player)))
            throw AdvancedReflection.failure("STORAGE_DYNAMIC_CONTAINER_CONTENTS_NOT_CONSERVED");
    }
    private static Map<String, Long> inventory(ItemStack stack, ServerPlayer player) {
        IItemHandler handler;
        if (AdvancedReflection.type(ITEM).isInstance(stack.getItem())) {
            // fromStack/load can initialise upgrade handlers and allocate another UUID even for a
            // detached carrier. Conservation reads only the existing approved native payload.
            UUID id = stack.get(uuidComponent());
            Object selectedStore = StorageDynamicNativeScope.storeForRead(id);
            if (!(selectedStore instanceof SavedData store) || store.getClass() != AdvancedReflection.type(STORE))
                throw AdvancedReflection.failure("STORAGE_DYNAMIC_CANONICAL_STORE_MISMATCH");
            String encoded = snapshot(store, List.of(id)).get(id.toString());
            if (encoded == null) throw AdvancedReflection.failure("STORAGE_DYNAMIC_SOURCE_CONTENTS_MISSING");
            CompoundTag inventoryTag = StorageDynamicNbt.unpack(encoded).getCompound("storageWrapper").getCompound("contents").getCompound("inventory");
            int size = inventoryTag.getInt("Size");
            if (size < 0 || size > 8192) throw AdvancedReflection.failure("STORAGE_DYNAMIC_NATIVE_SLOT_LIMIT");
            var inventory = new ItemStackHandler(); inventory.deserializeNBT(player.registryAccess(), inventoryTag.copy());
            handler = inventory;
        } else {
            handler = stack.getCapability(Capabilities.ItemHandler.ITEM);
        }
        if (handler == null || handler.getSlots() > 8192) throw AdvancedReflection.failure("STORAGE_DYNAMIC_NATIVE_ITEM_CAPABILITY_REQUIRED");
        var result = new TreeMap<String, Long>(); long bytes = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            var item = ItemSnapshotCodec.capture(handler.getStackInSlot(slot), player.registryAccess());
            if (!item.empty()) {
                bytes += item.itemJson().length(); if (bytes > 8 * 1024 * 1024L) throw AdvancedReflection.failure("STORAGE_DYNAMIC_CONTENTS_LIMIT");
                result.merge(item.itemJson(), (long) item.count(), Math::addExact);
            }
        }
        return Map.copyOf(result);
    }
    private static Set<UUID> sources(List<ItemStack> inputs) {
        var result = new LinkedHashSet<UUID>(); var type = AdvancedReflection.type(ITEM);
        for (var stack : inputs) if (!stack.isEmpty() && type.isInstance(stack.getItem()) && stack.has(uuidComponent())) result.add(stack.get(uuidComponent()));
        return Set.copyOf(result);
    }
    private static void capacity(ItemStack template) {
        if (template.isEmpty()) throw AdvancedReflection.failure("STORAGE_DYNAMIC_OUTPUT_INVALID");
        int slots = (int) AdvancedReflection.invoke(AdvancedReflection.type(ITEM), null, "getDefaultNumberOfInventorySlots", new Class<?>[]{ItemStack.class}, template);
        if (slots < 1 || slots > 8192) throw AdvancedReflection.failure("STORAGE_DYNAMIC_NATIVE_SLOT_LIMIT");
    }
    private static SavedData realStore(ServerPlayer player, boolean load) {
        var cache = ((AiSavedDataCacheAccess) player.server.overworld().getDataStorage()).fireflymc$existingStores();
        Object store = cache.get(STORE_ID);
        if (store == null && load) store = AdvancedReflection.invoke(AdvancedReflection.type(STORE), null, "get", new Class<?>[0]);
        if (store == null) return null;
        if (!(store instanceof SavedData data) || store.getClass() != AdvancedReflection.type(STORE) || cache.get(STORE_ID) != store)
            throw AdvancedReflection.failure("STORAGE_DYNAMIC_CANONICAL_STORE_MISMATCH");
        return data;
    }
    private static SavedData shadow(Map<String, String> entries, HolderLookup.Provider registries) {
        var list = new ListTag();
        entries.forEach((uuid, encoded) -> {
            var pair = new CompoundTag(); pair.put("uuid", NbtUtils.createUUID(UUID.fromString(uuid)));
            pair.put("contents", StorageDynamicNbt.unpack(encoded)); list.add(pair);
        });
        var root = new CompoundTag(); root.put("storageContents", list);
        return (SavedData) AdvancedReflection.invoke(AdvancedReflection.type(STORE), null, "load", new Class<?>[]{CompoundTag.class, HolderLookup.Provider.class}, root, registries);
    }
    @SuppressWarnings("unchecked")
    private static Map<String, String> snapshot(SavedData store, Collection<UUID> selected) {
        if (store == null) return Map.of();
        try {
            Field field = AdvancedReflection.type(STORE).getDeclaredField("storageContents");
            if (field.getType() != Map.class || !field.trySetAccessible()) throw AdvancedReflection.failure("STORAGE_DYNAMIC_CONTENTS_API_MISMATCH");
            var contents = (Map<UUID, CompoundTag>) field.get(store); var result = new TreeMap<String, String>();
            for (UUID uuid : selected) if (contents.containsKey(uuid)) result.put(uuid.toString(), StorageDynamicNbt.pack(contents.get(uuid)));
            return Map.copyOf(result);
        } catch (ReflectiveOperationException unavailable) { throw AdvancedReflection.failure("STORAGE_DYNAMIC_CONTENTS_API_MISMATCH"); }
    }
    private static void requireHooks() {
        AdvancedReflection.type(STORE); AdvancedReflection.type(WRAPPER); AdvancedReflection.type(REPOSITORY);
        if (!StorageDynamicHooks.ready()) throw AdvancedReflection.failure("STORAGE_DYNAMIC_NATIVE_HOOKS_UNAVAILABLE");
    }
    @SuppressWarnings("unchecked") private static DataComponentType<UUID> uuidComponent() {
        var value = AdvancedReflection.field("net.p3pp3rf1y.sophisticatedcore.init.ModCoreDataComponents", "STORAGE_UUID");
        if (!(value instanceof Supplier<?> supplier) || !(supplier.get() instanceof DataComponentType<?> component))
            throw AdvancedReflection.failure("STORAGE_DYNAMIC_COMPONENT_API_MISMATCH");
        return (DataComponentType<UUID>) component;
    }
    @SuppressWarnings("unchecked") private static void cleanup(Collection<Object> privateStacks) {
        try {
            var field = AdvancedReflection.type(REPOSITORY).getDeclaredField("stackStorageWrappers");
            if (!field.trySetAccessible()) throw AdvancedReflection.failure("STORAGE_DYNAMIC_WRAPPER_API_MISMATCH");
            var cache = (Cache<Object, Object>) field.get(null); for (Object stack : privateStacks) cache.invalidate(stack);
        } catch (ReflectiveOperationException unavailable) { throw AdvancedReflection.failure("STORAGE_DYNAMIC_WRAPPER_API_MISMATCH"); }
    }
}
