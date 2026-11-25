package firefly520.fireflymc.ai.material;

import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Private source permissions. Only workbench confirmation can add them; tool proposals cannot. */
public final class MaterialSourceGrants {
    public enum Domain { ITEMS("materials"), FLUIDS("fluids");
        private final String directory;
        Domain(String directory) { this.directory = directory; }
    }
    public enum Kind { BLOCK, BACKPACK_ITEM, PLAYER_EQUIPMENT, EQUIPMENT_BACKPACK, MAID_BACKPACK }
    public record Member(String dimension, int x, int y, int z, String blockEntityType, UUID identity) {}
    public record Candidate(Kind kind, String locator, String description, List<Member> members,
                            String hostFingerprint, boolean extract, boolean insert) {
        public Candidate {
            if (kind == null || locator == null || locator.length() > 512 || description == null
                    || description.length() > 1000 || members == null || members.size() > 128
                    || hostFingerprint == null || (!extract && !insert))
                throw new IllegalArgumentException("MATERIAL_SOURCE_INVALID");
            members = List.copyOf(members);
        }
        public String digest() { return AiJson.digest(AiJson.GSON.toJson(this)); }
    }
    public record Grant(UUID id, long revision, Candidate source, long createdAt, boolean revoked) {}
    public record Preview(UUID id, String owner, long revision, Candidate source, String digest,
                          long expiresAt) {}
    private record Stored(int version, String owner, long revision, List<Grant> grants) {}

    private final Workspace workspace;
    private final Domain domain;
    private final LongSupplier clock;
    private final long privacyEpoch;
    private final Map<UUID, Preview> previews = new LinkedHashMap<>();
    private volatile Stored state;

    public MaterialSourceGrants(Workspace workspace, LongSupplier clock) throws IOException {
        this(workspace, clock, Domain.ITEMS);
    }
    public MaterialSourceGrants(Workspace workspace, LongSupplier clock, Domain domain) throws IOException {
        this.workspace = workspace; this.clock = clock;
        this.domain = Objects.requireNonNull(domain);
        synchronized (workspace.conversation()) {
            privacyEpoch = workspace.conversation().privacyEpoch();
            Path file = file();
            if (Files.exists(file)) {
                state = AiJson.GSON.fromJson(Files.readString(file), Stored.class);
                if (state.version() != 1 || !state.owner().equals(workspace.owner()) || state.revision() < 0
                        || state.grants() == null || state.grants().size() > 128) throw new IOException("SOURCE_GRANT_CORRUPT");
            } else state = new Stored(1, workspace.owner(), 0, List.of());
        }
    }
    private Path file() throws IOException {
        Path dir = workspace.directory().resolve(domain.directory), file = dir.resolve("grants.json");
        if (Files.isSymbolicLink(dir) || Files.isSymbolicLink(file)) throw new IOException("MATERIAL_UNSAFE_PATH");
        return file;
    }
    public Domain domain() { return domain; }
    public List<Grant> list(Actor actor) {
        access(actor);
        return state.grants();
    }
    public synchronized Preview preview(Actor actor, Candidate source) {
        access(actor);
        previews.values().removeIf(p -> p.expiresAt() < clock.getAsLong());
        if (previews.size() >= 8) throw new IllegalStateException("SOURCE_PREVIEW_LIMIT");
        Preview preview = new Preview(UUID.randomUUID(), workspace.owner(), state.revision(), source,
                source.digest(), clock.getAsLong() + 120000);
        previews.put(preview.id(), preview);
        return preview;
    }
    public synchronized Preview pending(Actor actor, UUID id, String digest) {
        access(actor);
        Preview value = previews.get(id);
        if (value == null || value.expiresAt() < clock.getAsLong() || value.revision() != state.revision()
                || !value.digest().equals(digest)) throw new IllegalStateException("SOURCE_PREVIEW_STALE");
        return value;
    }
    /** Revalidated source includes the durable identities attached by the trusted main-thread confirmer. */
    public synchronized Grant confirm(Actor actor, UUID id, String digest, Candidate revalidated) throws IOException {
        synchronized (workspace.conversation()) { return confirmLocked(actor, id, digest, revalidated); }
    }
    private Grant confirmLocked(Actor actor, UUID id, String digest, Candidate revalidated) throws IOException {
        access(actor);
        Preview preview = previews.get(id);
        if (preview == null || preview.expiresAt() < clock.getAsLong() || preview.revision() != state.revision()
                || !preview.digest().equals(digest) || !sameCandidateBeforeIdentity(preview.source(), revalidated))
            throw new IllegalStateException("SOURCE_PREVIEW_STALE");
        if (revalidated.members().stream().anyMatch(member -> member.identity() == null))
            throw new IllegalArgumentException("SOURCE_IDENTITY_REQUIRED");
        var grants = new ArrayList<>(state.grants());
        grants.removeIf(grant -> grant.revoked() || grant.source().locator().equals(revalidated.locator()));
        if (grants.size() >= 64) throw new IllegalStateException("SOURCE_GRANT_LIMIT");
        Grant grant = new Grant(id, state.revision() + 1, revalidated, clock.getAsLong(), false);
        grants.add(grant);
        Stored updated = new Stored(1, workspace.owner(), state.revision() + 1, List.copyOf(grants));
        AiJson.write(file(), updated);
        state = updated;
        previews.remove(id);
        var audit = new com.google.gson.JsonObject(); audit.add("sourceGrant", AiJson.GSON.toJsonTree(grant));
        workspace.conversation().append(List.of(new firefly520.fireflymc.ai.conversation.ConversationStore.Draft(
                null, domain == Domain.FLUIDS ? "fluid_source_granted" : "source_granted", null, audit, null)));
        return grant;
    }
    public synchronized void revoke(Actor actor, UUID id) throws IOException {
        synchronized (workspace.conversation()) { revokeLocked(actor, id); }
    }
    private void revokeLocked(Actor actor, UUID id) throws IOException {
        access(actor);
        if (state.grants().stream().noneMatch(g -> g.id().equals(id))) throw new IllegalArgumentException("SOURCE_NOT_FOUND");
        var grants = state.grants().stream().map(g -> g.id().equals(id)
                ? new Grant(g.id(), state.revision() + 1, g.source(), g.createdAt(), true) : g).toList();
        Stored updated = new Stored(1, workspace.owner(), state.revision() + 1, grants);
        AiJson.write(file(), updated); state = updated; previews.clear();
        var audit = new com.google.gson.JsonObject(); audit.addProperty("sourceId", id.toString());
        audit.addProperty("revision", updated.revision());
        workspace.conversation().append(List.of(new firefly520.fireflymc.ai.conversation.ConversationStore.Draft(
                null, domain == Domain.FLUIDS ? "fluid_source_revoked" : "source_revoked", null, audit, null)));
    }
    public Grant require(Actor actor, UUID id, long revision, boolean extraction) {
        access(actor);
        return state.grants().stream().filter(g -> g.id().equals(id) && g.revision() == revision && !g.revoked()
                && (extraction ? g.source().extract() : g.source().insert())).findFirst()
                .orElseThrow(() -> new SecurityException("SOURCE_AUTHORIZATION_CHANGED"));
    }
    private void access(Actor actor) {
        actor.requireAccess(workspace.owner());
        if (workspace.conversation().privacyEpoch() != privacyEpoch) throw new IllegalStateException("WORKSPACE_PRIVACY_EPOCH_CHANGED");
    }
    private static boolean sameCandidateBeforeIdentity(Candidate preview, Candidate current) {
        var members = current.members().stream().map(m -> {
            // A pre-existing identity must still match. Only an originally absent identity can be attached.
            var original = preview.members().stream().filter(p -> p.dimension().equals(m.dimension())
                    && p.x() == m.x() && p.y() == m.y() && p.z() == m.z()).findFirst().orElse(null);
            return original != null && original.identity() == null
                    ? new Member(m.dimension(), m.x(), m.y(), m.z(), m.blockEntityType(), null) : m;
        }).toList();
        return preview.equals(new Candidate(current.kind(), current.locator(), current.description(), members,
                current.hostFingerprint(), current.extract(), current.insert()));
    }
}
