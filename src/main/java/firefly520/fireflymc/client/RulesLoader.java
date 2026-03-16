package firefly520.fireflymc.client;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 客户端公告加载器
 * 从 URL 同步加载公告文本并解析（无缓存）
 * 网络异常时返回 null，不抛出异常
 */
public class RulesLoader {
    private static final String RULES_URL = "https://mc.firefly520.top/FireflyMC_Server_Rules.txt";

    /**
     * 同步加载公告（无缓存，每次都重新请求）
     * 网络异常时返回 null
     */
    public static RulesContent loadRules() {
        try {
            String content = fetchFromUrl(RULES_URL);
            return parseRules(content);
        } catch (Exception e) {
            // 不抛出异常，返回 null，由调用方处理错误显示
            return null;
        }
    }

    /**
     * 从 URL 获取内容
     */
    private static String fetchFromUrl(String urlString) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(urlString).toURL().openConnection();
        conn.setConnectTimeout(10000);  // 10秒连接超时
        conn.setReadTimeout(10000);     // 10秒读取超时
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "FireflyMC-Client/2.1");

        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
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
     * 解析公告文本
     * 格式: # 注释, [SECTION_N] 标题, - 内容
     */
    private static RulesContent parseRules(String content) {
        String version = "V2.1";
        String updateDate = "";
        String website = "";
        String description = "";
        String contact = "";
        List<RulesContent.Section> sections = new ArrayList<>();

        String[] lines = content.split("\n");
        String currentTitle = null;
        List<String> currentLines = null;

        for (String line : lines) {
            String trimmedLine = line.trim();

            // 跳过注释分隔符
            if (trimmedLine.startsWith("# ====") || trimmedLine.startsWith("# -----")) {
                continue;
            }

            // 解析版本号: # 版本: V2.1
            if (trimmedLine.startsWith("# 版本:")) {
                if (trimmedLine.length() > 5) {  // 检查长度避免 IndexOutOfBoundsException
                    version = trimmedLine.substring(5).trim();
                }
                continue;
            }

            // 解析更新日期: # 更新日期: 2025.05.24
            if (trimmedLine.startsWith("# 更新日期:")) {
                if (trimmedLine.length() > 7) {  // 检查长度
                    updateDate = trimmedLine.substring(7).trim();
                }
                continue;
            }

            // 解析官网: # 官网: https://mc.firefly520.top/
            if (trimmedLine.startsWith("# 官网:")) {
                if (trimmedLine.length() > 5) {
                    website = trimmedLine.substring(5).trim();
                }
                continue;
            }

            // 解析说明: # 说明: FireflyMC 倡导...
            if (trimmedLine.startsWith("# 说明:")) {
                if (trimmedLine.length() > 5) {  // 检查长度
                    description = trimmedLine.substring(5).trim();
                }
                continue;
            }

            // 解析联系方式: # 联系: 违规举报请联系...
            if (trimmedLine.startsWith("# 联系:")) {
                if (trimmedLine.length() > 5) {  // 检查长度
                    contact = trimmedLine.substring(5).trim();
                }
                continue;
            }

            // 解析章节标题: # [SECTION_1] 行为准则 - 文明交流
            if (trimmedLine.startsWith("# [SECTION_")) {
                // 保存上一个章节
                if (currentTitle != null && currentLines != null) {
                    sections.add(new RulesContent.Section(currentTitle, new ArrayList<>(currentLines)));
                }

                // 提取新章节标题
                int endBracket = trimmedLine.indexOf("]");
                if (endBracket > 0 && trimmedLine.length() > endBracket + 1) {  // 检查长度
                    currentTitle = trimmedLine.substring(endBracket + 1).trim();
                    currentLines = new ArrayList<>();
                }
                continue;
            }

            // 解析内容行: - 禁止任何形式...
            if (trimmedLine.startsWith("- ") && currentLines != null) {
                currentLines.add(trimmedLine.substring(2));
            }
        }

        // 保存最后一个章节
        if (currentTitle != null && currentLines != null) {
            sections.add(new RulesContent.Section(currentTitle, new ArrayList<>(currentLines)));
        }

        return new RulesContent(version, updateDate, website, description, new ArrayList<>(sections), contact);
    }
}
