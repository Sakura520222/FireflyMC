package firefly520.fireflymc.validation;

import com.google.gson.*;
import firefly520.fireflymc.ai.*;
import firefly520.fireflymc.ai.knowledge.ServerKnowledgeService;
import firefly520.fireflymc.ai.storage.AiJson;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;

/** Real dedicated-process initialization/idle measurements. No model requests or player fixtures. */
@Mod("fireflymc_validation")
public final class ValidationMod {
    private final String scenario = System.getProperty("fireflymc.validation.scenario", "");
    private int ticks;
    private boolean finishing;
    private final List<Double> msptSamples = new ArrayList<>();
    private Object gameplay;
    public ValidationMod() {
        if (FMLEnvironment.production || !Set.of("dedicated-enabled", "dedicated-disabled", "dedicated-missing", "dedicated-construction", "dedicated-equipment", "dedicated-special", "dedicated-advanced", "dedicated-entities", "dedicated-fluids", "dedicated-environment", "dedicated-permissions", "dedicated-persistence", "dedicated-shutdown", "dedicated-fluid-storage", "dedicated-fluid-processing", "dedicated-processing-callbacks", "dedicated-performance", "dedicated-maid-tasks", "dedicated-maid-transport", "dedicated-maid-combat", "dedicated-tree-harvest", "dedicated-waystones-travel", "dedicated-exploration", "dedicated-storage-dynamic", "dedicated-craftable").contains(scenario))
            throw new IllegalStateException("EXPLICIT_DEVELOPMENT_VALIDATION_REQUIRED");
        NeoForge.EVENT_BUS.addListener(this::tick);
    }
    private void tick(ServerTickEvent.Post event) {
        if (finishing) return;
        MinecraftServer server = event.getServer();
        ticks++;
        if (Set.of("dedicated-construction", "dedicated-equipment", "dedicated-special", "dedicated-advanced", "dedicated-entities", "dedicated-fluids", "dedicated-environment", "dedicated-permissions", "dedicated-persistence", "dedicated-shutdown", "dedicated-fluid-storage", "dedicated-fluid-processing", "dedicated-processing-callbacks", "dedicated-performance", "dedicated-maid-tasks", "dedicated-maid-transport", "dedicated-maid-combat", "dedicated-tree-harvest", "dedicated-waystones-travel", "dedicated-exploration", "dedicated-storage-dynamic", "dedicated-craftable").contains(scenario)) {
            if (ticks < 20) return;
            JsonObject result;
            try {
                if (gameplay == null) gameplay = Class.forName("firefly520.fireflymc.validation."
                        + switch (scenario) { case "dedicated-construction" -> "ConstructionScenario"; case "dedicated-equipment" -> "EquipmentScenario";
                            case "dedicated-special", "dedicated-entities", "dedicated-fluids" -> "SpecialProcessingScenario";
                            case "dedicated-environment" -> "EnvironmentScenario"; case "dedicated-permissions" -> "PermissionsScenario";
                            case "dedicated-persistence" -> "NativePersistenceScenario";
                            case "dedicated-shutdown" -> "NativeShutdownScenario"; case "dedicated-fluid-storage" -> "FluidStorageScenario"; case "dedicated-fluid-processing" -> "FluidProcessingScenario"; case "dedicated-processing-callbacks" -> "ProcessingCallbackScenario";
                            case "dedicated-performance" -> "ProcessingPerformanceScenario";
                            case "dedicated-craftable" -> "CraftableDiscoveryScenario";
                            case "dedicated-maid-tasks" -> "MaidTaskScenario";
                            case "dedicated-maid-transport" -> "MaidTransportScenario";
                            case "dedicated-maid-combat" -> "MaidCombatScenario";
                            case "dedicated-tree-harvest" -> "TreeHarvestScenario";
                            case "dedicated-waystones-travel" -> "WaystonesTravelScenario";
                            case "dedicated-exploration" -> "ExplorationScenario";
                            case "dedicated-storage-dynamic" -> "StorageDynamicScenario";
                            default -> "AdvancedProcessingScenario"; })
                        .getConstructor(MinecraftServer.class).newInstance(server);
                result = (JsonObject) gameplay.getClass().getMethod("tick").invoke(gameplay);
                if (result == null && ticks < 3600) return;
                if (result == null) { result = new JsonObject(); result.addProperty("status", "FAILED"); result.addProperty("error", "VALIDATION_TIMEOUT"); }
            } catch (Exception failure) {
                failure.printStackTrace(); result = new JsonObject(); result.addProperty("status", "FAILED"); result.addProperty("errorClass", failure.getClass().getSimpleName());
            }
            result.addProperty("scenario", scenario); result.addProperty("dedicatedServer", server.isDedicatedServer());
            result.addProperty("java", System.getProperty("java.version")); result.addProperty("minecraft", server.getServerVersion());
            result.addProperty("measuredTicks", ticks);
            if (gameplay != null) try { gameplay.getClass().getMethod("close").invoke(gameplay); }
            catch (Exception failure) { result.addProperty("cleanupError", failure.getClass().getSimpleName()); result.addProperty("status", "FAILED"); }
            finishing = true; writeReport(server, result); return;
        }
        if (ticks >= 100 && ticks % 20 == 0) msptSamples.add(server.getAverageTickTimeNanos() / 1_000_000.0);
        if (ticks < 400) return;
        finishing = true;
        JsonObject report = new JsonObject();
        try {
            report.addProperty("scenario", scenario);
            report.addProperty("dedicatedServer", server.isDedicatedServer());
            report.addProperty("minecraft", server.getServerVersion());
            report.addProperty("java", System.getProperty("java.version"));
            report.addProperty("os", System.getProperty("os.name"));
            report.addProperty("maximumHeapBytes", Runtime.getRuntime().maxMemory());
            report.addProperty("measuredTicks", ticks);
            report.addProperty("warmupTicks", 100);
            report.add("msptSamples", AiJson.GSON.toJsonTree(msptSamples));
            report.addProperty("msptMean", msptSamples.stream().mapToDouble(Double::doubleValue).average().orElse(0));
            report.addProperty("msptMaxSample", msptSamples.stream().mapToDouble(Double::doubleValue).max().orElse(0));
            report.add("loadedMods", AiJson.GSON.toJsonTree(ModList.get().getMods().stream()
                    .map(mod -> Map.of("id", mod.getModId(), "version", mod.getVersion().toString())).toList()));
            Map<?, ?> services = field(AiServerEvents.class, null, "SERVICES", Map.class);
            AiServerService service = (AiServerService) services.get(server);
            report.addProperty("serviceInitialized", service != null);
            report.addProperty("available", service != null && service.available());
            boolean indexed = service == null;
            if (service != null) {
                var knowledge = field(AiServerService.class, service, "knowledge", ServerKnowledgeService.class);
                report.add("knowledge", knowledge.envelope());
                indexed = "READY".equals(knowledge.envelope().get("indexState").getAsString());
                report.addProperty("worstIndexSliceMillis",
                        field(ServerKnowledgeService.class, knowledge, "worstSliceNanos", Long.class) / 1_000_000.0);
            }
            Path ai = server.getWorldPath(LevelResource.ROOT).resolve("data/fireflymc/ai");
            boolean expected = "dedicated-enabled".equals(scenario);
            boolean passed = server.isDedicatedServer() && expected == (service != null)
                    && (!expected || service.available()) && Files.exists(ai) == expected && indexed;
            report.addProperty("aiDataDirectoryCreated", Files.exists(ai));
            report.addProperty("status", passed ? "PASSED" : "FAILED");
            report.addProperty("scope", "Dedicated initialization and idle indexing only; no player/UI/model/gameplay/pack compatibility claim.");
        } catch (Exception exception) {
            report.addProperty("status", "FAILED");
            report.addProperty("errorClass", exception.getClass().getSimpleName());
        }
        writeReport(server, report);
    }
    private void writeReport(MinecraftServer server, JsonObject report) {
        Thread.ofPlatform().daemon(false).name("ai4-validation-report").start(() -> {
            try {
                Path root = Path.of(System.getProperty("fireflymc.validation.reports")).toAbsolutePath().normalize();
                String profile = System.getProperty("fireflymc.validation.profile", "");
                report.addProperty("profile", profile);
                String name = scenario + (profile.isEmpty() ? "" : "-" + profile);
                String archive = "runs/" + name + "-" + UUID.randomUUID() + ".json";
                report.addProperty("archive", archive);
                AiJson.write(root.resolve(archive), report);
                AiJson.write(root.resolve(name + ".json"), report);
                System.out.println("AI4_VALIDATION " + scenario + " " + report.get("status").getAsString());
            } catch (Exception exception) {
                System.err.println("AI4_VALIDATION REPORT_WRITE_FAILED");
            } finally { server.execute(() -> server.halt(false)); }
        });
    }
    private static <T> T field(Class<?> type, Object instance, String name, Class<T> expected) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return expected.cast(field.get(instance));
    }
}
