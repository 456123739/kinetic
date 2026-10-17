package com.kinetic.client.sound.instance;

import com.kinetic.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;

public class MinecraftSoundInstance extends AbstractTickableSoundInstance {
    protected final Entity entity;
    protected float configuredVolume = 1.0f;

    public MinecraftSoundInstance(SoundEvent soundEvent, Entity entity) {
        super(soundEvent, SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
        this.entity = entity;
        this.x = this.entity.getX();
        this.y = this.entity.getY();
        this.z = this.entity.getZ();
    }

    @Override
    public void tick() {
        this.volume = this.configuredVolume * ClientConfig.SOUND_VOLUME.get().floatValue() / 100.0f;
        if (this.entity.isRemoved()) {
            this.stop();
        } else {
            this.x = this.entity.getX();
            this.y = this.entity.getY();
            this.z = this.entity.getZ();
        }
    }

    /**
     * 显式覆写 vanilla 的 isStopped()。
     *
     * <p>这里不能依赖「自定义接口声明同名方法」的做法：isStopped 与 vanilla
     * SoundInstance#isStopped 撞名，重映射阶段会把调用点改写成 SRG 名 m_7801_，
     * 却不会改写自制接口里的声明，运行时必然抛 NoSuchMethodError。
     * 直接覆写 vanilla 方法后，声明与调用点都会被正确重映射。
     */
    @Override
    public boolean isStopped() {
        return super.isStopped();
    }

    @Override
    public boolean canPlaySound() {
        return !isStopped();
    }

    public void setConfiguredVolume(float volume) {
        this.configuredVolume = volume;
    }

    public void setPitch(float pitch) {
        this.pitch = pitch;
    }

    public void setAsUI() {
        this.attenuation = Attenuation.NONE;
        this.relative = true;
    }

    /** 模组自定义方法：停止该声音实例（此前由已删除的 ICanStopSound 接口声明）。 */
    public void setStopped() {
        this.stop();
        Minecraft.getInstance().execute(() -> {
            Minecraft.getInstance().getSoundManager().stop(this);
        });
    }

    public void setLooping(boolean looping) {
        this.looping = looping;
    }
}
