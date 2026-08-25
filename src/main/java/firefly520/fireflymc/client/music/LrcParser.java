package firefly520.fireflymc.client.music;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LRC 歌词解析器（纯客户端组件）
 * 支持 [mm:ss.xx] 时间标签，同一行多个时间戳；无效行跳过
 */
public final class LrcParser {

    /** 匹配行首一个或多个连续时间标签 */
    private static final Pattern TIME_TAGS = Pattern.compile("(\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?])+");

    private LrcParser() {}

    /**
     * 解析 LRC 文本为 时间毫秒 -> 歌词行 的有序映射
     */
    public static TreeMap<Long, String> parse(String lrc) {
        TreeMap<Long, String> result = new TreeMap<>();
        if (lrc == null || lrc.isBlank()) {
            return result;
        }
        for (String line : lrc.split("\\r?\\n")) {
            line = line.strip();
            if (line.isEmpty()) {
                continue;
            }
            Matcher m = TIME_TAGS.matcher(line);
            if (!m.find() || m.start() != 0) {
                continue; // 行首不是时间标签则跳过（[ti:] 等元数据、纯文本）
            }
            String tags = m.group();
            String text = line.substring(tags.length()).strip();
            if (text.isEmpty()) {
                continue;
            }
            Matcher tagMatcher = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]").matcher(tags);
            while (tagMatcher.find()) {
                long minutes = Long.parseLong(tagMatcher.group(1));
                long seconds = Long.parseLong(tagMatcher.group(2));
                String fracStr = tagMatcher.group(3);
                long millis = 0;
                if (fracStr != null) {
                    // [.:ff] 按百分秒（2位）解释；3位按毫秒
                    millis = switch (fracStr.length()) {
                        case 1 -> Long.parseLong(fracStr) * 100L;
                        case 2 -> Long.parseLong(fracStr) * 10L;
                        default -> Long.parseLong(fracStr.length() > 3 ? fracStr.substring(0, 3) : fracStr);
                    };
                }
                if (minutes > 99 || seconds > 59) {
                    continue; // 分钟/秒越界视为无效标签
                }
                long timeMs = (minutes * 60 + seconds) * 1000L + millis;
                result.put(timeMs, text);
            }
        }
        return result;
    }

    /**
     * 查询 positionMs 时刻的当前歌词行（floorEntry）
     */
    public static Optional<String> currentLine(TreeMap<Long, String> map, long positionMs) {
        Map.Entry<Long, String> entry = map.floorEntry(positionMs);
        return entry == null ? Optional.empty() : Optional.ofNullable(entry.getValue());
    }
}
