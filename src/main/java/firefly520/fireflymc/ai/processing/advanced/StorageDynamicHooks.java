package firefly520.fireflymc.ai.processing.advanced;

/** Mixin bootstrap flags only. Deliberately has no Minecraft or optional-mod type references. */
public final class StorageDynamicHooks {
    private static volatile boolean store, uuid, cache;
    private StorageDynamicHooks() {}
    static void prepare() { }
    static void applied(String target) {
        if (target.equals("net.p3pp3rf1y.sophisticatedstorage.block.ItemContentsStorage")) store = true;
        if (target.equals("net.p3pp3rf1y.sophisticatedstorage.item.StackStorageWrapper")) uuid = true;
        if (target.equals("net.p3pp3rf1y.sophisticatedcore.inventory.StorageWrapperRepository")) cache = true;
    }
    public static boolean ready() { return store && uuid && cache; }
}
