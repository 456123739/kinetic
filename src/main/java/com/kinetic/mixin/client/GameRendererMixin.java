package com.kinetic.mixin.client;

import com.kinetic.natives.NativeProfiler;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Unique
    private boolean kinetic$profileFrameActive;

    @Inject(method = "render(FJZ)V", at = @At("HEAD"))
    private void beforeRender(float partialTicks, long nanoTime, boolean renderLevel, CallbackInfo ci) {
        kinetic$profileFrameActive = NativeProfiler.beginFrame();
    }

    @Inject(method = "render(FJZ)V", at = @At("RETURN"))
    private void afterRender(float partialTicks, long nanoTime, boolean renderLevel, CallbackInfo ci) {
        NativeProfiler.endFrame(kinetic$profileFrameActive);
        kinetic$profileFrameActive = false;
    }
}
