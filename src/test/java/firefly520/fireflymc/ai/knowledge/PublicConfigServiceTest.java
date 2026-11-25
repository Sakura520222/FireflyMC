package firefly520.fireflymc.ai.knowledge;

import com.google.gson.*;
import firefly520.fireflymc.ai.security.SecretFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PublicConfigServiceTest {
    @TempDir Path directory;
    @Test void onlyAllowlistedPrimitiveFieldIsReturnedAndFileValueIsNotClaimedAsRuntimeState() throws Exception {
        Files.writeString(directory.resolve("sample-common.toml"), "[public]\nrate=4\n[private]\npassword='never-export'");
        JsonArray fields = JsonParser.parseString("""
                [{"source":"global_file","modId":"sample","scope":"COMMON","file":"sample-common.toml","key":"public.rate"},
                {"source":"global_file","modId":"sample","scope":"COMMON","file":"sample-common.toml","key":"private.password"}]
                """).getAsJsonArray();
        var service = new PublicConfigService(fields, directory, directory, new SecretFilter(List.of()));
        var result = service.summarize("sample");
        assertEquals(4, result.get(0).getAsJsonObject().get("value").getAsInt());
        assertEquals("WHITELISTED_FILE_VALUE_NOT_RUNTIME_CONFIRMED", result.get(0).getAsJsonObject().get("status").getAsString());
        assertFalse(result.toString().contains("never-export"));
        assertFalse(result.get(1).getAsJsonObject().has("value"));
        assertThrows(SecurityException.class, () -> PublicConfigService.allowedFile(directory, "../outside.toml"));
        assertThrows(SecurityException.class, () -> PublicConfigService.allowedFile(directory, "fireflymc-server.toml"));
        assertFalse(PublicConfigService.safeKey("network.api_key"));
        assertFalse(PublicConfigService.safeKey("rcon.password"));
    }
}
