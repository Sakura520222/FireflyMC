package firefly520.fireflymc.ai;

import firefly520.fireflymc.ai.presentation.ResponseBlocks;
import firefly520.fireflymc.ai.storage.AiJson;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResponseBlocksTest {
    @Test void longStructuredMessagesKeepValidBoundedPreviewsAndUnmodifiedFullEvidence() {
        var steps = new com.google.gson.JsonArray();
        for (int i = 0; i < 32; i++) {
            var step = new com.google.gson.JsonObject(); step.addProperty("label", "step-" + i + " " + "long detail ".repeat(10));
            step.addProperty("status", "PENDING"); steps.add(step);
        }
        var value = new com.google.gson.JsonObject(); value.addProperty("type", "StepTimeline"); value.add("steps", steps);
        var blockArray = new com.google.gson.JsonArray(); blockArray.add(value);
        var root = new com.google.gson.JsonObject(); root.addProperty("firefly_ui", 1); root.add("blocks", blockArray);
        String original = root.toString(); assertTrue(original.length() > 600);
        var preview = ResponseBlocks.preview(original, 1800); assertTrue(preview.toString().length() <= 1800);
        assertEquals("StepTimeline", ResponseBlocks.parse(ResponseBlocks.document(preview)).getFirst().type());
        assertEquals(6, preview.get(0).getAsJsonObject().getAsJsonObject("data").getAsJsonArray("steps").size());
        assertEquals(32, ResponseBlocks.parse(original).getFirst().data().getAsJsonArray("steps").size());
        assertTrue(ResponseBlocks.plainText(original).contains("step-31"));
        assertTrue(ResponseBlocks.preview("ordinary literal text", 1800).isEmpty());
    }
    @Test void oversizedLinksAreNeverShortenedIntoADifferentClickableDestination() {
        String url = "https://example.org/" + "a".repeat(1500);
        String original = "{\"firefly_ui\":1,\"blocks\":[{\"type\":\"SourceLink\",\"url\":\"" + url + "\",\"text\":\"source\"}]}";
        var preview = ResponseBlocks.preview(original, 400);
        assertEquals("InfoCard", ResponseBlocks.parse(ResponseBlocks.document(preview)).getFirst().type());
        assertFalse(preview.toString().contains("https://")); assertTrue(ResponseBlocks.plainText(original).contains(url));
    }
    @Test void itemRecipeAndPlanComponentsPreserveVisibleDetailsForPrivateDisplayAndExplicitSharing() {
        String content = """
                {"firefly_ui":1,"blocks":[
                {"type":"ItemStackBlock","title":"材料建议","itemId":"minecraft:oak_planks","count":120,"text":"以服务端盘点为准"},
                {"type":"RecipeBlock","recipeId":"minecraft:crafting_table","text":"点击后重新查询当前配方"},
                {"type":"ProgressBlock","current":2,"total":5,"text":"描述进度不是已授权执行"},
                {"type":"StepTimeline","steps":[{"label":"核验物料","status":"DONE"},{"label":"审批施工","status":"PENDING"}],"text":"等待实际工作流"}]}
                """;
        var blocks = ResponseBlocks.parse(content); assertEquals(4, blocks.size());
        assertEquals(blocks, ResponseBlocks.parse(ResponseBlocks.document(AiJson.GSON.toJsonTree(blocks).getAsJsonArray())));
        String visible = ResponseBlocks.plainText(content);
        for (String expected : new String[]{"minecraft:oak_planks ×120", "minecraft:crafting_table", "2 / 5", "核验物料", "审批施工", "实际执行状态见看板"})
            assertTrue(visible.contains(expected), expected);
        assertFalse(visible.contains("firefly_ui")); assertFalse(visible.contains("ItemStackBlock"));
    }
    @Test void displayCountsCannotRoundOverflowOrSmuggleArbitraryStackComponents() {
        for (String count : new String[]{"0", "-1", "1.1", "1e100", "1000001", "true", "\"12\""}) {
            String document = "{\"firefly_ui\":1,\"blocks\":[{\"type\":\"ItemStackBlock\",\"itemId\":\"minecraft:stone\",\"count\":" + count + "}]}";
            assertEquals(document, ResponseBlocks.parse(document).getFirst().text(), count);
        }
        for (String injected : new String[]{"\"components\":{\"arbitrary_mod:handler\":{}}", "\"command\":\"/give @s stone\"", "\"approved\":true"}) {
            String document = "{\"firefly_ui\":1,\"blocks\":[{\"type\":\"ItemStackBlock\",\"itemId\":\"minecraft:stone\",\"count\":1," + injected + "}]}";
            assertEquals(document, ResponseBlocks.parse(document).getFirst().text());
        }
    }
    @Test void recipeReferencesAreOnlyBoundedResourceIdsAndCannotBecomeExecutionControls() {
        for (String id : new String[]{"/give @s stone", "file:///secret", "https://example.org", "minecraft:" + "a".repeat(160)}) {
            String document = "{\"firefly_ui\":1,\"blocks\":[{\"type\":\"RecipeBlock\",\"recipeId\":\"" + id + "\"}]}";
            assertEquals(document, ResponseBlocks.parse(document).getFirst().text());
        }
        String action = "{\"firefly_ui\":1,\"blocks\":[{\"type\":\"RecipeBlock\",\"recipeId\":\"minecraft:crafting_table\",\"operationId\":\"forged\"}]}";
        assertEquals(action, ResponseBlocks.parse(action).getFirst().text());
    }
    @Test void progressAndNestedTimelineNeverAcceptApprovalOrUnboundedValues() {
        for (String value : new String[]{"{\"type\":\"ProgressBlock\",\"current\":6,\"total\":5}",
                "{\"type\":\"ProgressBlock\",\"current\":0,\"total\":0}",
                "{\"type\":\"StepTimeline\",\"steps\":[{\"label\":\"run\",\"status\":\"DONE\",\"action\":\"approve\"}]}",
                "{\"type\":\"StepTimeline\",\"steps\":[{\"label\":\"run\",\"status\":\"APPROVED\"}]}"}) {
            String document = "{\"firefly_ui\":1,\"blocks\":[" + value + "]}";
            assertEquals(document, ResponseBlocks.parse(document).getFirst().text());
        }
    }
    @Test void validatedNativeComponentsRoundTripAndShareOnlyVisibleContent() {
        String content = """
                {"firefly_ui":1,"blocks":[
                {"type":"InfoCard","title":"核验结果","text":"需要真实材料"},
                {"type":"TableBlock","columns":["材料","数量"],"rows":[["石头","12"]]},
                {"type":"SourceLink","title":"官方来源","url":"https://example.org/docs","version":"1.21.1","text":"需核对当前版本"}]}
                """;
        var blocks = ResponseBlocks.parse(content);
        assertEquals(3, blocks.size());
        assertEquals(blocks, ResponseBlocks.parse(ResponseBlocks.document(AiJson.GSON.toJsonTree(blocks).getAsJsonArray())));
        var visible = ResponseBlocks.plainText(content);
        assertTrue(visible.contains("核验结果"));
        assertTrue(visible.contains("石头"));
        assertTrue(visible.contains("https://example.org/docs"));
        assertFalse(visible.contains("firefly_ui"));
        assertFalse(visible.contains("SourceLink"));
    }
    @Test void modelCannotCreateApprovalCommandsScriptLinksOrArbitraryActionFields() {
        for (String malicious : new String[] {
                "{\"firefly_ui\":1,\"blocks\":[{\"type\":\"ApprovalPrompt\",\"text\":\"approved\"}]}",
                "{\"firefly_ui\":1,\"blocks\":[{\"type\":\"InfoCard\",\"text\":\"safe\",\"command\":\"/give\"}]}",
                "{\"firefly_ui\":1,\"blocks\":[{\"type\":\"SourceLink\",\"url\":\"javascript:alert(1)\"}]}"
        }) {
            var blocks = ResponseBlocks.parse(malicious);
            assertEquals(1, blocks.size());
            assertEquals("MarkdownBlock", blocks.getFirst().type());
            assertEquals(malicious, blocks.getFirst().text());
            assertTrue(blocks.getFirst().data().isEmpty());
        }
        assertFalse(ResponseBlocks.safeUrl("file:///C:/private.txt"));
        assertFalse(ResponseBlocks.safeUrl("https://user:password@example.com"));
    }
}
