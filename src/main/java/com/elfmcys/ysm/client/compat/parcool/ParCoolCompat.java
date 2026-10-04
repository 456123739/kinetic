package com.elfmcys.ysm.client.compat.parcool;

import com.elfmcys.ysm.client.animation.molang.CtrlBinding;
import com.elfmcys.ysm.client.entity.CustomPlayerEntity;
import com.elfmcys.ysm.config.ClientConfig;
import com.elfmcys.ysm.geckolib3.core.controller.HybridAnimationController;
import com.elfmcys.ysm.geckolib3.core.controller.IAnimationController;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.loading.LoadingModList;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.function.BiFunction;

public class ParCoolCompat {
    /**
     * 兼容层使用的最低 Parcool 版本。
     *
     * <p>原值是 3.4.1，会把 3.3.1.0 判为不兼容，导致跑酷滑铲等动画完全不生效（滑铲时会错误
     * 显示 TACZ 爬行动作）。已逐个核对 3.3.1.0 与 3.4.1.2 的 API：兼容层用到的
     * {@code Animator} 及 12 个动画类（含 {@code SlidingAnimator}）、{@code Animation}、
     * {@code Parkourability}、{@code VectorUtil.fromYawDegree} 以及全部 mixin accessor 目标字段
     * 在两个版本上完全一致，因此下调到 3.3.1 是安全的。
     */
    private static final ArtifactVersion MIN_VERSION = new DefaultArtifactVersion("3.3.1");
    private static final String MOD_ID = "parcool";
    private static boolean INSTALLED;
    private static boolean INCOMPATIBLE;

    public static void init() {
        if (ClientConfig.ENABLE_PARCOOL_COMPAT != null) {
            if (!ClientConfig.ENABLE_PARCOOL_COMPAT.get()) {
                INSTALLED = false;
                return;
            }
        }

        var modFile = LoadingModList.get().getModFileById(MOD_ID);
        if (modFile != null) {
            if (modFile.getMods().get(0).getVersion().compareTo(MIN_VERSION) >= 0) {
                INSTALLED = true;
            } else {
                INCOMPATIBLE = true;
            }
        }
    }

    public static Optional<Pair<String, String>> getCompatibilityWarning() {
        if (INCOMPATIBLE) {
            return Optional.of(Pair.of(MOD_ID, MIN_VERSION.toString()));
        }
        return Optional.empty();
    }

    public static boolean isInstalled() {
        return INSTALLED;
    }

    public static Optional<BiFunction<String, CustomPlayerEntity, IAnimationController<CustomPlayerEntity>>> animationPredicate() {
        if (INSTALLED) {
            return Optional.of((name, entity) -> new HybridAnimationController<>(entity, name, 0.1f, new ParCoolPredicate()));
        } else {
            return Optional.empty();
        }
    }

    public static boolean hasAnimation(Player player) {
        if (isInstalled()) {
            return ParCoolAnimationManger.hasAnimation(player);
        }
        return false;
    }

    @Nullable
    public static String getAnimation(Player player) {
        if (isInstalled()) {
            return ParCoolAnimationManger.getAnimation(player);
        }
        return null;
    }

    public static void addBinding(CtrlBinding binding) {
        if (isInstalled()) {
            ParCoolCtrlBinding.addInnerBinding(binding);
        } else {
            addEmptyBinding(binding);
        }
    }

    /**
     * 没有安装此模组时，这些 molang 应该存在，否则会报错
     */
    private static void addEmptyBinding(CtrlBinding binding) {
        binding.livingEntityVar("parcool_state", ctx -> StringUtils.EMPTY);
    }
}
