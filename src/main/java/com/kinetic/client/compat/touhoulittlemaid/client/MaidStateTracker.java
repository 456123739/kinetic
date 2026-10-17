package com.kinetic.client.compat.touhoulittlemaid.client;

import com.kinetic.client.entity.HumanoidStateTracker;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

public class MaidStateTracker extends HumanoidStateTracker<EntityMaid> {
    public MaidStateTracker(EntityMaid entity) {
        super(entity);
    }

    @Override
    public void reset() {
        super.reset();
    }
}
