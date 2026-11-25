package firefly520.fireflymc.ai.integration.maid;

import firefly520.fireflymc.ai.material.*;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/** Approved current equipment/inventory state in the actual complete native entity payload. */
public final class MaidCombatSaveWitness {
    public interface Hook {}
    private record Witness(Object identity, UUID owner, MaidMeleeKernel.Snapshot expected, NativeSaveBarrier.Receipt receipt) {}
    private static final Map<Entity, List<Witness>> ACTIVE = new IdentityHashMap<>();
    private MaidCombatSaveWitness() {}
    static NativeSaveBarrier.Receipt observe(Entity entity, UUID owner, MaidMeleeKernel.Snapshot expected, NativeSaveObserver saves) {
        if (!(entity.level() instanceof ServerLevel level) || !level.getServer().isDedicatedServer() || !level.getServer().isSameThread()
                || !entity.getUUID().equals(expected.maid().maidId()) || !Hook.class.isAssignableFrom(Entity.class))
            throw new IllegalStateException("MAID_COMBAT_SAVE_WITNESS_CONTEXT");
        synchronized (ACTIVE) {
            if (ACTIVE.values().stream().mapToInt(List::size).sum() >= 128) throw new IllegalStateException("MAID_COMBAT_SAVE_WITNESS_LIMIT");
        }
        var identity = new Object();
        var receipt = saves.changedEntityWitness(identity, entity);
        var witness = new Witness(identity, owner, expected, receipt);
        synchronized (ACTIVE) { ACTIVE.computeIfAbsent(entity, ignored -> new ArrayList<>()).add(witness); }
        receipt.saved().whenComplete((proof, failure) -> { synchronized (ACTIVE) { var list = ACTIVE.get(entity); if (list != null) { list.remove(witness); if (list.isEmpty()) ACTIVE.remove(entity); } } });
        return receipt;
    }
    public static void serialized(Entity entity, CompoundTag tag) {
        if (!(entity.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) return;
        List<Witness> pending;
        synchronized (ACTIVE) { var values = ACTIVE.get(entity); if (values == null) return; pending = List.copyOf(values); }
        for (var witness : pending) if (matches(level, tag, witness.owner(), witness.expected())) NativeSaveObserver.serializedAdditional(witness.identity(), tag);
    }
    static boolean matches(ServerLevel level, CompoundTag tag, UUID owner, MaidMeleeKernel.Snapshot expected) {
        if (!MaidControlSaveWitness.matches(tag, owner, expected.maid()) || !tag.contains("MaidInventory", Tag.TAG_COMPOUND)
                || !tag.contains("MaidBaubleInventory", Tag.TAG_COMPOUND) || !tag.contains("MaidBackpackType", Tag.TAG_STRING)
                || !tag.getString("MaidBackpackType").equals(expected.backpackId())
                || !tag.contains("Health", Tag.TAG_FLOAT) || !tag.contains("AbsorptionAmount", Tag.TAG_FLOAT)
                || Float.compare(tag.getFloat("Health"), expected.maidHealth()) != 0 || Float.compare(tag.getFloat("AbsorptionAmount"), expected.maidAbsorption()) != 0) return false;
        try {
            return slots(level, tag.getCompound("MaidInventory"), expected.inventory().size()).equals(expected.inventory())
                    && slots(level, tag.getCompound("MaidBaubleInventory"), expected.baubles().size()).equals(expected.baubles())
                    && equipment(level, tag, "HandItems", 2).equals(List.of(expected.hand(), expected.offHand()))
                    && equipment(level, tag, "ArmorItems", 4).equals(expected.armour());
        } catch (RuntimeException | LinkageError unsupported) { return false; }
    }
    private static List<MaterialStack> slots(ServerLevel level, CompoundTag tag, int size) {
        if (!tag.contains("Items", Tag.TAG_LIST)) throw new IllegalArgumentException("MAID_COMBAT_SAVE_INVENTORY_INVALID");
        var result = new ArrayList<>(Collections.nCopies(size, MaterialStack.EMPTY)); var seen = new HashSet<Integer>();
        var items = tag.getList("Items", Tag.TAG_COMPOUND); if (items.size() > 128) throw new IllegalArgumentException("MAID_COMBAT_SAVE_INVENTORY_INVALID");
        for (var value : items) {
            var item = (CompoundTag) value; if (!item.contains("Slot", Tag.TAG_ANY_NUMERIC)) throw new IllegalArgumentException("MAID_COMBAT_SAVE_SLOT_INVALID");
            int slot = item.getInt("Slot"); if (slot < 0 || !seen.add(slot)) throw new IllegalArgumentException("MAID_COMBAT_SAVE_SLOT_INVALID");
            if (slot < size) result.set(slot, ItemSnapshotCodec.capture(ItemStack.parseOptional(level.registryAccess(), item), level.registryAccess()));
        }
        return List.copyOf(result);
    }
    private static List<MaterialStack> equipment(ServerLevel level, CompoundTag tag, String name, int size) {
        if (!tag.contains(name, Tag.TAG_LIST)) throw new IllegalArgumentException("MAID_COMBAT_SAVE_EQUIPMENT_INVALID");
        var values = tag.getList(name, Tag.TAG_COMPOUND); if (values.size() != size) throw new IllegalArgumentException("MAID_COMBAT_SAVE_EQUIPMENT_INVALID");
        var result = new ArrayList<MaterialStack>();
        for (var value : values) result.add(ItemSnapshotCodec.capture(ItemStack.parseOptional(level.registryAccess(), (CompoundTag) value), level.registryAccess()));
        return List.copyOf(result);
    }
}
