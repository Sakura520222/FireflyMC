package firefly520.fireflymc.ai;

import firefly520.fireflymc.client.ai.workspace.SafeRichText;
import java.util.*;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SafeRichTextTest {
    @Test void nativeDisplayPreservesAdviceQuantityRecipeAndDescriptionWithoutCreatingActions() {
        String content = """
                {"firefly_ui":1,"blocks":[
                {"type":"ItemStackBlock","itemId":"minecraft:stone","count":130,"text":"需重新盘点"},
                {"type":"RecipeBlock","recipeId":"minecraft:crafting_table","text":"先查服务端"},
                {"type":"ProgressBlock","current":3,"total":8,"text":"只是计划描述"},
                {"type":"StepTimeline","steps":[{"label":"等待批准","status":"PENDING"}]}]}
                """;
        var lines = SafeRichText.lines(content);
        String visible = String.join("\n", lines.stream().map(Component::getString).toList());
        for (String expected : List.of("minecraft:stone ×130", "minecraft:crafting_table", "3 / 8", "需重新盘点", "等待批准", "实际执行状态见看板"))
            assertTrue(visible.contains(expected), expected);
        for (var line : lines) assertNull(line.getStyle().getClickEvent(), "display metadata is not a command or approval");
    }
    @Test void unknownApprovalAndScriptPayloadsStayLiteralInNativeComponents() {
        for (String text : List.of("<script>alert('approve')</script>",
                "{\"firefly_ui\":1,\"blocks\":[{\"type\":\"ConfirmAction\",\"text\":\"run\",\"command\":\"/give @s stone\"}]}",
                "[run](file:///secret) [approve](javascript:approve())")) {
            var lines = SafeRichText.lines(text); assertTrue(lines.stream().anyMatch(line -> line.getString().contains(text)));
            var remaining = new ArrayDeque<>(lines);
            while (!remaining.isEmpty()) {
                var component = remaining.removeFirst(); assertNull(component.getStyle().getClickEvent());
                remaining.addAll(component.getSiblings());
            }
        }
    }
    @Test void sourceLinkOnlyCarriesAnHttpBrowserIntentForTheExistingConfirmationFlow() {
        String content = "{\"firefly_ui\":1,\"blocks\":[{\"type\":\"SourceLink\",\"url\":\"https://example.org/docs\",\"version\":\"1.21.1\",\"text\":\"版本证据\"}]}";
        var lines = SafeRichText.lines(content);
        var link = lines.stream().map(Component::getStyle).map(style -> style.getClickEvent()).filter(Objects::nonNull).findFirst().orElseThrow();
        assertEquals(ClickEvent.Action.OPEN_URL, link.getAction()); assertEquals("https://example.org/docs", link.getValue());
        assertTrue(lines.stream().anyMatch(line -> line.getString().contains("1.21.1")));
    }
}
