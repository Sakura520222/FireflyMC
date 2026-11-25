package firefly520.fireflymc.ai.processing;

import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.storage.AiJson;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FluidProcessingPlannerTest {
    static final FluidAmount WATER = new FluidAmount("{\"id\":\"minecraft:water\",\"amount\":1}", 1000);
    static final FluidAmount LAVA = new FluidAmount("{\"id\":\"minecraft:lava\",\"amount\":1}", 1000);
    static final MaterialStack BUCKET = new MaterialStack("{\"id\":\"minecraft:bucket\",\"count\":1}", 1);
    static FluidInventoryService.Source source(String backend, FluidTankSnapshot.Tank... tanks) {
        return new FluidInventoryService.Source(UUID.randomUUID(), 1, backend, "a".repeat(64), new FluidTankSnapshot(List.of(tanks)));
    }
    static MaterialLedger.Stock stock(String alias, String pool, MaterialStack stack, long units, long poolUnits) {
        return new MaterialLedger.Stock(alias, 1, "physical", pool, 0, stack, units, poolUnits, stack.count());
    }
    @Test void bucketEmptyRequiresExactDeclaredItemAndFillRequiresExactFluid() {
        assertThrows(IllegalArgumentException.class, () -> new FluidProcessingPlanner.Request("", FluidProcessingAdapters.BUCKET_EMPTY, "", "", 1, null));
        assertThrows(IllegalArgumentException.class, () -> new FluidProcessingPlanner.Request("", FluidProcessingAdapters.BUCKET_EMPTY, "minecraft:water", "minecraft:water_bucket", 1, null));
        assertThrows(IllegalArgumentException.class, () -> new FluidProcessingPlanner.Request("", FluidProcessingAdapters.BUCKET_FILL, "mod:unknown", "", 1, null));
        assertThrows(IllegalArgumentException.class, () -> new FluidProcessingPlanner.Request("recipe:test", FluidProcessingAdapters.BUCKET_FILL, "minecraft:water", "", 1, null));
        assertThrows(IllegalArgumentException.class, () -> new FluidProcessingPlanner.Request("recipe:test", "", "", "", 17, null));
        assertDoesNotThrow(() -> new FluidProcessingPlanner.Request(null, FluidProcessingAdapters.BUCKET_EMPTY, null, "minecraft:lava_bucket", 16, null));
    }
    @Test void verifiedArtifactIsCanonicalizedBeforeTheNativeIdentityGuard() {
        var plan = FluidProcessingPlanTest.plan(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID().toString(), 0);
        var binding = new FluidProcessingPlanner.Binding(plan, null);
        var loaded = AiJson.GSON.fromJson(AiJson.GSON.toJson(plan), FluidProcessingPlan.class);
        assertNotSame(plan, loaded);
        assertEquals(plan, loaded);
        assertThrows(SecurityException.class, () -> binding.requireOriginal(loaded));
        var canonical = binding.canonical(loaded);
        assertSame(plan, canonical);
        assertDoesNotThrow(() -> binding.requireOriginal(canonical));
    }
    @Test void evenAnIntactResignedArtifactCannotReplaceItsOriginalRuntimeBinding() {
        var plan = FluidProcessingPlanTest.plan(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID().toString(), 0);
        var binding = new FluidProcessingPlanner.Binding(plan, null);
        var json = AiJson.GSON.toJsonTree(plan).getAsJsonObject();
        json.addProperty("dimension", "minecraft:the_nether");
        var changed = AiJson.GSON.fromJson(json, FluidProcessingPlan.class).signed();
        assertTrue(changed.intact());
        assertEquals(plan.id(), changed.id());
        assertThrows(SecurityException.class, () -> binding.canonical(changed));
        assertThrows(SecurityException.class, () -> binding.requireOriginal(changed));
        assertDoesNotThrow(() -> binding.requireOriginal(plan));
    }
    @Test void aliasesDoNotMultiplyAvailableItemCount() {
        var one = stock("alias1", "pool", BUCKET, 1, 1); var two = stock("alias2", "pool", BUCKET, 1, 1);
        assertThrows(IllegalStateException.class, () -> FluidProcessingPlanner.selectItems(List.of(one, two), Map.of(one.key(), 1L), Map.of("pool", 1L), 2));
        var selected = FluidProcessingPlanner.selectItems(List.of(one, two), Map.of(one.key(), 1L), Map.of("pool", 1L), 1);
        assertEquals(1, selected.sources().size()); assertEquals(1, selected.inputs().size());
    }
    @Test void compactedAlternativesUseSharedPoolUnitsAndAvoidGreedyFailure() {
        var block = stock("drawer", "pool", new MaterialStack("{\"id\":\"minecraft:iron_block\"}", 1), 9, 9);
        var ingots = stock("drawer", "pool", new MaterialStack("{\"id\":\"minecraft:iron_ingot\"}", 9), 1, 9);
        var selected = FluidProcessingPlanner.selectItems(List.of(block, ingots), Map.of(block.key(), 1L, ingots.key(), 9L), Map.of("pool", 9L), 9);
        assertEquals(ingots.key(), selected.needs().getFirst().stockKey()); assertEquals(9, selected.inputs().size());
        assertThrows(IllegalStateException.class, () -> FluidProcessingPlanner.selectItems(List.of(block, ingots),
                Map.of(block.key(), 1L, ingots.key(), 9L), Map.of("pool", 9L), 10));
    }
    @Test void existingGlobalReservationsRemainUnavailableToAllocation() {
        var many = stock("player", "pool", BUCKET.withCount(8), 1, 8);
        assertThrows(IllegalStateException.class, () -> FluidProcessingPlanner.selectItems(List.of(many), Map.of(many.key(), 2L), Map.of("pool", 2L), 3));
    }
    @Test void aliasPoolDisagreementIsRejectedInsteadOfSelectingLargerSnapshot() {
        var one = stock("alias1", "pool", BUCKET, 1, 1);
        var two = stock("alias2", "pool", BUCKET.withCount(2), 1, 2);
        assertThrows(IllegalStateException.class, () -> FluidProcessingPlanner.deduplicateItems(List.of(one, two)));
    }
    @Test void duplicateFluidBackendDoesNotDoubleOutputCapacity() {
        var one = source("backend", new FluidTankSnapshot.Tank(1000, FluidAmount.EMPTY));
        var alias = new FluidInventoryService.Source(UUID.randomUUID(), 1, one.backend(), one.authorizationFingerprint(), one.snapshot());
        assertThrows(IllegalStateException.class, () -> FluidProcessingPlanner.allocateOutputs(List.of(WATER.withAmount(1500)), List.of(one, alias), List.of()));
        var result = FluidProcessingPlanner.allocateOutputs(List.of(WATER), List.of(one, alias), List.of());
        assertEquals(1, result.size()); assertEquals(one.grantId(), result.getFirst().source().grantId());
    }
    @Test void sameAndDifferentFluidOutputsCompeteForTheSameEmptyTank() {
        var tank = source("backend", new FluidTankSnapshot.Tank(1500, FluidAmount.EMPTY));
        assertThrows(IllegalStateException.class, () -> FluidProcessingPlanner.allocateOutputs(List.of(WATER, WATER), List.of(tank), List.of()));
        assertThrows(IllegalStateException.class, () -> FluidProcessingPlanner.allocateOutputs(List.of(WATER.withAmount(500), LAVA.withAmount(500)), List.of(tank), List.of()));
    }
    @Test void matchingTankIsUsedBeforeAnEmptyTankNeededByAnotherFluid() {
        var tank = source("backend", new FluidTankSnapshot.Tank(1000, FluidAmount.EMPTY), new FluidTankSnapshot.Tank(2000, WATER));
        var targets = FluidProcessingPlanner.allocateOutputs(List.of(WATER, LAVA), List.of(tank), List.of());
        assertEquals(2, targets.size()); assertEquals(List.of(0, 1), targets.stream().map(FluidProcessingPlan.OutputTarget::outputIndex).toList());
        assertTrue(tank.snapshot().tanks().getFirst().fluid().empty()); // Pure planning never mutates the captured source.
    }
    @Test void exactPlannedDrainReleasesSpaceAndPartialDrainCannotReplaceAnotherFluid() {
        var tank = source("backend", new FluidTankSnapshot.Tank(1000, WATER));
        var claim = new FluidProcessingPlan.Claim(UUID.randomUUID(), tank, WATER);
        assertEquals(1, FluidProcessingPlanner.allocateOutputs(List.of(LAVA), List.of(tank), List.of(claim)).size());
        var partial = new FluidProcessingPlan.Claim(UUID.randomUUID(), tank, WATER.withAmount(500));
        assertThrows(IllegalStateException.class, () -> FluidProcessingPlanner.allocateOutputs(List.of(LAVA.withAmount(500)), List.of(tank), List.of(partial)));
    }
    @Test void partialDrainAcrossSeveralTanksDoesNotGuessWhichTankWillBeEmpty() {
        var tank = source("backend", new FluidTankSnapshot.Tank(1000, WATER), new FluidTankSnapshot.Tank(1000, WATER));
        var partial = new FluidProcessingPlan.Claim(UUID.randomUUID(), tank, WATER);
        assertThrows(IllegalStateException.class, () -> FluidProcessingPlanner.allocateOutputs(List.of(LAVA), List.of(tank), List.of(partial)));
        var full = new FluidProcessingPlan.Claim(UUID.randomUUID(), tank, WATER.withAmount(2000));
        assertEquals(1, FluidProcessingPlanner.allocateOutputs(List.of(LAVA), List.of(tank), List.of(full)).size());
    }
    @Test void identicalOutputRowsKeepDistinctIndexesAndMaySplitAcrossSources() {
        var a = source("a", new FluidTankSnapshot.Tank(1500, FluidAmount.EMPTY));
        var b = source("b", new FluidTankSnapshot.Tank(500, FluidAmount.EMPTY));
        var result = FluidProcessingPlanner.allocateOutputs(List.of(WATER, WATER), List.of(a, b), List.of());
        assertEquals(List.of(0, 1, 1), result.stream().map(FluidProcessingPlan.OutputTarget::outputIndex).toList());
        assertEquals(List.of(1000, 500, 500), result.stream().map(target -> target.amount().millibuckets()).toList());
    }
    @Test void changedFluidAliasStateCannotBeCombined() {
        var a = source("backend", new FluidTankSnapshot.Tank(1000, WATER));
        var b = source("backend", new FluidTankSnapshot.Tank(1000, FluidAmount.EMPTY));
        assertThrows(IllegalStateException.class, () -> FluidProcessingPlanner.deduplicateFluids(List.of(a, b)));
    }
}
