package firefly520.fireflymc.ai.config;

import com.google.gson.*;
import firefly520.fireflymc.ai.agent.AgentRuntime;
import firefly520.fireflymc.ai.context.ContextCompactor;
import firefly520.fireflymc.ai.model.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

/** Dedicated-server-only configuration. Never registered as a client-synchronized ModConfig. */
public final class AiConfiguration {
    public static final int SCHEMA_VERSION = 1;
    private final boolean enabled;
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final String protocol;
    private final int concurrency;
    private final int timeoutSeconds;
    private final AgentRuntime.Limits limits;
    private final ContextCompactor.Settings context;
    private final Set<String> disabledTools;
    private final JsonObject web;
    private final JsonArray publicConfigFields;
    private final boolean crossChatSharing;
    private final boolean virtualConstruction;
    private final String crossChatEndpointHash, crossChatAudience;

    private AiConfiguration(JsonObject json) {
        if (integer(json, "schemaVersion", 0) != SCHEMA_VERSION)
            throw new IllegalArgumentException("UNSUPPORTED_AI_SCHEMA");
        enabled = bool(json, "enabled", false);
        JsonObject provider = object(json, "model");
        endpoint = string(provider, "endpoint", "");
        apiKey = string(provider, "apiKey", "");
        model = string(provider, "name", "");
        protocol = string(provider, "protocol", "");
        if (enabled) {
            if (endpoint.isBlank() || apiKey.isBlank() || model.isBlank()
                    || !Set.of("chat_completions", "responses").contains(protocol))
                throw new IllegalArgumentException("NEW_MODEL_CONFIGURATION_REQUIRED");
            URI uri = URI.create(endpoint);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null)
                throw new IllegalArgumentException("MODEL_HTTPS_ENDPOINT_REQUIRED");
        }
        JsonObject runtime = object(json, "runtime");
        concurrency = bounded(runtime, "concurrency", 2, 1, 8);
        timeoutSeconds = bounded(provider, "requestTimeoutSeconds", 90, 1, 300);
        int output = bounded(provider, "outputTokens", 2048, 128, 16384);
        limits = new AgentRuntime.Limits(bounded(runtime, "maxSteps", 32, 1, 128),
                bounded(runtime, "maxToolCalls", 128, 1, 512),
                bounded(runtime, "tokenBudget", 250000, 1024, 2000000),
                bounded(runtime, "durationSeconds", 600, 5, 7200) * 1000L, output,
                bool(provider, "supportsTools", true), bool(provider, "parallelToolCalls", false),
                bool(provider, "streaming", true));
        context = new ContextCompactor.Settings(bounded(provider, "contextWindow", 32768, 4096, 1000000),
                output, bounded(provider, "toolReserveTokens", 4096, 0, 65536),
                bounded(provider, "safetyReserveTokens", 1024, 0, 65536),
                provider.has("compactionThreshold") ? provider.get("compactionThreshold").getAsDouble() : .75,
                bounded(provider, "keepRecentTurns", 2, 1, 10));
        Set<String> disabled = new HashSet<>();
        if (runtime.has("disabledTools")) {
            for (var value : runtime.getAsJsonArray("disabledTools")) disabled.add(value.getAsString());
        }
        disabledTools = Set.copyOf(disabled);
        virtualConstruction = bool(object(json, "construction"), "allowVirtualProxy", false);
        web = object(json, "web").deepCopy();
        publicConfigFields = json.has("publicConfigFields") ? json.getAsJsonArray("publicConfigFields").deepCopy()
                : JsonParser.parseString("[{\"source\":\"fireflymc_api\",\"key\":\"server.storyEnabled\"},"
                + "{\"source\":\"fireflymc_api\",\"key\":\"server.enableItemCleanup\"}]").getAsJsonArray();
        if (publicConfigFields.size() > 32) throw new IllegalArgumentException("PUBLIC_CONFIG_WHITELIST_LIMIT");
        JsonObject sharing = object(json, "sharing");
        crossChatSharing = bool(sharing, "crossChatEnabled", false);
        String relay = string(sharing, "crossChatEndpoint", "");
        crossChatAudience = string(sharing, "crossChatAudience", "");
        if (crossChatSharing) {
            URI uri = URI.create(relay);
            if (!("ws".equals(uri.getScheme()) || "wss".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null || crossChatAudience.isBlank() || crossChatAudience.length() > 512)
                throw new IllegalArgumentException("EXPLICIT_SHARE_CHANNEL_REQUIRED");
        }
        crossChatEndpointHash = firefly520.fireflymc.ai.storage.AiJson.digest(relay);
    }

    /** Missing new file is not an invitation to inspect any previous AI settings. */
    public static Optional<AiConfiguration> load(Path file) throws IOException {
        if (!Files.exists(file)) return Optional.empty();
        if (Files.isSymbolicLink(file) || Files.size(file) > 65536)
            throw new IOException("INVALID_AI_CONFIGURATION_FILE");
        try {
            return Optional.of(new AiConfiguration(JsonParser.parseString(Files.readString(file)).getAsJsonObject()));
        } catch (RuntimeException e) {
            // Parsing exceptions can contain source excerpts including credentials.
            throw new IOException("INVALID_NEW_AI_CONFIGURATION");
        }
    }

    public static AiConfiguration parse(JsonObject json) { return new AiConfiguration(json); }
    private static JsonObject object(JsonObject json, String key) {
        return json.has(key) ? json.getAsJsonObject(key) : new JsonObject();
    }
    private static String string(JsonObject json, String key, String fallback) {
        return json.has(key) ? json.get(key).getAsString() : fallback;
    }
    private static int integer(JsonObject json, String key, int fallback) {
        return json.has(key) ? json.get(key).getAsInt() : fallback;
    }
    private static boolean bool(JsonObject json, String key, boolean fallback) {
        return json.has(key) ? json.get(key).getAsBoolean() : fallback;
    }
    private static int bounded(JsonObject json, String key, int fallback, int min, int max) {
        int value = integer(json, key, fallback);
        if (value < min || value > max) throw new IllegalArgumentException("INVALID_AI_LIMIT");
        return value;
    }
    public boolean enabled() { return enabled; }
    public String endpoint() { return endpoint; }
    public String apiKey() { return apiKey; }
    public String model() { return model; }
    public String protocol() { return protocol; }
    public int concurrency() { return concurrency; }
    public int timeoutSeconds() { return timeoutSeconds; }
    public AgentRuntime.Limits limits() { return limits; }
    public ContextCompactor.Settings context() { return context; }
    public Set<String> disabledTools() { return disabledTools; }
    public JsonObject web() { return web.deepCopy(); }
    public JsonArray publicConfigFields() { return publicConfigFields.deepCopy(); }
    public boolean crossChatSharing() { return crossChatSharing; }
    /** Missing FTB rank node uses this explicit server grant; OP is never a fallback. */
    public boolean virtualConstruction() { return virtualConstruction; }
    public String crossChatEndpointHash() { return crossChatEndpointHash; }
    public String crossChatAudience() { return crossChatAudience; }
    public ModelProtocol adapter() {
        return "responses".equals(protocol) ? new ResponsesAdapter() : new ChatCompletionsAdapter();
    }
    @Override public String toString() { return "AiConfiguration[schema=1, credentials=REDACTED]"; }
}
