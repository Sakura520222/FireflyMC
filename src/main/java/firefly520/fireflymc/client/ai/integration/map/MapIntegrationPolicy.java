package firefly520.fireflymc.client.ai.integration.map;

/** Exact profiles are checked before optional client classes are loaded. */
public final class MapIntegrationPolicy {
    private MapIntegrationPolicy() {}
    public enum Provider {
        XAERO_MINIMAP("xaerominimap", "26.4.2", "Xaero 小地图"),
        XAERO_WORLDMAP("xaeroworldmap", "1.44.2", "Xaero 世界地图"),
        MAP_ATLASES("map_atlases", "1.21-6.6.0", "地图册");
        private final String modId, version, label;
        Provider(String modId, String version, String label) { this.modId = modId; this.version = version; this.label = label; }
        public String modId() { return modId; }
        public String version() { return version; }
        public String label() { return label; }
    }
    public enum State { NOT_INSTALLED, VERSION_UNSUPPORTED, INTERFACE_MISMATCH, RUNTIME_NOT_READY, RUNTIME_READY }
    public record Status(Provider provider, State state, String installedVersion, String reason,
                         boolean singlePointSelection, boolean temporaryMarkers, boolean nativeView) {
        public boolean ready() { return state == State.RUNTIME_READY; }
    }
    public static boolean supported(Provider provider, String version) { return provider.version().equals(version); }
    public static Status unavailable(Provider provider, State state, String version, String reason) {
        return new Status(provider, state, version, reason, false, false, false);
    }
}
