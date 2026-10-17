package com.kinetic.geckolib3.geo.animated;

import com.kinetic.geckolib3.geo.render.built.GeoLocator;
import com.kinetic.geckolib3.geo.render.built.GeoModel;
import com.kinetic.geckolib3.model.AnimatedGeoModel;
import com.kinetic.natives.render.NativeModelState;
import com.kinetic.util.Closeable;
import com.kinetic.util.ExposedShortArrayList;
import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;


public final class GeoModelState implements Closeable {
    private final NativeModelState nativeState = NativeModelState.create();
    private final ReferenceArrayList<ShortArrayList> activeLocatorMap = new ReferenceArrayList<>(0);

    /**
     * {@link #visitLocatorGroup} 每个渲染帧、每个 locator group 都要用 4 个临时矩阵，
     * 之前每帧都会分配它们。这里改为复用实例级 scratch。
     *
     * <p><b>重入保护</b>：visitor 有可能（直接或经由物品/模型渲染间接）再次访问同一个实例，
     * 那时内层会覆盖外层正在使用的 scratch，因此用 {@link #locatorVisitOwner} 记录占用者：
     * 只有成功 CAS 到当前线程的调用才复用 scratch，其余（重入、跨线程并发）退回分配。
     * 这样即使出现意料之外的并发也只是多分配，不会破坏状态。
     */
    private final Matrix4f scratchBackupPose = new Matrix4f();
    private final Matrix3f scratchBackupNormal = new Matrix3f();
    private final Matrix4f scratchBonePose = new Matrix4f();
    private final Matrix3f scratchBoneNormal = new Matrix3f();
    private final AtomicReference<Thread> locatorVisitOwner = new AtomicReference<>();

    private GeoModel model;

    public boolean extract(AnimatedGeoModel animatedModel) {
        invalidate();
        var model = animatedModel.getModel();
        var boneCount = model.sortedBones().size();
        var activeLocatorMap = this.activeLocatorMap;

        var valid = nativeState.extract(model.bakedModel(),
                animatedModel.getBoneAttributes());
        if (!valid) {
            return false;
        }

        var locatorSlotCount = model.locatorType().size();
        if (activeLocatorMap.size() != locatorSlotCount) {
            var oldSize = activeLocatorMap.size();
            activeLocatorMap.size(locatorSlotCount);
            for (var i = oldSize; i < locatorSlotCount; i++) {
                activeLocatorMap.set(i, new ExposedShortArrayList(2));
            }
        }
        for(var list : activeLocatorMap) {
            list.clear();
        }

        var sortedBones = model.sortedBones();
        var activeLocators = nativeState.getLocatorBoneIndices();
        for (var i = 0; i < activeLocators.size(); i++) {
            var boneIndex = Short.toUnsignedInt(activeLocators.getShort(i));
            if (boneIndex >= boneCount) {
                return false;
            }
            var locatorType = sortedBones.get(boneIndex).locatorType();
            if (locatorType != null) {
                activeLocatorMap.get(Byte.toUnsignedInt(locatorType.seq()) - 1).add((short) boneIndex);
            }
        }

        this.model = model;
        return true;
    }

    public boolean isValid() {
        return nativeState.isValid();
    }

    public int getVertexCount() {
        return nativeState.getTotalVertexCount();
    }

    public boolean hasTranslucentVertices() {
        return nativeState.getTranslucentVertexCount() != 0;
    }

    public NativeModelState getNativeState() {
        return nativeState;
    }

    public void visitLocatorGroupOrDefault(GeoLocator locator, PoseStack poseStack,
                                           Consumer<PoseStack> visitor, Consumer<PoseStack> applyDefaultPose) {
        if (locatorGroupSize(locator) > 0) {
            visitLocatorGroup(locator, poseStack, visitor);
        } else {
            poseStack.pushPose();
            try {
                applyDefaultPose.accept(poseStack);
                visitor.accept(poseStack);
            } finally {
                poseStack.popPose();
            }
        }
    }

    public void visitLocatorGroup(GeoLocator locator, PoseStack poseStack,
                                  Consumer<PoseStack> visitor) {
        if (model.locatorType() != locator.type()) {
            throw new IllegalArgumentException("locator type mismatch");
        }

        final Matrix4f backupPose;
        final Matrix3f backupNormal;
        final Matrix4f bonePose;
        final Matrix3f boneNormal;
        // 只有拿到 scratch 所有权的调用才能复用它；重入或并发时退回分配。
        final boolean reusable = locatorVisitOwner.compareAndSet(null, Thread.currentThread());
        if (reusable) {
            backupPose = scratchBackupPose.set(poseStack.last().pose());
            backupNormal = scratchBackupNormal.set(poseStack.last().normal());
            bonePose = scratchBonePose;
            boneNormal = scratchBoneNormal;
        } else {
            backupPose = new Matrix4f(poseStack.last().pose());
            backupNormal = new Matrix3f(poseStack.last().normal());
            bonePose = new Matrix4f();
            boneNormal = new Matrix3f();
        }
        var bonePoseView = nativeState.getBonePoses();
        var last = poseStack.last();

        try {
            for (var boneIndexShort : activeLocatorMap.get(Byte.toUnsignedInt(locator.seq()) - 1)) {
                var boneIndex = Short.toUnsignedInt(boneIndexShort);
                var bone = model.sortedBones().get(boneIndex);

                bonePoseView.getPose(boneIndex, bonePose);
                bonePoseView.getNormal(boneIndex, boneNormal);
                backupPose.mulAffine(bonePose, last.pose());
                backupNormal.mul(boneNormal, last.normal());

                var pivot = bone.pivot();
                poseStack.translate(pivot.x / 16, pivot.y / 16, pivot.z / 16);

                visitor.accept(poseStack);
            }
        } finally {
            last.pose().set(backupPose);
            last.normal().set(backupNormal);
            if (reusable) {
                locatorVisitOwner.set(null);
            }
        }
    }

    public int locatorGroupSize(GeoLocator locator) {
        if (model.locatorType() != locator.type()) {
            throw new IllegalArgumentException("locator type mismatch");
        }
        return activeLocatorMap.get(Byte.toUnsignedInt(locator.seq())).size();
    }

    private void invalidate() {
        model = null;
    }

    public GeoModel getModel() {
        return model;
    }

    @Override
    public void close() {
        invalidate();
        nativeState.close();
    }
}
