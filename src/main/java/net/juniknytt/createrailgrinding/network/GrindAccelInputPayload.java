package net.juniknytt.createrailgrinding.network;

import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: which input source the server should use to decide if the player is
 * accelerating while grinding. Sent only on transitions, like {@link SteerInputPayload}.
 *
 * <ul>
 *   <li>{@link #VANILLA} — server polls {@code player.isShiftKeyDown()} (default behaviour).
 *   <li>{@link #OVERRIDE_OFF} — override mode active, override key not held → not accelerating.
 *   <li>{@link #OVERRIDE_ON} — override mode active, override key held → accelerating.
 * </ul>
 *
 * <p>The mode bit is what's actually load-bearing on the server: it lets us read the override
 * state without the server needing access to the client's config or KeyMapping. When the player
 * disables the config or unbinds the key, the client transitions back to {@code VANILLA} and the
 * server resumes shift-polling.
 */
public record GrindAccelInputPayload(byte mode) implements CustomPacketPayload {
    public static final byte VANILLA = 0;
    public static final byte OVERRIDE_OFF = 1;
    public static final byte OVERRIDE_ON = 2;

    public static final Type<GrindAccelInputPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(RailGrind.MODID, "grind_accel_input"));

    public static final StreamCodec<FriendlyByteBuf, GrindAccelInputPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> buf.writeByte(payload.mode),
            buf -> new GrindAccelInputPayload(buf.readByte())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
