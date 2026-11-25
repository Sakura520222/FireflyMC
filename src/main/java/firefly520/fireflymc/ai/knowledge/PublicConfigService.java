package firefly520.fireflymc.ai.knowledge;

import com.google.gson.*;
import com.electronwill.nightconfig.toml.TomlParser;
import firefly520.fireflymc.ServerConfig;
import firefly520.fireflymc.ai.security.SecretFilter;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.tools.*;
import net.neoforged.neoforge.common.ModConfigSpec;
import java.nio.file.*;
import java.util.*;

/** Explicit field allowlist only; no directory scan, model-controlled paths or client config access. */
public final class PublicConfigService {
    private final JsonArray fields;
    private final Path globalRoot, worldRoot;
    private final SecretFilter secrets;
    public PublicConfigService(JsonArray fields, Path globalRoot, Path worldRoot, SecretFilter secrets) {
        this.fields = fields.deepCopy(); this.globalRoot = globalRoot; this.worldRoot = worldRoot; this.secrets = secrets;
    }
    public void register(ToolRegistry registry) {
        JsonObject schema = AiJson.object("{\"type\":\"object\",\"properties\":{\"modId\":{\"type\":\"string\"}},\"additionalProperties\":false}", 1024, 8);
        registry.register(new DefinedTool(new AgentTool.Descriptor("mod_config_summary", "读取明确列入公开白名单的配置字段；不会读取客户端配置或敏感原值。",
                schema, AgentTool.Risk.READ_ONLY, "config.public.read", 0, AgentTool.ExecutionThread.IO, 5000, false, ""),
                (context, args) -> {
                    JsonObject data = new JsonObject();
                    data.add("fields", summarize(args.has("modId") ? args.get("modId").getAsString() : ""));
                    return new AgentTool.Result(true, "", "仅返回公开白名单字段；文件回退值不会冒充已生效的运行值。",
                            data, "configured-public-allowlist", "", false);
                }));
    }
    public JsonArray summarize(String filter) {
        JsonArray result = new JsonArray();
        for (JsonElement element : fields) {
            JsonObject field = element.getAsJsonObject();
            String source = text(field, "source", "");
            String mod = text(field, "modId", "fireflymc");
            if (!filter.isBlank() && !filter.equals(mod)) continue;
            String key = text(field, "key", "");
            JsonObject row = new JsonObject();
            row.addProperty("modId", mod);
            row.addProperty("key", key);
            if (!safeKey(key)) { row.addProperty("status", "SENSITIVE_OR_INVALID_FIELD_REJECTED"); result.add(row); continue; }
            try {
                if ("fireflymc_api".equals(source) && "fireflymc".equals(mod)) {
                    Object raw = ServerConfig.SERVER_SPEC.getValues().get(key);
                    if (!(raw instanceof ModConfigSpec.ConfigValue<?> value)) throw new IllegalArgumentException();
                    row.add("value", safeValue(value.get()));
                    row.add("defaultValue", safeValue(value.getDefault()));
                    row.addProperty("differsFromDefault", !Objects.equals(value.get(), value.getDefault()));
                    String comment = value.getSpec().getComment();
                    if (comment != null) row.addProperty("comment", secrets.redact(comment.substring(0, Math.min(400, comment.length()))));
                    row.addProperty("scope", "SERVER");
                    row.addProperty("status", "REGISTERED_CONFIG_RUNTIME_VALUE");
                } else if (Set.of("global_file", "world_file").contains(source)) {
                    String scope = text(field, "scope", "");
                    if (!Set.of("COMMON", "SERVER", "STARTUP").contains(scope)) throw new SecurityException();
                    Path root = "global_file".equals(source) ? globalRoot : worldRoot;
                    Path file = allowedFile(root, text(field, "file", ""));
                    try (var reader = Files.newBufferedReader(file)) {
                        var config = new TomlParser().parse(reader);
                        row.add("value", safeValue(config.get(key)));
                    }
                    row.addProperty("scope", scope);
                    row.addProperty("status", "WHITELISTED_FILE_VALUE_NOT_RUNTIME_CONFIRMED");
                    row.addProperty("defaultValue", "unknown");
                } else throw new IllegalArgumentException();
            } catch (Exception e) { row.addProperty("status", "FIELD_UNAVAILABLE"); }
            result.add(row);
        }
        return result;
    }
    static boolean safeKey(String key) {
        return key.matches("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*")
                && !key.toLowerCase(Locale.ROOT).matches(".*(apikey|api_key|token|secret|password|credential|rcon|webhook|proxy|database|private|auth).*")
                && !key.toLowerCase(Locale.ROOT).startsWith("ai.");
    }
    static Path allowedFile(Path root, String name) throws java.io.IOException {
        if (!name.matches("[A-Za-z0-9_.-]{1,120}\\.toml") || name.contains("..")
                || name.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])\\..*")
                || name.equalsIgnoreCase("fireflymc-server.toml"))
            throw new SecurityException("PUBLIC_CONFIG_PATH_REJECTED");
        Path path = root.resolve(name).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path) || Files.size(path) > 256 * 1024
                || !path.toRealPath().startsWith(root.toRealPath())) throw new SecurityException("PUBLIC_CONFIG_PATH_REJECTED");
        return path;
    }
    private JsonElement safeValue(Object value) {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof Boolean || value instanceof Number) return AiJson.GSON.toJsonTree(value);
        if (value instanceof String || value instanceof Enum<?>) {
            String text = secrets.redact(value.toString());
            return new JsonPrimitive(text.substring(0, Math.min(200, text.length())));
        }
        return new JsonPrimitive("[structured value omitted]");
    }
    private static String text(JsonObject object, String key, String fallback) {
        return object.has(key) ? object.get(key).getAsString() : fallback;
    }
}
