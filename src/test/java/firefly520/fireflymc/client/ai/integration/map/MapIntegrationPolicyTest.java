package firefly520.fireflymc.client.ai.integration.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapIntegrationPolicyTest {
    @Test void supportedProfilesRequireTheExactInstalledVersionIncludingTheAtlasMinecraftPrefix() {
        for (var provider : MapIntegrationPolicy.Provider.values()) {
            assertTrue(MapIntegrationPolicy.supported(provider, provider.version()));
            for (String version : new String[]{"", provider.version() + ".1", "[" + provider.version() + "]", "unknown"})
                assertFalse(MapIntegrationPolicy.supported(provider, version));
        }
        assertFalse(MapIntegrationPolicy.supported(MapIntegrationPolicy.Provider.MAP_ATLASES, "6.6.0"));
    }
    @Test void everyUnavailableStateDisablesNativeCapabilitiesWithoutClaimingRuntimeSuccess() {
        for (var state : MapIntegrationPolicy.State.values()) if (state != MapIntegrationPolicy.State.RUNTIME_READY) {
            var status = MapIntegrationPolicy.unavailable(MapIntegrationPolicy.Provider.XAERO_MINIMAP, state, "26.4.2", "test unavailable state");
            assertFalse(status.ready()); assertFalse(status.nativeView()); assertFalse(status.temporaryMarkers()); assertFalse(status.singlePointSelection());
        }
    }
}
