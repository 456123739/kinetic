package com.kinetic.capability;

import com.kinetic.Kinetic;
import com.kinetic.client.compat.FirstPersonCompat;
import com.kinetic.client.compat.bettercombat.BetterCombatCompat;
import com.kinetic.client.entity.CustomPlayerEntity;
import com.kinetic.model.resource.client.ModelRenderTarget;
import com.kinetic.model.service.ClientModelService;
import com.kinetic.model.resource.client.ResourceLease;
import com.kinetic.geckolib3.core.event.predicate.AnimationEvent;
import com.kinetic.geckolib3.model.AnimatableEntity;
import com.kinetic.geckolib3.model.AnimatedGeoModel;
import com.kinetic.molang.runtime.Struct;
import it.unimi.dsi.fastutil.ints.Int2FloatMap;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@OnlyIn(Dist.CLIENT)
public final class PlayerAnimatableCapability extends CustomPlayerEntity {
    /**
     * 模组是否安装由启动期的 {@code ClientSetupEvent} 决定，游戏运行期间不可能改变，
     * 而 {@link #recoverLastCodedAnimation} 每个渲染帧、每个本地玩家都会执行。
     */
    private static final boolean RECOVER_FIRST_PERSON_HEAD =
            FirstPersonCompat.isInstalled() || BetterCombatCompat.isInstalled();

    private final ClientRoamingSession roamingSession;

    /**
     * 实体类型在实例存活期间不会改变，逐帧 {@code instanceof} 没有意义。
     * 只有 {@link AbstractClientPlayer} 才可能带皮肤模型名，否则变体判定直接跳过。
     */
    @Nullable
    private final AbstractClientPlayer clientPlayer;

    /**
     * 皮肤变体判定与其记忆（皮肤模型名 / 当前模型未变时不重算）。
     *
     * <p>纯逻辑、可离线单测；见 {@link BuiltinSkinVariantDecision}。
     */
    private final BuiltinSkinVariantDecision skinVariantDecision = new BuiltinSkinVariantDecision();

    public PlayerAnimatableCapability(Player player) {
        super(player, player instanceof LocalPlayer, true);
        this.clientPlayer = player instanceof AbstractClientPlayer client ? client : null;
        // 每个玩家第一次被跟踪时判定一次：之后只有 Quick Skin 报告皮肤变化或模型重新加载才会再判定。
        SkinVariantInvalidation.INSTANCE.markDirty(player.getUUID());
        roamingSession = new ClientRoamingSession(
                player, player instanceof LocalPlayer, this::reloadGeoModel);
    }

    @Override
    protected PlayerStateTracker createStateTracker(Player entity) {
        return new PlayerStateTracker(entity, entity instanceof LocalPlayer);
    }

    @Override
    public void checkModelUpdate() {
        // 不再逐帧轮询皮肤：只有被显式标脏（玩家创建 / 模型就绪 / Quick Skin 报告变化）时才判定一次。
        // 未标脏时这里只是一次并发集合的 isEmpty 检查，不触碰皮肤 API，也不扫描目录。
        if (SkinVariantInvalidation.INSTANCE.consumeDirty(entity.getUUID())) {
            applySkinMatchedBuiltinVariant();
        }
        super.checkModelUpdate();
    }

    /**
     * 让内置玩家模型（Steve/Alex）的宽/细手臂跟随玩家当前皮肤。
     *
     * <p><b>为什么必须在客户端本地决定</b>：宽手臂还是细手臂由皮肤模型名（{@code default}/{@code slim}）
     * 决定，而这个值在 1.20.1 只有客户端可得；服务端既看不到玩家皮肤，也只能在会话协商时对齐一次，
     * 玩家在游戏中换皮肤（包括在皮肤模组界面里切换宽/细）之后不会再协商。因此在皮肤变化被报告时
     * 把「当前选择就是内置玩家模型」的玩家收敛到与当前皮肤一致的变体，作为绘制的依据。
     *
     * <p><b>为什么不再逐帧轮询</b>：原版皮肤只能通过重启游戏更换，所以游戏内唯一会变的路径是皮肤模组；
     * Quick Skin 通过事件通知，其余时刻判定结果不可能变化。每个玩家在能力创建与模型就绪时各判定一次，
     * 覆盖启动/加入的初始状态。
     *
     * <p>只对内置玩家模型生效：当前选择不是这两个内置模型时本方法不做任何事，不会覆盖玩家或服务端
     * 的其它选择。换肤后变体随之切换，手臂贴图与手臂宽度因此始终匹配。
     */
    private void applySkinMatchedBuiltinVariant() {
        var player = this.clientPlayer;
        if (player == null) {
            return;
        }
        var applied = getModelHash();
        if (applied == null) {
            return;
        }
        var skinModelName = SkinModelNames.resolve(player);
        var service = ClientModelService.current().orElse(null);
        if (service == null) {
            return;
        }
        var matched = skinVariantDecision.decide(
                skinModelName, applied, service::isBuiltinPlayerModel, service::resolvePath);
        if (matched != null) {
            updateModelHash(matched);
            Kinetic.LOGGER.debug(
                    "Switched the builtin player model to match skin model '{}' for {}",
                    skinModelName, player.getName().getString());
        }
    }

    public PlayerStateTracker getStateTracker() {
        return (PlayerStateTracker) super.getStateTracker();
    }

    @Override
    public @Nullable Struct getRoamingStruct() {
        return roamingSession.currentStruct();
    }

    @Override
    protected void onModelRenderTargetLoaded(ModelRenderTarget newModel) {
        super.onModelRenderTargetLoaded(newModel);
        // 模型真正可用（或切换）时判定一次：覆盖能力创建时模型尚未就绪、以及模型被换掉的情形。
        SkinVariantInvalidation.INSTANCE.markDirty(entity.getUUID());
        roamingSession.modelLoaded(getModelRenderTarget().modelHash().roamingHash());
    }

    @Override
    protected void resetModelRenderTarget() {
        roamingSession.modelReset();
        super.resetModelRenderTarget();
    }

    @Override
    public void onLoadGeoModel(AnimatedGeoModel model) {
        super.onLoadGeoModel(model);
        roamingSession.geoModelLoaded();
    }

    @Override
    protected void resetGeoModel() {
        roamingSession.geoModelReset();
        super.resetGeoModel();
    }

    @Override
    protected void codeAnimation(AnimationEvent<? extends AnimatableEntity<Player>> animationEvent, boolean update) {
        super.codeAnimation(animationEvent, update);

        // 更新第一人称相机偏移与头部隐藏
        AnimatedGeoModel model = getLoadedGeoModel();
        if (model != null && isLocalPlayer()) {
            var ctx = animationEvent.getRenderContext();
            if (ctx.firstPersonMod()) {
                if (model.getFirstPersonAllHead() != null) {
                    model.getFirstPersonAllHead().setHidden(true);
                }
                if (model.getFirstPersonViewLocator() != null) {
                    FirstPersonCompat.setHeadPos(model.getFirstPersonViewLocator().getPivot().y * getHeightScale());
                } else if (update) {
                    var head = model.getFirstPersonHead();
                    if (head != null) {
                        FirstPersonCompat.setHeadPos(head.getPivotY() * getHeightScale());
                    }
                }
            }
        }
    }

    @Override
    protected void recoverLastCodedAnimation(boolean lastFrameUpdated) {
        super.recoverLastCodedAnimation(lastFrameUpdated);

        AnimatedGeoModel model = getLoadedGeoModel();
        if (model != null && isLocalPlayer()) {
            if (RECOVER_FIRST_PERSON_HEAD && model.getFirstPersonAllHead() != null) {
                model.getFirstPersonAllHead().setHidden(false);
            }
        }
    }

    public void resetRoamingVars(int modelHashShort, Int2FloatOpenHashMap vars) {
        roamingSession.resetFromServer(modelHashShort, vars);
    }

    public void clearRoamingVars() {
        roamingSession.clearFromServer();
    }

    public boolean hasRoamingStorage(int hashShort) {
        return roamingSession.hasStorage(hashShort);
    }

    public void updateRemoteRoamingVars(int modelHashShort, Int2FloatMap vars) {
        roamingSession.updateRemote(modelHashShort, vars);
    }

    public void handleRoamingVarsChanges() {
        roamingSession.flushLocalChanges();
    }

    public RoamingSnapshot roamingSnapshot(int authoritativeModelKey) {
        return roamingSession.snapshot(authoritativeModelKey);
    }

    /** The roaming namespace of the applied model, or null before one is loaded. */
    public Integer localRoamingKey() {
        return roamingSession.currentRoamingKey();
    }

    public record RoamingSnapshot(int modelKey, Int2FloatOpenHashMap values) {
    }

    public void moveFrom(PlayerAnimatableCapability source) {
        roamingSession.moveFrom(source.roamingSession);
        updateModelAndTexture(source.getModelHash(), source.textureName);
        setDisabled(source.isDisabled());
    }

    @Override
    @NotNull
    protected HumanoidResourceHolder createResourceHolder(ResourceLease lease, boolean isFallback) {
        return new HumanoidResourceHolder(lease, isFallback);
    }

}
