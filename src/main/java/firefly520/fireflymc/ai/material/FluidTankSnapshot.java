package firefly520.fireflymc.ai.material;

import firefly520.fireflymc.ai.storage.AiJson;
import java.util.*;
import net.minecraft.core.HolderLookup;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/** Snapshot of the whole routing backend. A native handler can drain several tanks in one call. */
public record FluidTankSnapshot(List<Tank> tanks) {
    public record Tank(int capacity, FluidAmount fluid) {
        public Tank {
            if (capacity < 1 || fluid == null || fluid.millibuckets() > capacity)
                throw new IllegalArgumentException("FLUID_TANK_STATE_INVALID");
        }
    }
    public FluidTankSnapshot {
        if (tanks == null || tanks.isEmpty() || tanks.size() > 64) throw new IllegalArgumentException("FLUID_TANK_LIMIT");
        tanks = List.copyOf(tanks);
        if (tanks.stream().mapToLong(tank -> tank.fluid().unitJson().length()).sum() > 1048576)
            throw new IllegalArgumentException("FLUID_COMPONENT_BUDGET");
    }
    public static FluidTankSnapshot capture(IFluidHandler handler, HolderLookup.Provider registries) {
        int size = handler.getTanks();
        if (size < 1 || size > 64) throw new IllegalArgumentException("FLUID_TANK_LIMIT");
        var tanks = new ArrayList<Tank>();
        for (int i = 0; i < size; i++) tanks.add(new Tank(handler.getTankCapacity(i),
                FluidSnapshotCodec.capture(handler.getFluidInTank(i), registries)));
        return new FluidTankSnapshot(tanks);
    }
    public String digest() { return AiJson.digest(AiJson.GSON.toJson(this)); }
    public long amount(String fingerprint) {
        return tanks.stream().map(Tank::fluid).filter(fluid -> fluid.fingerprint().equals(fingerprint))
                .mapToLong(FluidAmount::millibuckets).sum();
    }
    public Map<String, FluidAmount> kinds() {
        var result = new LinkedHashMap<String, FluidAmount>();
        tanks.stream().map(Tank::fluid).filter(fluid -> !fluid.empty())
                .forEach(fluid -> result.putIfAbsent(fluid.fingerprint(), fluid.withAmount(1)));
        return Collections.unmodifiableMap(result);
    }
    /** A verified delta cannot silently move other fluids, capacities or equal-amount tank contents. */
    public boolean verifies(FluidTankSnapshot after, FluidAmount actual, boolean filling) {
        if (after == null || after.tanks.size() != tanks.size()) return false;
        long changed = 0;
        for (int i = 0; i < tanks.size(); i++) {
            Tank before = tanks.get(i), next = after.tanks.get(i);
            if (before.capacity != next.capacity) return false;
            if (before.fluid.equals(next.fluid)) continue;
            if (actual.empty() || !before.fluid.empty() && !before.fluid.unitJson().equals(actual.unitJson())
                    || !next.fluid.empty() && !next.fluid.unitJson().equals(actual.unitJson())) return false;
            long delta = (long) next.fluid.millibuckets() - before.fluid.millibuckets();
            if (filling ? delta < 0 : delta > 0) return false;
            changed += delta;
        }
        return changed == (filling ? actual.millibuckets() : -actual.millibuckets());
    }
}
