package firefly520.fireflymc.music;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * txqq 聚合接口客户端（netease 平台）。
 * 全部异步（调用方跑在虚拟线程）；超时与有界读取见各常量。
 * 队列与 payload 中只存 songId，播放时由客户端访问 outer url 延迟解析。
 */
public final class MusicApiClient {

    private static final String SEARCH_URL = "https://music.txqq.pro/";
    private static final String OUTER_URL_TEMPLATE = "https://music.163.com/song/media/outer/url?id=%s.mp3";
    private static final Gson GSON = new Gson();

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration SEARCH_TIMEOUT = Duration.ofSeconds(15);
    /** 时长探测单次超时：3 次重试 + 退避的整体预算控制在 10s 内（3×3s+0.6s≈9.6s） */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);
    /** 搜索响应体上限 2 MiB */
    private static final int MAX_SEARCH_BYTES = 2 * 1024 * 1024;
    /** 时长探测最多读 64 KiB */
    private static final int PROBE_HEAD_BYTES = 64 * 1024;

    /** 字段上限 */
    private static final int MAX_TITLE = 128, MAX_AUTHOR = 128, MAX_LRC = 256 * 1024;

    /** 预检候选数：逐首检查直链可播性，取第一条可播的 */
    private static final int PLAYABLE_CHECK_LIMIT = 5;
    /** 预检超时：单首轻量 302 检查 */
    private static final Duration PLAYABLE_TIMEOUT = Duration.ofSeconds(5);

    /** 搜索结果（第一首） */
    public record SongInfo(String songId, String title, String author, String lrc) {}

    /** 构造客户端用的 outer url（延迟解析入口，只依赖 songId） */
    public static String outerUrl(String songId) {
        return String.format(OUTER_URL_TEMPLATE, songId);
    }

    public static final String OUTBOUND_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:146.0) Gecko/20100101 Firefox/146.0";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            // ALWAYS：outer url 302 到 http://m*.music.126.net（https→http 降级），
            // NORMAL 不跟随降级重定向会拿到 302 空响应
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    /**
     * body 读取 watchdog：ofInputStream 的 request timeout 只保护到响应头到达，
     * 之后流式 read 不受任何超时保护——第三方服务停滞会让阻塞读永久挂起、
     * 玩家永久 pending。到期 close 流可解除阻塞 read（read 抛 IOException 走正常失败链路）。
     */
    private static final java.util.concurrent.ScheduledExecutorService READ_WATCHDOG =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "fireflymc-music-read-watchdog");
                t.setDaemon(true);
                return t;
            });

    private MusicApiClient() {}

    /** 搜索歌曲，取第一首。songId 必须纯数字（SSRF 防护）。无结果返回 null。 */
    public static CompletableFuture<SongInfo> search(String keyword) {
        return CompletableFuture.supplyAsync(() -> {
            String form = "input=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8)
                    + "&filter=name&type=netease&page=1";
            HttpRequest request = HttpRequest.newBuilder(URI.create(SEARCH_URL))
                    .timeout(SEARCH_TIMEOUT)
                    .header("User-Agent", OUTBOUND_UA)
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Origin", SEARCH_URL)
                    .header("Referer", SEARCH_URL)
                    .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                    .build();
            try {
                HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    int code = response.statusCode();
                    closeQuietly(response.body()); // 非 200 也要释放流与底层连接
                    throw new IOException("搜索接口 HTTP " + code);
                }
                // body 读取 watchdog：停滞则 close 解除阻塞（见 READ_WATCHDOG 注释）
                java.util.concurrent.ScheduledFuture<?> watchdog = READ_WATCHDOG.schedule(
                        () -> closeQuietly(response.body()),
                        SEARCH_TIMEOUT.toMillis() + 2_000L, java.util.concurrent.TimeUnit.MILLISECONDS);
                // 有界读取：最多 2 MiB，超出视为异常响应
                byte[] body;
                try (InputStream in = response.body()) {
                    body = readBounded(in, MAX_SEARCH_BYTES);
                } finally {
                    watchdog.cancel(false);
                }
                JsonObject json = GSON.fromJson(new String(body, StandardCharsets.UTF_8), JsonObject.class);
                if (json == null || !json.has("data") || !json.get("data").isJsonArray()) {
                    throw new IOException("搜索接口返回结构异常");
                }
                JsonArray data = json.getAsJsonArray("data");
                if (data.isEmpty()) {
                    return null; // 无结果（含付费歌被过滤）
                }
                // 逐首预检直链可播性：付费/VIP 歌的 outer url 会 302 到 404 页而非音频 CDN，
                // 客户端拿到 200 HTML 解码必失败 → 点歌阶段就跳过这些歌
                for (int i = 0; i < Math.min(data.size(), PLAYABLE_CHECK_LIMIT); i++) {
                    JsonObject candidate = data.get(i).getAsJsonObject();
                    String songId = truncate(candidate.has("songid") ? candidate.get("songid").getAsString() : "", 32);
                    if (!songId.matches("\\d{4,20}")) {
                        continue;
                    }
                    if (!isPlayable(songId)) {
                        continue;
                    }
                    return new SongInfo(
                            songId,
                            truncate(candidate.has("title") ? candidate.get("title").getAsString() : "未知", MAX_TITLE),
                            truncate(candidate.has("author") ? candidate.get("author").getAsString() : "未知", MAX_AUTHOR),
                            truncate(candidate.has("lrc") ? candidate.get("lrc").getAsString() : "", MAX_LRC));
                }
                return null; // 前 N 候选全部不可播（多为付费歌曲）
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new RuntimeException("搜索失败: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 预检直链可播性：不跟随重定向，看 302 Location。
     * 三段式判据（防代理/网络抖动污染响应误杀可播歌曲）：
     * - Location 含 126.net → 可播
     * - Location 明确是 music.163.com/404 → 不可播（付费/无版权的确定性信号）
     * - 其他一切响应（200 直连、无 Location、302 到未知域、请求异常）→ 放行，
     *   宁可播放时失败走 FAILED 兜底，不在点歌阶段误杀
     */
    private static boolean isPlayable(String songId) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(outerUrl(songId)))
                    .timeout(PLAYABLE_TIMEOUT)
                    .header("User-Agent", OUTBOUND_UA)
                    .GET()
                    .build();
            HttpResponse<Void> response = HttpClient.newBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NEVER) // 只看 302 的 Location
                    .build()
                    .send(request, HttpResponse.BodyHandlers.discarding());
            String location = response.headers().firstValue("Location").orElse("");
            if (location.contains("126.net")) {
                return true;
            }
            if (location.contains("music.163.com/404")) {
                firefly520.fireflymc.FireflyMCMod.LOGGER.debug(
                        "[Music] 预检跳过不可播歌曲 songId={}", songId);
                return false;
            }
            return true; // 未知响应放行（可能为污染/抖动）
        } catch (Exception e) {
            return true; // 预检失败放行（不挡播放，播放失败走 FAILED 链路兜底）
        }
    }

    /**
     * 时长探测结果缓存：songId → durationMs。
     * 客户端缓存（music-cache/*.mp3）是持久化的，服务端探测缓存若只在内存，
     * 游戏重启后同一首歌会重新探测——网络抖动时 fallback 会再次腰斩已缓存的歌。
     * 因此持久化到 music-cache/durations.json（与音频缓存同目录，语义一致）。
     */
    private static final Path DURATION_CACHE_FILE = Path.of("music-cache", "durations.json");
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> DURATION_CACHE = loadDurationCache();

    private static java.util.concurrent.ConcurrentHashMap<String, Long> loadDurationCache() {
        java.util.concurrent.ConcurrentHashMap<String, Long> map = new java.util.concurrent.ConcurrentHashMap<>();
        try {
            if (Files.isRegularFile(DURATION_CACHE_FILE)) {
                JsonObject json = GSON.fromJson(
                        Files.readString(DURATION_CACHE_FILE), JsonObject.class);
                if (json != null) {
                    json.entrySet().forEach(e -> {
                        try {
                            map.put(e.getKey(), e.getValue().getAsLong());
                        } catch (Exception ignored) {
                        }
                    });
                }
            }
        } catch (Exception ignored) {
            // 缓存缺失/损坏：忽略，重新探测
        }
        return map;
    }

    /** 探测成功写入缓存后异步落盘（缓存损坏不影响主链路） */
    private static void persistDurationCache() {
        Thread.ofVirtual().name("fireflymc-music-cache-save").start(() -> {
            try {
                Files.createDirectories(DURATION_CACHE_FILE.getParent());
                JsonObject json = new JsonObject();
                DURATION_CACHE.forEach(json::addProperty);
                Files.writeString(DURATION_CACHE_FILE, GSON.toJson(json));
            } catch (Exception ignored) {
            }
        });
    }

    /**
     * 时长探测：Range 请求头部 64 KiB。
     * 206 → 总大小取 Content-Range 的 total；200 → Content-Length。
     * 必须流式有界读取（ofInputStream + 最多 64KiB 即 close），严禁 ofByteArray。
     * 网络抖动重试最多 3 次（整体预算 ≤10s）：fallback 时长偏短会腰斩长歌，尽量避免降级。
     */
    public static CompletableFuture<Long> probeDurationMs(String songId) {
        Long cached = DURATION_CACHE.get(songId);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return CompletableFuture.supplyAsync(() -> {
            for (int attempt = 1; attempt <= 3; attempt++) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(outerUrl(songId)))
                        .timeout(PROBE_TIMEOUT)
                        .header("User-Agent", OUTBOUND_UA)
                        .header("Range", "bytes=0-" + (PROBE_HEAD_BYTES - 1))
                        .header("Accept-Encoding", "identity") // 字节长度语义不被内容编码干扰
                        .GET()
                        .build();
                try {
                    HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
                    java.util.concurrent.ScheduledFuture<?> watchdog = READ_WATCHDOG.schedule(
                            () -> closeQuietly(response.body()),
                            PROBE_TIMEOUT.toMillis() + 1_000L, java.util.concurrent.TimeUnit.MILLISECONDS);
                    try (InputStream body = response.body()) {
                        long totalBytes = resolveTotalBytes(response);
                        // 读满 64 KiB 即停（前缀语义）：服务器忽略 Range 返回 200 全文件时
                        // 不能把"超上限"当异常——否则每次重试都失败、探测必 fallback 腰斩
                        byte[] head = readPrefix(body, PROBE_HEAD_BYTES);
                        long duration = Mp3DurationProbe.probeDurationMs(head, totalBytes);
                        if (duration != Mp3DurationProbe.FALLBACK_DURATION_MS) {
                            DURATION_CACHE.put(songId, duration); // 探测成功才缓存（fallback 不污染）
                            persistDurationCache(); // 异步落盘：重启后仍生效
                            return duration;
                        }
                        // 解析结果为 fallback（数据异常/污染页）→ 也重试
                    } finally {
                        watchdog.cancel(false); // 读取完成/异常后才解除停滞保护
                    }
                } catch (IOException | InterruptedException e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                        return Mp3DurationProbe.FALLBACK_DURATION_MS;
                    }
                }
                if (attempt < 3) {
                    try {
                        Thread.sleep(300L); // 退避 0.3s：保持整体预算 ≤10s
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return Mp3DurationProbe.FALLBACK_DURATION_MS;
                    }
                }
            }
            firefly520.fireflymc.FireflyMCMod.LOGGER.warn(
                    "[Music] 时长探测 3 次均失败 songId={}，使用保守 fallback", songId);
            return Mp3DurationProbe.FALLBACK_DURATION_MS;
        });
    }

    /** 206 → Content-Range total；200 → Content-Length；异常 → -1（触发 fallback） */
    private static long resolveTotalBytes(HttpResponse<?> response) {
        String contentRange = response.headers().firstValue("Content-Range").orElse(null);
        // 格式：bytes 0-65535/4738291
        if (contentRange != null && response.statusCode() == 206) {
            int slash = contentRange.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < contentRange.length()) {
                try {
                    return Long.parseLong(contentRange.substring(slash + 1).strip());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return response.headers().firstValueAsLong("Content-Length").orElse(-1L);
    }

    /** 有界读取：最多 maxBytes，超出抛异常（防无界下载进内存；搜索响应用） */
    private static byte[] readBounded(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (out.size() + n > maxBytes) {
                throw new IOException("响应超过上限 " + maxBytes + " 字节");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * 前缀读取：读满 maxBytes 即停（提前返回，不消费剩余流，由调用方 close）。
     * 探测头部专用：服务器忽略 Range 返回 200 全文件时，只需前 64 KiB——
     * 用 readBounded 会把"超过上限"当异常，导致探测必然 fallback。
     * 包内可见供单测。
     */
    static byte[] readPrefix(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while (out.size() < maxBytes && (n = in.read(buf, 0, Math.min(buf.length, maxBytes - out.size()))) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static void closeQuietly(InputStream in) {
        try {
            if (in != null) {
                in.close();
            }
        } catch (IOException ignored) {
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
