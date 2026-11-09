package com.kinetic.network.forge;

import com.kinetic.capability.PlayerAnimatableCapability;
import com.kinetic.capability.SkinModelNames;
import com.kinetic.client.demand.LocalPlayerDemand;
import com.kinetic.model.domain.Hash256;
import com.kinetic.model.resource.client.ResourceLease;
import com.kinetic.model.resource.client.ResourceRequest;
import com.kinetic.model.service.ClientModelService;
import com.kinetic.network.NetworkHandler;
import com.kinetic.network.protocol.EntityRefEncoder;
import com.kinetic.network.protocol.ProtocolMessages;
import com.kinetic.proto.network.EmitMolangSync;
import com.kinetic.proto.network.EntityAnimationActionRequest;
import com.kinetic.proto.network.Hand;
import com.kinetic.proto.network.RouletteAnimationSelection;
import com.kinetic.proto.network.SelectModelRequest;
import com.kinetic.proto.network.StarredModelOperation;
import com.kinetic.proto.network.SubmitRouletteExpressionRequest;
import com.kinetic.proto.network.SwingHandRequest;
import com.kinetic.proto.network.UpdateStarredModelRequest;
import it.unimi.dsi.fastutil.floats.FloatList;
import it.unimi.dsi.fastutil.objects.Object2FloatMap;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import us.hebi.quickbuf.ProtoMessage;
import com.kinetic.model.catalog.builtin.BuiltinModelIndex;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;

public final class ClientProtocolGateway {
    private static final LocalPlayerStateReporter STATE_REPORTER = new LocalPlayerStateReporter();
    private static final LocalPlayerDemand MODEL_DEMAND = new LocalPlayerDemand(
            ClientProtocolGateway::monotonicMillis,
            new LocalPlayerDemand.ResourceAccess() {
                @Override
                public ResourceRequest request(
                        Hash256 modelId, String textureId) {
                    return ClientModelService.instance().resourceRequest(modelId, textureId);
                }

                @Override
                public CompletableFuture<Optional<ResourceLease>>
                getOrStartOffline(ResourceRequest request) {
                    return ClientModelService.instance().getOrStartOffline(request);
                }

                @Override
                public ResourceLease getOrStart(
                        ResourceRequest request) {
                    return ClientModelService.instance().getOrStart(request);
                }
            },
            new LocalPlayerDemand.SelectionEffect() {
                @Override
                public boolean available() {
                    return NetworkHandler.isRemoteChannelPresent()
                            && ClientSessionRuntime.businessSession().isPresent();
                }

                @Override
                public void send(Hash256 modelId, String textureId) {
                    sendModelSelection(modelId, textureId);
                }
            });

    private ClientProtocolGateway() {
    }

    public static void tick(LocalPlayer player, PlayerAnimatableCapability capability) {
        MODEL_DEMAND.tick();
        STATE_REPORTER.tick(player, capability);
        adaptSelfSelectionToSkin(capability);
    }

    /**
     * 把本地玩家当前使用的内置默认模型按皮肤类型改选为 Steve/Alex。
     *
     * <p>服务端无法得知玩家皮肤（1.20.1 的皮肤模型名只有客户端可得），因此默认加载的皮肤适配
     * 必须由客户端发起：只要本地玩家当前应用的是 intrinsic default，而皮肤要求的是另一个内置
     * 玩家模型，就发送一次显式选择。目标一致或玩家已选别的模型时不做任何事；重复调用由
     * {@link LocalPlayerDemand} 按键去重，不会重复发包。
     */
    private static void adaptSelfSelectionToSkin(PlayerAnimatableCapability capability) {
        if (!NetworkHandler.isRemoteChannelPresent()
                || ClientSessionRuntime.businessSession().isEmpty()) {
            return;
        }
        var service = ClientModelService.current().orElse(null);
        if (service == null) {
            return;
        }
        var matched = skinMatchedBuiltinPlayer();
        if (matched == null) {
            return;
        }
        var applied = capability.getModelHash();
        if (matched.equals(applied)) {
            return;
        }
        if (!service.isBuiltinPlayerModel(applied)) {
            // 当前不是内置玩家模型：玩家或服务端已给出别的选择，不覆盖。
            // 这里必须同时接受宽与细两个内置模型：只认 intrinsic default 会让适配变成单向的，
            // 一旦切到细手臂就再也切不回来。
            return;
        }
        // 空纹理名由服务端按目标模型自身的默认贴图补齐。
        selectModel(matched, "");
    }

    public static void resetWorld() {
        MODEL_DEMAND.reset();
        STATE_REPORTER.resetSession();
    }

    public static void playSelfAnimation(String animationId) {
        STATE_REPORTER.setAnimation(animationId);
    }

    public static void stopSelfAnimation() {
        STATE_REPORTER.setAnimation("");
    }

    public static void acceptAuthoritativeFull(Hash256 modelHash, Integer roamingKey) {
        STATE_REPORTER.acceptAuthoritativeFull(modelHash, roamingKey);
    }

    public static void localPlayerCloned() {
        STATE_REPORTER.restartReportLifetime();
    }

    public static void playMaidAnimation(int entityId, int index, String classificationId) {
        send(ProtocolMessages.ENTITY_ANIMATION_ACTION_REQUEST_ID,
                EntityAnimationActionRequest.newBuilder()
                        .setTarget(EntityRefEncoder.encode(entityId))
                        .setPlay(RouletteAnimationSelection.newBuilder()
                                .setAnimationIndex(index)
                                .setClassificationId(classificationId)
                                .build())
                        .build());
    }

    public static void stopMaidAnimation(int entityId) {
        send(ProtocolMessages.ENTITY_ANIMATION_ACTION_REQUEST_ID,
                EntityAnimationActionRequest.newBuilder()
                        .setTarget(EntityRefEncoder.encode(entityId))
                        .setStop(true)
                        .build());
    }

    public static void selectModel(Hash256 hash, String textureId) {
        MODEL_DEMAND.select(hash, textureId);
    }

    private static void sendModelSelection(Hash256 hash, String textureId) {
        STATE_REPORTER.setAnimation("");
        // 没有显式选择时不再依赖已删除的 default 模型：按玩家皮肤类型（default/slim）
        // 选择内置的 Steve / Alex。只有本地目录还没有该模型时才退回 intrinsic default。
        var resolved = hash != null ? hash : skinMatchedBuiltinPlayer();
        var request = SelectModelRequest.newBuilder()
                .setTextureId(textureId);
        if (resolved == null) {
            request.setIntrinsicDefault(true);
        } else {
            request.setModelId(ByteBuffer.wrap(resolved.bytes()));
        }
        send(ProtocolMessages.SELECT_MODEL_REQUEST_ID, request.build());
    }

    /**
     * Resolves the builtin vanilla player model that matches the local player's skin.
     *
     * <p>{@link AbstractClientPlayer#getModelName()} returns {@code "default"} for the wide skin
     * and {@code "slim"} for the narrow one; {@link PlayerInfo#getModelName()} is the same value
     * behind it. Returns {@code null} when the local catalog cannot resolve the model yet, in which
     * case the server falls back to the intrinsic default.
     */
    private static Hash256 skinMatchedBuiltinPlayer() {
        var player = Minecraft.getInstance().player;
        var service = ClientModelService.current().orElse(null);
        if (player == null || service == null) {
            return null;
        }
        // 必须和本地渲染用同一份答案：否则本地已按 Quick Skin 的预设切成细手臂，
        // 这里仍读原版皮肤名（宽），匹配不上就不发包，服务端一直存着宽手臂。
        var path = BuiltinModelIndex.playerPathForSkin(SkinModelNames.resolve(player));
        return service.resolvePath(path.value()).orElse(null);
    }

    private static long monotonicMillis() {
        return System.nanoTime() / 1_000_000L;
    }

    public static void updateStar(Hash256 hash, boolean add) {
        send(ProtocolMessages.UPDATE_STARRED_MODEL_REQUEST_ID,
                UpdateStarredModelRequest.newBuilder()
                        .setModelHash(ByteBuffer.wrap(hash.bytes()))
                        .setOperation(add ? StarredModelOperation.STARRED_MODEL_OPERATION_ADD
                                : StarredModelOperation.STARRED_MODEL_OPERATION_REMOVE)
                        .build());
    }

    public static void submitRouletteExpression(Entity entity, String expression) {
        send(ProtocolMessages.SUBMIT_ROULETTE_EXPRESSION_REQUEST_ID,
                SubmitRouletteExpressionRequest.newBuilder()
                        .setTarget(EntityRefEncoder.encode(entity.getId()))
                        .setExpression(expression).build());
    }

    public static void emitMolangSync(FloatList values) {
        var message = EmitMolangSync.newBuilder();
        values.forEach((float value) -> message.addArguments(value));
        send(ProtocolMessages.EMIT_MOLANG_SYNC_ID, message.build());
    }

    public static void swingHand(InteractionHand hand) {
        send(ProtocolMessages.SWING_HAND_REQUEST_ID, SwingHandRequest.newBuilder()
                .setHand(hand == InteractionHand.MAIN_HAND ? Hand.HAND_MAIN : Hand.HAND_OFF)
                .build());
    }

    public static void reportRoamingChanges(int modelKey, Object2FloatMap<String> values) {
        // The reporter samples the authoritative local roaming structure on the next client tick.
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void send(int id, ProtoMessage message) {
        var spec = ProtocolMessages.REGISTRY.find(id).orElse(null);
        if (spec != null && spec.messageType().isInstance(message)
                && NetworkHandler.isRemoteChannelPresent()
                && ClientSessionRuntime.businessSession().isPresent()) {
            try {
                NetworkHandler.sendToServer(message);
            } catch (RuntimeException ignored) {
            }
        }
    }
}
