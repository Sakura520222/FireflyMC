package firefly520.fireflymc.client.serverlist;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 后台下发的官方服务器：一条名称 + 地址。
 * <p>
 * 名称与地址的校验规则和云端官网 {@code firefly_website/routes/admin.py} 的
 * {@code _clean_server} / {@code _SERVER_ADDRESS_RE} 保持一致，改动需两端同步。
 *
 * @param id       后台记录 id（仅作标识，接口里可缺省）
 * @param name     游戏内列表显示的名称
 * @param address  服务器地址：域名 / IPv4 / 方括号 IPv6，可带端口
 */
public record OfficialServer(long id, String name, String address) {

    static final int MAX_NAME_LENGTH = 32;
    static final int MAX_ADDRESS_LENGTH = 255;
    static final int MAX_SERVERS = 64;
    static final int DEFAULT_PORT = 25565;

    private static final String HOST_LABEL = "[a-z0-9_](?:[a-z0-9_-]*[a-z0-9_])?";
    private static final Pattern ADDRESS_PATTERN = Pattern.compile(
            "^(?:" + HOST_LABEL + "(?:\\." + HOST_LABEL + ")*(?::([0-9]{1,5}))?"
                    + "|\\[[0-9a-f:.%]+\\](?::([0-9]{1,5}))?)$",
            Pattern.CASE_INSENSITIVE);

    public OfficialServer {
        if (!isValidName(name)) {
            throw new IllegalArgumentException("invalid official server name");
        }
        if (!isValidAddress(address)) {
            throw new IllegalArgumentException("invalid official server address");
        }
    }

    static boolean isValidName(String name) {
        if (name == null || name.isEmpty() || name.codePointCount(0, name.length()) > MAX_NAME_LENGTH) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return false;
            }
        }
        return true;
    }

    static boolean isValidAddress(String address) {
        if (address == null || address.isEmpty() || address.length() > MAX_ADDRESS_LENGTH) {
            return false;
        }
        if (!ADDRESS_PATTERN.matcher(address).matches()) {
            return false;
        }
        // port() 对 0 与超过 65535 的端口返回 -1
        return port(address) > 0;
    }

    /** 去重 / 接管判定用的归一化地址，见 {@link #normalizeAddress(String)}。 */
    public String normalizedAddress() {
        return normalizeAddress(address);
    }

    /**
     * 去重 / 接管判定用的归一化地址：域名不区分大小写，省略的端口按 25565 补齐。
     * 例如 {@code Play.Example.com} 与 {@code play.example.com:25565} 归一化后相同。
     *
     * @param address 已通过 {@link #isValidAddress} 的地址
     */
    static String normalizeAddress(String address) {
        String host;
        if (address.startsWith("[")) {
            host = address.substring(0, address.indexOf(']') + 1);
        } else {
            int colon = address.lastIndexOf(':');
            host = colon >= 0 ? address.substring(0, colon) : address;
        }
        return host.toLowerCase(Locale.ROOT) + ":" + port(address);
    }

    /** 解析端口（省略时为 25565）；端口为 0 或超过 65535 时返回 -1。 */
    private static int port(String address) {
        String digits;
        int bracket = address.indexOf(']');
        if (address.startsWith("[") && bracket >= 0) {
            // "[::1]:25565" → 跳过 "]:"
            digits = bracket + 1 < address.length() ? address.substring(bracket + 2) : null;
        } else {
            int colon = address.lastIndexOf(':');
            digits = colon >= 0 ? address.substring(colon + 1) : null;
        }
        if (digits == null) {
            return DEFAULT_PORT;
        }
        int value = Integer.parseInt(digits);
        return value >= 1 && value <= 65535 ? value : -1;
    }

    /**
     * 解析后台返回的 JSON：{@code {"servers":[{"id":1,"name":"...","address":"host:port"}, ...]}}。
     * <p>
     * 结构不对或任一条目不合法时整体拒绝（抛出 {@link IllegalArgumentException}），由调用方沿用旧列表；
     * 地址重复的条目只保留第一条。
     */
    public static List<OfficialServer> parse(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("empty payload");
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid json", e);
        }
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("root is not an object");
        }
        JsonElement servers = root.getAsJsonObject().get("servers");
        if (servers == null || !servers.isJsonArray()) {
            throw new IllegalArgumentException("servers is missing or not an array");
        }
        JsonArray array = servers.getAsJsonArray();
        if (array.size() > MAX_SERVERS) {
            throw new IllegalArgumentException("too many servers");
        }
        List<OfficialServer> result = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("server entry is not an object");
            }
            JsonObject entry = element.getAsJsonObject();
            OfficialServer server = new OfficialServer(
                    readId(entry), readString(entry, "name"), readString(entry, "address"));
            if (keys.add(server.normalizedAddress())) {
                result.add(server);
            }
        }
        return List.copyOf(result);
    }

    private static long readId(JsonObject entry) {
        JsonElement id = entry.get("id");
        if (id == null || id.isJsonNull()) {
            return 0L;
        }
        if (!id.isJsonPrimitive() || !id.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("id is not a number");
        }
        return id.getAsLong();
    }

    private static String readString(JsonObject entry, String field) {
        JsonElement value = entry.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + " is missing or not a string");
        }
        return value.getAsString().trim();
    }
}
