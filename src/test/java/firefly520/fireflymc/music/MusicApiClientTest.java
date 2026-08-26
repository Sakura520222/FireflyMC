package firefly520.fireflymc.music;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class MusicApiClientTest {

    @Test
    void readPrefixStopsAtLimit() throws IOException {
        // 服务器忽略 Range 返回全文件：读满 64 KiB 必须立即返回前缀，而非把超限当异常
        ByteArrayInputStream in = new ByteArrayInputStream(new byte[100_000]);
        byte[] prefix = MusicApiClient.readPrefix(in, 65_536);
        assertEquals(65_536, prefix.length);
    }

    @Test
    void readPrefixReturnsShortStreamEntirely() throws IOException {
        // 正常 206 分片（短于上限）：全部读出
        ByteArrayInputStream in = new ByteArrayInputStream(new byte[1_000]);
        byte[] prefix = MusicApiClient.readPrefix(in, 65_536);
        assertEquals(1_000, prefix.length);
    }
}
