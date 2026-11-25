package firefly520.fireflymc.ai.blueprint;

import java.util.Set;
import java.util.Map;
import java.lang.invoke.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;

/** Registered vanilla empty-container defaults are data, not permission to carry arbitrary BE contents. */
public final class NativePlacementComponents {
    private static volatile MethodHandle rawItems;
    private NativePlacementComponents() {}

    public static void require(ItemStack item) {
        if (item.has(DataComponents.BLOCK_ENTITY_DATA)) throw new IllegalArgumentException("PLACEMENT_COMPONENT_ADAPTER_REQUIRED");
        var contents = item.get(DataComponents.CONTAINER);
        if (contents == null) return;
        if (item.getItem().getClass() != BlockItem.class || !(item.getItem() instanceof BlockItem block)
                || !Set.of(Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER, Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL).contains(block.getBlock())
                || !ItemContainerContents.EMPTY.equals(contents)
                || !ItemContainerContents.EMPTY.equals(new ItemStack(item.getItem()).get(DataComponents.CONTAINER)))
            throw new IllegalArgumentException("PLACEMENT_COMPONENT_ADAPTER_REQUIRED");
    }
    /** Read only the audited raw list; Container.isEmpty can unpack a loot table and is not a safe observer. */
    @SuppressWarnings("unchecked")
    static void requireEmptyNativeContainers(ServerLevel level, Map<BlockPos, BlockState> targets, ItemStack material) {
        if (material.get(DataComponents.CONTAINER) == null) return;
        require(material);
        var expected = Map.of(Blocks.FURNACE, FurnaceBlockEntity.class, Blocks.BLAST_FURNACE, BlastFurnaceBlockEntity.class,
                Blocks.SMOKER, SmokerBlockEntity.class, Blocks.CHEST, ChestBlockEntity.class,
                Blocks.TRAPPED_CHEST, TrappedChestBlockEntity.class, Blocks.BARREL, BarrelBlockEntity.class);
        for (var target : targets.entrySet()) {
            var type = expected.get(target.getValue().getBlock());
            if (type == null) throw new IllegalStateException("PLACEMENT_DEFAULT_CONTAINER_TARGET_MISMATCH");
            var entity = level.getBlockEntity(target.getKey());
            if (entity == null || entity.getClass() != type || entity.isRemoved()
                    || entity instanceof RandomizableContainerBlockEntity random && random.getLootTable() != null)
                throw new IllegalStateException("PLACEMENT_DEFAULT_CONTAINER_CHANGED");
            try {
                var getter = rawItems;
                if (getter == null) rawItems = getter = MethodHandles.privateLookupIn(BaseContainerBlockEntity.class, MethodHandles.lookup())
                        .findVirtual(BaseContainerBlockEntity.class, "getItems", MethodType.methodType(NonNullList.class));
                var contents = (NonNullList<ItemStack>) getter.invoke(entity);
                int slots = entity instanceof AbstractFurnaceBlockEntity ? 3 : 27;
                if (contents.size() != slots || contents.stream().anyMatch(stack -> !stack.isEmpty()))
                    throw new IllegalStateException("PLACEMENT_DEFAULT_CONTAINER_CHANGED");
            } catch (RuntimeException | Error failure) { throw failure; }
            catch (Throwable unavailable) { throw new IllegalStateException("PLACEMENT_DEFAULT_CONTAINER_CONTRACT_UNAVAILABLE", unavailable); }
        }
    }
}
