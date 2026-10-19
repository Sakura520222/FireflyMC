package firefly520.fireflymc.story.progress;

import firefly520.fireflymc.story.condition.StoryCondition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.common.util.INBTSerializable;

/** Overworld-backed shared story flags and variables; all access is owner-thread confined. */
public final class WorldStoryState extends SavedData
    implements INBTSerializable<CompoundTag> {
    public static final String DATA_NAME = "fireflymc_story_world";

    private static final SavedData.Factory<WorldStoryState> FACTORY = new SavedData.Factory<>(
        WorldStoryState::new,
        WorldStoryState::load,
        null
    );

    private final Map<String, Boolean> flags = new LinkedHashMap<>();
    private final Map<String, Integer> vars = new LinkedHashMap<>();
    private final Thread owner;

    public WorldStoryState() {
        owner = Thread.currentThread();
    }

    public static WorldStoryState get(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        if (!server.isSameThread()) {
            throw new IllegalThreadStateException(
                "WorldStoryState must be resolved on the server thread");
        }
        WorldStoryState state = server.overworld().getDataStorage().computeIfAbsent(
            FACTORY,
            DATA_NAME
        );
        state.requireOwnerThread();
        return state;
    }

    private static WorldStoryState load(CompoundTag tag, HolderLookup.Provider provider) {
        WorldStoryState state = new WorldStoryState();
        state.deserializeNBT(provider, tag);
        return state;
    }

    public boolean getFlag(String name) {
        requireOwnerThread();
        requireStateName(name);
        return flags.getOrDefault(name, false);
    }

    public boolean setFlag(String name, boolean value) {
        requireOwnerThread();
        requireStateName(name);
        if (vars.containsKey(name)) {
            throw new IllegalArgumentException("name is already used by a variable: " + name);
        }
        Boolean previous = flags.get(name);
        boolean changed;
        if (previous == null) {
            changed = value;
        } else {
            changed = previous.booleanValue() != value;
        }
        if (!changed) {
            return false;
        }
        flags.put(name, value);
        setDirty();
        return true;
    }

    public int getVar(String name) {
        requireOwnerThread();
        requireStateName(name);
        return vars.getOrDefault(name, 0);
    }

    public boolean setVar(String name, int value, int min, int max) {
        requireOwnerThread();
        requireStateName(name);
        if (min > max) {
            throw new IllegalArgumentException("min must not exceed max");
        }
        if (flags.containsKey(name)) {
            throw new IllegalArgumentException("name is already used by a flag: " + name);
        }
        int changedValue = clamp(value, min, max);
        int previous = getVar(name);
        if (changedValue == previous) {
            return false;
        }
        vars.put(name, changedValue);
        setDirty();
        return true;
    }

    public boolean addVar(String name, int amount, int min, int max) {
        requireOwnerThread();
        requireStateName(name);
        if (min > max) {
            throw new IllegalArgumentException("min must not exceed max");
        }
        if (flags.containsKey(name)) {
            throw new IllegalArgumentException("name is already used by a flag: " + name);
        }
        int changedValue = clamp((long) getVar(name) + (long) amount, min, max);
        int previous = getVar(name);
        if (changedValue == previous) {
            return false;
        }
        vars.put(name, changedValue);
        setDirty();
        return true;
    }

    public Map<String, Boolean> flags() {
        requireOwnerThread();
        Map<String, Boolean> result = new TreeMap<>();
        result.putAll(flags);
        return Collections.unmodifiableMap(result);
    }

    public Map<String, Integer> vars() {
        requireOwnerThread();
        Map<String, Integer> result = new TreeMap<>();
        result.putAll(vars);
        return Collections.unmodifiableMap(result);
    }

    public void clear() {
        requireOwnerThread();
        if (flags.isEmpty() && vars.isEmpty()) {
            return;
        }
        flags.clear();
        vars.clear();
        setDirty();
    }

    @Override
    public void setDirty() {
        requireOwnerThread();
        super.setDirty();
    }

    @Override
    public void setDirty(boolean dirty) {
        requireOwnerThread();
        super.setDirty(dirty);
    }

    @Override
    public boolean isDirty() {
        requireOwnerThread();
        return super.isDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        requireOwnerThread();
        Objects.requireNonNull(tag, "tag");
        return serializeNBT(registries);
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        requireOwnerThread();
        Objects.requireNonNull(provider, "provider");
        CompoundTag root = new CompoundTag();
        CompoundTag flagTag = new CompoundTag();
        flags().forEach((name, value) -> flagTag.putInt(name, value ? 1 : 0));
        root.put("flags", flagTag);
        CompoundTag varTag = new CompoundTag();
        vars().forEach(varTag::putInt);
        root.put("vars", varTag);
        return root;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        requireOwnerThread();
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(tag, "tag");

        CompoundTag flagTag = optionalCompound(tag, "flags", "flags");
        Map<String, Boolean> parsedFlags = new LinkedHashMap<>();
        for (String name : sortedNames(flagTag)) {
            requireStateName(name);
            if (flagTag.getTagType(name) != Tag.TAG_INT) {
                throw new IllegalArgumentException("flags." + name + " must be an int");
            }
            parsedFlags.put(name, flagTag.getInt(name) != 0);
        }

        CompoundTag varTag = optionalCompound(tag, "vars", "vars");
        Map<String, Integer> parsedVars = new LinkedHashMap<>();
        for (String name : sortedNames(varTag)) {
            requireStateName(name);
            if (varTag.getTagType(name) != Tag.TAG_INT) {
                throw new IllegalArgumentException("vars." + name + " must be an int");
            }
            if (parsedFlags.containsKey(name)) {
                throw new IllegalArgumentException("flag and variable names collide: " + name);
            }
            parsedVars.put(name, varTag.getInt(name));
        }

        flags.clear();
        flags.putAll(parsedFlags);
        vars.clear();
        vars.putAll(parsedVars);
    }

    private void requireOwnerThread() {
        Thread current = Thread.currentThread();
        if (owner != current) {
            throw new IllegalThreadStateException(
                "WorldStoryState access is confined to its server thread");
        }
    }

    private static int clamp(long value, int min, int max) {
        if (min > max) {
            throw new IllegalArgumentException("min must not exceed max");
        }
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return (int) value;
    }

    private static void requireStateName(String name) {
        if (!StoryCondition.isValidStateName(name)) {
            throw new IllegalArgumentException("invalid story state name: " + name);
        }
    }

    private static CompoundTag optionalCompound(
        CompoundTag parent,
        String name,
        String path
    ) {
        Tag value = parent.get(name);
        if (value == null) {
            return new CompoundTag();
        }
        if (!(value instanceof CompoundTag compound)) {
            throw new IllegalArgumentException(path + " must be a compound tag");
        }
        return compound;
    }

    private static List<String> sortedNames(CompoundTag tag) {
        List<String> names = new ArrayList<>(tag.getAllKeys());
        Collections.sort(names);
        return names;
    }
}
