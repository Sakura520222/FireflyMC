package firefly520.fireflymc.client.music;

import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.TreeMap;
import static org.junit.jupiter.api.Assertions.*;

class LrcParserTest {

    @Test
    void standardTimestamp() {
        TreeMap<Long, String> map = LrcParser.parse("[00:12.50]吹着前奏望着天空\n[01:30.00]但偏偏雨渐渐大到我看你不见");
        assertEquals(2, map.size());
        assertEquals("吹着前奏望着天空", map.get(12500L));
        assertEquals("但偏偏雨渐渐大到我看你不见", map.get(90000L));
    }

    @Test
    void multipleTimestampsOnOneLine() {
        TreeMap<Long, String> map = LrcParser.parse("[00:10.00][00:20.00]重复歌词");
        assertEquals(2, map.size());
        assertEquals("重复歌词", map.get(10000L));
        assertEquals("重复歌词", map.get(20000L));
    }

    @Test
    void invalidLinesSkipped() {
        TreeMap<Long, String> map = LrcParser.parse("[ti:晴天]\n[ar:周杰伦]\n不是时间标签的行\n[99:99.99]分钟越界\n[00:05.00]有效行");
        assertEquals(1, map.size());
        assertEquals("有效行", map.firstEntry().getValue());
    }

    @Test
    void emptyLyrics() {
        assertTrue(LrcParser.parse("").isEmpty());
        assertTrue(LrcParser.parse(null).isEmpty());
        assertTrue(LrcParser.parse("[00:00.00]").isEmpty()); // 只有标签没有文本
    }

    @Test
    void currentLineLookup() {
        TreeMap<Long, String> map = LrcParser.parse("[00:10.00]第一句\n[00:20.00]第二句");
        assertEquals(Optional.empty(), LrcParser.currentLine(map, 5000L));
        assertEquals(Optional.of("第一句"), LrcParser.currentLine(map, 10000L));
        assertEquals(Optional.of("第一句"), LrcParser.currentLine(map, 19999L));
        assertEquals(Optional.of("第二句"), LrcParser.currentLine(map, 25000L));
    }
}
