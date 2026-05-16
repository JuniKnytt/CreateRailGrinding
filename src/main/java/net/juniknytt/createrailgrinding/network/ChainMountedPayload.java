package net.juniknytt.createrailgrinding.network;

import io.netty.buffer.ByteBuf;
import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: the local player just wrench-mounted a Create chain conveyor. Sent the
 * same client tick {@code ChainConveyorRidingHandler.ridingChainConveyor} flips to non-null
 * (i.e. the tick the wrench interaction calls {@code embark}). Without this the only signal
 * the server-side grind has is {@code ServerChainConveyorHandler.hangingPlayers}, which is
 * populated by Create's TTL packet — fired only every 10 client ticks from
 * {@code ChainConveyorRidingHandler.clientTick}, so the per-tick handoff check in
 * {@link net.juniknytt.createrailgrinding.rail.RailGrindHandler#tick} can lag up to ~10
 * ticks behind the actual mount. This payload narrows that to ~1 tick.
 */
public record ChainMountedPayload() implements CustomPacketPayload {
    public static final ChainMountedPayload INSTANCE = new ChainMountedPayload();

    public static final Type<ChainMountedPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(RailGrind.MODID, "chain_mounted"));

    public static final StreamCodec<ByteBuf, ChainMountedPayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
