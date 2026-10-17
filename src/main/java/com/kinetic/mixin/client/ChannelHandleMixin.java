package com.kinetic.mixin.client;

import com.kinetic.client.sound.instance.HostAudioHandoff;
import com.kinetic.client.sound.instance.SoundChannelHandleExtension;
import net.minecraft.client.sounds.ChannelAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChannelAccess.ChannelHandle.class)
public class ChannelHandleMixin implements SoundChannelHandleExtension {
    @Unique
    private HostAudioHandoff kinetic$handoff;
    @Unique
    private boolean kinetic$released;

    @Override
    public void kinetic$bindHandoff(HostAudioHandoff handoff) {
        boolean released;
        synchronized (this) {
            if (kinetic$handoff != null && kinetic$handoff != handoff) {
                throw new IllegalStateException("Sound channel already has a handoff");
            }
            released = kinetic$released;
            if (!released) {
                kinetic$handoff = handoff;
            }
        }
        if (released) {
            handoff.hostReleased();
        }
    }

    @Inject(method = "release", at = @At("RETURN"))
    private void releaseAudioHandoff(CallbackInfo callback) {
        HostAudioHandoff handoff;
        synchronized (this) {
            kinetic$released = true;
            handoff = kinetic$handoff;
            kinetic$handoff = null;
        }
        if (handoff != null) {
            handoff.hostReleased();
        }
    }
}
