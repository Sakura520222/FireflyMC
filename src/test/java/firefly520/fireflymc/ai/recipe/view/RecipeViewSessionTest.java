package firefly520.fireflymc.ai.recipe.view;
import org.junit.jupiter.api.Test;
import java.util.*;
import static firefly520.fireflymc.ai.recipe.view.RecipeViewData.*;
import static org.junit.jupiter.api.Assertions.*;
class RecipeViewSessionTest {
    @Test void referencesCannotCrossConnectionGenerationOrRuntimeReload() {
        var session = new RecipeViewSession<String>();
        Object connection = new Object(), runtime = new Object();
        var authority = new Authority("server", 3);
        session.bind(connection, authority); session.observeRuntime(Viewer.EMI, runtime);
        String key = session.remember(Viewer.EMI, "native reference"); long epoch = session.epoch();
        assertEquals("native reference", session.resolve(connection, authority, Viewer.EMI, key, epoch));
        assertThrows(IllegalStateException.class, () -> session.resolve(new Object(), authority, Viewer.EMI, key, epoch));
        assertThrows(IllegalStateException.class, () -> session.resolve(connection, new Authority("server", 4), Viewer.EMI, key, epoch));
        session.observeRuntime(Viewer.EMI, new Object());
        assertThrows(IllegalStateException.class, () -> session.resolve(connection, authority, Viewer.EMI, key, epoch));
    }
    @Test void latePagesCannotRevertGenerationButNewConnectionsCanRestartIt() {
        var session = new RecipeViewSession<String>(); Object connection = new Object();
        session.bind(connection, new Authority("server", 10));
        assertFalse(session.bind(connection, new Authority("server", 2)));
        assertEquals(10, session.authority().recipeGeneration());
        assertTrue(session.bind(new Object(), new Authority("server", 1)));
        assertEquals(1, session.authority().recipeGeneration());
    }
    @Test void clientCandidatesStayNonAuthoritativeAndOnlySharedNativeIdsDeduplicateAcrossViewers() {
        var authority = new Authority("server", 2);
        var jei = new Candidate(Viewer.JEI, "minecraft:crafting", "minecraft:sticks", "minecraft:sticks", "a", List.of(), List.of(), false, false);
        var emi = new Candidate(Viewer.EMI, "emi:crafting", "emi:generated/sticks", "minecraft:sticks", "b", List.of(), List.of(), false, false);
        assertEquals(jei.deduplicationKey(authority), emi.deduplicationKey(authority));
        assertThrows(IllegalArgumentException.class, () -> new Candidate(Viewer.EMI, "emi:test", "", "", "key", List.of(), List.of(), false, true));
        var loose = new Candidate(Viewer.EMI, "emi:custom", "emi:sticks", "", "c", List.of(), List.of(), false, false);
        assertNotEquals(jei.deduplicationKey(authority), loose.deduplicationKey(authority));
        assertThrows(IllegalArgumentException.class, () -> new CandidateReport(authority, Viewer.EMI, 1, List.of(loose), -1, false, "SERVER_RUNTIME"));
    }
}
