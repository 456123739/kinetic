package com.kinetic.client.animation.predicate;

import com.kinetic.client.animation.condition.ConditionArmor;
import com.kinetic.client.entity.CustomHumanoidEntity;
import com.kinetic.client.entity.IPreviewEntity;
import com.kinetic.geckolib3.core.PlayState;
import com.kinetic.geckolib3.core.builder.LoopType;
import com.kinetic.geckolib3.core.event.predicate.AnimationEvent;
import com.kinetic.molang.runtime.ExpressionEvaluator;
import com.kinetic.util.EquipmentUtil;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.apache.commons.lang3.StringUtils;

import static com.kinetic.client.animation.predicate.IAnimationPredicate.playAnimation;

public class ArmorPredicate implements IAnimationPredicate<CustomHumanoidEntity<?>> {
    private final EquipmentSlot slot;

    public ArmorPredicate(EquipmentSlot slot) {
        this.slot = slot;
    }

    @Override
    public PlayState test(AnimationEvent<CustomHumanoidEntity<?>> event, ExpressionEvaluator<?> evaluator) {
        LivingEntity entity = event.getAnimatableEntity().getEntity();
        if (entity == null || event.getAnimatableEntity() instanceof IPreviewEntity) {
            return PlayState.STOP;
        }
        ItemStack itemBySlot = EquipmentUtil.getEquippedItem(entity, slot);
        if (itemBySlot.isEmpty()) {
            return PlayState.STOP;
        }

        ConditionArmor conditionArmor = event.getAnimatableEntity().getConditionManager().getArmor();
        if (conditionArmor != null) {
            String name = conditionArmor.doTest(entity, slot);
            if (StringUtils.isNoneBlank(name)) {
                return playAnimation(event, name, LoopType.LOOP);
            }
        }

        String defaultName = slot.getName() + ":default";
        if (event.getAnimatableEntity().getAnimation(defaultName) != null) {
            return playAnimation(event, defaultName, LoopType.LOOP);
        }
        return PlayState.STOP;
    }
}
