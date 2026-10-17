package com.kinetic.client.compat.touhoulittlemaid.client.animation;

import com.kinetic.client.animation.condition.ConditionArmor;
import com.kinetic.client.animation.predicate.*;
import com.kinetic.client.compat.touhoulittlemaid.client.CustomKineticMaidEntity;
import com.kinetic.client.compat.touhoulittlemaid.client.animation.predicate.MaidMiscPredicate;
import com.kinetic.client.compat.touhoulittlemaid.client.animation.predicate.MaidRoulettePredicate;
import com.kinetic.client.compat.touhoulittlemaid.client.animation.predicate.MaidStatuePredicate;
import com.kinetic.client.compat.touhoulittlemaid.client.animation.predicate.KineticMaidMainPredicate;
import com.kinetic.client.controller.*;
import com.kinetic.model.resource.client.CommonAsset;
import com.kinetic.model.resource.client.PlayerModelResources;
import com.kinetic.geckolib3.core.builder.Animation;
import com.kinetic.geckolib3.core.builder.controller.AnimationControllerData;
import com.kinetic.geckolib3.core.controller.CodedAnimationController;
import com.kinetic.geckolib3.core.controller.HybridAnimationController;
import com.kinetic.geckolib3.core.controller.IAnimationController;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMap;
import net.minecraft.world.entity.EquipmentSlot;
import org.apache.commons.lang3.function.TriFunction;

import java.util.function.BiFunction;
import java.util.function.Consumer;

public class MaidControllerCollection {
    private static final String CATEGORY = "player";
    private static final String CATEGORY_MAID = "maid";
    private static final AnimationControllerCollection<CustomKineticMaidEntity, PlayerModelResources> COLLECTION = new AnimationControllerCollection<>();

    @SuppressWarnings("rawtypes,unchecked,deprecation")
    private static void init() {
        parallel("pre_parallel", (name, entity, anim) ->
                new HybridAnimationController(entity, name, 0, anim != null ? new ParallelPredicate(anim) : EmptyPredicate.INSTANCE));

        simple("vehicle", (name, entity) -> new HybridAnimationController(entity, name, 0.1f, new VehiclePredicate()));

        multi("pre_main", (name, entity) -> new HybridAnimationController(entity, name, 0, new EmptyPredicate()));
        simple("main", (name, entity) -> new HybridAnimationController(entity, name, 0.1f, new KineticMaidMainPredicate()));
        multi("post_main", (name, entity) -> new HybridAnimationController(entity, name, 0, new EmptyPredicate()));

        multi("pre_hold", (name, entity) -> new HybridAnimationController(entity, name, 0, new EmptyPredicate()));
        simple("hold_offhand", (name, entity) -> new HybridAnimationController(entity, name, 0.1f, new OffhandPredicate()));
        simple("hold_mainhand", (name, entity) -> new HybridAnimationController(entity, name, 0.1f, new MainhandPredicate()));
        multi("post_hold", (name, entity) -> new HybridAnimationController(entity, name, 0, new EmptyPredicate()));

        if (GunFirePredicate.available()) {
            simple("fire", (name, entity) -> new HybridAnimationController(entity, name, 0f, new GunFirePredicate()));
        }

        multi("pre_swing", (name, entity) -> new HybridAnimationController(entity, name, 0, new EmptyPredicate()));
        simple("swing", (name, entity) -> new HybridAnimationController(entity, name, 0, new SwingPredicate()));
        multi("post_swing", (name, entity) -> new HybridAnimationController(entity, name, 0, new EmptyPredicate()));

        multi("pre_use", (name, entity) -> new HybridAnimationController(entity, name, 0, new EmptyPredicate()));
        simple("use", (name, entity) -> new HybridAnimationController(entity, name, 0.1f, new UsePredicate()));
        multi("post_use", (name, entity) -> new HybridAnimationController(entity, name, 0, new EmptyPredicate()));

        maidSingle("misc", MaidMiscPredicate.ANIM_LIST, true, (name, entity) -> new HybridAnimationController(entity, name, 0.1f, new MaidMiscPredicate()));
        simple("passenger", (name, entity) -> new HybridAnimationController(entity, name, 0.1f, new PassengerPredicate()));

        // 下面不需要自定义动画控制器
        simple("cap", (name, entity) -> new CodedAnimationController(entity, name, 0, new MaidRoulettePredicate()));

        parallel("parallel", (name, entity, anim) ->
                new HybridAnimationController(entity, name, 0, anim != null ? new ParallelPredicate(anim) : EmptyPredicate.INSTANCE, true));

        armor("armor", (name, entity, slot) -> new HybridAnimationController(entity, name, 0, new ArmorPredicate(slot)));
        maidSingle("statue", MaidStatuePredicate.ANIM_LIST, true, (name, entity) -> new HybridAnimationController(entity, name, 0, new MaidStatuePredicate()));
    }

    public static Consumer<CustomKineticMaidEntity> build(PlayerModelResources model, CommonAsset assets) {
        COLLECTION.initialize(MaidControllerCollection::init);
        return COLLECTION.build(model, assets);
    }

    private static ControllerDiscovery<CustomKineticMaidEntity, PlayerModelResources> simple(String name, BiFunction<String, CustomKineticMaidEntity, IAnimationController<CustomKineticMaidEntity>> simpleFactory) {
        var controllerName = String.format("%s.%s", CATEGORY, name);
        return COLLECTION.add((model, asset) -> (animatable, consumer) -> {
            consumer.accept(simpleFactory.apply(controllerName, animatable));
        });
    }

    private static ControllerDiscovery<CustomKineticMaidEntity, PlayerModelResources> multi(String regex, BiFunction<String, CustomKineticMaidEntity, IAnimationController<CustomKineticMaidEntity>> controllerFunc) {
        return COLLECTION.add(new MultiControllerDiscovery<>(CATEGORY, regex, Adapter.INSTANCE, controllerFunc));
    }

    private static ControllerDiscovery<CustomKineticMaidEntity, PlayerModelResources> maidSingle(String name, String[] animations, boolean hybrid, BiFunction<String, CustomKineticMaidEntity, IAnimationController<CustomKineticMaidEntity>> controllerFunc) {
        return COLLECTION.add(new SingleControllerDiscovery<>(CATEGORY_MAID, name, animations, hybrid, Adapter.INSTANCE, controllerFunc));
    }

    private static ControllerDiscovery<CustomKineticMaidEntity, PlayerModelResources> parallel(String name, TriFunction<String, CustomKineticMaidEntity, String, IAnimationController<CustomKineticMaidEntity>> controllerFunc) {
        return COLLECTION.add(new ParallelControllerDiscovery<>(CATEGORY, name, true, Adapter.INSTANCE, controllerFunc));
    }

    private static ControllerDiscovery<CustomKineticMaidEntity, PlayerModelResources> armor(String name, TriFunction<String, CustomKineticMaidEntity, EquipmentSlot, IAnimationController<CustomKineticMaidEntity>> controllerFunc) {
        return COLLECTION.add(new ArmorControllerDiscovery<>(CATEGORY, name, Adapter.INSTANCE, controllerFunc));
    }

    private static class Adapter implements ResourceAdapter<PlayerModelResources> {
        static final Adapter INSTANCE = new Adapter();

        @Override
        public Object2ReferenceMap<String, AnimationControllerData> getControllers(PlayerModelResources model, CommonAsset assets) {
            return model.animationControllers();
        }

        @Override
        public Object2ReferenceMap<String, Animation> getAnimations(PlayerModelResources model, CommonAsset assets) {
            return model.animations();
        }

        @Override
        public ConditionArmor getArmorCondition(PlayerModelResources model, CommonAsset assets) {
            return model.conditionManager().getArmor();
        }
    }
}
