package firefly520.fireflymc.ai.material;

import net.minecraft.core.UUIDUtil;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import java.util.UUID;
import java.util.function.Supplier;

/** Registration creates no AI state. Identity is attached only after explicit source confirmation. */
public final class MaterialSourceIdentity {
    private static final DeferredRegister<AttachmentType<?>> TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, "fireflymc");
    private static final Supplier<AttachmentType<UUID>> ID = TYPES.register("ai_material_source",
            () -> AttachmentType.builder(UUID::randomUUID).serialize(UUIDUtil.CODEC).build());
    private MaterialSourceIdentity() {}
    public static void register(IEventBus bus) { TYPES.register(bus); }
    public static UUID existing(BlockEntity entity) { return entity.getExistingDataOrNull(ID); }
    public static UUID confirmed(BlockEntity entity) {
        if (entity.getLevel() == null || entity.getLevel().getServer() == null
                || !entity.getLevel().getServer().isDedicatedServer() || !entity.getLevel().getServer().isSameThread())
            throw new IllegalStateException("DEDICATED_SERVER_THREAD_REQUIRED");
        UUID value = entity.getData(ID);
        entity.setChanged();
        return value;
    }
}
