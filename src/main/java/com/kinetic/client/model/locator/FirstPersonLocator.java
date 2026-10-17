package com.kinetic.client.model.locator;

import com.kinetic.Kinetic;
import com.kinetic.api.model.v0.ModelKind;
import com.kinetic.api.model.v0.event.RegisterModelLocatorEvent;
import com.kinetic.geckolib3.geo.render.built.GeoLocator;
import com.kinetic.geckolib3.geo.render.built.GeoLocatorType;
import net.minecraft.resources.ResourceLocation;

public class FirstPersonLocator extends GeoLocatorType {
    private static FirstPersonLocator INSTANCE;

    public final GeoLocator leftArm = register("LeftArm");
    public final GeoLocator rightArm = register("RightArm");
    public final GeoLocator background = register("Background");

    @SuppressWarnings("removal")
    private FirstPersonLocator() {
        super(new ResourceLocation(Kinetic.MOD_ID, "first_person"));
    }

    public static void init() {
        if (INSTANCE == null) {
            synchronized (FirstPersonLocator.class) {
                if (INSTANCE == null) {
                    INSTANCE = new FirstPersonLocator();
                    Kinetic.postEvent(new RegisterModelLocatorEvent(ModelKind.HUMANOID_FIRST_PERSON, INSTANCE::register));
                    INSTANCE.freeze();
                }
            }
        }
    }

    public static FirstPersonLocator get() {
        if (INSTANCE != null) {
            return INSTANCE;
        }
        throw new IllegalStateException("PlayerLocator has not been initialized");
    }
}
