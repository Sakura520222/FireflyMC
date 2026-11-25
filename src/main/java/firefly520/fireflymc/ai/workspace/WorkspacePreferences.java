package firefly520.fireflymc.ai.workspace;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import java.io.IOException;
import java.nio.file.Files;

/** New player-owned settings; event capture is opt-in and never imports prior AI profiles. */
public record WorkspacePreferences(int version, String name, String personality,
                                   boolean recordDeaths, boolean recordAdvancements, boolean yoloMode) {
    public WorkspacePreferences(int version, String name, String personality, boolean recordDeaths, boolean recordAdvancements) {
        this(version, name, personality, recordDeaths, recordAdvancements, false);
    }
    public WorkspacePreferences {
        if (version != 1 || name == null || name.isBlank() || name.length() > 32
                || personality == null || personality.length() > 2048
                || name.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("INVALID_WORKSPACE_PREFERENCES");
    }
    public static WorkspacePreferences defaults() {
        return new WorkspacePreferences(1, "小萤", "", false, false);
    }
    /** A header toggle must not reset a concurrently edited name, style, or event preference. */
    public WorkspacePreferences update(JsonObject changes) {
        if (!java.util.Set.of("version", "name", "personality", "recordDeaths", "recordAdvancements", "yoloMode").containsAll(changes.keySet()))
            throw new IllegalArgumentException("INVALID_WORKSPACE_PREFERENCES");
        for (String key : java.util.List.of("recordDeaths", "recordAdvancements", "yoloMode"))
            if (changes.has(key) && (!changes.get(key).isJsonPrimitive() || !changes.getAsJsonPrimitive(key).isBoolean()))
                throw new IllegalArgumentException("INVALID_WORKSPACE_PREFERENCES");
        return new WorkspacePreferences(changes.has("version") ? changes.get("version").getAsInt() : version,
                changes.has("name") ? changes.get("name").getAsString() : name,
                changes.has("personality") ? changes.get("personality").getAsString() : personality,
                changes.has("recordDeaths") ? changes.get("recordDeaths").getAsBoolean() : recordDeaths,
                changes.has("recordAdvancements") ? changes.get("recordAdvancements").getAsBoolean() : recordAdvancements,
                changes.has("yoloMode") ? changes.get("yoloMode").getAsBoolean() : yoloMode);
    }
    public static WorkspacePreferences load(WorkspaceManager.Workspace workspace) throws IOException {
        var path = workspace.directory().resolve("preferences.json");
        if (!Files.exists(path)) return defaults();
        try {
            return AiJson.GSON.fromJson(Files.readString(path), WorkspacePreferences.class);
        } catch (RuntimeException e) { throw new IOException("INVALID_WORKSPACE_PREFERENCES"); }
    }
    public void save(WorkspaceManager.Workspace workspace, Actor actor) throws IOException {
        actor.requireAccess(workspace.owner());
        AiJson.write(workspace.directory().resolve("preferences.json"), this);
    }
}
