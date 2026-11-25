package firefly520.fireflymc.ai.material;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.task.TaskControl;
import firefly520.fireflymc.ai.tools.AgentTool;
import java.util.UUID;

/** Called only after a trusted material tool has unwound its guarded native-effect scopes.
 * It describes the retained ledger; it never settles an intent, returns items, or authorizes replay. */
public final class MaterialStopReceipt {
    private MaterialStopReceipt() { }
    public static AgentTool.Result capture(MaterialLedger ledger, AgentTool.Context context, JsonObject args,
            TaskControl.Stopped stopped) {
        context.actor().requireAccess(context.workspace().owner());
        String key = args.has("planId") ? "planId" : args.has("operationId") ? "operationId" : "";
        if (key.isEmpty()) throw stopped;
        UUID id = UUID.fromString(args.get(key).getAsString());
        var operation = ledger.find(context.actor(), id); var data = new JsonObject();
        data.addProperty("operationId", id.toString()); data.addProperty("stoppedReason", stopped.getMessage());
        boolean uncertain = false;
        if (operation.isEmpty()) data.addProperty("dispatch", "NO_NATIVE_OPERATION_RECORDED");
        else {
            var op = operation.get(); uncertain = op.intent() != null || op.uncertain();
            data.addProperty("state", op.state().name());
            data.addProperty("escrowRecordedUnits", op.escrow().stream().mapToLong(MaterialStack::count).sum());
            data.addProperty("undeliveredRecordedUnits", op.remainingProducts().stream().mapToLong(MaterialStack::count).sum());
            data.addProperty("worldPlacementsAcknowledged", op.worldDebits().stream().filter(effect -> effect.count() > 0).count());
            data.addProperty("externalEffectsAcknowledged", op.externalDebits().stream().filter(effect -> effect.known() && effect.accepted()).count());
        }
        data.addProperty("recoveryRequired", uncertain);
        return new AgentTool.Result(false, uncertain ? "RECOVERY_REQUIRED" : "PROCESSING_PAUSED",
                uncertain ? "调用已停止，仍有未决原生效果；保留账本等待对账，不能重放。"
                        : "调用已停止，已确定的操作结果与托管物料保留；后续执行仍须重新核验。",
                data, "owned-material-journal:" + id, "", false);
    }
}
