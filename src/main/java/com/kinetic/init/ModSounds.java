package com.kinetic.init;

import com.kinetic.Kinetic;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

public class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, Kinetic.MOD_ID);

    public static final SoundEvent CUSTOM = registerSound("custom");

    private static SoundEvent registerSound(String name) {
        var ev = SoundEvent.createFixedRangeEvent(new ResourceLocation(Kinetic.MOD_ID, name), 16.0F);
        SOUNDS.register(name, () -> ev);
        return ev;
    }
}
