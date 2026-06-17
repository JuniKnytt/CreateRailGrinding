package net.juniknytt.createrailgrinding.client;

import net.juniknytt.createrailgrinding.Config;
import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = RailGrind.MODID, value = Dist.CLIENT)
public final class RailGrindCameraRoll {

    private static final float LEAN_ROLL_DEG = 10.0f;

    private static final float LEAN_EPSILON = 1e-4f;

    private RailGrindCameraRoll() {}

    @SubscribeEvent
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (!Config.CAMERA_ROLL_ON_LEAN.get()) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        if (!BalancingPoseTracker.isBalancing(player)) return;
        float lean = RailGrindLeanTracker.getRenderLean(player, (float) event.getPartialTick());
        if (Math.abs(lean) < LEAN_EPSILON) return;

        event.setRoll(event.getRoll() + lean * LEAN_ROLL_DEG);
    }
}
