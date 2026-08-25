package firefly520.fireflymc.music;

/**
 * 手动验证类（无 @Test 注解，不进 CI）：
 * 验证 txqq 搜索 + outer url 时长探测全链路。
 * 运行方式：gradlew compileTestJava 后用 IDE 运行 main。
 */
public class ManualApiCheck {
    /** 调试：打印探测请求的 HTTP 状态/头/异常，定位 fallback 原因 */
    private static void debugProbe(String songId) {
        try {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                            java.net.URI.create(MusicApiClient.outerUrl(songId)))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .header("User-Agent", MusicApiClient.OUTBOUND_UA)
                    .header("Range", "bytes=0-65535")
                    .header("Accept-Encoding", "identity")
                    .GET()
                    .build();
            java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                    .build();
            java.net.http.HttpResponse<java.io.InputStream> response =
                    http.send(request, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            System.out.println("[调试] 状态码: " + response.statusCode());
            System.out.println("[调试] URI: " + response.uri());
            response.headers().map().forEach((k, v) ->
                    System.out.println("[调试] 头 " + k + ": " + v));
            byte[] head = response.body().readNBytes(128);
            System.out.println("[调试] 前32字节: " + java.util.HexFormat.of().formatHex(head, 0, Math.min(32, head.length)));
            response.body().close();
        } catch (Exception e) {
            System.out.println("[调试] 异常: " + e);
        }
    }

    public static void main(String[] args) {
        String keyword = args.length > 0 ? args[0] : "起风了";
        System.out.println("[关键词] " + keyword);
        long t0 = System.currentTimeMillis();
        MusicApiClient.search(keyword).thenAccept(song -> {
            System.out.println("[搜索] 耗时 " + (System.currentTimeMillis() - t0) + "ms");
            System.out.println("[搜索] 结果: " + song);
            if (song == null) {
                return;
            }
            // 调试：直接打印探测的 HTTP 细节
            debugProbe(song.songId());
            long t1 = System.currentTimeMillis();
            MusicApiClient.probeDurationMs(song.songId()).thenAccept(d -> {
                System.out.println("[探测] 耗时 " + (System.currentTimeMillis() - t1) + "ms");
                System.out.println("[探测] 时长: " + d + "ms（约 " + d / 1000 + "s，fallback=" + Mp3DurationProbe.FALLBACK_DURATION_MS + "）");
            });
        }).exceptionally(e -> {
            System.err.println("[失败] " + e);
            return null;
        }).join();
        try { Thread.sleep(8000); } catch (InterruptedException ignored) {}
    }
}
