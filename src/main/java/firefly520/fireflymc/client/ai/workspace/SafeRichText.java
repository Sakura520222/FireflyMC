package firefly520.fireflymc.client.ai.workspace;

import com.google.gson.*;
import firefly520.fireflymc.ai.presentation.ResponseBlocks;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.*;
import net.minecraft.util.FormattedCharSequence;
import java.util.*;
import java.util.regex.Pattern;

/** Native Components only. No HTML, scripts, commands, or model-authored approval actions. */
public final class SafeRichText {
    public record Line(FormattedCharSequence text, int inset, String itemId, int itemCount,
                       long current, long total, String recipeId) {}
    private static final Pattern INLINE = Pattern.compile("\\[([^\\]\\n]{1,160})]\\((https?://[^\\s)]{1,2048})\\)|\\*\\*([^*\\n]+)\\*\\*|`([^`\\n]+)`");
    private SafeRichText() {}
    public static List<Component> lines(String text) {
        List<Component> result = new ArrayList<>();
        for (var block : ResponseBlocks.parse(text)) result.addAll(blockLines(block));
        return List.copyOf(result);
    }
    /** Display metadata has no command channel. Recipe clicks only open a fresh server lookup. */
    public static List<Line> layout(String text, Font font, int width) {
        var result = new ArrayList<Line>();
        for (var block : ResponseBlocks.parse(text)) {
            var components = blockLines(block); var data = block.data();
            int metadata = data.has("title") ? 1 : 0;
            for (int index = 0; index < components.size(); index++) {
                boolean icon = block.type().equals("ItemStackBlock") && index == metadata;
                boolean progress = block.type().equals("ProgressBlock") && index == metadata + 1;
                String recipe = block.type().equals("RecipeBlock") && index == metadata ? data.get("recipeId").getAsString() : "";
                int inset = icon ? 22 : 0;
                var wrapped = font.split(components.get(index), Math.max(30, width - inset));
                if (wrapped.isEmpty()) wrapped = List.of(FormattedCharSequence.EMPTY);
                for (int part = 0; part < wrapped.size(); part++) result.add(new Line(wrapped.get(part), inset,
                        icon && part == 0 ? data.get("itemId").getAsString() : "", icon && part == 0 ? data.get("count").getAsInt() : 0,
                        progress && part == 0 ? data.get("current").getAsLong() : 0, progress && part == 0 ? data.get("total").getAsLong() : 0, recipe));
            }
        }
        return List.copyOf(result);
    }
    private static List<Component> blockLines(ResponseBlocks.Block block) {
        List<Component> result = new ArrayList<>();
        var data = block.data();
        if (data.has("title")) result.add(Component.literal(data.get("title").getAsString()).withStyle(s -> s.withBold(true).withColor(0xefcf88)));
        switch (block.type()) {
            case "CodeBlock" -> block.text().lines().forEach(line -> result.add(Component.literal("  " + line).withStyle(s -> s.withColor(0xa9c9e9))));
            case "QuoteBlock" -> block.text().lines().forEach(line -> result.add(Component.literal("│ " + line).withStyle(s -> s.withItalic(true).withColor(0xaebcc7))));
            case "TableBlock" -> {
                result.add(tableRow(data.getAsJsonArray("columns")).withStyle(s -> s.withBold(true).withColor(0xefcf88)));
                data.getAsJsonArray("rows").forEach(row -> result.add(tableRow(row.getAsJsonArray())));
            }
            case "SourceLink" -> {
                String url = data.get("url").getAsString();
                result.add(Component.literal(url).withStyle(s -> s.withColor(0x8fc7f0).withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))));
                result.add(inline(block.text()));
            }
            case "StatCard" -> result.add(Component.literal(data.has("value") ? data.get("value").getAsString() : block.text())
                    .withStyle(s -> s.withBold(true).withColor(0xa9dfbf)));
            case "ItemStackBlock", "RecipeBlock", "ProgressBlock", "StepTimeline" -> {
                var metadata = ResponseBlocks.displayLines(block);
                for (int i = 0; i < metadata.size(); i++) {
                    result.add(Component.literal(metadata.get(i)).withStyle(s -> s.withColor(0xa9c9e9)));
                    if (i == 0 && Set.of("ItemStackBlock", "ProgressBlock").contains(block.type())) result.add(Component.empty());
                }
                markdown(block.text(), result, 0xdbe3ef);
            }
            default -> markdown(block.text(), result, "ErrorBlock".equals(block.type()) ? 0xffb0a0 : 0xdbe3ef);
        }
        for (String field : List.of("source", "version", "retrievedAt"))
            if (data.has(field)) result.add(Component.literal(field + ": " + data.get(field).getAsString()).withStyle(s -> s.withColor(0x94a4b6)));
        result.add(Component.empty());
        return List.copyOf(result);
    }
    private static MutableComponent tableRow(JsonArray cells) {
        MutableComponent result = Component.literal("│ ");
        for (var cell : cells) result.append(inline(cell.getAsString())).append(" │ ");
        return result;
    }
    private static void markdown(String text, List<Component> output, int color) {
        boolean code = false;
        for (String line : text.split("\\n", -1)) {
            if (line.stripLeading().startsWith("```")) {
                code = !code;
                output.add(Component.literal(code ? "┌ " + line.strip().substring(3) : "└").withStyle(s -> s.withColor(0x90b9da)));
            } else if (code) output.add(Component.literal("  " + line).withStyle(s -> s.withColor(0xa9c9e9)));
            else if (line.matches("#{1,6} .*")) output.add(inline(line.replaceFirst("^#{1,6} ", "")).withStyle(s -> s.withBold(true).withColor(0xefcf88)));
            else if (line.startsWith("> ")) output.add(inline("│ " + line.substring(2)).withStyle(s -> s.withItalic(true).withColor(0xaebcc7)));
            else if (line.matches("\\s*\\|?[ :|-]{3,}\\|?\\s*")) output.add(Component.literal("────────────────").withStyle(s -> s.withColor(0x657c90)));
            else output.add(inline(line.replaceFirst("^\\s*[-*] \\[x] ", "☑ ").replaceFirst("^\\s*[-*] \\[ ] ", "☐ ")
                    .replaceFirst("^\\s*[-*] ", "• ")).withStyle(s -> s.withColor(color)));
        }
    }
    private static MutableComponent inline(String value) {
        MutableComponent output = Component.empty();
        var matcher = INLINE.matcher(value);
        int cursor = 0;
        while (matcher.find()) {
            output.append(Component.literal(value.substring(cursor, matcher.start())));
            if (matcher.group(1) != null && ResponseBlocks.safeUrl(matcher.group(2))) {
                output.append(Component.literal(matcher.group(1)).withStyle(s -> s.withUnderlined(true).withColor(0x8fc7f0)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, matcher.group(2)))));
            } else if (matcher.group(3) != null) output.append(Component.literal(matcher.group(3)).withStyle(s -> s.withBold(true)));
            else output.append(Component.literal(matcher.group(4) == null ? matcher.group() : matcher.group(4))
                    .withStyle(s -> s.withColor(0xa9c9e9)));
            cursor = matcher.end();
        }
        return output.append(Component.literal(value.substring(cursor)));
    }
}
