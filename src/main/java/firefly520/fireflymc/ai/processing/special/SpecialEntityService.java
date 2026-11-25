package firefly520.fireflymc.ai.processing.special;

import com.google.gson.*;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.integration.InstalledPermissionAdapters;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.processing.VirtualProcessingService;
import firefly520.fireflymc.ai.processing.NativeProcessingCallback;
import firefly520.fireflymc.ai.blueprint.NativeConstructionSave;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.TaskControl;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.phys.AABB;

/** Real multi-input entity conversion, backed by an external-effect material intent and full private native receipts. */
final class SpecialEntityService {
    record Plan(int version, UUID id, UUID epoch, UUID serverId, UUID conversationId, String owner, UUID taskId,
            long privacyEpoch, long generation, EntityProcessingAdapter.Plan entity, List<MaterialLedger.Stock> sources,
            List<MaterialLedger.Need> needs, Map<Long, String> area, String hash) {
        Plan { sources = List.copyOf(sources); needs = List.copyOf(needs); area = Map.copyOf(area); }
        Plan withHash(String digest) { return new Plan(version, id, epoch, serverId, conversationId, owner, taskId, privacyEpoch, generation, entity, sources, needs, area, digest); }
        boolean intact() { return AiJson.digest(AiJson.canonical(withHash(""))).equals(hash); }
    }
    private record PreparedOutcome(boolean success, boolean entered, EntityProcessingAdapter.Prepared prepared, String error,
                                   NativeSaveBarrier.Receipt save) {
        PreparedOutcome(boolean success, boolean entered, EntityProcessingAdapter.Prepared prepared, String error) { this(success, entered, prepared, error, null); }
    }
    private record SpawnOutcome(boolean known, boolean accepted, String error, List<UUID> observed, String preparedDigest,
                                NativeSaveBarrier.Receipt save) {
        SpawnOutcome(boolean known, boolean accepted, String error, List<UUID> observed, String preparedDigest) { this(known, accepted, error, observed, preparedDigest, null); }
    }
    private final SpecialProcessingService dispatcher;
    private final MinecraftServer server;
    private final MaterialInventoryService inventory;
    private final MaterialLedger ledger;
    private final VirtualProcessingService processing;
    private final LongSupplier generation;
    private final UUID epoch;
    private final InstalledPermissionAdapters permissions;
    private final SpecialEntityProtection entityProtection = new SpecialEntityProtection();
    private final Map<UUID, Recipe<?>> runtime = new LinkedHashMap<>();
    private final Set<UUID> executing = ConcurrentHashMap.newKeySet();

    SpecialEntityService(SpecialProcessingService dispatcher, MinecraftServer server, MaterialInventoryService inventory,
            MaterialLedger ledger, VirtualProcessingService processing, LongSupplier generation, UUID epoch) {
        this.dispatcher = dispatcher; this.server = server; this.inventory = inventory; this.ledger = ledger;
        this.processing = processing; this.generation = generation; this.epoch = epoch; permissions = new InstalledPermissionAdapters(server);
    }

    void register(ToolRegistry tools) {
        String assignment = "{\"type\":\"object\",\"properties\":{\"sourceKey\":{\"type\":\"string\",\"maxLength\":2048},\"roleSlot\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":8},\"count\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":1}},\"required\":[\"sourceKey\",\"roleSlot\",\"count\"],\"additionalProperties\":false}";
        dispatcher.tool(tools, "special_entity_plan", "规划真实材料支持的天境孵化/女仆实体祭坛：固定recipeId、来源角色槽和产出位置，保留蛋/照片组件及主人信息，免设备/power/等待。只规划，不生成实体；默认本人近处8格，远程需专门虚拟实体权限。",
                "{\"recipeId\":{\"type\":\"string\"},\"snapshotId\":{\"type\":\"string\"},\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"},\"assignments\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":6,\"items\":" + assignment + "}}",
                List.of("recipeId", "x", "y", "z", "assignments"), AgentTool.Risk.READ_ONLY, this::plan);
        dispatcher.tool(tools, "special_entity_execute", "执行工作台批准的固定实体加工计划。真实托管原料、逐区域权限、原生实体初始化、持久UUID回执及成功核验；不是管理刷怪。缺少/取消/不确定的生成不会伪报成功或自动重放。",
                "{\"planId\":{\"type\":\"string\"},\"planHash\":{\"type\":\"string\"}}", List.of("planId", "planHash"), AgentTool.Risk.WORLD_CHANGE,
                (ctx, args) -> execute(ctx, load(ctx.workspace(), ctx.actor(), UUID.fromString(args.get("planId").getAsString()), args.get("planHash").getAsString())));
    }

    private AgentTool.Result plan(AgentTool.Context ctx, JsonObject args) throws Exception {
        if (ctx.taskId() == null) throw new IllegalArgumentException("ENTITY_TASK_REQUIRED");
        long privacy = ctx.workspace().conversation().privacyEpoch();
        var assignments = Arrays.asList(AiJson.GSON.fromJson(args.get("assignments"), SpecialProcessingService.Assignment[].class));
        if (assignments.isEmpty() || assignments.size() > 6 || assignments.stream().anyMatch(a -> a.count() != 1)) throw new IllegalArgumentException("ENTITY_ASSIGNMENT_LIMIT");
        var snapshot = args.has("snapshotId") ? dispatcher.onMain(ctx.control(), () -> inventory.snapshot(ctx.actor(), UUID.fromString(args.get("snapshotId").getAsString())))
                : ctx.control().await(inventory.scan(ctx.actor()), 30000);
        var rows = new LinkedHashMap<String, MaterialLedger.Stock>();
        snapshot.rows().forEach(row -> rows.merge(row.key(), row, (a, b) -> a.extractableCount() >= b.extractableCount() ? a : b));
        var selected = new LinkedHashMap<String, MaterialLedger.Stock>(); var counts = new LinkedHashMap<String, Integer>();
        for (var assignment : assignments) {
            var row = rows.get(assignment.sourceKey()); if (row == null) throw new IllegalArgumentException("ENTITY_SOURCE_NOT_IN_SNAPSHOT");
            selected.putIfAbsent(row.key(), row); counts.merge(row.key(), 1, Math::addExact);
        }
        var needs = counts.entrySet().stream().map(e -> new MaterialLedger.Need(e.getKey(), e.getValue())).toList();
        var stocks = List.copyOf(selected.values()); var available = ledger.available(stocks);
        for (var need : needs) if (need.count() > available.getOrDefault(need.stockKey(), 0L)) throw new IllegalStateException("ENTITY_MATERIAL_MISSING");
        UUID id = UUID.randomUUID();
        Plan plan = dispatcher.onMain(ctx.control(), () -> {
            ServerPlayer player = inventory.requirePlayer(ctx.actor());
            var holder = recipe(args.get("recipeId").getAsString()); var kind = EntityProcessingAdapter.kind(holder.value());
            if (kind == null) throw new IllegalArgumentException("ENTITY_RECIPE_UNSUPPORTED");
            var inputs = new ArrayList<>(Collections.nCopies(kind == EntityProcessingAdapter.Kind.AETHER_INCUBATION ? 1 : 9, ItemStack.EMPTY));
            for (var assignment : assignments) {
                if (assignment.roleSlot() >= inputs.size() || !inputs.get(assignment.roleSlot()).isEmpty()) throw new IllegalArgumentException("ENTITY_ROLE_ASSIGNMENT_INVALID");
                inputs.set(assignment.roleSlot(), ItemSnapshotCodec.restore(selected.get(assignment.sourceKey()).stack().withCount(1), player.registryAccess()));
            }
            BlockPos target = new BlockPos(args.get("x").getAsBigDecimal().intValueExact(), args.get("y").getAsBigDecimal().intValueExact(), args.get("z").getAsBigDecimal().intValueExact());
            if (Math.abs((long) target.getX()) > 29_999_980 || Math.abs((long) target.getZ()) > 29_999_980) throw new IllegalArgumentException("ENTITY_TARGET_LIMIT");
            EntityProcessingAdapter.Plan nativePlan = EntityProcessingAdapter.plan(holder, inputs, player, target, id, this::guard);
            entityProtection.itemUse(player, nativePlan);
            if (nativePlan.entityType().equals(BuiltInRegistries.ENTITY_TYPE.getKey(EntityType.COMMAND_BLOCK_MINECART))) throw new IllegalArgumentException("ENTITY_ADMINISTRATIVE_TYPE_FORBIDDEN");
            var area = area(player, target); runtime.put(id, holder.value());
            while (runtime.size() > 128) runtime.remove(runtime.keySet().iterator().next());
            Plan value = new Plan(1, id, epoch, ctx.workspace().metadata().serverInstanceId(), ctx.workspace().primaryConversationId(), ctx.actor().owner(),
                    ctx.taskId(), privacy, generation.getAsLong(), nativePlan, stocks, needs, area, "");
            return value.withHash(AiJson.digest(AiJson.canonical(value)));
        });
        synchronized (ctx.workspace().conversation()) {
            ctx.control().check();
            var task = ctx.workspace().conversation().task(ctx.taskId());
            if (privacy != ctx.workspace().conversation().privacyEpoch() || task == null || task.state().terminal()) throw new IllegalStateException("ENTITY_PLAN_TASK_OR_PRIVACY_CHANGED");
            if (Files.exists(ctx.workspace().artifact(plan.id()))) throw new IllegalStateException("ENTITY_PLAN_ALREADY_EXISTS");
            AiJson.write(ctx.workspace().artifact(plan.id()), plan);
            var manifest = new JsonObject(); manifest.addProperty("planId", plan.id().toString()); manifest.addProperty("hash", plan.hash());
            manifest.addProperty("recipeId", plan.entity().recipeId().toString());
            ctx.workspace().conversation().append(List.of(new Draft(ctx.taskId(), "special_entity_plan", null, manifest, null)));
        }
        var data = new JsonObject(); data.addProperty("planId", plan.id().toString()); data.addProperty("planHash", plan.hash());
        data.addProperty("entityType", plan.entity().entityType().toString()); data.addProperty("nativeEntityCount", plan.entity().entityType().toString().equals("touhou_little_maid:box") ? 2 : 1);
        return result(true, "", "实体产物与真实材料已核验；等待工作台批准，尚未生成。", data);
    }

    private AgentTool.Result execute(AgentTool.Context ctx, Plan plan) throws Exception {
        ctx.actor().requireAccess(plan.owner()); if (!Objects.equals(ctx.taskId(), plan.taskId())) throw new SecurityException("ENTITY_TASK_MISMATCH");
        if (!executing.add(plan.id())) return result(false, "ENTITY_ALREADY_EXECUTING", "该实体操作正在执行。", new JsonObject());
        try {
            var prior = ledger.find(ctx.actor(), plan.id());
            if (prior.isPresent()) return result(false, prior.get().uncertain() ? "RECOVERY_REQUIRED" : "ENTITY_ALREADY_RECORDED", "该操作已有记录，未重放。", status(prior.get()));
            dispatcher.onMain(ctx.control(), () -> { check(ctx, plan, true); return true; });
            for (var need : plan.needs()) dispatcher.onMain(ctx.control(), () -> {
                check(ctx, plan, true); var stock = plan.sources().stream().filter(s -> s.key().equals(need.stockKey())).findFirst().orElseThrow();
                return inventory.prepareExtraction(ctx.actor(), stock, Math.min(need.count(), stock.maxTransferCount()));
            });
            var op = ledger.reserve(ctx.actor(), ctx.taskId(), plan.id(), plan.hash(), plan.sources(), plan.needs());
            try {
                op = processing.extractReserved(ctx, op, () -> check(ctx, plan, true));
                if (op.uncertain()) return result(false, "RECOVERY_REQUIRED", "取料证据不确定，停止实体生成。", status(op));
                processing.awaitInputSaves(ctx, plan.id(), fresh -> finishSavedEscrow(fresh, plan));
                return processSavedEscrow(ctx, plan);
            } catch (AgentTool.Deferred deferred) { throw deferred; }
            catch (Exception failure) {
                var saved = ledger.get(ctx.actor(), plan.id());
                if (saved.intent() != null) saved = ledger.markUncertain(ctx.actor(), plan.id(), "ENTITY_EFFECT_INTERRUPTED");
                if (failure instanceof TaskControl.Stopped stopped) throw stopped;
                return result(false, saved.uncertain() ? "RECOVERY_REQUIRED" : "PROCESSING_PAUSED", "实体加工未全部完成；原料和原生证据已保留。", status(saved));
            }
        } catch (AgentTool.Deferred deferred) { throw deferred; }
        catch (TaskControl.Stopped stopped) { throw stopped; }
        catch (Exception failure) {
            if (ledger.find(ctx.actor(), plan.id()).isPresent()) throw failure;
            return result(false, code(failure), "执行前拒绝，未扣料或生成实体。", new JsonObject());
        } finally { executing.remove(plan.id()); }
    }

    private AgentTool.Result finishSavedEscrow(AgentTool.Context ctx, Plan plan) throws Exception {
        ctx.actor().requireAccess(plan.owner());
        if (!Objects.equals(ctx.taskId(), plan.taskId())) throw new SecurityException("ENTITY_TASK_MISMATCH");
        if (!executing.add(plan.id())) throw new IllegalStateException("ENTITY_ALREADY_EXECUTING");
        try { return processSavedEscrow(ctx, plan); }
        catch (AgentTool.Deferred deferred) { throw deferred; }
        catch (Exception failure) {
            var op = ledger.get(ctx.actor(), plan.id());
            if (op.intent() != null && !op.uncertain()) op = ledger.markUncertain(ctx.actor(), plan.id(), "ENTITY_EFFECT_INTERRUPTED");
            if (failure instanceof TaskControl.Stopped stopped) throw stopped;
            return result(false, op.uncertain() ? "RECOVERY_REQUIRED" : "PROCESSING_PAUSED", "实体加工续接未完成；真实物料和证据保持托管。", status(op));
        } finally { executing.remove(plan.id()); }
    }
    private AgentTool.Result processSavedEscrow(AgentTool.Context ctx, Plan plan) throws Exception {
        ctx.actor().requireAccess(plan.owner());
        if (!Objects.equals(ctx.taskId(), plan.taskId())) throw new SecurityException("ENTITY_TASK_MISMATCH");
        var op = ledger.get(ctx.actor(), plan.id());
        if (!op.planHash().equals(plan.hash())) throw new SecurityException("ENTITY_PLAN_CHANGED");
        processing.awaitInputSaves(ctx, plan.id(), fresh -> finishSavedEscrow(fresh, plan));
        for (var input : op.escrow()) if (inventory.protectedByOwner(ctx.actor(), input)) throw new SecurityException("MATERIAL_PROTECTED_BY_OWNER");
        dispatcher.onMain(ctx.control(), () -> { check(ctx, plan, true); return true; });
        UUID effectId = UUID.nameUUIDFromBytes((plan.id() + ":entity-conversion").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var materials = plan.entity().consumed().stream().filter(s -> !s.empty()).toList();
        ledger.beginExternalUse(ctx.actor(), plan.id(), effectId, "entity_conversion", materials);
        PreparedOutcome prepared = processing.commitOwned(ctx, () -> prepare(ctx, plan), evidence -> {
            var data = new JsonObject(); data.addProperty("success", evidence.success()); data.addProperty("entered", evidence.entered());
            data.addProperty("error", evidence.error()); data.add("prepared", AiJson.GSON.toJsonTree(evidence.prepared()));
            append(ctx, "special_entity_prepared", plan, effectId, data);
            if (!evidence.success()) return ledger.externalEvidence(ctx.actor(), plan.id(), effectId, "entity_conversion", !evidence.entered(), false, List.of(), AiJson.digest(AiJson.canonical(evidence)));
            return ledger.get(ctx.actor(), plan.id());
        });
        if (!prepared.success()) return refused(ctx, plan, prepared.error());
        SpawnOutcome spawn = processing.commitOwned(ctx, () -> spawn(ctx, plan, prepared.prepared()), evidence -> {
            var data = new JsonObject(); data.addProperty("known", evidence.known()); data.addProperty("accepted", evidence.accepted());
            data.addProperty("error", evidence.error()); data.addProperty("preparedDigest", evidence.preparedDigest()); data.add("observed", AiJson.GSON.toJsonTree(evidence.observed()));
            boolean covered = prepared.save() != null && evidence.save() != null;
            data.addProperty("saveCovered", covered); append(ctx, "special_entity_spawn_evidence", plan, effectId, data);
            var recorded = ledger.externalEvidence(ctx.actor(), plan.id(), effectId, "entity_conversion", evidence.known() && covered, evidence.accepted(), List.of(), AiJson.digest(AiJson.canonical(data)));
            if (covered) inventory.nativeSaves().record(ledger, ctx.actor(), plan.id(), "external:entity_conversion", effectId.toString(),
                    NativeSaveBarrier.combine(List.of(java.util.concurrent.CompletableFuture.completedFuture(prepared.save()),
                            java.util.concurrent.CompletableFuture.completedFuture(evidence.save()))));
            return recorded;
        });
        op = ledger.get(ctx.actor(), plan.id());
        if (!spawn.accepted() || op.uncertain()) return refused(ctx, plan, spawn.error());
        op = ledger.externalProductsReady(ctx.actor(), plan.id());
        String callbackWitness = "ENTITY_RECIPE_ADVANCEMENT:" + prepared.prepared().digest();
        ledger.beginCallback(ctx.actor(), plan.id(), callbackWitness, 1);
        var callback = processing.commitOwned(ctx, () -> {
            ServerPlayer player = check(ctx, plan, false);
            return NativeProcessingCallback.run(player, () -> EntityProcessingAdapter.callback(recipe(plan.entity().recipeId().toString()), prepared.prepared(), player), 0);
        }, effect -> {
            append(ctx, "special_entity_callback_evidence", plan, effectId, Map.of("completed", effect.completed(), "entered", effect.entered(),
                    "error", effect.code(), "preparedDigest", prepared.prepared().digest()));
            return processing.recordNativeCallbackPart(ctx, plan.id(), callbackWitness, 0, effect);
        });
        op = ledger.get(ctx.actor(), plan.id());
        if (!callback.completed() || op.uncertain()) return result(false, "RECOVERY_REQUIRED", "实体已出现，但回调需核对，未重复生成。", status(op));
        if (op.state() == MaterialLedger.State.CALLBACK_DONE) op = processing.deliver(ctx, op);
        return processing.awaitFinalResult(ctx, plan.id(), current -> {
            boolean saved = current.state() == MaterialLedger.State.COMPLETED && current.saved();
            return result(saved, saved ? "" : "CUSTODY_OR_SAVE_PENDING",
                    saved ? "服务端已核验配方实体、UUID、乘客关系及保存凭据，真实原料已按回执结算。" : "已记录实际实体与原料结算；物料尚未全部交付。", status(current));
        });
    }

    private PreparedOutcome prepare(AgentTool.Context ctx, Plan plan) {
        boolean entered = false;
        try {
            ServerPlayer player = check(ctx, plan, true);
            try (var scope = NativeConstructionSave.beginEntities(player, List.of())) {
                entered = true;
                var prepared = EntityProcessingAdapter.prepareAfterEscrow(recipe(plan.entity().recipeId().toString()), plan.entity(), player, this::guard);
                return new PreparedOutcome(true, true, prepared, "", scope.finish());
            }
        } catch (Exception | LinkageError failure) { return new PreparedOutcome(false, entered, null, code(failure)); }
    }
    private SpawnOutcome spawn(AgentTool.Context ctx, Plan plan, EntityProcessingAdapter.Prepared prepared) {
        boolean entered = false;
        List<UUID> observed = List.of();
        try {
            ServerPlayer player = check(ctx, plan, true);
            Entity entity = EntityProcessingAdapter.restorePrepared(prepared, player, this::guard);
            for (var member : prepared.members()) if (player.serverLevel().getEntity(member.uuid()) != null)
                return new SpawnOutcome(false, false, "ENTITY_UUID_ALREADY_PRESENT", List.of(member.uuid()), prepared.digest());
            for (Entity member : entity.getSelfAndPassengers().toList()) if (!player.serverLevel().noCollision(member))
                return new SpawnOutcome(true, false, "ENTITY_OUTPUT_SPACE_OCCUPIED", List.of(), prepared.digest());
            try (var scope = NativeConstructionSave.beginEntities(player, entity.getSelfAndPassengers().toList())) {
                entered = true;
                entityProtection.mobSpawn(player, plan.entity(), entity);
                boolean added = player.serverLevel().tryAddFreshEntityWithPassengers(entity);
                observed = prepared.members().stream().filter(m -> player.serverLevel().getEntity(m.uuid()) != null).map(EntityProcessingAdapter.Member::uuid).toList();
                boolean verified = added && EntityProcessingAdapter.verifyPresent(prepared, player.serverLevel());
                return new SpawnOutcome(verified, verified, verified ? "" : "ENTITY_NATIVE_INSERTION_UNCERTAIN", observed, prepared.digest(), scope.finish());
            }
        } catch (Exception | LinkageError failure) { return new SpawnOutcome(!entered, false, entered ? "ENTITY_NATIVE_INSERTION_UNCERTAIN" : code(failure), observed, prepared.digest()); }
    }
    private AgentTool.Result refused(AgentTool.Context ctx, Plan plan, String reason) throws Exception {
        var op = ledger.get(ctx.actor(), plan.id());
        if (op.uncertain()) return result(false, "RECOVERY_REQUIRED", "实体原生步骤不确定，未重放；需先核对UUID与证据。", status(op));
        op = ledger.cancel(ctx.actor(), plan.id());
        if (op.state() == MaterialLedger.State.CALLBACK_DONE) op = processing.deliver(ctx, op);
        return result(false, reason.isEmpty() ? "ENTITY_NATIVE_REJECTED" : reason, "实体步骤明确拒绝，已返还可确认的真实原料；未返还部分继续托管。", status(op));
    }
    private void append(AgentTool.Context ctx, String kind, Plan plan, UUID effectId, Object evidence) throws Exception {
        JsonObject data = AiJson.GSON.toJsonTree(evidence).getAsJsonObject(); data.addProperty("operationId", plan.id().toString());
        data.addProperty("effectId", effectId.toString()); data.addProperty("planHash", plan.hash());
        ctx.workspace().conversation().append(List.of(new Draft(ctx.taskId(), kind, null, data, effectId.toString())));
    }

    private ServerPlayer check(AgentTool.Context ctx, Plan plan, boolean checkArea) {
        if (!plan.intact() || !plan.epoch().equals(epoch) || plan.generation() != generation.getAsLong()) throw new IllegalStateException("ENTITY_PLAN_OR_GENERATION_CHANGED");
        if (!plan.owner().equals(ctx.actor().owner()) || !plan.serverId().equals(ctx.workspace().metadata().serverInstanceId())
                || !plan.conversationId().equals(ctx.workspace().primaryConversationId()) || plan.privacyEpoch() != ctx.workspace().conversation().privacyEpoch())
            throw new SecurityException("ENTITY_WORKSPACE_CHANGED");
        ServerPlayer player = inventory.requirePlayer(ctx.actor());
        if (!player.serverLevel().dimension().location().toString().equals(plan.entity().dimension())) throw new IllegalStateException("ENTITY_DIMENSION_CHANGED");
        var holder = recipe(plan.entity().recipeId().toString());
        if (holder.value() != runtime.get(plan.id())) throw new IllegalStateException("ENTITY_RECIPE_INSTANCE_CHANGED");
        guard(player, plan.entity(), List.of(new AABB(plan.entity().target())));
        entityProtection.itemUse(player, plan.entity());
        if (checkArea && !plan.area().equals(area(player, plan.entity().target()))) throw new IllegalStateException("ENTITY_APPROVED_AREA_CHANGED");
        return player;
    }
    private void guard(ServerPlayer player, EntityProcessingAdapter.Plan plan, List<AABB> bounds) {
        if (!server.isSameThread() || !player.getUUID().equals(plan.owner())) throw new SecurityException("ENTITY_ACTOR_MISMATCH");
        var privilege = permissions.checkBooleanPermission(player, "fireflymc.ai.entity_processing", true);
        if (!privilege.allowed()) throw new SecurityException(privilege.code());
        if (player.distanceToSqr(plan.target().getX() + .5, plan.target().getY(), plan.target().getZ() + .5) > 64
                && !permissions.checkBooleanPermission(player, "fireflymc.ai.virtual_entity_processing", false).allowed())
            throw new SecurityException("VIRTUAL_ENTITY_PROCESSING_PERMISSION_REQUIRED");
        AABB approved = new AABB(plan.target().getX() - 1, plan.target().getY(), plan.target().getZ() - 1,
                plan.target().getX() + 2, plan.target().getY() + 4, plan.target().getZ() + 2);
        for (AABB bound : bounds) {
            if (bound.minX < approved.minX || bound.maxX > approved.maxX || bound.minY < approved.minY || bound.maxY > approved.maxY
                    || bound.minZ < approved.minZ || bound.maxZ > approved.maxZ) throw new SecurityException("ENTITY_OUTPUT_EXCEEDS_APPROVED_AREA");
            for (BlockPos position : BlockPos.betweenClosed((int) Math.floor(bound.minX), (int) Math.floor(bound.minY), (int) Math.floor(bound.minZ),
                    (int) Math.ceil(bound.maxX) - 1, (int) Math.ceil(bound.maxY) - 1, (int) Math.ceil(bound.maxZ) - 1)) {
                if (!player.serverLevel().hasChunkAt(position) || player.serverLevel().isOutsideBuildHeight(position)
                        || !player.serverLevel().getWorldBorder().isWithinBounds(position)) throw new SecurityException("ENTITY_TARGET_UNAVAILABLE");
                var verdict = permissions.checkPlace(player, player.serverLevel(), position);
                if (!verdict.allowed()) throw new SecurityException(verdict.code());
            }
        }
    }
    private Map<Long, String> area(ServerPlayer player, BlockPos target) {
        var result = new TreeMap<Long, String>();
        for (BlockPos position : BlockPos.betweenClosed(target.offset(-1, 0, -1), target.offset(1, 3, 1))) {
            if (!player.serverLevel().hasChunkAt(position)) throw new IllegalStateException("ENTITY_AREA_NOT_LOADED");
            var state = player.serverLevel().getBlockState(position);
            if (state.hasBlockEntity()) throw new IllegalArgumentException("ENTITY_AREA_CONTAINS_BLOCK_ENTITY");
            result.put(position.asLong(), state.toString());
        }
        return result;
    }
    private RecipeHolder<?> recipe(String id) { return server.getRecipeManager().byKey(ResourceLocation.parse(id)).orElseThrow(() -> new IllegalArgumentException("ENTITY_RECIPE_NOT_FOUND")); }
    private Plan load(Workspace workspace, Actor actor, UUID id, String hash) throws Exception {
        synchronized (workspace.conversation()) {
            actor.requireAccess(workspace.owner());
            boolean manifest = workspace.conversation().getSnapshot(workspace.conversation().privacyEpoch()).events().stream().anyMatch(e -> e.kind().equals("special_entity_plan")
                    && id.toString().equals(e.data().get("planId").getAsString()) && hash.equals(e.data().get("hash").getAsString()));
            var file = workspace.artifact(id);
            if (!manifest || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 8 * 1024 * 1024) throw new IllegalArgumentException("ENTITY_PLAN_NOT_FOUND");
            Plan plan = AiJson.GSON.fromJson(Files.readString(file), Plan.class);
            if (plan.version() != 1 || !plan.id().equals(id) || !plan.hash().equals(hash) || !plan.intact() || !plan.owner().equals(actor.owner())
                    || !plan.serverId().equals(workspace.metadata().serverInstanceId()) || !plan.conversationId().equals(workspace.primaryConversationId())
                    || plan.privacyEpoch() != workspace.conversation().privacyEpoch()) throw new SecurityException("ENTITY_PLAN_IDENTITY_CHANGED");
            return plan;
        }
    }
    String review(Workspace workspace, Actor actor, UUID id, String hash) throws Exception {
        Plan plan = load(workspace, actor, id, hash);
        var text = new StringBuilder("确定的实体加工计划\n\n配方：").append(plan.entity().recipeId()).append("\n产出实体：").append(plan.entity().entityType())
                .append("\n位置：").append(plan.entity().dimension()).append(' ').append(plan.entity().target().toShortString()).append("\n区域：以目标为中心3×4×3格，逐位置检查保护\n\n真实材料：\n");
        for (var need : plan.needs()) {
            var row = plan.sources().stream().filter(s -> s.key().equals(need.stockKey())).findFirst().orElseThrow();
            text.append("• ").append(AiJson.object(row.stack().itemJson(), 1_048_576, 32).get("id").getAsString()).append(" × ").append(need.count()).append('\n');
        }
        if (plan.entity().entityType().toString().equals("touhou_little_maid:box")) text.append("\n包含盒子与骑乘女仆两个实体，各自记录唯一UUID。\n");
        return text.append("\n免设备、power、燃料、能耗、经验费用和等待。原料仅在原生成功回执后结算；不确定结果先对账，不自动重放。\n计划摘要：").append(hash).toString();
    }
    private static JsonObject status(MaterialLedger.Operation operation) { JsonObject data = new JsonObject(); data.addProperty("operationId", operation.operationId().toString()); data.addProperty("state", operation.state().name()); data.addProperty("reason", operation.reason()); data.addProperty("saved", operation.saved()); data.addProperty("entityReceipts", operation.externalDebits().size()); return data; }
    private static String code(Throwable failure) { String code = failure.getMessage(); return code != null && code.matches("[A-Z0-9_]{1,100}") ? code : "ENTITY_NATIVE_FAILURE"; }
    private static AgentTool.Result result(boolean success, String code, String summary, JsonObject data) { return new AgentTool.Result(success, code, summary, data, "server-native-entity-receipts", "", false); }
}
