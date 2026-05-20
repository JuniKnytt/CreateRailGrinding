package net.juniknytt.createrailgrinding.network;

import io.netty.buffer.ByteBuf;
import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record GrindParticleBurstPayload(UUID playerId,
                                        float tangentX, float tangentY, float tangentZ,
                                        float speedRatio,
                                        byte count) implements CustomPacketPayload {
    public static final Type<GrindParticleBurstPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(RailGrind.MODID, "grind_particle_burst"));

    public static final StreamCodec<ByteBuf, GrindParticleBurstPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            UUIDUtil.STREAM_CODEC.encode(buf, p.playerId);
            buf.writeFloat(p.tangentX);
            buf.writeFloat(p.tangentY);
            buf.writeFloat(p.tangentZ);
            buf.writeFloat(p.speedRatio);
            buf.writeByte(p.count);
        },
        buf -> new GrindParticleBurstPayload(
            UUIDUtil.STREAM_CODEC.decode(buf),
            buf.readFloat(), buf.readFloat(), buf.readFloat(),
            buf.readFloat(),
            buf.readByte()
        )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
