package com.kinetic.mixin.client;

import com.kinetic.client.sound.instance.HostAwareAudioStream;
import com.mojang.blaze3d.audio.Channel;
import net.minecraft.client.sounds.AudioStream;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Channel.class)
public class AudioChannelMixin {
    @Inject(method = "attachBufferStream", at = @At("HEAD"), cancellable = true)
    private void adoptModelAudio(AudioStream stream, CallbackInfo callback) {
        if (stream instanceof HostAwareAudioStream handoff
                && !handoff.kinetic$tryHostAdopt()) {
            callback.cancel();
        }
    }
}
