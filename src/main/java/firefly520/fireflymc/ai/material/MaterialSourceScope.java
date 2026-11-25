package firefly520.fireflymc.ai.material;

import java.util.UUID;

/** Conservative recovery scope, independent of a block's newly assigned attachment UUID. */
public final class MaterialSourceScope {
    private MaterialSourceScope() {}
    public static String key(String backend) {
        String stable = backend;
        if (backend.startsWith("block:") || backend.startsWith("block/")) {
            int suffix = backend.lastIndexOf(':');
            if (suffix >= 0) try { UUID.fromString(backend.substring(suffix + 1)); stable = backend.substring(0, suffix); }
            catch (IllegalArgumentException ignored) { }
        }
        if (stable.startsWith("block:")) {
            int position = stable.lastIndexOf(':');
            try {
                long packed = Long.parseLong(stable.substring(position + 1));
                // Minecraft 1.21.1 BlockPos uses signed 26/12/26 coordinate packing.
                int x = (int) (packed >> 38), y = (int) (packed << 52 >> 52), z = (int) (packed << 26 >> 38);
                return "@world:" + stable.substring(6, position) + ":" + x + "," + y + "," + z;
            } catch (NumberFormatException | IndexOutOfBoundsException ignored) { }
        } else if (stable.startsWith("block/")) {
            int position = stable.lastIndexOf('/');
            if (position > 6 && stable.substring(position + 1).matches("-?[0-9]+,-?[0-9]+,-?[0-9]+"))
                return "@world:" + stable.substring(6, position) + ":" + stable.substring(position + 1);
        }
        return "@backend:" + stable;
    }
}
