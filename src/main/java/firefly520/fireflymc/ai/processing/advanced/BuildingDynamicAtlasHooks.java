package firefly520.fireflymc.ai.processing.advanced;

/** Mixin bootstrap flags only: this class must never link Minecraft during postApply. */
public final class BuildingDynamicAtlasHooks {
    private static volatile boolean headers, collections;
    private BuildingDynamicAtlasHooks() { }
    public static void applied(String mixin) {
        if (mixin.endsWith("BuildingDynamicAtlasFindMixin")) headers = true;
        if (mixin.endsWith("BuildingDynamicAtlasCollectionMixin")) collections = true;
    }
    public static boolean ready() { return headers && collections; }
}
