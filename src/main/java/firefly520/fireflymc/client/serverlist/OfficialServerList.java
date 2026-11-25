package firefly520.fireflymc.client.serverlist;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import firefly520.fireflymc.Config;
import firefly520.fireflymc.FireflyMCMod;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * 后台下发的官方服务器列表（客户端侧状态）。
 * <p>
 * 数据来源是官网的公开接口 {@code GET /api/servers}（地址见客户端配置 {@code official_server_list.url}）。
 * 打开多人游戏界面时后台拉取，列表有变化时在客户端主线程回调监听者；
 * 最近一次成功的列表连同各条目的图标 / 资源包选择缓存在
 * {@code <游戏目录>/fireflymc_official_servers.json}，拉取失败或离线时沿用，界面不会因此空白。
 * <p>
 * 线程约定：网络请求在虚拟线程中执行，结果一律经 {@code clientExecutor}（默认为游戏主线程）落地；
 * 对外的状态读写方法均为 {@code synchronized}。
 */
public final class OfficialServerList {

    interface Settings {
        boolean enabled();

        String url();
    }

    interface Transport {
        String fetch(String url) throws Exception;
    }

    /** 本地保存的条目附加状态：服务器图标（ping 得到的 favicon，Base64）与资源包选择，跨启动保留。 */
    public record SavedState(String iconBase64, String packStatus) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(OfficialServerList.class);
    private static final String CACHE_FILE_NAME = "fireflymc_official_servers.json";
    private static final int CACHE_VERSION = 1;
    private static final long MAX_CACHE_BYTES = 8L * 1024 * 1024;
    private static final long MAX_RESPONSE_BYTES = 256L * 1024;
    /** 两次拉取之间的最小间隔（每次打开多人界面都会触发一次拉取） */
    private static final long MIN_REFRESH_INTERVAL_MILLIS = 30_000L;
    /** 一次拉取允许占用的最长时间：超时即使上一次仍未返回，也允许发起新的拉取 */
    private static final long FETCH_WATCHDOG_MILLIS = 60_000L;
    private static final long NEVER = Long.MIN_VALUE;

    private final Settings settings;
    private final Transport transport;
    private final Executor fetchExecutor;
    private final Executor clientExecutor;
    private final Clock clock;
    private final Path cacheFile;

    private List<OfficialServer> servers = List.of();
    /** 归一化地址 → 图标 / 资源包选择 */
    private final Map<String, SavedState> states = new HashMap<>();
    private boolean loaded;
    private boolean dirty;
    private long lastAttemptAt = NEVER;
    private long fetchStartedAt = NEVER;
    private Runnable changeListener;

    /** 测试用构造器（包私有，全注入）。生产实例见 {@link #getInstance()}。 */
    OfficialServerList(
            Settings settings,
            Transport transport,
            Executor fetchExecutor,
            Executor clientExecutor,
            Clock clock,
            Path cacheFile) {
        this.settings = settings;
        this.transport = transport;
        this.fetchExecutor = fetchExecutor;
        this.clientExecutor = clientExecutor;
        this.clock = clock;
        this.cacheFile = cacheFile;
    }

    private static final class DefaultHolder {
        static final OfficialServerList INSTANCE = new OfficialServerList(
                new Settings() {
                    @Override
                    public boolean enabled() {
                        return Config.CLIENT.OFFICIAL_SERVER_LIST_ENABLED.get();
                    }

                    @Override
                    public String url() {
                        return Config.CLIENT.OFFICIAL_SERVER_LIST_URL.get();
                    }
                },
                OfficialServerList::httpFetch,
                task -> Thread.ofVirtual().name("fireflymc-official-server-list").start(task),
                task -> Minecraft.getInstance().execute(task),
                Clock.systemUTC(),
                FMLPaths.GAMEDIR.get().resolve(CACHE_FILE_NAME));
    }

    public static OfficialServerList getInstance() {
        return DefaultHolder.INSTANCE;
    }

    public boolean enabled() {
        return settings.enabled();
    }

    /** 列表内容变化时在客户端主线程回调（界面据此重建列表）。 */
    public synchronized void setChangeListener(Runnable listener) {
        this.changeListener = listener;
    }

    /** 读取本地缓存（幂等）。缓存缺失或损坏时保持空列表，等后台拉取。 */
    public synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (cacheFile == null || !Files.isRegularFile(cacheFile)) {
            return;
        }
        try {
            readCache();
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[FireflyMC] 官方服务器列表本地缓存无法读取，已忽略: {}", describe(e));
        }
    }

    /** 后台拉取（有节流；进行中的拉取不重复发起）。 */
    public void refreshAsync() {
        refreshAsync(false);
    }

    /**
     * 后台拉取。
     *
     * @param force 为 true 时跳过节流间隔（原版「刷新」按钮），但仍有进行中的拉取时不重复发起
     */
    public synchronized void refreshAsync(boolean force) {
        if (!settings.enabled()) {
            return;
        }
        String url = settings.url();
        if (url == null) {
            return;
        }
        String target = url.trim();
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            return;
        }
        ensureLoaded();
        long now = clock.millis();
        if (fetchStartedAt != NEVER && now - fetchStartedAt < FETCH_WATCHDOG_MILLIS) {
            return;
        }
        if (!force && lastAttemptAt != NEVER && now - lastAttemptAt < MIN_REFRESH_INTERVAL_MILLIS) {
            return;
        }
        lastAttemptAt = now;
        fetchStartedAt = now;
        try {
            fetchExecutor.execute(() -> fetch(target));
        } catch (RuntimeException e) {
            fetchStartedAt = NEVER;
            throw e;
        }
    }

    private void fetch(String url) {
        List<OfficialServer> fetched = null;
        Exception failure = null;
        try {
            fetched = OfficialServer.parse(transport.fetch(url));
        } catch (Exception e) {
            failure = e;
        }
        List<OfficialServer> result = fetched;
        Exception error = failure;
        clientExecutor.execute(() -> accept(result, error));
    }

    private void accept(List<OfficialServer> fetched, Exception error) {
        Runnable listener;
        synchronized (this) {
            fetchStartedAt = NEVER;
            if (error != null) {
                LOGGER.warn("[FireflyMC] 拉取官方服务器列表失败，沿用当前列表: {}", describe(error));
                return;
            }
            // 只看界面上可见的内容：名称与地址（含顺序）。本地缓存不保存 id，
            // 仅 id 变化不算列表变化，不去重建界面
            if (signatureOf(fetched).equals(signatureOf(servers))) {
                return;
            }
            servers = fetched;
            // 已下架的地址不再保留图标 / 资源包选择
            Set<String> kept = new HashSet<>();
            for (OfficialServer server : fetched) {
                kept.add(server.normalizedAddress());
            }
            states.keySet().retainAll(kept);
            dirty = true;
            listener = changeListener;
            LOGGER.info("[FireflyMC] 官方服务器列表已更新: {} 个服务器", fetched.size());
        }
        flush();
        if (listener != null) {
            listener.run();
        }
    }

    private static boolean containsKey(List<OfficialServer> list, String key) {
        for (OfficialServer server : list) {
            if (server.normalizedAddress().equals(key)) {
                return true;
            }
        }
        return false;
    }

    /** 当前生效的列表（不可变，按后台配置的顺序）。 */
    public synchronized List<OfficialServer> servers() {
        return servers;
    }

    /** 该地址是否为官方服务器（地址归一化后比较，见 {@link OfficialServer#normalizeAddress}）。 */
    public synchronized boolean isOfficial(String address) {
        if (!OfficialServer.isValidAddress(address)) {
            return false;
        }
        return containsKey(servers, OfficialServer.normalizeAddress(address));
    }

    /** 列表内容的签名：名称与地址按顺序拼接，签名不变即界面无需重建。 */
    public synchronized String signature() {
        return signatureOf(servers);
    }

    private static String signatureOf(List<OfficialServer> list) {
        StringBuilder builder = new StringBuilder();
        for (OfficialServer server : list) {
            builder.append(server.name()).append('\u0000').append(server.address()).append('\u0001');
        }
        return builder.toString();
    }

    public synchronized SavedState savedState(String normalizedAddress) {
        return states.get(normalizedAddress);
    }

    /** 更新某个条目的图标 / 资源包选择；与已保存的相同则不标记为待写入。 */
    public synchronized void updateState(String normalizedAddress, String iconBase64, String packStatus) {
        SavedState next = new SavedState(iconBase64, packStatus);
        if (!next.equals(states.get(normalizedAddress))) {
            states.put(normalizedAddress, next);
            dirty = true;
        }
    }

    /** 把当前列表与附加状态写入本地缓存（仅有变更时写）。 */
    public synchronized void flush() {
        if (!dirty || cacheFile == null) {
            return;
        }
        try {
            Files.writeString(cacheFile, toJson());
            dirty = false;
        } catch (IOException e) {
            LOGGER.warn("[FireflyMC] 保存官方服务器列表缓存失败: {}", describe(e));
        }
    }

    private void readCache() throws IOException {
        if (Files.size(cacheFile) > MAX_CACHE_BYTES) {
            throw new IOException("cache file too large");
        }
        JsonElement root = JsonParser.parseString(Files.readString(cacheFile, StandardCharsets.UTF_8));
        if (!root.isJsonObject()) {
            throw new IOException("cache root is not an object");
        }
        JsonObject object = root.getAsJsonObject();
        JsonElement version = object.get("version");
        if (version == null || !version.getAsJsonPrimitive().isNumber()
                || version.getAsInt() != CACHE_VERSION) {
            throw new IOException("unsupported cache version");
        }
        JsonElement list = object.get("servers");
        if (list == null || !list.isJsonArray()) {
            throw new IOException("cache has no servers array");
        }
        List<OfficialServer> cached = new ArrayList<>();
        Map<String, SavedState> saved = new HashMap<>();
        int skipped = 0;
        for (JsonElement element : list.getAsJsonArray()) {
            // 缓存是尽力而为：单条损坏只跳过该条
            try {
                JsonObject entry = element.getAsJsonObject();
                OfficialServer server = new OfficialServer(0,
                        requireString(entry, "name"), requireString(entry, "address"));
                if (saved.put(server.normalizedAddress(),
                        new SavedState(optionalString(entry, "icon"), optionalString(entry, "packStatus"))) != null) {
                    continue;
                }
                cached.add(server);
            } catch (RuntimeException e) {
                skipped++;
            }
            if (cached.size() >= OfficialServer.MAX_SERVERS) {
                break;
            }
        }
        if (skipped > 0) {
            LOGGER.warn("[FireflyMC] 官方服务器列表缓存中有 {} 条无效记录，已跳过", skipped);
        }
        states.clear();
        states.putAll(saved);
        servers = List.copyOf(cached);
    }

    private String toJson() {
        JsonArray array = new JsonArray();
        for (OfficialServer server : servers) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", server.name());
            entry.addProperty("address", server.address());
            SavedState state = states.get(server.normalizedAddress());
            if (state != null) {
                if (state.iconBase64() != null) {
                    entry.addProperty("icon", state.iconBase64());
                }
                if (state.packStatus() != null) {
                    entry.addProperty("packStatus", state.packStatus());
                }
            }
            array.add(entry);
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", CACHE_VERSION);
        root.add("servers", array);
        return root.toString();
    }

    private static String requireString(JsonObject object, String field) {
        String value = optionalString(object, field);
        if (value == null) {
            throw new IllegalArgumentException(field + " is missing");
        }
        return value;
    }

    private static String optionalString(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return null;
        }
        return value.getAsString();
    }

    private static String describe(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static final class HttpHolder {
        static final HttpClient CLIENT = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    private static String httpFetch(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .header("User-Agent", "FireflyMC-Client/" + FireflyMCMod.VERSION)
                .GET()
                .build();
        // ofInputStream：响应体按需读取并限制长度（请求超时只覆盖到响应头，后续读取由看门狗兜底）
        HttpResponse<InputStream> response = HttpHolder.CLIENT
                .send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode());
            }
            byte[] bytes = body.readNBytes((int) MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) {
                throw new IOException("response too large");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
