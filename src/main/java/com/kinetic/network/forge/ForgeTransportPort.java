package com.kinetic.network.forge;

import com.kinetic.network.NetworkHandler;
import com.kinetic.network.dispatch.SendResult;
import com.kinetic.network.dispatch.TransportPort;
import com.kinetic.network.frame.OutboundFrame;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import java.util.Objects;

/** Netty-specific pressure and submission details kept behind TransportPort. */
public final class ForgeTransportPort implements TransportPort {
    private final ServerPlayer player;

    public ForgeTransportPort(ServerPlayer player) {
        this.player = Objects.requireNonNull(player, "player");
    }

    @Override
    public boolean isOpen() {
        return player.connection != null && player.connection.connection.isConnected();
    }

    @Override
    public boolean isWritable() {
        return isOpen() && player.connection.connection.channel().isWritable();
    }

    @Override
    public long highWatermarkBytes() {
        return player.connection.connection.channel().config().getWriteBufferHighWaterMark();
    }

    @Override
    public long pendingBytes() {
        var outbound = player.connection.connection.channel().unsafe().outboundBuffer();
        return outbound == null ? 0 : outbound.totalPendingWriteBytes();
    }

    @Override
    public SendResult trySend(OutboundFrame frame) {
        if (!isOpen()) {
            return SendResult.FAILED;
        }
        try {
            NetworkHandler.sendFrame(PacketDistributor.PLAYER.with(() -> player), frame);
            return SendResult.SUCCESS;
        } catch (RuntimeException failure) {
            return SendResult.FAILED;
        }
    }
}
