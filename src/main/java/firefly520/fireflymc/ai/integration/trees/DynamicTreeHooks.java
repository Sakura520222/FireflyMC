package firefly520.fireflymc.ai.integration.trees;

/** Bootstrap-only marker collector. Deliberately has no Minecraft, NeoForge or runtime-scope links. */
final class DynamicTreeHooks {
    private static final int ALL = 63;
    private static volatile int applied;

    private DynamicTreeHooks() { }
    static void prepare() { /* Initialize this plain JDK-only class before target transformations. */ }
    static synchronized void applied(String target) {
        applied |= switch (target) {
            case "com.dtteam.dynamictrees.systems.FutureBreak" -> 1;
            case "com.dtteam.dynamictrees.entity.FallingTreeEntity" -> 2;
            case "net.minecraft.server.level.ServerLevel" -> 4;
            case "net.minecraft.world.entity.item.ItemEntity" -> 8;
            case "com.dtteam.dynamictrees.entity.animation.FalloverAnimationHandler" -> 16;
            case "net.minecraft.world.ticks.LevelTicks" -> 32;
            default -> 0;
        };
    }
    static boolean available() { return applied == ALL; }
}
