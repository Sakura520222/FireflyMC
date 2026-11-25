package firefly520.fireflymc.mixin;

import firefly520.fireflymc.ai.integration.maid.transport.*;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Locked Touhou Little Maid 1.5.3 native passive tick, never a new setTask mode. */
@Pseudo
@Mixin(targets = "com.github.tartaricacid.touhoulittlemaid.item.bauble.WirelessIOBauble", remap = false)
public abstract class AiMaidWirelessTransportMixin implements MaidTransportHook {
    @Inject(method = "onTick", at = @At("HEAD"), cancellable = true, require = 1)
    private void fireflymc$approvedTick(@Coerce Object maid, ItemStack stack, CallbackInfo ci) {
        if (NativeMaidTransportScope.before(maid, stack)) ci.cancel();
    }
    @Inject(method = "onTick", at = @At("RETURN"), require = 1)
    private void fireflymc$observedTick(@Coerce Object maid, ItemStack stack, CallbackInfo ci) { NativeMaidTransportScope.after(); }
    @Inject(method = "maidToChest", at = @At("HEAD"), cancellable = true, require = 1)
    private void fireflymc$sourceFirstExport(IItemHandler maid, IItemHandler chest, boolean blacklist,
            IItemHandler filter, List<Boolean> excluded, CallbackInfo ci) {
        if (NativeMaidTransportScope.helper(this, true, maid, chest, blacklist, filter, excluded)) ci.cancel();
    }
    @Inject(method = "chestToMaid", at = @At("HEAD"), cancellable = true, require = 1)
    private void fireflymc$sourceFirstImport(IItemHandler chest, IItemHandler maid, boolean blacklist,
            IItemHandler filter, List<Boolean> excluded, CallbackInfo ci) {
        if (NativeMaidTransportScope.helper(this, false, maid, chest, blacklist, filter, excluded)) ci.cancel();
    }
}
