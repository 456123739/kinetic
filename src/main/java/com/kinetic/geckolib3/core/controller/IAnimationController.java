package com.kinetic.geckolib3.core.controller;

import com.kinetic.geckolib3.core.event.predicate.AnimationEvent;
import com.kinetic.geckolib3.core.molang.context.MolangContext;
import com.kinetic.geckolib3.core.molang.value.IValue;
import com.kinetic.geckolib3.core.snapshot.BoneTopLevelSnapshot;
import com.kinetic.geckolib3.model.AnimatableEntity;
import com.kinetic.molang.runtime.ExpressionEvaluator;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMap;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

public interface IAnimationController<T extends AnimatableEntity<?>> {
    /**
     * 获取动画控制器名称
     */
    String getName();

    /**
     * 获取控制器当时所处状态
     */
    String getStateName();

    /**
     * 本帧正在播放的动画名；没有在播任何动画时为 {@code null}。
     *
     * <p>匍匐侧躺频道靠它判断"其他频道此刻在演什么动作"（打药 / 换弹 / 手雷蓄力），
     * 因此这里返回的必须是<b>正在生效</b>的动画，而不是曾经请求过的动画名：
     * 一次性动画播完之后必须回到 {@code null}。默认实现不知道，返回 {@code null}，
     * 于是这类控制器不参与推断——行为与改动前一致。
     */
    @Nullable
    default String getCurrentAnimationName() {
        return null;
    }

    /**
     * 下一段动画从第 {@code ticks} 个 tick 开始播。
     *
     * <p>默认什么也不做——只有 {@code CodedAnimationController} 支持跳转。
     */
    default void seekAnimation(float ticks) {
    }

    /** 当前动画已播放到第几 tick；不知道就返回 0。 */
    default float getAnimationTicks() {
        return 0.0f;
    }

    /** 指定动画的总长度（tick）；不知道就返回 0。 */
    default float getAnimationLength(@Nullable String animationName) {
        return 0.0f;
    }

    /**
     * 更新模型
     */
    void updateModel(List<BoneTopLevelSnapshot> modelRendererList, Object2ReferenceMap<String, List<IValue>> eventHandlers);

    void process(AnimationEvent<T> event, ExpressionEvaluator<MolangContext<?>> evaluator, boolean allowEmitting);

    void visitBoneAnimationQueues(Consumer<IBoneAnimationQueue> visitor);

    /**
     * 这个参数是为了处理一个 KINETIC 遗留问题
     * <p>
     * 曾经某个版本的 KINETIC 加入了并行动画的混合功能，但是仅混合动画旋转数值
     *
     * @return 如果是 CodedAnimationController，那么仅并行动画返回 true
     * <p>
     * 如果使用动画控制器，那么将永远返回 false
     */
    @Deprecated
    default boolean blendRotation() {
        return false;
    }

    void clear();
}
