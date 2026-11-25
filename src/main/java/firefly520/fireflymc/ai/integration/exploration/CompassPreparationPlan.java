package firefly520.fireflymc.ai.integration.exploration;

import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.util.Objects;
import java.util.UUID;

/** New generation permission is an independently signed, explicit part of the original fixed search. */
public record CompassPreparationPlan(UUID id, CompassSearchPlan search, CompassPreparationBudget budget, String hash) {
    public CompassPreparationPlan {
        Objects.requireNonNull(id); Objects.requireNonNull(search); Objects.requireNonNull(budget);
        if (id.equals(search.id()) || search.kind() != CompassSearchPlan.Kind.STRUCTURE || !search.intact() || !budget.enabled()
                || budget.maximumMillis() > search.budget().maxMillis() || hash == null || !hash.isEmpty() && !hash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("COMPASS_PREPARATION_PLAN_INVALID");
    }
    public CompassPreparationPlan signed() { return new CompassPreparationPlan(id, search, budget, AiJson.digest(AiJson.canonical(new CompassPreparationPlan(id, search, budget, "")))); }
    public boolean intact() { return hash.equals(AiJson.digest(AiJson.canonical(new CompassPreparationPlan(id, search, budget, "")))); }
    public void requireIdentity(Workspace workspace, Actor actor, UUID epoch, long generation, long now) {
        if (!intact()) throw new SecurityException("COMPASS_PREPARATION_PLAN_CHANGED");
        search.requireIdentity(workspace, actor, epoch, generation, now);
    }
}
