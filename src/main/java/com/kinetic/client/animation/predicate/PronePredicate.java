package com.kinetic.client.animation.predicate;

import com.kinetic.client.animation.ProneAction;
import com.kinetic.client.animation.condition.InnerClassify;
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
import net.minecraft.world.item.UseAnim;
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
 * <p>动作判定优先使用真实玩家状态：使用中的物品决定打药/蓄力，TaCZ 本地换弹事件与
 * 同步状态共同决定换弹，主手挥动或真实消耗决定投掷。频道动画名只作兼容回退。
 * 投掷随挥由当前 PLAY_ONCE 维持到完成，裸持有物品不会让动作永久成立。
 */
public class PronePredicate implements IAnimationPredicate<CustomHumanoidEntity<?>> {
    static final String CONTROLLER_SWING = "player.swing";
    static final String CONTROLLER_USE = "player.use";
    static final String CONTROLLER_FIRE = "player.fire";
    static final String CONTROLLER_HOLD = "player.hold_mainhand";
    static final String CONTROLLER_PRONE = "player.prone";

    static final String IDLE_ANIMATION = "prone:action:idle";
    /** 匍匐侧躺挥砍动画名；本频道不重复写 swing 频道的手臂弧线。 */
    static final String PRONE_SWING = "prone:swing";
    static final String PRONE_SWING_ALT = "prone:swing:alt";
    /** 匍匐投掷动画；swing 已接手时本频道让位，否则由本频道播完整个随挥。 */
    static final String THROW_ANIMATION = "prone:throw";

    /** 物品分类表里"医疗/注射类消耗品"的键，与 {@code use_mainhand:medical} 动画名同源。 */
    private static final String MEDICAL_CLASS = "medical";

    /**
     * 兜底识别用的物品 id 关键词。
     *
     * <p>关键词只回答"这是什么物品"，不回答"玩家此刻是否在投掷"。动作开始/结束仍由
     * 使用状态、主手挥动、物品消耗或真实模组事件决定，不能由关键词单独维持。
     */
    private static final String[] MEDICAL_HINTS = {
            "medkit", "medic", "bandage", "syringe", "inject", "morphine", "firstaid",
            "healthkit", "stimulant", "antidote", "tourniquet"};
    /** 投掷物：手雷之外还包括药水/炸弹/飞刀这类"甩出去"的东西。 */
    private static final String[] THROW_HINTS = {
            "grenade", "nade", "frag", "c4", "claymore", "dynamite", "semtex",
            "potion", "bomb", "throwing", "throwable", "tomahawk", "knife"};

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
        ProneAction.Kind locked = ProneAction.kindOf(player);
        String animation = animationFor(event.getAnimatableEntity(), locked,
                currentAnimation(event, CONTROLLER_SWING));
        if (animation == null) {
            return PlayState.STOP;
        }
        // 投掷是一次性的：按 LOOP 播会在一整段挥动窗口里反复起手。
        LoopType loop = locked == ProneAction.Kind.THROW ? LoopType.PLAY_ONCE : LoopType.LOOP;
        if (shouldRestartThrow(kind, animation, currentAnimation(event, CONTROLLER_PRONE))) {
            // 同名 PLAY_ONCE 完成后 lastSetAnim 不会自动清；新的真实脱手沿必须显式重播。
            // 回位阶段 kind=NONE，不走这里，因此不会反复起手。
            event.getCodedController().indicateReload();
        }
        return playAnimation(event, animation, loop);
    }

    /** 只有新的真实脱手沿才重播同名 PLAY_ONCE；随挥与 roll 回位期都不得重启。 */
    public static boolean shouldRestartThrow(ProneAction.Kind detected, @Nullable String selected,
                                             @Nullable String current) {
        return detected == ProneAction.Kind.THROW && THROW_ANIMATION.equals(selected)
                && !THROW_ANIMATION.equals(current);
    }

    /** 本帧的动作种类；{@code NONE} 表示没有正在进行的动作。 */
    static ProneAction.Kind detect(AnimationEvent<? extends CustomHumanoidEntity<?>> event, Player player) {
        ProneAction.Kind swingKind = kindForSwingAnimation(currentAnimation(event, CONTROLLER_SWING));
        if (swingKind != ProneAction.Kind.NONE) {
            return swingKind;
        }
        // 正在使用物品：优先读玩家自己的状态，而不是某个频道"恰好"把动画名选成我们认识的样子。
        // 只看动画名会漏掉一大类情况：趴下按右键掐雷时 hold 频道播的是 tac:hold:grenade（瞄准/持握），
        // 打药时 use 频道也可能被条件表接管成别的名字——表现就是"趴着按不出来，得先站起来做一次再趴下"。
        // 这条判定不依赖任何联动模组的分支细节，因此对 TaCZ 之外的枪械/消耗品联动同样成立。
        if (player.isUsingItem()) {
            ItemStack using = player.getUseItem();
            if (isThrowable(using) && using.getUseAnimation() != UseAnim.EAT
                    && using.getUseAnimation() != UseAnim.DRINK) {
                ProneAction.noteThrowableCharge(player, using);
                return ProneAction.Kind.GRENADE;
            }
            if (!using.isEmpty()) {
                ProneAction.cancelThrowableCharge(player);
            }
            // using 为空说明最后一件投掷物可能刚被消耗：保留快照给下面的脱手判定。
            if (MEDICAL_CLASS.equals(InnerClassify.getClassify(using))
                    || idLooksLike(using, MEDICAL_HINTS)) {
                return ProneAction.Kind.MEDICAL;
            }
            UseAnim useAnim = using.getUseAnimation();
            if (useAnim == UseAnim.EAT || useAnim == UseAnim.DRINK) {
                return ProneAction.Kind.OTHER;
            }
        }
        // 投掷物：swing 频道只在 entity.swinging 的 6 tick 里换成 prone:throw，
        // 而不少枪械/投掷模组的投掷根本不置 swinging（或者只手雷蓄力、不挥臂）。
        // 于是"趴下扔雷"会一直识别成没有动作，玩家只能先站着扔一次再趴下。
        // 这里直接看玩家自己的动作，不看任何频道选了什么动画。
        ItemStack main = player.getMainHandItem();
        if (player.swinging && player.swingingArm == net.minecraft.world.InteractionHand.MAIN_HAND
                && isThrowable(main)) {
            ProneAction.cancelThrowableCharge(player);
            return ProneAction.Kind.THROW;
        }
        if (ProneAction.consumeThrowableRelease(player, main)) {
            return ProneAction.Kind.THROW;
        }
        // 兼容路径：不通过 isUsingItem 表达蓄力的模组，仍按频道动画名识别。
        String hold = currentAnimation(event, CONTROLLER_HOLD);
        if (hold != null && hold.startsWith("tac:mainhand:grenade")) {
            return ProneAction.Kind.GRENADE;
        }
        // 投掷脱手后，主手可能已经空了、swinging 也已归零，但一次性随挥还没播完。
        // 用当前 PLAY_ONCE 动画是否仍在播来维持事务，绝不能靠"手里拿着雷"维持。
        if (ProneAction.kindOf(player) == ProneAction.Kind.THROW
                && THROW_ANIMATION.equals(currentAnimation(event, CONTROLLER_PRONE))) {
            return ProneAction.Kind.THROW;
        }
        String use = currentAnimation(event, CONTROLLER_USE);
        if (use != null) {
            if (use.contains(":medical") || use.contains(":inject")) {
                return ProneAction.Kind.MEDICAL;
            }
            if (use.endsWith(":eat") || use.endsWith(":drink")) {
                return ProneAction.Kind.OTHER;
            }
        }
        for (String controller : new String[]{CONTROLLER_FIRE, CONTROLLER_USE}) {
            String current = currentAnimation(event, controller);
            if (current != null && current.contains("reload")) {
                return ProneAction.Kind.RELOAD;
            }
        }
        if (isReloading(player)) {
            return ProneAction.Kind.RELOAD;
        }
        return ProneAction.Kind.NONE;
    }


    /**
     * 这个物品是不是"扔出去"的东西。
     *
     * <p>手雷与投掷药水各有一条 KINETIC 自己的标签；两条都不在时退到注册名关键词，
     * 保证不依赖任何联动模组的数据包是否加载成功。
     */
    public static boolean isThrowable(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return stack.is(com.kinetic.init.ModItemTags.GRENADE)
                || stack.is(com.kinetic.init.ModItemTags.THROWABLE_POTION)
                || idLooksLike(stack, THROW_HINTS);
    }

    private static boolean isGrenade(ItemStack stack) {
        return TACZCompat.isGrenade(stack) || SWarfareCompat.isGrenade(stack);
    }

    /** 将 swing 频道的侧躺动画映射为需要侧滚的动作种类。 */
    public static ProneAction.Kind kindForSwingAnimation(@Nullable String animation) {
        if (THROW_ANIMATION.equals(animation)) {
            return ProneAction.Kind.THROW;
        }
        if (PRONE_SWING.equals(animation) || PRONE_SWING_ALT.equals(animation)) {
            return ProneAction.Kind.SWING;
        }
        return ProneAction.Kind.NONE;
    }

    /** 物品注册名里是否含有关键词（不依赖任何联动模组）。 */
    static boolean idLooksLike(ItemStack stack, String[] hints) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        var key = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (key == null) {
            return false;
        }
        String path = key.getPath().toLowerCase(java.util.Locale.ROOT);
        for (String hint : hints) {
            if (path.contains(hint)) {
                return true;
            }
        }
        return false;
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
        return animationFor(animatable, kind, null);
    }

    /**
     * 同 {@link #animationFor(CustomHumanoidEntity, ProneAction.Kind)}，但知道 swing 频道这一帧在播什么。
     *
     * <p>投掷姿势平时由 swing 频道播 {@code prone:throw}（它同时管手臂弧线）。可很多投掷根本
     * 不经过那个频道——玩家趴着扔手雷时 swing 频道什么都没播，于是"侧躺投掷"永远不出现，
     * 只有先站着扔、再趴下才碰巧看到。这里补上：swing 频道没接手时，匍匐频道自己播一段。
     *
     * @param swingAnimation swing 频道本帧的动画名，可为 {@code null}
     */
    @Nullable
    static String animationFor(CustomHumanoidEntity<?> animatable, ProneAction.Kind kind,
                               @Nullable String swingAnimation) {
        return animationFor(kind, swingAnimation, name -> animatable.getAnimation(name) != null);
    }

    /** {@link #animationFor(CustomHumanoidEntity, ProneAction.Kind, String)} 的纯函数版本，便于离线验证。 */
    @Nullable
    public static String animationFor(ProneAction.Kind kind, @Nullable String swingAnimation,
                                      java.util.function.Predicate<String> available) {
        if (kind == ProneAction.Kind.THROW) {
            if (THROW_ANIMATION.equals(swingAnimation)) {
                return null;                       // swing 频道已经接手，别叠第二套时间线
            }
            if (available.test(THROW_ANIMATION)) {
                return THROW_ANIMATION;
            }
            // 模型没有投掷姿势：退到通用姿势，至少把身体滚进侧躺
            return available.test(IDLE_ANIMATION) ? IDLE_ANIMATION : null;
        }
        return animationNameFor(kind, available);
    }

    /**
     * 动作种类 → 动画名的映射（纯函数，便于离线验证回退规则）。
     *
     * <p>模型可能只有通用的 {@code prone:action:idle}（自建模型常常只改这部分），
     * 那时侧滚仍然成立、头和腿照样被摆正，只是手臂留给原动作动画。
     */
    @Nullable
    public static String animationNameFor(ProneAction.Kind kind, java.util.function.Predicate<String> available) {
        String name = switch (kind) {
            case MEDICAL -> "prone:action:medical";
            case RELOAD -> "prone:action:reload";
            case GRENADE -> "prone:action:grenade";
            case OTHER, NONE -> IDLE_ANIMATION;
            case SWING, THROW -> null;
        };
        if (name == null) {
            // 投掷与挥砍姿势完全由 swing 频道负责，本频道只管把 roll 推上去。
            return null;
        }
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
