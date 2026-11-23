package com.kinetic.mixin.plugin;

import com.google.common.collect.Lists;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public class MixinTweaker implements IMixinConfigPlugin {
    private static final String RAGDOLLIFIED_MOD_ID = "ragdollified";

    public MixinTweaker() {
        // 不要在这里直接调用联动模组的 compat 类：插件类一加载就会把它们连带的类型一起加载，
        // 模组缺席时抛 NoClassDefFoundError，Mixin 只会报"kinetic.mixins.json was invalid"。
        // 初始化推迟到确定模组存在之后（见 getMixins）。
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        try {
            return collectMixins();
        } catch (Throwable failure) {
            // 整份配置失效会让客户端在启动阶段直接崩（"kinetic.mixins.json was invalid"）。
            // 任何一个联动模组的判定出问题，都只能丢掉它自己的 mixin，不能牵连全部。
            org.apache.logging.log4j.LogManager.getLogger("kinetic")
                    .error("Failed to collect conditional mixins; continuing with the static list only", failure);
            return null;
        }
    }

    private List<String> collectMixins() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            List<String> list = Lists.newArrayList();

            if (net.minecraftforge.fml.ModList.get().isLoaded("parcool")) {
                com.kinetic.client.compat.parcool.ParCoolCompat.init();
                list.add("client.parcool.AnimationAccessor");
                list.add("client.parcool.DodgeAnimatorAccessor");
                list.add("client.parcool.ExaggeratedSideDodgeAnimatorAccessor");
                list.add("client.parcool.FlippingAnimatorAccessor");
                list.add("client.parcool.HorizontalWallRunAnimatorAccessor");
                list.add("client.parcool.RollAnimatorAccessor");
                list.add("client.parcool.SpeedVaultAnimatorAccessor");
                list.add("client.parcool.WallJumpAnimatorAccessor");
            }

            // Embeddium 是可选的渲染联动。它的 mixin 目标类只在该模组存在时才加载得进来，
            // 静态写在配置里会让"没装 Embeddium"的客户端在启动阶段就崩在 Mixin 初始化。
            if (LoadingModList.get().getModFileById("embeddium") != null) {
                list.add("client.embeddium.SodiumBufferBuilderMixin");
            }

            // TaCZ 是可选联动：它的类不存在时这条 mixin 连加载都不该尝试。
            if (net.minecraftforge.fml.ModList.get().isLoaded("tacz")) {
                list.add("client.tacz.BedrockGunModelMixin");
            }

            if (net.minecraftforge.fml.ModList.get().isLoaded("create")) {
                com.kinetic.client.compat.create.CreateCompat.init();
                list.add("client.create.PlayerSkyhookRendererAccessor");
            }

            // 只查 FML 的模组列表：这里加载任何引用 vanilla 实体类型的 KINETIC 类，都会让
            // net.minecraft.world.entity.Entity 在本阶段被提前加载，从而使其它模组针对
            // Entity 的 Mixin 在 prepare 阶段直接失败（MixinTargetAlreadyLoadedException）。
            if (LoadingModList.get().getModFileById(RAGDOLLIFIED_MOD_ID) != null) {
                list.add("client.ragdollified.ClientRagdollRendererMixin");
            }

            if (list.isEmpty()) {
                return null;
            } else {
                return list;
            }
        }
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}