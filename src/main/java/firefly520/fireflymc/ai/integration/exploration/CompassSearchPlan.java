package firefly520.fireflymc.ai.integration.exploration;

import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.util.*;

public record CompassSearchPlan(int version, UUID id, String owner, UUID serverInstanceId, UUID conversationId,
        long privacyEpoch, UUID executionEpoch, UUID taskId, long registryGeneration, long expiresAt,
        Kind kind, String targetId, String hand, String itemId, String itemFingerprint, String dimension,
        int originX, int originY, int originZ, String configFingerprint, int expectedXpLevels,
        boolean coordinatesVisible, CompassSearchBudget budget, String hash) {
    public enum Kind {
        BIOME("naturescompass", "NaturesCompass"), STRUCTURE("explorerscompass", "ExplorersCompass");
        public static final String VERSION = "1.21.1-3.4.0-neoforge";
        private final String mod, name;
        Kind(String mod, String name) { this.mod = mod; this.name = name; }
        public String modId() { return mod; }
        public String prefix() { return "com.chaosthedude." + mod; }
        public String itemClass() { return prefix() + ".items." + name + "Item"; }
        public String modClass() { return prefix() + "." + name; }
    }
    public CompassSearchPlan {
        UUID.fromString(owner); Objects.requireNonNull(id); Objects.requireNonNull(serverInstanceId); Objects.requireNonNull(conversationId);
        Objects.requireNonNull(executionEpoch); Objects.requireNonNull(taskId); Objects.requireNonNull(kind); Objects.requireNonNull(budget);
        if (version != 1 || privacyEpoch < 0 || registryGeneration < 0 || expiresAt <= 0 || expectedXpLevels < 0
                || !resource(targetId) || !resource(itemId) || !resource(dimension) || !Set.of("MAIN_HAND", "OFF_HAND").contains(hand)
                || !digest(itemFingerprint) || !digest(configFingerprint) || Math.abs((long) originX) > 30_000_000 || Math.abs((long) originZ) > 30_000_000
                || hash == null || !hash.isEmpty() && !digest(hash)) throw new IllegalArgumentException("COMPASS_PLAN_INVALID");
    }
    private static boolean digest(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static boolean resource(String value) { return value != null && value.length() <= 160 && value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"); }
    public CompassSearchPlan signed() { return withHash(AiJson.digest(AiJson.canonical(withHash("")))); }
    public boolean intact() { return hash.equals(AiJson.digest(AiJson.canonical(withHash("")))); }
    private CompassSearchPlan withHash(String next) {
        return new CompassSearchPlan(version, id, owner, serverInstanceId, conversationId, privacyEpoch, executionEpoch, taskId,
                registryGeneration, expiresAt, kind, targetId, hand, itemId, itemFingerprint, dimension, originX, originY, originZ,
                configFingerprint, expectedXpLevels, coordinatesVisible, budget, next);
    }
    public void requireIdentity(Workspace workspace, Actor actor, UUID currentEpoch, long generation, long now) {
        actor.requireAccess(owner); actor.requireAccess(workspace.owner());
        if (!intact() || !serverInstanceId.equals(workspace.metadata().serverInstanceId()) || !conversationId.equals(workspace.primaryConversationId())
                || privacyEpoch != workspace.conversation().privacyEpoch()) throw new SecurityException("COMPASS_PLAN_IDENTITY_CHANGED");
        if (!executionEpoch.equals(currentEpoch)) throw new IllegalStateException("COMPASS_PLAN_EXECUTION_EPOCH_CHANGED");
        if (registryGeneration != generation) throw new IllegalStateException("COMPASS_REGISTRIES_RELOADED");
        if (now >= expiresAt) throw new IllegalStateException("COMPASS_PLAN_EXPIRED");
    }
}
