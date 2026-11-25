package firefly520.fireflymc.ai.integration;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

import static firefly520.fireflymc.ai.integration.InstalledPermissionAdapters.*;
import static org.junit.jupiter.api.Assertions.*;

class InstalledPermissionAdaptersTest {
    @Test void travelDestinationUsesItsOwnIdentityGuardWithoutRelaxingOrdinaryWorldOperations() {
        var runtime = new FakeRuntime(); runtime.actorFailure = "WORLD_CONTEXT_MISMATCH"; runtime.allowDestinationContext = true;
        var adapters = new InstalledPermissionAdapters(runtime);
        assertFalse(adapters.checkPlace(null, null, null).allowed());
        assertFalse(adapters.checkBreak(null, null, null, Direction.UP).allowed());
        assertFalse(adapters.checkContainer(null, null, null, Direction.UP).allowed());
        assertEquals(0, runtime.claimCalls);
        assertTrue(adapters.checkTravelDestination(null, null, null).allowed());
        assertEquals(Action.TRAVEL_DESTINATION, runtime.lastRequest.action()); assertEquals(1, runtime.claimCalls);
        runtime.claimDenied = true;
        assertEquals("CLAIM_DENIED", adapters.checkTravelDestination(null, null, null).code()); assertEquals(2, runtime.claimCalls);
        runtime.administrativeMode = true;
        assertEquals("CLAIMS_ADMIN_MODE_ACTIVE", adapters.checkTravelDestination(null, null, null).code()); assertEquals(2, runtime.claimCalls);
        runtime.travelActorFailure = "WORLD_UNLOADED";
        assertEquals("WORLD_UNLOADED", adapters.checkTravelDestination(null, null, null).code()); assertEquals(2, runtime.claimCalls);
    }

    @Test void destinationQueriesCannotSkipDedicatedIdentityOrFailedInstalledProtection() {
        var runtime = new FakeRuntime(); runtime.allowDestinationContext = true; runtime.serverFailure = "SERVER_THREAD_REQUIRED";
        var adapters = new InstalledPermissionAdapters(runtime);
        assertEquals("SERVER_THREAD_REQUIRED", adapters.checkTravelDestination(null, null, null).code()); assertEquals(0, runtime.bindCalls);
        runtime.serverFailure = null; runtime.travelActorFailure = "PLAYER_NOT_ACTIVE";
        assertEquals("PLAYER_NOT_ACTIVE", adapters.checkTravelDestination(null, null, null).code()); assertEquals(0, runtime.bindCalls);
        runtime.travelActorFailure = null; runtime.opacVersion = "unmatched-version";
        assertFalse(adapters.checkTravelDestination(null, null, null).allowed());
        assertEquals(State.UNSUPPORTED_VERSION, adapters.snapshot().protection().state()); assertEquals(0, runtime.claimCalls);
    }
    @Test void nativeTrueDeniesAndPermissionsAreReadAgainForEveryOperation() {
        FakeRuntime runtime = new FakeRuntime();
        var adapters = new InstalledPermissionAdapters(runtime);
        runtime.claimDenied = false;
        assertTrue(adapters.checkPlace(null, null, null).allowed());
        runtime.claimDenied = true;
        assertFalse(adapters.checkBreak(null, null, null, Direction.NORTH).allowed());
        assertEquals(Action.BREAK, runtime.lastRequest.action());
        assertEquals(Direction.NORTH, runtime.lastRequest.side());
        runtime.claimDenied = false;
        assertTrue(adapters.checkContainer(null, null, null, Direction.EAST).allowed());
        assertEquals(Action.CONTAINER, runtime.lastRequest.action());
        assertEquals(3, runtime.claimCalls);
        assertEquals(State.RUNTIME_CALLABLE, adapters.snapshot().protection().state());
        assertFalse(adapters.snapshot().protection().behaviorVerified());
    }

    @Test void administrativeModeStopsWritesUntilThePlayerChangesMode() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.administrativeMode = true;
        var adapters = new InstalledPermissionAdapters(runtime);
        assertEquals("CLAIMS_ADMIN_MODE_ACTIVE", adapters.checkPlace(null, null, null).code());
        assertEquals(0, runtime.claimCalls);
        assertTrue(runtime.administrativeMode);
        runtime.administrativeMode = false;
        assertTrue(adapters.checkPlace(null, null, null).allowed());
        assertEquals(1, runtime.claimCalls);
    }

    @Test void absentModsUseTheExplicitBaselineButNeverSkipActorOrServerGuards() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.opacVersion = null;
        runtime.ranksVersion = null;
        var adapters = new InstalledPermissionAdapters(runtime);
        assertTrue(adapters.checkPlace(null, null, null).allowed());
        assertTrue(adapters.checkBooleanPermission(null, "fireflymc.ai.build", true).allowed());
        assertFalse(adapters.checkBooleanPermission(null, "fireflymc.ai.build", false).allowed());
        runtime.actorFailure = "PLAYER_NOT_ACTIVE";
        assertEquals("PLAYER_NOT_ACTIVE", adapters.checkPlace(null, null, null).code());
        runtime.serverFailure = "SERVER_THREAD_REQUIRED";
        assertEquals("SERVER_THREAD_REQUIRED",
                adapters.checkBooleanPermission(null, "fireflymc.ai.build", true).code());
        assertEquals(0, runtime.bindCalls);
        assertEquals(0, runtime.claimCalls);
        assertEquals(0, runtime.rankCalls);
    }

    @Test void unsupportedInstalledVersionsNeverFallBackToVanilla() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.opacVersion = "0.28.2";
        runtime.ranksVersion = "2101.1.4";
        var adapters = new InstalledPermissionAdapters(runtime);
        assertFalse(adapters.checkPlace(null, null, null).allowed());
        assertFalse(adapters.checkBooleanPermission(null, "fireflymc.ai.build", true).allowed());
        assertFalse(adapters.checkQuotaPermission(null, "fireflymc.ai.max_blocks", 64, 128).allowed());
        assertEquals(State.UNSUPPORTED_VERSION, adapters.snapshot().protection().state());
        assertEquals("0.28.2", adapters.snapshot().protection().installedVersion());
        assertEquals(0, runtime.bindCalls);
    }

    @Test void signatureFailureAndInvocationFailureDisableOnlyTheAffectedAdapter() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.protectionBindFailure = true;
        var adapters = new InstalledPermissionAdapters(runtime);
        assertFalse(adapters.checkPlace(null, null, null).allowed());
        assertEquals(State.FAILED, adapters.snapshot().protection().state());
        runtime.permission = new PermissionReading(false, true, null);
        assertTrue(adapters.checkBooleanPermission(null, "fireflymc.ai.build", false).allowed());
        runtime.rankCallFailure = true;
        assertFalse(adapters.checkBooleanPermission(null, "fireflymc.ai.build", true).allowed());
        runtime.rankCallFailure = false;
        int completedCalls = runtime.rankCalls;
        assertFalse(adapters.checkBooleanPermission(null, "fireflymc.ai.build", true).allowed());
        assertEquals(completedCalls, runtime.rankCalls, "A failed installed adapter cannot silently recover into fallback");
    }

    @Test void missingExplicitFalseAndWrongTypeHaveDifferentDecisions() {
        FakeRuntime runtime = new FakeRuntime();
        var adapters = new InstalledPermissionAdapters(runtime);
        runtime.permission = new PermissionReading(true, null, null);
        assertFalse(adapters.checkBooleanPermission(null, "fireflymc.ai.build", false).allowed());
        var missing = adapters.checkBooleanPermission(null, "fireflymc.ai.build", true);
        assertTrue(missing.allowed());
        assertEquals(Origin.MISSING_NODE, missing.origin());
        runtime.permission = new PermissionReading(false, false, null);
        assertFalse(adapters.checkBooleanPermission(null, "fireflymc.ai.build", true).allowed());
        runtime.permission = new PermissionReading(false, null, 100);
        assertEquals("PERMISSION_TYPE_INVALID",
                adapters.checkBooleanPermission(null, "fireflymc.ai.build", true).code());
        runtime.permission = new PermissionReading(false, true, null);
        assertTrue(adapters.checkBooleanPermission(null, "fireflymc.ai.build", false).allowed());
        assertEquals(5, runtime.rankCalls);
    }

    @Test void quotasClampValidValuesAndFallbackWithoutTruncatingInvalidNumbers() {
        FakeRuntime runtime = new FakeRuntime();
        var adapters = new InstalledPermissionAdapters(runtime);
        assertEquals(32, adapters.checkQuotaPermission(null, "fireflymc.ai.max_blocks", 128, 32).value());
        runtime.permission = new PermissionReading(false, null, 500L);
        assertEquals(32, adapters.checkQuotaPermission(null, "fireflymc.ai.max_blocks", 0, 32).value());
        runtime.permission = new PermissionReading(false, null, 0L);
        assertEquals(0, adapters.checkQuotaPermission(null, "fireflymc.ai.max_blocks", 32, 32).value());
        for (Number invalid : List.<Number>of(-1L, 1.5D, Double.NaN, Double.POSITIVE_INFINITY,
                new BigDecimal("1.01"), new BigInteger("9223372036854775808"))) {
            runtime.permission = new PermissionReading(false, null, invalid);
            var result = adapters.checkQuotaPermission(null, "fireflymc.ai.max_blocks", 32, 32);
            assertFalse(result.allowed(), "Invalid quota must not use the positive fallback: " + invalid);
            assertEquals(0, result.value());
        }
        runtime.permission = new PermissionReading(false, true, null);
        assertFalse(adapters.checkQuotaPermission(null, "fireflymc.ai.max_blocks", 32, 32).allowed());
        assertFalse(adapters.checkQuotaPermission(null, "fireflymc.ai.max_blocks", -1, 32).allowed());
    }

    @Test void malformedPermissionNodesAreRejectedBeforeOptionalCodeRuns() {
        FakeRuntime runtime = new FakeRuntime();
        var adapters = new InstalledPermissionAdapters(runtime);
        for (String node : List.of("op", "fireflymc.ai.*", "fireflymc.ai..build", "fireflymc.ai.Build",
                "fireflymc.ai." + "x".repeat(128))) {
            assertFalse(adapters.checkBooleanPermission(null, node, true).allowed());
        }
        assertEquals(0, runtime.bindCalls);
        assertEquals(0, runtime.rankCalls);
    }

    @Test void contextFailuresPreventProbingAndDoNotMislabelAnUnprobedMod() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.serverFailure = "AI_DEDICATED_SERVER_REQUIRED";
        var adapters = new InstalledPermissionAdapters(runtime);
        assertFalse(adapters.checkPlace(null, null, null).allowed());
        assertThrows(IllegalStateException.class, adapters::probe);
        assertEquals(State.DISCOVERED, adapters.snapshot().protection().state());
        assertEquals(0, runtime.bindCalls);
        runtime.serverFailure = null;
        assertEquals(State.STATIC_API_MATCH, adapters.probe().protection().state());
        assertFalse(adapters.snapshot().protection().behaviorVerified());
        assertEquals(0, runtime.claimCalls);
    }

    private static final class FakeRuntime implements RuntimeAccess {
        String opacVersion = OPAC_VERSION;
        String ranksVersion = RANKS_VERSION;
        String serverFailure;
        String actorFailure;
        String travelActorFailure;
        boolean allowDestinationContext;
        boolean claimDenied;
        boolean administrativeMode;
        boolean protectionBindFailure;
        boolean rankCallFailure;
        int bindCalls;
        int claimCalls;
        int rankCalls;
        WorldRequest lastRequest;
        PermissionReading permission = new PermissionReading(true, null, null);

        @Override public String serverGuard() { return serverFailure; }
        @Override public String actorGuard(ServerPlayer player, ServerLevel level, BlockPos pos, boolean worldOperation) {
            return actorFailure;
        }
        @Override public String travelDestinationGuard(ServerPlayer player, ServerLevel level, BlockPos pos) {
            return allowDestinationContext ? travelActorFailure : actorFailure;
        }
        @Override public String installedVersion(String modId) {
            return modId.equals(OPAC_ID) ? opacVersion : ranksVersion;
        }
        @Override public ProtectionCalls bindProtection() throws ReflectiveOperationException {
            bindCalls++;
            if (protectionBindFailure) throw new NoSuchMethodException("simulated changed public signature");
            return new ProtectionCalls() {
                @Override public boolean administrativeMode(ServerPlayer player) { return administrativeMode; }
                @Override public boolean denies(WorldRequest request) {
                    lastRequest = request;
                    claimCalls++;
                    return claimDenied;
                }
            };
        }
        @Override public RankCalls bindRanks() {
            bindCalls++;
            return (player, node) -> {
                rankCalls++;
                if (rankCallFailure) throw new ReflectiveOperationException("simulated adapter failure");
                return permission;
            };
        }
    }
}
