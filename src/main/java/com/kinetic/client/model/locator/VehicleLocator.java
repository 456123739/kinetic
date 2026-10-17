package com.kinetic.client.model.locator;

import com.kinetic.Kinetic;
import com.kinetic.api.model.v0.ModelKind;
import com.kinetic.api.model.v0.event.RegisterModelLocatorEvent;
import com.kinetic.geckolib3.geo.render.built.GeoLocator;
import com.kinetic.geckolib3.geo.render.built.GeoLocatorType;
import net.minecraft.resources.ResourceLocation;

public class VehicleLocator extends GeoLocatorType {
    private static VehicleLocator INSTANCE;

    public final GeoLocator passenger = register("PassengerLocator");

    @SuppressWarnings("removal")
    private VehicleLocator() {
        super(new ResourceLocation(Kinetic.MOD_ID, "vehicle"));
    }

    public static void init() {
        if (INSTANCE == null) {
            synchronized (VehicleLocator.class) {
                if (INSTANCE == null) {
                    INSTANCE = new VehicleLocator();
                    Kinetic.postEvent(new RegisterModelLocatorEvent(ModelKind.VEHICLE, INSTANCE::register));
                    INSTANCE.freeze();
                }
            }
        }
    }

    public static VehicleLocator get() {
        if (INSTANCE != null) {
            return INSTANCE;
        }
        throw new IllegalStateException("VehicleLocator has not been initialized");
    }
}
