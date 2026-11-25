package firefly520.fireflymc.ai.integration.equipment;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.moddiscovery.ModFile;

/**
 * Signature-pinned read infrastructure for the equipment/maid adapters.
 * Call only after OwnedPlayerAccess. It never makes a field writable or writes native state.
 */
public final class ReadOnlyNativeAccess {
    public enum State { ABSENT, STATIC_API_MATCH, OBSERVED, NOT_READY, UNSUPPORTED_VERSION, REFUSED, API_MISMATCH }
    public record AdapterStatus(String modId, String version, State state, String reason, String evidenceId) {
        public boolean behaviorVerified() { return false; }
        public boolean runtimeHashVerified() { return false; }
        public AdapterStatus with(State next, String reason) {
            return new AdapterStatus(modId, version, next, reason, evidenceId);
        }
    }
    public record ItemObservation(String itemId, int count, int damage, int maximumDamage,
                                  String componentsDigest) {
        public boolean consumptionAuthorized() { return false; }
    }

    private final Map<String, Class<?>> classes = new HashMap<>();
    private final Map<String, MethodHandle> methods = new HashMap<>();
    private final Map<String, Field> fields = new HashMap<>();

    public AdapterStatus version(String id, String expected, String expectedManifest, String evidence) {
        ModList mods = ModList.get();
        if (mods == null || !mods.isLoaded(id)) return new AdapterStatus(id, "", State.ABSENT, "MOD_ABSENT", evidence);
        String actual = mods.getModContainerById(id).map(mod -> mod.getModInfo().getVersion().toString()).orElse("");
        if (!expected.equals(actual))
            return new AdapterStatus(id, actual, State.UNSUPPORTED_VERSION, "UNSUPPORTED_MOD_VERSION", evidence);
        if (expectedManifest != null) {
            var info = mods.getModFileById(id);
            if (info == null || !(info.getFile() instanceof ModFile file)
                    || !expectedManifest.equals(file.getJarVersion().toString()))
                return new AdapterStatus(id, actual, State.UNSUPPORTED_VERSION, "UNSUPPORTED_MOD_BUILD", evidence);
        }
        return new AdapterStatus(id, actual, State.STATIC_API_MATCH, "SIGNATURES_NOT_YET_INVOKED", evidence);
    }

    public boolean loaded(String id) { return ModList.get() != null && ModList.get().isLoaded(id); }

    public Class<?> type(String name) throws ClassNotFoundException {
        Class<?> value = classes.get(name);
        if (value == null) {
            value = Class.forName(name, false, ReadOnlyNativeAccess.class.getClassLoader());
            classes.put(name, value);
        }
        return value;
    }

    public Object call(Object receiver, String owner, String name, Class<?> result,
                       Class<?>[] parameterTypes, Object... arguments) throws ReflectiveOperationException {
        String key = owner + "#" + name + MethodType.methodType(result, parameterTypes) + "/" + (receiver == null);
        MethodHandle handle = methods.get(key);
        if (handle == null) {
            MethodType signature = MethodType.methodType(result, parameterTypes);
            handle = receiver == null ? MethodHandles.publicLookup().findStatic(type(owner), name, signature)
                    : MethodHandles.publicLookup().findVirtual(type(owner), name, signature);
            methods.put(key, handle);
        }
        List<Object> inputs = new ArrayList<>(arguments.length + 1);
        if (receiver != null) inputs.add(receiver);
        java.util.Collections.addAll(inputs, arguments);
        try {
            return handle.invokeWithArguments(inputs);
        } catch (Error error) {
            throw error;
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            throw new ReflectiveOperationException("NATIVE_READ_FAILED", error);
        }
    }

    public Object query(Object receiver, String owner, String name, Class<?> result) throws ReflectiveOperationException {
        return call(receiver, owner, name, result, new Class<?>[0]);
    }

    /**
     * Visibility is explicit: PUBLIC, PROTECTED, PRIVATE, or 0 for package visibility.
     * Only inspected pending-state fields use non-public access; refusal is the fallback.
     */
    public Object field(Object receiver, String owner, String name, Class<?> type, int visibility)
            throws ReflectiveOperationException {
        String key = owner + "#" + name;
        Field field = fields.get(key);
        if (field == null) {
            field = type(owner).getDeclaredField(name);
            int actual = field.getModifiers() & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE);
            if (field.getType() != type || actual != visibility
                    || Modifier.isStatic(field.getModifiers()) != (receiver == null))
                throw new NoSuchFieldException(key);
            if (visibility != Modifier.PUBLIC && !field.trySetAccessible()) throw new IllegalAccessException(key);
            fields.put(key, field);
        }
        return field.get(receiver);
    }

    public static ItemObservation item(ItemStack stack) {
        Objects.requireNonNull(stack);
        // No native stack is retained: observations are safe to serialize and cannot leak private
        // book/custom data or mutate a component through ItemStack.copy()'s shallow component aliases.
        return new ItemObservation(stack.isEmpty() ? "minecraft:air" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                stack.getCount(), stack.getDamageValue(), stack.getMaxDamage(),
                digest(stack.getComponents().toString()));
    }

    public static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static String ownSlotId(UUID owner, String adapter, String slot, int index) {
        return owner + "/" + adapter + "/" + slot + "/" + index;
    }

    public static void require(boolean condition, String reason) {
        if (!condition) throw new ReadRefused(reason);
    }

    public static final class ReadRefused extends RuntimeException {
        public ReadRefused(String reason) { super(reason); }
    }
}
