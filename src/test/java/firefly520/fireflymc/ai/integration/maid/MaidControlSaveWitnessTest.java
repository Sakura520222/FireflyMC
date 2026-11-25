package firefly520.fireflymc.ai.integration.maid;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MaidControlSaveWitnessTest {
    final UUID owner = UUID.randomUUID(), maid = UUID.randomUUID();
    MaidControlPlan.NativeState expected() {
        return new MaidControlPlan.NativeState(maid, "minecraft:overworld", "touhou_little_maid:idle", "native.TaskIdle", 1,
                false, false, true, true, "DAY", 0, 0, 0, 8, false, false, false, false, 0, true);
    }
    CompoundTag tag() {
        var data = new CompoundTag(); data.putUUID("UUID", maid); data.putUUID("Owner", owner);
        data.putBoolean("Sitting", false); data.putBoolean("MaidIsHome", false); data.putBoolean("MaidIsPickup", true);
        data.putBoolean("MaidIsRideable", true); data.putString("MaidTask", "touhou_little_maid:idle"); data.putString("MaidScheduleMode", "DAY");
        return data;
    }
    @Test void onlyExactNativeOwnerModeTaskAndScheduleFieldsCanCarryTheWitness() {
        assertTrue(MaidControlSaveWitness.matches(tag(), owner, expected()));
        for (String key : java.util.List.of("Sitting", "MaidIsHome", "MaidIsPickup", "MaidIsRideable")) {
            var changed = tag(); changed.putBoolean(key, !changed.getBoolean(key)); assertFalse(MaidControlSaveWitness.matches(changed, owner, expected()), key);
        }
        for (String key : java.util.List.of("MaidTask", "MaidScheduleMode")) {
            var changed = tag(); changed.putString(key, "changed"); assertFalse(MaidControlSaveWitness.matches(changed, owner, expected()), key);
        }
        var changedOwner = tag(); changedOwner.putUUID("Owner", UUID.randomUUID()); assertFalse(MaidControlSaveWitness.matches(changedOwner, owner, expected()));
        var changedMaid = tag(); changedMaid.putUUID("UUID", UUID.randomUUID()); assertFalse(MaidControlSaveWitness.matches(changedMaid, owner, expected()));
    }
    @Test void missingWrongTypeAndUnrelatedPrivateAiDataCannotSupplyModeProof() {
        for (String key : java.util.List.of("UUID", "Owner", "Sitting", "MaidIsHome", "MaidIsPickup", "MaidIsRideable", "MaidTask", "MaidScheduleMode")) {
            var missing = tag(); missing.remove(key); assertFalse(MaidControlSaveWitness.matches(missing, owner, expected()), key);
        }
        var wrongType = tag(); wrongType.putString("MaidIsHome", "false"); assertFalse(MaidControlSaveWitness.matches(wrongType, owner, expected()));
        var privateData = tag(); privateData.putString("UnrelatedAiSettings", "opaque-test-value");
        assertTrue(MaidControlSaveWitness.matches(privateData, owner, expected()));
        assertFalse(MaidControlSaveWitness.matches(null, owner, expected()));
    }
}
