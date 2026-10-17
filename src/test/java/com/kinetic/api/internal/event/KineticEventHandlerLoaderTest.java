package com.kinetic.api.internal.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.kinetic.api.annotation.KineticEventHandler;
import java.lang.annotation.ElementType;
import java.util.List;
import java.util.Map;
import net.minecraftforge.forgespi.language.ModFileScanData;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

class KineticEventHandlerLoaderTest {
    @Test
    void selectsTypeAnnotationsByNameThenDeduplicatesAndSortsHandlers() {
        ModFileScanData first = new ModFileScanData();
        first.getAnnotations().add(annotation(
                ElementType.TYPE, "sample.ZHandler", KineticEventHandler.class));
        first.getAnnotations().add(annotation(
                ElementType.METHOD, "sample.Ignored#method", KineticEventHandler.class));
        first.getAnnotations().add(annotation(
                ElementType.TYPE, "sample.IgnoredAnnotation", Deprecated.class));

        ModFileScanData second = new ModFileScanData();
        second.getAnnotations().add(annotation(
                ElementType.TYPE, "sample.A$Nested", KineticEventHandler.class));
        second.getAnnotations().add(annotation(
                ElementType.TYPE, "sample.ZHandler", KineticEventHandler.class));

        assertEquals(List.of("sample.A$Nested", "sample.ZHandler"),
                KineticEventHandlerLoader.discoverHandlerNames(List.of(first, second)));
    }

    private static ModFileScanData.AnnotationData annotation(
            ElementType target,
            String memberName,
            Class<?> annotationType) {
        return new ModFileScanData.AnnotationData(
                Type.getType(annotationType), target, Type.getType(Object.class),
                memberName, Map.of());
    }
}
