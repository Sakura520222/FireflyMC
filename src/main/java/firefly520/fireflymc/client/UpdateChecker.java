package firefly520.fireflymc.client;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import firefly520.fireflymc.FireflyMCMod;

/**
 * Mod更新检查器
 * 通过 GitHub Releases API 检查是否有新版本
 */
public class UpdateChecker {
    private static final String GITHUB_API_URL = "https://api.github.com/repos/Sakura520222/FireflyMC/releases/latest";
    private static volatile boolean checked = false;

    /**
     * 检查更新（在客户端启动时调用）
     */
    public static void checkForUpdate() {
        if (checked) return;

        Thread.startVirtualThread(() -> {
            try {
                JsonObject release = fetchLatestRelease();
                if (release == null) {
                    return;
                }

                checked = true;

                String tagName = release.get("tag_name").getAsString();
                String latestVersion = tagName.replaceFirst("^v", "");
                String releasePageUrl = release.get("html_url").getAsString();

                // 查找 JAR 资产下载链接
                String downloadUrl = "";
                JsonArray assets = release.getAsJsonArray("assets");
                if (assets != null) {
                    for (JsonElement assetElem : assets) {
                        JsonObject asset = assetElem.getAsJsonObject();
                        String name = asset.get("name").getAsString();
                        if (name.endsWith(".jar")) {
                            downloadUrl = asset.get("browser_download_url").getAsString();
                            break;
                        }
                    }
                }

                System.out.println("[FireflyMC] Checking for updates: current=" + FireflyMCMod.VERSION + ", latest=" + latestVersion);

                if (isNewerVersion(latestVersion)) {
                    ClientState.hasUpdateAvailable = true;
                    ClientState.updateVersion = latestVersion;
                    // 优先使用发布页面 URL，用户可查看更新日志
                    ClientState.updateUrl = releasePageUrl;
                    System.out.println("[FireflyMC] Update available: " + latestVersion);
                    if (!downloadUrl.isEmpty()) {
                        System.out.println("[FireflyMC] Download: " + downloadUrl);
                    }
                } else {
                    System.out.println("[FireflyMC] No update available");
                }
            } catch (Exception e) {
                System.out.println("[FireflyMC] Update check failed: " + e.getMessage());
            }
        });
    }

    /**
     * 从 GitHub Releases API 获取最新发布信息
     */
    private static JsonObject fetchLatestRelease() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(GITHUB_API_URL).toURL().openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "FireflyMC-Client/" + FireflyMCMod.VERSION);
        conn.setRequestProperty("Accept", "application/vnd.github+json");

        try {
            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                System.out.println("[FireflyMC] Update check failed: HTTP " + responseCode);
                return null;
            }

            StringBuilder result = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    result.append(line);
                }
            }

            return JsonParser.parseString(result.toString()).getAsJsonObject();
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 比较版本号，判断是否有更新
     * 返回 true 如果 latestVersion 比 currentVersion 新
     */
    private static boolean isNewerVersion(String latestVersion) {
        if (latestVersion == null || latestVersion.isEmpty()) return false;

        try {
            String[] latestParts = latestVersion.split("\\.");
            String[] currentParts = FireflyMCMod.VERSION.split("\\.");

            int length = Math.max(latestParts.length, currentParts.length);
            for (int i = 0; i < length; i++) {
                int latest = i < latestParts.length ? Integer.parseInt(latestParts[i]) : 0;
                int current = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;

                if (latest > current) return true;
                if (latest < current) return false;
            }
        } catch (NumberFormatException e) {
            return false;
        }

        return false;
    }
}
