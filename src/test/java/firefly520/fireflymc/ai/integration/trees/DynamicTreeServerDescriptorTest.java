package firefly520.fireflymc.ai.integration.trees;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.invoke.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

class DynamicTreeServerDescriptorTest {
    @Test void resolvesAuditedServerMethodEvenWhenUnrelatedClientSignatureIsUnavailable() throws Throwable {
        // DT SoilBlock has exactly this class-loading shape on a dedicated distribution:
        // its selected server method is valid, but another method mentions absent BlockColors.
        Class<?> reflective = fixture();
        assertThrows(NoClassDefFoundError.class, () -> reflective.getMethod("selected"));

        Class<?> audited = fixture();
        Object receiver = MethodHandles.publicLookup().findConstructor(audited, MethodType.methodType(void.class)).invoke();
        assertEquals("server-safe", DynamicTreeAccess.invokeVirtual(audited, "selected", String.class, receiver, new Class<?>[0]));
    }

    private static Class<?> fixture() {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "fixture/OptionalSoil", null, "java/lang/Object", null);
        var constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode(); constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN); constructor.visitMaxs(1, 1); constructor.visitEnd();
        var selected = writer.visitMethod(Opcodes.ACC_PUBLIC, "selected", "()Ljava/lang/String;", null, null);
        selected.visitCode(); selected.visitLdcInsn("server-safe"); selected.visitInsn(Opcodes.ARETURN);
        selected.visitMaxs(1, 1); selected.visitEnd();
        var client = writer.visitMethod(Opcodes.ACC_PUBLIC, "clientColor", "(Lfixture/ClientOnlyColor;)I", null, null);
        client.visitCode(); client.visitInsn(Opcodes.ICONST_0); client.visitInsn(Opcodes.IRETURN);
        client.visitMaxs(1, 2); client.visitEnd(); writer.visitEnd();
        byte[] bytes = writer.toByteArray();
        return new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            Class<?> create() { return defineClass("fixture.OptionalSoil", bytes, 0, bytes.length); }
        }.create();
    }
}
