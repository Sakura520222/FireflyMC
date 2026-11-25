package firefly520.fireflymc.ai.processing;
import firefly520.fireflymc.ai.material.MaterialStack;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MaterialProjectionTest {
    @Test void modelInventoryFactsExcludeBookContentsAndPrivateCustomData() {
        var source = new MaterialStack("""
                {"id":"minecraft:written_book","components":{"minecraft:written_book_content":{"pages":["private page"]},
                "minecraft:custom_data":{"password":"secret material data"},"minecraft:custom_name":"private name"}}
                """, 1);
        var view = VirtualProcessingService.publicMaterial(source);
        assertEquals("minecraft:written_book", view.get("id").getAsString());
        assertEquals(source.fingerprint(), view.get("componentsDigest").getAsString());
        assertTrue(view.get("customNamePresent").getAsBoolean());
        assertFalse(view.toString().contains("private page"));
        assertFalse(view.toString().contains("secret material data"));
        assertFalse(view.toString().contains("private name"));
        assertTrue(source.itemJson().contains("private page"), "Exact server escrow data is retained for conservation");
    }
}
