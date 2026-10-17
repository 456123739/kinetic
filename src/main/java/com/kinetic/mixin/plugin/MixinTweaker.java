package com.kinetic.mixin.plugin;

import com.kinetic.client.compat.create.CreateCompat;
import com.kinetic.client.compat.parcool.ParCoolCompat;
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
        ParCoolCompat.init();
        CreateCompat.init();
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
        if (FMLEnvironment.dist == Dist.CLIENT) {
            List<String> list = Lists.newArrayList();

            if (ParCoolCompat.isInstalled()) {
                list.add("client.parcool.AnimationAccessor");
                list.add("client.parcool.DodgeAnimatorAccessor");
                list.add("client.parcool.ExaggeratedSideDodgeAnimatorAccessor");
                list.add("client.parcool.FlippingAnimatorAccessor");
                list.add("client.parcool.HorizontalWallRunAnimatorAccessor");
                list.add("client.parcool.RollAnimatorAccessor");
                list.add("client.parcool.SpeedVaultAnimatorAccessor");
                list.add("client.parcool.WallJumpAnimatorAccessor");
            }

            if (CreateCompat.isInstalled()) {
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