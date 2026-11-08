package com.kinetic.client.animation.predicate;

import com.kinetic.client.animation.ProneAction;
import com.kinetic.client.compat.swarfare.SWarfareCompat;
import com.kinetic.client.compat.tacz.TACZCompat;
import com.kinetic.client.entity.CustomHumanoidEntity;
import com.kinetic.geckolib3.core.PlayState;
import com.kinetic.geckolib3.core.builder.LoopType;
import com.kinetic.geckolib3.core.controller.IAnimationController;
import com.kinetic.geckolib3.core.event.predicate.AnimationEvent;
import com.kinetic.molang.runtime.ExpressionEvaluator;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import static com.kinetic.client.animation.predicate.IAnimationPredicate.playAnimation;

/**
 * 匍匐侧躺频道。
 *
 * <p>注册在 {@code use} 之后，因此它在同一帧里晚于 use / hold / fire / swing 处理，
 * 写下的骨骼就是最终值。选哪段动画只看"这一轮是什么动作"（{@link ProneAction.Kind}），
 * 姿态的全部细节在动画数据里。
 *
 * <p>动作种类由其他频道<b>本帧正在播的动画</b>推断，而不是去读枪械模组的内部状态：
 * <ul>
 *   <li>{@code player.hold_mainhand} 在播 {@code tac:mainhand:grenade} → 手雷蓄力；</li>
 *   <li>{@code player.use} 在播 {@code *:medical} → 打药；</li>
 *   <li>{@code player.fire} 在播 {@code tac:reload:*} → 换弹；</li>
 *   <li>{@code player.use} 在播 {@code *:eat} / {@code *:drink} → 吃喝（只需要把头腿摆正）。</li>
 * </ul>
 * 换弹动画是一次性的，播完就离开 RUNNING；TaCZ 的重载计数还在时用它兜底。
 */
public class PronePredicate implements IAnimationPredicate<CustomHumanoidEntity<?>> {
    static final String CONTROLLER_SWING = "player.swing";
    static final String CONTROLLER_USE = "player.use";
    static final String CONTROLLER_FIRE = "player.fire";
    static final String CONTROLLER_HOLD = "player.hold_mainhand";

    static final String IDLE_ANIMATION = "prone:action:idle";
    /** 由 swing 频道播放的匍匐投掷动画；本频道不重复写它。 */
    static final String THROW_ANIMATION = "prone:throw";

    @Override
    public PlayState test(AnimationEvent<CustomHumanoidEntity<?>> event, ExpressionEvaluator<?> evaluator) {
        Entity entity = event.getAnimatableEntity().getEntity();
        if (!(entity instanceof Player player)) {
            return PlayState.STOP;
        }
        ProneAction.Kind kind = detect(event, player);
        float roll = ProneAction.observe(player, event.getRenderTicks(), kind);
        if (roll <= ProneAction.EPSILON) {
            return PlayState.STOP;
        }
        String animation = animationFor(event.getAnimatableEntity(), ProneAction.kindOf(player));
        if (animation == null) {
            return PlayState.STOP;
        }
        return playAnimation(event, animation, LoopType.LOOP);
    }

    /** 本帧的动作种类；{@code NONE} 表示没有正在进行的动作。 */
    static ProneAction.Kind detect(AnimationEvent<? extends CustomHumanoidEntity<?>> event, Player player) {
        // 投掷：swing 频道正在播匍匐投掷动画。它自己会写侧躺姿势，这里只要把 roll 推上去。
        String swing = currentAnimation(event, CONTROLLER_SWING);
        if (swing != null && swing.equals(THROW_ANIMATION)) {
            return ProneAction.Kind.THROW;
        }
        String hold = currentAnimation(event, CONTROLLER_HOLD);
        if (hold != null && hold.startsWith("tac:mainhand:grenade")) {
            return ProneAction.Kind.GRENADE;
        }
        String use = currentAnimation(event, CONTROLLER_USE);
        if (use != null) {
            if (use.contains(":medical")) {
                return ProneAction.Kind.MEDICAL;
            }
            if (use.endsWith(":eat") || use.endsWith(":drink")) {
                return ProneAction.Kind.OTHER;
            }
        }
        String fire = currentAnimation(event, CONTROLLER_FIRE);
        if (fire != null && fire.startsWith("tac:reload:")) {
            return ProneAction.Kind.RELOAD;
        }
        if (isReloading(player)) {
            return ProneAction.Kind.RELOAD;
        }
        return ProneAction.Kind.NONE;
    }

    private static boolean isReloading(Player player) {
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) {
            return false;
        }
        return TACZCompat.isReloading(player) || SWarfareCompat.isReloading(player);
    }

    /** 这一轮对应的动画名；模型没有该动画时回退到通用姿势，再没有就不接这一帧。 */
    @Nullable
    static String animationFor(CustomHumanoidEntity<?> animatable, ProneAction.Kind kind) {
        return animationNameFor(kind, name -> animatable.getAnimation(name) != null);
    }

    /**
     * 动作种类 → 动画名的映射（纯函数，便于离线验证回退规则）。
     *
     * <p>模型可能只有通用的 {@code prone:action:idle}（自建模型常常只改这部分），
     * 那时侧滚仍然成立、头和腿照样被摆正，只是手臂留给原动作动画。
     */
    @Nullable
    public static String animationNameFor(ProneAction.Kind kind, java.util.function.Predicate<String> available) {
        if (kind == ProneAction.Kind.THROW) {
            // 投掷姿势完全由 swing 频道的 prone:throw 负责，本频道只管把 roll 推上去。
            return null;
        }
        String name = switch (kind) {
            case MEDICAL -> "prone:action:medical";
            case RELOAD -> "prone:action:reload";
            case GRENADE -> "prone:action:grenade";
            case OTHER, NONE, THROW -> IDLE_ANIMATION;
        };
        if (available.test(name)) {
            return name;
        }
        return available.test(IDLE_ANIMATION) ? IDLE_ANIMATION : null;
    }

    /** 某个控制器本帧正在播的动画名。控制器不存在（模型没这套频道）时为 null。 */
    @Nullable
    static String currentAnimation(AnimationEvent<? extends CustomHumanoidEntity<?>> event, String controllerName) {
        List<IAnimationController> controllers = event.getAnimatableEntity().getAnimationData().getAnimationControllers();
        for (int i = 0; i < controllers.size(); i++) {
            IAnimationController<?> controller = controllers.get(i);
            if (controllerName.equals(controller.getName())) {
                return controller.getCurrentAnimationName();
            }
        }
        return null;
    }
}
