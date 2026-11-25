package firefly520.fireflymc.ai.integration.maid;

import firefly520.fireflymc.ai.material.*;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/** Exact owned ordinary-backpack and hand data at native serialization, without reading AI/config fields. */
public final class MaidFarmSaveWitness {
    private record Witness(Object identity, MaidFarmReconcile expected) {}
    private static final Map<Entity, List<Witness>> ACTIVE = new IdentityHashMap<>();
    private MaidFarmSaveWitness() {}
    static NativeSaveBarrier.Receipt observe(Entity entity, MaidFarmReconcile expected, NativeSaveObserver saves) {
        if (!(entity.level() instanceof ServerLevel level) || !level.getServer().isDedicatedServer() || !level.getServer().isSameThread()
                || !entity.getUUID().equals(expected.maid().maidId())) throw new IllegalStateException("MAID_FARM_SAVE_CONTEXT");
        synchronized (ACTIVE) {
            if (ACTIVE.values().stream().mapToInt(List::size).sum() >= 128) throw new IllegalStateException("MAID_FARM_SAVE_BACKLOG");
        }
        var witness = new Witness(new Object(), expected);
        var receipt = saves.changedEntityWitness(witness.identity(), entity);
        synchronized (ACTIVE) { ACTIVE.computeIfAbsent(entity, ignored -> new ArrayList<>()).add(witness); }
        receipt.saved().whenComplete((proof, failure) -> {
            synchronized (ACTIVE) { var list = ACTIVE.get(entity); if (list != null) { list.remove(witness); if (list.isEmpty()) ACTIVE.remove(entity); } }
        });
        return receipt;
    }
    public static void serialized(Entity entity, CompoundTag tag) {
        List<Witness> snapshots;
        synchronized (ACTIVE) { var current = ACTIVE.get(entity); if (current == null) return; snapshots = List.copyOf(current); }
        if (!(entity.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) return;
        for (var witness : snapshots) if (matches(level, tag, witness.expected())) NativeSaveObserver.serializedAdditional(witness.identity(), tag);
    }
    private static boolean matches(ServerLevel level, CompoundTag tag, MaidFarmReconcile expected) {
        if (!MaidControlSaveWitness.matches(tag, UUID.fromString(expected.owner()), expected.maid()) || !tag.contains("MaidBackpackType", Tag.TAG_STRING)
                || !tag.getString("MaidBackpackType").equals(expected.backpackId()) || !tag.contains("MaidInventory", Tag.TAG_COMPOUND)
                || !tag.contains("HandItems", Tag.TAG_LIST)) return false;
        var inventory = tag.getCompound("MaidInventory");
        if (!inventory.contains("Items", Tag.TAG_LIST)) return false;
        var items = new ArrayList<MaterialStack>(Collections.nCopies(expected.inventory().size(), MaterialStack.EMPTY));
        var seen = new HashSet<Integer>(); var entries = inventory.getList("Items", Tag.TAG_COMPOUND);
        if (entries.size() > 256) return false;
        try {
            for (int i = 0; i < entries.size(); i++) {
                var entry = entries.getCompound(i); if (!entry.contains("Slot", Tag.TAG_ANY_NUMERIC)) return false; int slot = entry.getInt("Slot");
                if (slot < 0) return false; if (slot >= items.size()) continue;
                if (!seen.add(slot)) return false;
                ItemStack actual = ItemStack.parseOptional(level.registryAccess(), entry); if (actual.isEmpty()) return false;
                items.set(slot, ItemSnapshotCodec.capture(actual, level.registryAccess()));
            }
            var hands = tag.getList("HandItems", Tag.TAG_COMPOUND);
            return hands.size() == 2 && items.equals(expected.inventory())
                    && ItemSnapshotCodec.capture(ItemStack.parseOptional(level.registryAccess(), hands.getCompound(0)), level.registryAccess()).equals(expected.hand())
                    && ItemSnapshotCodec.capture(ItemStack.parseOptional(level.registryAccess(), hands.getCompound(1)), level.registryAccess()).equals(expected.offHand());
        } catch (RuntimeException | LinkageError unknownEncoding) { return false; }
    }
}
