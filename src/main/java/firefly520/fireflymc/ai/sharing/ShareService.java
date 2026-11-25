package firefly520.fireflymc.ai.sharing;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.conversation.ConversationStore;
import firefly520.fireflymc.ai.security.*;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Only explicit, immutable, owner-reviewed assistant text can leave the private workspace. */
public final class ShareService {
    public enum Target { SERVER_CHAT, CROSS_CHAT }
    public record Channel(String key, String audience, String endpointHash) {
        public Channel {
            if (key == null || key.isBlank() || audience == null || audience.isBlank() || audience.length() > 512
                    || endpointHash == null) throw new IllegalArgumentException("SHARE_CHANNEL_REQUIRED");
        }
        public String binding() { return AiJson.digest(AiJson.GSON.toJson(this)); }
    }
    public enum State { PREVIEW, DISPATCHING, PUBLISHED, RELAY_ACCEPTED, FAILED, DELIVERY_UNCERTAIN }
    public record Preview(UUID id, String owner, UUID conversationId, UUID messageId, long messageRevision,
                          String sourceDigest, String contentDigest, Target target, String audience,
                          String publicText, boolean truncated, long expiresAt, State state, UUID requestId,
                          String channelBinding, String endpointHash) {}
    public record Confirmation(Preview preview, boolean dispatch) {}
    private record Data(int version, List<Preview> previews) {}
    private final Workspace workspace;
    private final SecretFilter secrets;
    private final LongSupplier clock;
    private final Map<Target, Channel> channels;
    public ShareService(Workspace workspace, SecretFilter secrets, LongSupplier clock) {
        this(workspace, secrets, clock, Map.of());
    }
    public ShareService(Workspace workspace, SecretFilter secrets, LongSupplier clock, Map<Target, Channel> channels) {
        this.workspace = workspace; this.secrets = secrets; this.clock = clock;
        var configured = new EnumMap<Target, Channel>(Target.class);
        configured.put(Target.SERVER_CHAT, new Channel("server:" + workspace.metadata().serverInstanceId(), "当前专服全体在线玩家", ""));
        configured.putAll(channels);
        this.channels = Map.copyOf(configured);
    }
    private List<Preview> load() throws IOException {
        Path path = workspace.directory().resolve("shares.json");
        if (!Files.exists(path)) return new ArrayList<>();
        Data data = AiJson.GSON.fromJson(Files.readString(path), Data.class);
        if (data.version != 1) throw new IOException("SHARE_VERSION");
        return new ArrayList<>(data.previews);
    }
    private ConversationStore.Event message(UUID id) {
        return workspace.conversation().getSnapshot(0).events().stream().filter(e -> id.equals(e.messageId()))
                .findFirst().orElseThrow(() -> new SecurityException("MESSAGE_NOT_OWNED"));
    }
    public Preview preview(Actor actor, UUID messageId, Target target, String author, boolean targetAvailable) throws IOException {
        actor.requireAccess(workspace.owner());
        if (!targetAvailable || !channels.containsKey(target)) throw new IllegalStateException("SHARE_TARGET_UNAVAILABLE");
        Channel channel = channels.get(target);
        synchronized (workspace.conversation()) {
            var event = message(messageId);
            if (event.message() == null || !"assistant".equals(event.message().role())
                    || !event.message().calls().isEmpty() || event.message().text().isBlank())
                throw new SecurityException("MESSAGE_NOT_SHAREABLE");
            String safe = publicText(firefly520.fireflymc.ai.presentation.ResponseBlocks.plainText(event.message().text()));
            if (safe.isBlank()) throw new IllegalStateException("NO_PUBLIC_CONTENT");
            boolean truncated = safe.length() > 1800;
            safe = safe.substring(0, Math.min(1800, safe.length()));
            String text = secrets.redact("[" + author + " 分享小萤] " + safe + (truncated ? "\n（已截断）" : ""));
            String audience = channel.audience();
            Preview preview = new Preview(UUID.randomUUID(), actor.owner(), workspace.primaryConversationId(),
                    messageId, event.seq(), AiJson.digest(event.message().text()), AiJson.digest(text), target,
                    audience, text, truncated, clock.getAsLong() + 120000, State.PREVIEW, null, channel.binding(), channel.endpointHash());
            var previews = load();
            previews.removeIf(p -> p.expiresAt < clock.getAsLong() - 86400000L && p.state != State.DISPATCHING);
            if (previews.size() >= 256) throw new IllegalStateException("SHARE_HISTORY_LIMIT");
            previews.add(preview);
            AiJson.write(workspace.directory().resolve("shares.json"), new Data(1, previews));
            return preview;
        }
    }
    /** Dispatch is marked durably before transport. A retry never dispatches the same preview again. */
    public Confirmation confirm(Actor actor, UUID id, UUID request, String digest, Target target, boolean targetAvailable)
            throws IOException {
        actor.requireAccess(workspace.owner());
        if (!targetAvailable || !actor.online() || !channels.containsKey(target)) throw new SecurityException("SHARE_TARGET_UNAVAILABLE");
        synchronized (workspace.conversation()) {
            var previews = load();
            Preview preview = previews.stream().filter(p -> p.id.equals(id)).findFirst()
                    .orElseThrow(() -> new SecurityException("SHARE_NOT_FOUND"));
            if (!preview.owner.equals(actor.owner()) || !preview.contentDigest.equals(digest) || preview.target != target
                    || !channels.get(target).binding().equals(preview.channelBinding()))
                throw new SecurityException("SHARE_PREVIEW_CHANGED");
            if (preview.state != State.PREVIEW) return new Confirmation(preview, false);
            var event = message(preview.messageId);
            if (clock.getAsLong() >= preview.expiresAt || event.seq() != preview.messageRevision
                    || !AiJson.digest(event.message().text()).equals(preview.sourceDigest))
                throw new SecurityException("SHARE_EXPIRED_OR_EDITED");
            Preview dispatching = state(preview, State.DISPATCHING, request);
            previews.set(previews.indexOf(preview), dispatching);
            AiJson.write(workspace.directory().resolve("shares.json"), new Data(1, previews));
            return new Confirmation(dispatching, true);
        }
    }
    public void recoverPending() throws IOException {
        synchronized (workspace.conversation()) {
            var previews = load();
            boolean changed = false;
            for (int i = 0; i < previews.size(); i++) {
                Preview preview = previews.get(i);
                if (preview.state == State.DISPATCHING) {
                    previews.set(i, state(preview, State.DELIVERY_UNCERTAIN, preview.requestId));
                    changed = true;
                }
            }
            if (changed) AiJson.write(workspace.directory().resolve("shares.json"), new Data(1, previews));
        }
    }
    public Preview finish(Actor actor, UUID id, State result) throws IOException {
        actor.requireAccess(workspace.owner());
        if (!Set.of(State.PUBLISHED, State.RELAY_ACCEPTED, State.FAILED, State.DELIVERY_UNCERTAIN).contains(result))
            throw new IllegalArgumentException("SHARE_RESULT");
        synchronized (workspace.conversation()) {
            var previews = load();
            Preview previous = previews.stream().filter(p -> p.id.equals(id)).findFirst().orElseThrow();
            if (previous.state != State.DISPATCHING) return previous;
            Preview done = state(previous, result, previous.requestId);
            previews.set(previews.indexOf(previous), done);
            AiJson.write(workspace.directory().resolve("shares.json"), new Data(1, previews));
            JsonObject audit = new JsonObject();
            audit.addProperty("shareId", id.toString());
            audit.addProperty("contentDigest", done.contentDigest);
            audit.addProperty("target", done.target.name());
            audit.addProperty("result", result.name());
            workspace.conversation().append(List.of(new ConversationStore.Draft(null, "share", null, audit, null)));
            return done;
        }
    }
    private static Preview state(Preview p, State state, UUID request) {
        return new Preview(p.id, p.owner, p.conversationId, p.messageId, p.messageRevision, p.sourceDigest,
                p.contentDigest, p.target, p.audience, p.publicText, p.truncated, p.expiresAt, state, request,
                p.channelBinding, p.endpointHash);
    }
    private String publicText(String raw) {
        String redacted = secrets.redact(raw);
        StringBuilder safe = new StringBuilder();
        for (String line : redacted.split("\\R")) {
            if (line.matches("(?i).*(api.?key|authorization|password|rcon|secret|approval.?token|tool.?arguments|密钥|口令|审批令牌).*"))
                safe.append("[敏感字段已移除]\n");
            else safe.append(line.replaceAll("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b", "[内部引用]"))
                    .append('\n');
        }
        return safe.toString().trim();
    }
}
