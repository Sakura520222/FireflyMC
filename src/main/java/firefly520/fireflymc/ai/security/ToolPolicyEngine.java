package firefly520.fireflymc.ai.security;

import com.google.gson.*;
import firefly520.fireflymc.ai.model.ModelMessage.ToolCall;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.tools.AgentTool;
import java.util.*;

/** Re-evaluated at dispatch AND at the server-thread execution boundary. */
public final class ToolPolicyEngine {
    public enum Decision { ALLOW, DENY, APPROVAL, PAUSE }
    public record Verdict(Decision decision, String reason) {}

    public Verdict check(Actor actor, String owner, AgentTool.Descriptor tool, boolean enabled,
                         ToolCall call, String approvalDigest) {
        return check(actor, owner, tool, enabled, call, approvalDigest, false);
    }

    public Verdict check(Actor actor, String owner, AgentTool.Descriptor tool, boolean enabled,
                         ToolCall call, String approvalDigest, boolean yoloMode) {
        try { actor.requireAccess(owner); }
        catch (SecurityException e) { return new Verdict(Decision.DENY, "AUTH_REQUIRED"); }
        if (!enabled) return new Verdict(Decision.DENY, "TOOL_DISABLED");
        if (tool.unavailableReason() != null && !tool.unavailableReason().isBlank())
            return new Verdict(Decision.DENY, tool.unavailableReason());
        if (actor.permissionLevel() < tool.permissionLevel())
            return new Verdict(Decision.DENY, "PERMISSION_DENIED");
        if (!actor.online()
                && (tool.needsOnlinePlayer() || tool.risk().requiresApproval()))
            return new Verdict(Decision.PAUSE, "PLAYER_OFFLINE");
        if (!yoloMode && tool.risk().requiresApproval() && !digest(owner, call).equals(approvalDigest))
            return new Verdict(Decision.APPROVAL, "EXPLICIT_APPROVAL_REQUIRED");
        return new Verdict(Decision.ALLOW, "");
    }

    public static String digest(String owner, ToolCall call) {
        return AiJson.digest(owner + "\n" + call.id() + "\n" + call.name() + "\n" + call.arguments());
    }

    /** The supported schema subset is deliberately small and rejects extra keys and wrong JSON types. */
    public static JsonObject arguments(ToolCall call, JsonObject schema) {
        JsonObject args = AiJson.object(call.arguments(), 65536, 24);
        JsonObject properties = schema.has("properties") ? schema.getAsJsonObject("properties") : new JsonObject();
        if (schema.has("required")) {
            for (JsonElement key : schema.getAsJsonArray("required"))
                if (!args.has(key.getAsString()) || args.get(key.getAsString()).isJsonNull())
                    throw new IllegalArgumentException("missing argument");
        }
        for (var entry : args.entrySet()) {
            if (!properties.has(entry.getKey())) throw new IllegalArgumentException("unknown argument");
            JsonObject rule = properties.getAsJsonObject(entry.getKey());
            JsonElement value = entry.getValue();
            String type = rule.has("type") ? rule.get("type").getAsString() : "";
            boolean valid = switch (type) {
                case "string" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                        && value.getAsString().length() <= 8192;
                case "number", "integer" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                        && Double.isFinite(value.getAsDouble())
                        && (!type.equals("integer") || value.getAsDouble() == Math.rint(value.getAsDouble()));
                case "boolean" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
                case "array" -> value.isJsonArray() && value.getAsJsonArray().size() <= 64;
                case "object" -> value.isJsonObject();
                default -> false;
            };
            if (!valid) throw new IllegalArgumentException("argument type");
            if (rule.has("enum") && !rule.getAsJsonArray("enum").contains(value))
                throw new IllegalArgumentException("argument enum");
            if (rule.has("minimum") && value.getAsDouble() < rule.get("minimum").getAsDouble()
                    || rule.has("maximum") && value.getAsDouble() > rule.get("maximum").getAsDouble())
                throw new IllegalArgumentException("argument range");
        }
        return args;
    }
}
