package firefly520.fireflymc.client.serverlist;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfficialServerTest {

    private static OfficialServer server(String address) {
        return new OfficialServer(1, "测试服", address);
    }

    @Test
    void acceptsCommonAddressForms() {
        for (String address : List.of(
                "play.example.com:25565",
                "play.example.com", // 省略端口
                "localhost",
                "192.168.1.10:25566",
                "[::1]:25565",
                "[fe80::1]",
                "my_server.example.com", // 下划线（SRV 记录等场景）
                "PLAY.EXAMPLE.COM:1")) {
            assertTrue(OfficialServer.isValidAddress(address), address);
        }
    }

    @Test
    void rejectsMalformedAddresses() {
        for (String address : List.of(
                "", "   ",
                "play.example.com:0", // 端口 0
                "play.example.com:65536",
                "play.example.com:99999",
                "play.example.com:", // 冒号后没有端口
                "http://play.example.com", // 带协议
                "play..example.com",
                ".example.com",
                "play.example.com.",
                "-play.example.com",
                "play.example.com-",
                ":25565",
                "play example.com",
                "play.example.com/path",
                "[::1", // IPv6 字面量必须带方括号
                "::1",
                "[::1]:abc",
                "a".repeat(256))) {
            assertFalse(OfficialServer.isValidAddress(address), String.valueOf(address));
        }
        assertFalse(OfficialServer.isValidAddress(null));
    }

    @Test
    void validatesName() {
        assertTrue(OfficialServer.isValidName("生存服"));
        assertTrue(OfficialServer.isValidName("a".repeat(32)));
        assertTrue(OfficialServer.isValidName("§a绿色服务器")); // 颜色代码允许
        assertFalse(OfficialServer.isValidName(null));
        assertFalse(OfficialServer.isValidName(""));
        assertFalse(OfficialServer.isValidName("a".repeat(33)));
        assertFalse(OfficialServer.isValidName("a\nb"));
        assertFalse(OfficialServer.isValidName("a\u007fb"));
    }

    @Test
    void constructorRejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> new OfficialServer(1, "", "a.example.com"));
        assertThrows(IllegalArgumentException.class, () -> new OfficialServer(1, "a", "http://a.example.com"));
    }

    @Test
    void normalizesAddressForDeduplication() {
        // 域名不区分大小写，省略的端口补 25565
        assertEquals("play.example.com:25565", server("Play.Example.com").normalizedAddress());
        assertEquals("play.example.com:25565", server("play.example.com:25565").normalizedAddress());
        assertEquals("play.example.com:1", server("PLAY.example.com:1").normalizedAddress());
        assertEquals("[::1]:25565", server("[::1]").normalizedAddress());
        assertEquals("[fe80::1]:1", server("[FE80::1]:1").normalizedAddress());
    }

    @Test
    void parsesBackendPayload() {
        List<OfficialServer> servers = OfficialServer.parse("""
                {"servers":[
                  {"id":1,"name":"生存服","address":"play.example.com:25565"},
                  {"id":2,"name":" 空岛 ","address":"sky.example.com"},
                  {"name":"无 id","address":"[::1]:25565"}
                ]}""");
        assertEquals(3, servers.size());
        assertEquals(1, servers.get(0).id());
        assertEquals("生存服", servers.get(0).name());
        assertEquals("play.example.com:25565", servers.get(0).address());
        // 名称首尾空白被去掉
        assertEquals("空岛", servers.get(1).name());
        // id 可缺省
        assertEquals(0, servers.get(2).id());
        // 返回的是不可变列表
        assertThrows(UnsupportedOperationException.class, () -> servers.add(server("x.example.com")));
    }

    @Test
    void parseKeepsFirstOfDuplicateAddresses() {
        List<OfficialServer> servers = OfficialServer.parse("""
                {"servers":[
                  {"name":"一区","address":"Play.Example.com"},
                  {"name":"重复","address":"play.example.com:25565"},
                  {"name":"二区","address":"play2.example.com"}
                ]}""");
        assertEquals(List.of("一区", "二区"), servers.stream().map(OfficialServer::name).toList());
    }

    @Test
    void parseRejectsInvalidPayloads() {
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < OfficialServer.MAX_SERVERS + 1; i++) {
            tooMany.add("{\"name\":\"s\",\"address\":\"host" + i + ".example.com\"}");
        }
        for (String json : List.of(
                "",
                "   ",
                "not json",
                "[]",
                "{}", // 缺 servers
                "{\"servers\":{}}", // servers 不是数组
                "{\"servers\":[1]}", // 条目不是对象
                "{\"servers\":[{\"name\":\"a\"}]}", // 缺 address
                "{\"servers\":[{\"address\":\"a.example.com\"}]}", // 缺 name
                "{\"servers\":[{\"name\":\"a\",\"address\":123}]}",
                "{\"servers\":[{\"name\":\"a\",\"address\":\"http://bad\"}]}",
                "{\"servers\":[{\"name\":\"\",\"address\":\"a.example.com\"}]}",
                "{\"servers\":[{\"id\":\"1\",\"name\":\"a\",\"address\":\"a.example.com\"}]}",
                "{\"servers\":[]} trailing",
                "{\"servers\":[" + String.join(",", tooMany) + "]}")) {
            assertThrows(IllegalArgumentException.class, () -> OfficialServer.parse(json), json);
        }
        assertThrows(IllegalArgumentException.class, () -> OfficialServer.parse(null));
    }
}
