package firefly520.fireflymc.ai.blueprint;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** An AI location proposal is private read-only metadata. Only a separate UI receipt confirms it. */
public final class BuildRegionProposalStore {
    public record Proposal(int version, UUID id, UUID server, UUID conversation, String owner, long privacyEpoch,
                           UUID blueprintId, long blueprintRevision, String blueprintHash, String documentHash,
                           String dimension, BlueprintDocument.Position first, BlueprintDocument.Position second,
                           String worldDigest, JsonObject survey, long createdAt, String hash) {
        public Proposal { survey = survey.deepCopy(); }
        @Override public JsonObject survey() { return survey.deepCopy(); }
    }
    public record Confirmation(UUID proposalId, String proposalHash, UUID blueprintId, long blueprintRevision,
                               String blueprintHash, long privacyEpoch, long regionRevision, String regionHash) {}
    private final Workspace workspace;
    private final long epoch;
    public BuildRegionProposalStore(Workspace workspace) { this.workspace = workspace; epoch = workspace.conversation().privacyEpoch(); }

    public Optional<Proposal> latest() {
        synchronized (workspace.conversation()) {
            epoch();
            return workspace.conversation().latestEvent("build_region_proposal")
                    .map(event -> verify(AiJson.GSON.fromJson(event.data(), Proposal.class)));
        }
    }

    public Proposal save(Actor actor, BlueprintRepository.Revision blueprint, BuildRegionStore.Bounds bounds, JsonObject survey) throws IOException {
        synchronized (workspace.conversation()) {
            actor.requireAccess(workspace.owner()); epoch();
            if (!new BlueprintRepository(workspace).current(actor, blueprint.entry())) throw new IllegalStateException("BLUEPRINT_VERSION_NOT_CURRENT");
            var entry = blueprint.entry();
            var unsigned = new Proposal(1, UUID.randomUUID(), workspace.metadata().serverInstanceId(), workspace.primaryConversationId(),
                    workspace.owner(), epoch, entry.id(), entry.revision(), entry.hash(), entry.documentHash(), entry.dimension(),
                    bounds.minimum(), bounds.maximum(), survey.get("worldDigest").getAsString(), survey.deepCopy(), System.currentTimeMillis(), "");
            var proposal = withHash(unsigned, AiJson.digest(AiJson.canonical(unsigned)));
            new BlueprintRepository(workspace).invalidateReview(actor, entry.id(), entry.hash());
            workspace.conversation().append(List.of(new Draft(entry.taskId(), "build_region_proposal", null,
                    AiJson.GSON.toJsonTree(proposal).getAsJsonObject(), null)));
            return proposal;
        }
    }

    public Proposal require(Actor actor, UUID id, String hash) {
        actor.requireAccess(workspace.owner());
        var proposal = latest().orElseThrow(() -> new IllegalStateException("BUILD_PROPOSAL_REQUIRED"));
        if (!proposal.id().equals(id) || !proposal.hash().equals(hash)) throw new IllegalStateException("BUILD_PROPOSAL_CHANGED");
        return proposal;
    }

    public void confirmed(Actor actor, Proposal proposal, BuildRegionStore.Selection region) throws IOException {
        synchronized (workspace.conversation()) {
            require(actor, proposal.id(), proposal.hash());
            if (!region.confirmed() || region.privacyEpoch() != epoch || !region.dimension().equals(proposal.dimension())
                    || !region.bounds().minimum().equals(proposal.first()) || !region.bounds().maximum().equals(proposal.second()))
                throw new IllegalStateException("BUILD_PROPOSAL_REGION_CHANGED");
            var receipt = new Confirmation(proposal.id(), proposal.hash(), proposal.blueprintId(), proposal.blueprintRevision(),
                    proposal.blueprintHash(), epoch, region.revision(), region.hash());
            workspace.conversation().append(List.of(new Draft(null, "build_region_proposal_confirmed", null,
                    AiJson.GSON.toJsonTree(receipt).getAsJsonObject(), null)));
        }
    }

    /** Construction calls this in addition to its existing region, protection, material and approval gates. */
    public void requireConfirmedBlueprint(Actor actor, BlueprintRepository.Entry blueprint) {
        synchronized (workspace.conversation()) {
            actor.requireAccess(workspace.owner()); epoch();
            boolean proposed = workspace.conversation().getSnapshot(epoch).events().stream().filter(e -> e.kind().equals("build_region_proposal"))
                    .map(e -> verify(AiJson.GSON.fromJson(e.data(), Proposal.class)))
                    .anyMatch(p -> p.blueprintId().equals(blueprint.id()));
            if (!proposed) return; // Existing imported/manual workflows retain their original gates.
            if (!new BlueprintRepository(workspace).current(actor, blueprint)) throw new IllegalStateException("BLUEPRINT_VERSION_NOT_CURRENT");
            var proposal = latest().orElseThrow(() -> new IllegalStateException("BUILD_PROPOSAL_REQUIRED"));
            var receipt = workspace.conversation().latestEvent("build_region_proposal_confirmed")
                    .map(e -> AiJson.GSON.fromJson(e.data(), Confirmation.class))
                    .orElseThrow(() -> new IllegalStateException("BUILD_PROPOSAL_NOT_CONFIRMED"));
            var region = new BuildRegionStore(workspace).current(actor).orElseThrow(() -> new IllegalStateException("BUILD_PROPOSAL_NOT_CONFIRMED"));
            if (!proposal.blueprintId().equals(blueprint.id()) || proposal.blueprintRevision() != blueprint.revision()
                    || !proposal.blueprintHash().equals(blueprint.hash()) || !receipt.proposalId().equals(proposal.id())
                    || !receipt.proposalHash().equals(proposal.hash()) || receipt.privacyEpoch() != epoch
                    || !receipt.blueprintHash().equals(blueprint.hash()) || receipt.blueprintRevision() != blueprint.revision()
                    || !receipt.blueprintId().equals(blueprint.id()) || !region.confirmed()
                    || receipt.regionRevision() != region.revision() || !receipt.regionHash().equals(region.hash()))
                throw new IllegalStateException("BUILD_PROPOSAL_NOT_CONFIRMED");
        }
    }

    public JsonObject view() {
        var proposal = latest();
        if (proposal.isEmpty()) return new JsonObject();
        JsonObject data = AiJson.GSON.toJsonTree(proposal.get()).getAsJsonObject();
        data.remove("owner"); data.remove("server"); data.remove("conversation");
        var latestBlueprint = workspace.conversation().getSnapshot(epoch).events().stream().filter(e -> e.kind().equals("blueprint_revision"))
                .map(e -> AiJson.GSON.fromJson(e.data(), BlueprintRepository.Entry.class))
                .filter(entry -> entry.id().equals(proposal.get().blueprintId())).reduce((earlier, later) -> later);
        boolean blueprintCurrent = latestBlueprint.isPresent() && latestBlueprint.get().hash().equals(proposal.get().blueprintHash());
        data.addProperty("blueprintCurrent", blueprintCurrent);
        var receipt = workspace.conversation().latestEvent("build_region_proposal_confirmed")
                .map(e -> AiJson.GSON.fromJson(e.data(), Confirmation.class));
        var region = workspace.conversation().latestEvent("build_region")
                .map(e -> AiJson.GSON.fromJson(e.data(), BuildRegionStore.Selection.class));
        boolean confirmed = blueprintCurrent && receipt.isPresent() && region.isPresent() && region.get().confirmed()
                && receipt.get().privacyEpoch() == epoch && receipt.get().proposalId().equals(proposal.get().id())
                && receipt.get().proposalHash().equals(proposal.get().hash()) && receipt.get().regionHash().equals(region.get().hash())
                && receipt.get().regionRevision() == region.get().revision();
        data.addProperty("regionConfirmed", confirmed);
        data.addProperty("constructionApproved", false);
        return data;
    }

    private Proposal verify(Proposal proposal) {
        epoch();
        if (proposal == null || proposal.version() != 1 || !workspace.owner().equals(proposal.owner())
                || !workspace.metadata().serverInstanceId().equals(proposal.server()) || !workspace.primaryConversationId().equals(proposal.conversation())
                || proposal.privacyEpoch() != epoch || !AiJson.digest(AiJson.canonical(withHash(proposal, ""))).equals(proposal.hash()))
            throw new IllegalStateException("BUILD_PROPOSAL_RECORD_INVALID");
        BuildRegionStore.bounds(proposal.first(), proposal.second());
        return proposal;
    }
    private void epoch() { if (workspace.conversation().privacyEpoch() != epoch) throw new IllegalStateException("BUILD_PROPOSAL_PRIVACY_CHANGED"); }
    private static Proposal withHash(Proposal p, String hash) { return new Proposal(p.version(), p.id(), p.server(), p.conversation(), p.owner(), p.privacyEpoch(),
            p.blueprintId(), p.blueprintRevision(), p.blueprintHash(), p.documentHash(), p.dimension(), p.first(), p.second(), p.worldDigest(), p.survey(), p.createdAt(), hash); }
}
