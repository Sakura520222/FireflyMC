package firefly520.fireflymc.ai.processing;

import firefly520.fireflymc.ai.blueprint.NativeConstructionSave;
import firefly520.fireflymc.ai.material.NativeSaveBarrier;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerPlayer;

/** Audited synchronous native stores; an unadapted effect is observed, never declared successful. */
public final class NativeProcessingCallback {
    public record Outcome(boolean completed, boolean entered, String code, NativeSaveBarrier.Receipt save, String observations) {}
    private NativeProcessingCallback() {}

    public static Outcome run(ServerPlayer player, Runnable callback, int experience) {
        NativeConstructionSave scope;
        try { scope = NativeConstructionSave.begin(player); }
        catch (RuntimeException | LinkageError failure) { return new Outcome(false, false, code(failure), null, ""); }
        boolean completed = false;
        String error = "";
        NativeSaveBarrier.Receipt save = null;
        var initialLevel = player.serverLevel();
        try (scope) {
            NativeExperienceAward award = null;
            try { if (experience > 0) award = NativeExperienceAward.begin(player, experience); }
            catch (RuntimeException | LinkageError failure) { return new Outcome(false, false, code(failure), null, ""); }
            try {
                try { callback.run(); completed = true; }
                catch (RuntimeException | LinkageError failure) { error = code(failure); }
                if (player.serverLevel() != initialLevel) { completed = false; error = "CALLBACK_DIMENSION_CHANGED"; }
                try { save = scope.finish(); }
                catch (RuntimeException | LinkageError failure) { completed = false; if (error.isEmpty()) error = code(failure); }
                if (award != null) {
                    try {
                        var entitySave = award.finish();
                        var stores = new ArrayList<CompletableFuture<NativeSaveBarrier.Receipt>>();
                        if (save != null) stores.add(CompletableFuture.completedFuture(save));
                        stores.add(CompletableFuture.completedFuture(entitySave));
                        save = NativeSaveBarrier.combine(stores);
                    } catch (RuntimeException | LinkageError failure) { completed = false; if (error.isEmpty()) error = code(failure); }
                }
                return new Outcome(completed, true, error, save, award == null ? "" : award.observations());
            } finally { if (award != null) award.close(); }
        }
    }

    private static String code(Throwable failure) {
        String value = failure.getMessage();
        return value != null && value.matches("[A-Z0-9_]{1,128}") ? value : "NATIVE_CALLBACK_FAILED";
    }
}
