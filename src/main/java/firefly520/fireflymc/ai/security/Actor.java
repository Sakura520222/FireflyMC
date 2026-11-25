package firefly520.fireflymc.ai.security;

import java.util.UUID;

/** Created exclusively by the trusted server bridge, never from model/client identity fields. */
public record Actor(String owner, boolean authenticated, boolean online, int permissionLevel) {
    public Actor {
        UUID.fromString(owner);
        if (permissionLevel < 0 || permissionLevel > 4) throw new IllegalArgumentException("permission");
    }

    public static Actor player(UUID id, boolean authenticated, boolean online, int level) {
        return new Actor(id.toString(), authenticated, online, level);
    }

    public void requireAccess(String owner) {
        if (!authenticated || !this.owner.equals(owner)) throw new SecurityException("WORKSPACE_ACCESS_DENIED");
    }
}
