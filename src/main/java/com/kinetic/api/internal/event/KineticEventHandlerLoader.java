package com.kinetic.api.internal.event;

import com.kinetic.Kinetic;
import com.kinetic.api.annotation.KineticEventHandler;
import java.lang.annotation.ElementType;
import java.util.List;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.forgespi.language.ModFileScanData;

/** Internal Forge discovery and registration bridge for {@link KineticEventHandler}. */
public final class KineticEventHandlerLoader {
    private static final String ANNOTATION_NAME = KineticEventHandler.class.getName();

    private KineticEventHandlerLoader() {
    }

    public static void attach(IEventBus eventBus) {
        eventBus.addListener((FMLLoadCompleteEvent event) -> registerAll(eventBus));
    }

    private static void registerAll(IEventBus eventBus) {
        List<String> handlerNames;
        try {
            handlerNames = discoverHandlerNames();
        } catch (RuntimeException | LinkageError exception) {
            Kinetic.LOGGER.error("Failed to discover @KineticEventHandler classes", exception);
            return;
        }

        ClassLoader loader = KineticEventHandlerLoader.class.getClassLoader();
        if (loader == null) {
            loader = ClassLoader.getSystemClassLoader();
        }
        for (String handlerName : handlerNames) {
            registerOne(eventBus, handlerName, loader);
        }
    }

    private static List<String> discoverHandlerNames() {
        return discoverHandlerNames(ModList.get().getAllScanData());
    }

    static List<String> discoverHandlerNames(List<ModFileScanData> scanData) {
        return scanData.stream()
                .flatMap(fileScan -> fileScan.getAnnotations().stream())
                .filter(annotation -> annotation.targetType() == ElementType.TYPE)
                .filter(annotation -> ANNOTATION_NAME.equals(
                        annotation.annotationType().getClassName()))
                .map(ModFileScanData.AnnotationData::memberName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    private static void registerOne(
            IEventBus eventBus,
            String handlerName,
            ClassLoader loader) {
        KineticEventHandlerRegistration.Outcome outcome;
        try {
            outcome = KineticEventHandlerRegistration.prepare(handlerName, loader);
        } catch (KineticEventHandlerRegistration.Failure failure) {
            if (failure.stage() == KineticEventHandlerRegistration.Stage.CHECKER_MISSING) {
                Kinetic.LOGGER.error(
                        "Skipping @KineticEventHandler {} because checker {} is missing. "
                                + "Add the classifierless KINETIC JAR to annotationProcessor.",
                        handlerName, failure.subject());
            } else {
                Kinetic.LOGGER.error(
                        "Skipping @KineticEventHandler {}: {} ({})",
                        handlerName, failure.getMessage(), failure.subject(), failure.getCause());
            }
            return;
        }

        if (!outcome.shouldRegister()) {
            Kinetic.LOGGER.warn(
                    "Skipping @KineticEventHandler {}: status={}, coverageComplete={}, issues={}",
                    handlerName, outcome.status(), outcome.coverageComplete(), outcome.issues());
            return;
        }
        if (outcome.hasWarnings()) {
            Kinetic.LOGGER.warn(
                    "Registering @KineticEventHandler {} with compatibility warnings: "
                            + "status={}, coverageComplete={}, issues={}",
                    handlerName, outcome.status(), outcome.coverageComplete(), outcome.issues());
        }

        try {
            eventBus.register(outcome.handler());
            Kinetic.LOGGER.info("Registered @KineticEventHandler {}", handlerName);
        } catch (RuntimeException | LinkageError exception) {
            Kinetic.LOGGER.error(
                    "Failed to register @KineticEventHandler {}; continuing without it",
                    handlerName, exception);
        }
    }
}
