package com.kinetic.client.model.locator;

import com.kinetic.Kinetic;
import com.kinetic.api.model.v0.ModelKind;
import com.kinetic.api.model.v0.event.RegisterModelLocatorEvent;
import com.kinetic.geckolib3.geo.render.built.GeoLocatorType;
import net.minecraft.resources.ResourceLocation;

public class ProjectileLocator extends GeoLocatorType {
    private static ProjectileLocator INSTANCE;

    @SuppressWarnings("removal")
    private ProjectileLocator() {
        super(new ResourceLocation(Kinetic.MOD_ID, "projectile"));
    }

    public static void init() {
        if (INSTANCE == null) {
            synchronized (ProjectileLocator.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ProjectileLocator();
                    Kinetic.postEvent(new RegisterModelLocatorEvent(ModelKind.PROJECTILE, INSTANCE::register));
                    INSTANCE.freeze();
                }
            }
        }
    }

    public static ProjectileLocator get() {
        if (INSTANCE != null) {
            return INSTANCE;
        }
        throw new IllegalStateException("ProjectileLocator has not been initialized");
    }
}
