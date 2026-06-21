package firefly520.fireflymc.client;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import firefly520.fireflymc.FireflyMCMod;

/**
 * 客户端公告加载器
 * 从官网公告 API（/api/announcements）同步加载公告并解析（无缓存）
 * 网络异常时返回 null，不抛出异常
 */
public class RulesLoader {
    private static final String ANNOUNCEMENTS_URL = "https://mc.firefly520.top/api/announcements";
    private static final String WEBSITE_URL = "https://mc.firefly520.top";
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 1000;

    /**
     * 同步加载公告（无缓存，每次都重新请求）
     * 网络异常时返回 null
     */
    public static RulesContent loadRules() {
        int attempts = 0;
        while (attempts < MAX_RETRIES) {
            try {
                String content = fetchFromUrl(ANNOUNCEMENTS_URL);
                return parseAnnouncements(content);
            } catch (Exception e) {
                attempts++;
                if (attempts < MAX_RETRIES) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                } else {
                    // 最后一次尝试失败
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * 从 URL 获取内容
     */
    private static String fetchFromUrl(String urlString) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(urlString).toURL().openConnection();
        conn.setConnectTimeout(10000);  // 10秒连接超时
        conn.setReadTimeout(10000);     // 10秒读取超时
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "FireflyMC-Client/" + FireflyMCMod.VERSION);

        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line).append("\n");
            }
        } finally {
            conn.disconnect();
        }
        return result.toString();
    }

    /**
     * 解析官网公告 JSON 数组
     * 每条公告映射为一个章节（标题=title，条目=content 按行拆分）
     * API 已按置顶 + 发布时间排序，直接按返回顺序展示
     */
    private static RulesContent parseAnnouncements(String json) {
        List<RulesContent.Section> sections = new ArrayList<>();
        JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject obj = el.getAsJsonObject();
            String title = obj.has("title") && !obj.get("title").isJsonNull()
                    ? obj.get("title").getAsString() : "";
            String content = obj.has("content") && !obj.get("content").isJsonNull()
                    ? obj.get("content").getAsString() : "";

            // content 按行拆分为条目，跳过空行
            List<String> lines = new ArrayList<>();
            for (String line : content.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    lines.add(trimmed);
                }
            }
            if (!title.isEmpty() || !lines.isEmpty()) {
                sections.add(new RulesContent.Section(title, lines));
            }
        }

        // version/updateDate/description/contact 无对应数据来源，留空（RulesScreen 自动跳过）
        return new RulesContent("", "", WEBSITE_URL, "", sections, "");
    }
}
