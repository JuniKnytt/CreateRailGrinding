package net.juniknytt.createrailgrinding.mixin;

import net.juniknytt.createrailgrinding.rail.RailGrindHandler;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerMovePacketMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
    private void createrailgrinding$suppressDuringCrossDim(
            ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (player != null && RailGrindHandler.shouldRejectMoveDuringCrossDim(player)) {
            ci.cancel();
        }
    }
}
