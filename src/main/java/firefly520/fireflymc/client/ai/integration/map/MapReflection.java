package firefly520.fireflymc.client.ai.integration.map;

import java.lang.reflect.*;

final class MapReflection {
    private MapReflection() {}
    static Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(name, false, MapReflection.class.getClassLoader());
    }
    static Method method(Class<?> owner, String name, Class<?> result, Class<?>... arguments) throws ReflectiveOperationException {
        var method = owner.getMethod(name, arguments);
        if (method.getReturnType() != result) throw new NoSuchMethodException("MAP_METHOD_SIGNATURE_CHANGED");
        return method;
    }
    static Method privateMethod(Class<?> owner, String name, Class<?> result) throws ReflectiveOperationException {
        var method = owner.getDeclaredMethod(name);
        if (method.getReturnType() != result || !method.trySetAccessible()) throw new NoSuchMethodException("MAP_METHOD_NOT_ACCESSIBLE");
        return method;
    }
    static Field field(Class<?> owner, String name, Class<?> type) throws ReflectiveOperationException {
        var field = owner.getDeclaredField(name);
        if (field.getType() != type || !field.trySetAccessible()) throw new NoSuchFieldException("MAP_FIELD_SIGNATURE_CHANGED");
        return field;
    }
    static Object call(Method method, Object owner, Object... args) throws ReflectiveOperationException {
        try { return method.invoke(owner, args); }
        catch (InvocationTargetException failure) { throw new ReflectiveOperationException("MAP_NATIVE_INVOCATION_FAILED", failure.getCause()); }
    }
    static Object nonnull(Object value) {
        if (value == null) throw new IllegalStateException("MAP_NATIVE_CONTEXT_NOT_READY"); return value;
    }
}
