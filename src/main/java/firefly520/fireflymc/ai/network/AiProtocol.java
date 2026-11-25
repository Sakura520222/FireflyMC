package firefly520.fireflymc.ai.network;

import com.google.gson.*;
import java.util.*;

/** Versioned bounded UI protocol; no owner/permission fields are accepted from the client. */
public final class AiProtocol {
    public static final int VERSION = 1;
    public static final int REQUEST_CHARS = 8192;
    public static final int RESPONSE_CHARS = 24576;
    public static final UUID NO_SESSION = new UUID(0, 0);
    public enum Action {
        HELLO, OPEN, SYNC, HISTORY, SUBMIT, CONTROL, SETTINGS, MEMORY_VIEW, MEMORY_REMEMBER,
        MEMORY_FORGET, MEMORY_CLEAR, CONTEXT, ARTIFACT, EXPORT, EVENTS, EVENTS_CLEAR,
        SHARE_PREVIEW, SHARE_CONFIRM, QUESTION_ANSWER, PRIVACY_PREVIEW, PRIVACY_CONFIRM,
        INTEGRATIONS, RECIPE_SHOW, BLUEPRINT_IMPORT, BLUEPRINT_TRANSFORM, BUILD_SELECT, BUILD_CONTROL,
        APPROVAL_PREVIEW, SHARE_DELIVERY_RESULT, MESSAGE, SETTINGS_VIEW, STREAM, QUESTION_VIEW, EXPORT_CHUNK, EXPORT_LIST, SEARCH,
        MEMORY_UPDATE, MATERIALS_VIEW, MATERIALS_SCAN, SOURCE_PREVIEW, SOURCE_CONFIRM, SOURCE_REVOKE, BLUEPRINT_VIEW,
        EQUIPMENT_VIEW, CARRIER_SELECT, MATERIAL_PREFERENCES,
        FLUIDS_VIEW, FLUIDS_SCAN, FLUID_SOURCE_PREVIEW, FLUID_SOURCE_CONFIRM, FLUID_SOURCE_REVOKE,
        RECOVERY_VIEW, RECOVERY_PREVIEW, RECOVERY_CONFIRM, CLIENT_POINT, MAP_ROUTE, TASK_MESSAGES, CONTEXT_COMPACT
    }
    private AiProtocol() {}
    public static boolean requiresAdvertisement(Action action) {
        return Set.of(Action.MEMORY_UPDATE, Action.MATERIALS_VIEW, Action.MATERIALS_SCAN,
                Action.SOURCE_PREVIEW, Action.SOURCE_CONFIRM, Action.SOURCE_REVOKE, Action.RECIPE_SHOW,
                Action.FLUIDS_VIEW, Action.FLUIDS_SCAN, Action.FLUID_SOURCE_PREVIEW, Action.FLUID_SOURCE_CONFIRM, Action.FLUID_SOURCE_REVOKE,
                Action.RECOVERY_VIEW, Action.RECOVERY_PREVIEW, Action.RECOVERY_CONFIRM, Action.CLIENT_POINT, Action.MAP_ROUTE, Action.TASK_MESSAGES, Action.CONTEXT_COMPACT,
                Action.BLUEPRINT_VIEW, Action.BLUEPRINT_IMPORT, Action.BLUEPRINT_TRANSFORM, Action.EQUIPMENT_VIEW, Action.CARRIER_SELECT, Action.MATERIAL_PREFERENCES, Action.INTEGRATIONS, Action.BUILD_SELECT).contains(action);
    }
    public static List<String> implementedActions() {
        return Arrays.stream(Action.values()).filter(action -> action != Action.BUILD_CONTROL).map(Enum::name).toList();
    }

    public static JsonObject object(String text, int maximum) {
        if (text == null || text.length() > maximum) throw new IllegalArgumentException("AI_MESSAGE_TOO_LARGE");
        int depth = 0;
        boolean quoted = false, escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '{' || c == '[') {
                if (++depth > 24) throw new IllegalArgumentException("AI_JSON_TOO_DEEP");
            } else if (c == '}' || c == ']') depth--;
            if (depth < 0) throw new IllegalArgumentException("AI_INVALID_JSON");
        }
        if (quoted || depth != 0) throw new IllegalArgumentException("AI_INVALID_JSON");
        JsonObject object = JsonParser.parseString(text).getAsJsonObject();
        if (object.has("owner") || object.has("playerUUID") || object.has("workspaceId")
                || object.has("permissionLevel") || object.has("toolResult"))
            throw new IllegalArgumentException("AI_CLIENT_AUTHORITY_FIELD");
        return object;
    }
}
