package firefly520.fireflymc.ai.integration.equipment;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.integration.maid.MaidCarrierBridge;
import firefly520.fireflymc.ai.material.MaterialInventoryService;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.tools.AgentTool;
import firefly520.fireflymc.ai.tools.ToolRegistry;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Own observations for the Agent; carrier changes are workbench-only preview/confirm methods. */
public final class EquipmentTools {
    private final MinecraftServer server;
    private final MaterialInventoryService inventory;
    private final Map<String, CarrierRepository> repositories = new ConcurrentHashMap<>();
    private final Map<UUID, Witness> witnesses = new ConcurrentHashMap<>();
    private record Witness(String owner, CarrierRepository.Choice choice, Object entity, Object connection, long expiresAt) {}

    public EquipmentTools(MinecraftServer server, MaterialInventoryService inventory) {
        if (!server.isDedicatedServer()) throw new IllegalStateException("AI_DEDICATED_SERVER_REQUIRED");
        this.server = server;
        this.inventory = inventory;
    }
    /** Workspace IO executor, after authentication; no repository is created on client/integrated servers. */
    public void prepare(Workspace workspace) throws IOException {
        CarrierRepository current = repositories.get(workspace.owner());
        if (current == null) repositories.put(workspace.owner(), new CarrierRepository(workspace, System::currentTimeMillis));
    }
    public void clearPrivateViews(String owner) {
        repositories.remove(owner);
        witnesses.values().removeIf(witness -> witness.owner().equals(owner));
        server.execute(() -> inventory.maids().disconnected(UUID.fromString(owner)));
    }
    public void disconnected(UUID owner) {
        witnesses.values().removeIf(witness -> witness.owner().equals(owner.toString()));
        inventory.maids().disconnected(owner);
    }
    public void close() { repositories.clear(); witnesses.clear(); }

    public void register(ToolRegistry tools) {
        register(tools, "equipment_inspect", "查看本人的装备、Curios/Accessories 饰品和携带背包宿主；查看不允许消耗装备或背包内容。",
                (actor, offset) -> inspectEquipment(actor, offset));
        register(tools, "carrier_inspect", "查看本人在工作台选定的可选女仆载体及原生任务状态；不改变任务，不读取他人女仆。",
                (actor, offset) -> inspectCarrier(actor, offset));
    }
    private interface Read { JsonObject run(Actor actor, int offset); }
    private void register(ToolRegistry tools, String name, String description, Read read) {
        JsonObject schema = AiJson.object("""
                {"type":"object","properties":{"offset":{"type":"integer","minimum":0,"maximum":512}},
                 "additionalProperties":false}
                """, 2048, 8);
        tools.register(new AgentTool() {
            @Override public Descriptor descriptor() {
                return new Descriptor(name, description, schema, Risk.READ_ONLY, "equipment.own.read", 0,
                        ExecutionThread.SERVER, 5000, true, "");
            }
            @Override public Result execute(Context context, JsonObject arguments) {
                context.actor().requireAccess(context.workspace().owner()); context.control().check();
                JsonObject data = read.run(context.actor(), arguments.has("offset") ? arguments.get("offset").getAsInt() : 0);
                return new Result(true, "", "本人的服务端观察；装备和来源许可分别处理。", data,
                        "SERVER_RUNTIME", "", false);
            }
        });
    }

    public JsonObject inspectEquipment(Actor actor, int offset) {
        ServerPlayer player = inventory.requirePlayer(actor);
        var snapshot = inventory.equipment().inspect(player, player.getUUID());
        if (offset < 0 || offset > snapshot.slots().size()) throw new IllegalArgumentException("EQUIPMENT_OFFSET_INVALID");
        JsonObject data = new JsonObject(); data.addProperty("reason", snapshot.reason());
        JsonArray rows = new JsonArray();
        for (var slot : snapshot.slots().subList(offset, Math.min(offset + 24, snapshot.slots().size()))) {
            JsonObject row = item(slot.item());
            row.addProperty("source", slot.source()); row.addProperty("slot", slot.slotName()); row.addProperty("index", slot.index());
            row.addProperty("active", slot.active()); row.addProperty("hostId", slot.hostId());
            row.addProperty("contentsBackendId", slot.contentsBackendId()); row.addProperty("hostState", slot.hostState());
            rows.add(row);
        }
        data.add("slots", rows); data.addProperty("nextOffset", offset + rows.size());
        data.addProperty("total", snapshot.slots().size()); data.addProperty("consumptionAuthorized", false);
        data.add("adapters", AiJson.GSON.toJsonTree(snapshot.adapters()));
        data.add("backpackAliases", AiJson.GSON.toJsonTree(snapshot.backpacks()));
        return data;
    }

    public JsonObject inspectCarrier(Actor actor, int offset) {
        ServerPlayer player = inventory.requirePlayer(actor);
        var selected = repository(actor).selection(actor);
        JsonObject data = new JsonObject(); data.add("selection", AiJson.GSON.toJsonTree(selected));
        data.addProperty("inventoryAuthorized", false); data.addProperty("worldActionAuthorized", false);
        if (selected.choice() == null) { data.addProperty("reason", "CARRIER_NOT_SELECTED"); return data; }
        var result = inventory.maids().inspectOwned(player, player.getUUID(), selected.choice().maidId());
        data.addProperty("reason", result.reason()); data.add("adapter", AiJson.GSON.toJsonTree(result.adapter()));
        if (result.maid() == null) return data;
        var maid = result.maid();
        if (!choice(maid).equals(selected.choice())) { data.addProperty("reason", "CARRIER_TASK_OR_DIMENSION_CHANGED"); return data; }
        var binding = inventory.maids().validateBinding(player, player.getUUID());
        if (binding.binding() == null || !binding.binding().maidId().equals(selected.choice().maidId()))
            binding = inventory.maids().bindOwned(player, player.getUUID(), selected.choice().maidId());
        data.add("binding", AiJson.GSON.toJsonTree(binding.binding()));
        if (binding.binding() == null) data.addProperty("reason", binding.reason());
        data.addProperty("nativeTask", maid.nativeTaskId()); data.addProperty("idle", maid.nativeIdle());
        data.addProperty("combat", maid.combat()); data.addProperty("seated", maid.seated());
        data.addProperty("backpackType", maid.backpackType()); data.addProperty("inventoryState", maid.inventoryState());
        if (offset < 0 || offset > maid.inventory().size()) throw new IllegalArgumentException("EQUIPMENT_OFFSET_INVALID");
        JsonArray rows = new JsonArray();
        for (var slot : maid.inventory().subList(offset, Math.min(offset + 24, maid.inventory().size()))) {
            var row = item(slot.item()); row.addProperty("slot", slot.slot()); rows.add(row);
        }
        data.add("inventory", rows); data.addProperty("nextOffset", offset + rows.size());
        data.addProperty("total", maid.inventory().size());
        return data;
    }
    private static JsonObject item(ReadOnlyNativeAccess.ItemObservation item) {
        JsonObject row = new JsonObject(); row.addProperty("itemId", item.itemId()); row.addProperty("count", item.count());
        row.addProperty("damage", item.damage()); row.addProperty("maximumDamage", item.maximumDamage());
        row.addProperty("componentsDigest", item.componentsDigest()); row.addProperty("consumptionAuthorized", false);
        return row;
    }

    /** IO caller; the maid ID is selected explicitly in the workbench, not inferred from proximity. */
    public CarrierRepository.Preview previewCarrier(Actor actor, UUID maidId) throws Exception {
        var witness = main(() -> observe(actor, maidId == null ? inventory.lookedAtOwnedMaid(actor) : maidId));
        var preview = repository(actor).preview(actor, witness.choice());
        witnesses.values().removeIf(value -> value.expiresAt() < System.currentTimeMillis());
        witnesses.put(preview.id(), witness);
        return preview;
    }
    public CarrierRepository.Selection confirmCarrier(Actor actor, UUID id, String digest) throws Exception {
        var repo = repository(actor); var pending = repo.pending(actor, id, digest);
        Witness original = witnesses.get(id);
        if (original == null || !original.owner().equals(actor.owner())) throw new SecurityException("CARRIER_PREVIEW_STALE");
        var observed = main(() -> observe(actor, pending.choice().maidId()));
        if (original.connection() != observed.connection() || original.entity() != observed.entity()
                || !original.choice().equals(observed.choice())) throw new IllegalStateException("CARRIER_PREVIEW_CHANGED");
        var selected = repo.confirm(actor, id, digest, observed.choice());
        witnesses.remove(id);
        return selected;
    }
    public CarrierRepository.Selection clearCarrier(Actor actor, long revision) throws Exception {
        main(() -> { inventory.requirePlayer(actor); return true; });
        var result = repository(actor).clear(actor, revision);
        main(() -> {
            var current = repository(actor).selection(actor);
            if (current.revision() == result.revision() && current.choice() == null)
                inventory.maids().unbindOwn(inventory.requirePlayer(actor), UUID.fromString(actor.owner()));
            return true;
        });
        return result;
    }
    private Witness observe(Actor actor, UUID maidId) {
        ServerPlayer player = inventory.requirePlayer(actor);
        var result = inventory.maids().inspectOwned(player, player.getUUID(), maidId);
        if (result.maid() == null) throw new IllegalStateException(result.reason());
        return new Witness(actor.owner(), choice(result.maid()), player.serverLevel().getEntity(maidId), player.connection,
                System.currentTimeMillis() + 120000);
    }
    private static CarrierRepository.Choice choice(MaidCarrierBridge.MaidView maid) {
        return new CarrierRepository.Choice(maid.maidId(), maid.dimension(), maid.nativeTaskId());
    }
    private CarrierRepository repository(Actor actor) {
        actor.requireAccess(actor.owner());
        var result = repositories.get(actor.owner());
        if (result == null) throw new IllegalStateException("CARRIER_WORKSPACE_NOT_READY");
        return result;
    }
    private <T> T main(Callable<T> action) throws Exception {
        if (server.isSameThread()) return action.call();
        var future = new CompletableFuture<T>();
        server.execute(() -> { try { future.complete(action.call()); } catch (Exception failure) { future.completeExceptionally(failure); } });
        return future.get(10, TimeUnit.SECONDS);
    }
}
