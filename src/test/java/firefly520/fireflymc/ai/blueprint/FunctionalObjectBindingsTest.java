package firefly520.fireflymc.ai.blueprint;

import firefly520.fireflymc.ai.storage.AiJson;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FunctionalObjectBindingsTest {
    private final UUID server = UUID.randomUUID(), conversation = UUID.randomUUID(), plan = UUID.randomUUID();
    private final String owner = UUID.randomUUID().toString(), hash = AiJson.digest("fixed-native-plan");
    private FunctionalObjectBindings.Scope scope(long privacy) {
        return new FunctionalObjectBindings.Scope(server, conversation, owner, privacy, plan, hash, "minecraft:overworld");
    }
    @Test void samePositionReplacementCannotInheritOrRebindAnOlderNativeIdentity() {
        var bindings = new FunctionalObjectBindings<Object>(4, 8); var actual = new Object();
        bindings.bind(scope(0), 123, actual);
        assertSame(actual, bindings.get(scope(0), 123));
        assertNull(bindings.get(scope(0), 124));
        var replacement = new Object();
        assertNotSame(replacement, bindings.get(scope(0), 123));
        bindings.bind(scope(0), 123, replacement);
        assertNull(bindings.get(scope(0), 123));
        bindings.bind(scope(0), 123, replacement);
        assertNull(bindings.get(scope(0), 123));
    }
    @Test void serverConversationOwnerPrivacyAndFixedPlanHashRemainIndependentBoundaries() {
        var bindings = new FunctionalObjectBindings<Object>(4, 8); var actual = new Object(); var scope = scope(2);
        bindings.bind(scope, 123, actual);
        var changed = List.of(new FunctionalObjectBindings.Scope(UUID.randomUUID(), conversation, owner, 2, plan, hash, "minecraft:overworld"),
                new FunctionalObjectBindings.Scope(server, UUID.randomUUID(), owner, 2, plan, hash, "minecraft:overworld"),
                new FunctionalObjectBindings.Scope(server, conversation, UUID.randomUUID().toString(), 2, plan, hash, "minecraft:overworld"),
                scope(3), new FunctionalObjectBindings.Scope(server, conversation, owner, 2, plan, AiJson.digest("changed-plan"), "minecraft:overworld"),
                new FunctionalObjectBindings.Scope(server, conversation, owner, 2, plan, hash, "minecraft:the_nether"));
        for (var other : changed) assertNull(bindings.get(other, 123));
        assertSame(actual, bindings.get(scope, 123));
    }
    @Test void boundedEvictionAndExplicitPrivacyErasureWithdrawReadObservationsWithoutReconstructingThem() {
        var bindings = new FunctionalObjectBindings<Object>(2, 2);
        var first = new Object(); var second = new Object(); var third = new Object();
        bindings.bind(scope(0), 1, first); bindings.bind(scope(1), 2, second); bindings.bind(scope(2), 3, third);
        assertNull(bindings.get(scope(0), 1)); assertSame(second, bindings.get(scope(1), 2)); assertSame(third, bindings.get(scope(2), 3));
        bindings.forgetOwner(owner); assertNull(bindings.get(scope(1), 2)); assertNull(bindings.get(scope(2), 3));
        bindings.bind(scope(3), 4, first); bindings.clear(); assertNull(bindings.get(scope(3), 4));
    }
}
