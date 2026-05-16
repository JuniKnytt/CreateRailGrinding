package net.juniknytt.createrailgrinding.client;

import net.juniknytt.createrailgrinding.Config;
import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Rolls the camera up to {@value #LEAN_ROLL_DEG}° about its forward axis for the local
 * grinding player when their steer-input lean is non-zero. Gated by the client-side
 * {@link Config#CAMERA_ROLL_ON_LEAN} bool (default off) — some players find any camera roll
 * uncomfortable, so the rest of the lean visual (model tilt + camera lateral/down offset)
 * applies unconditionally and only the roll is opt-in.
 *
 * <h2>Why ViewportEvent and not a mixin</h2>
 * The event fires inside {@code Camera.setup} immediately before {@code setRotation(yaw,
 * pitch, roll)} is called — its {@code getRoll()} value is the third argument to that
 * call, which baked into the camera's rotation quaternion via the {@code rotationYXZ}
 * Z-component. {@link ViewportEvent.ComputeCameraAngles} is NeoForge's intended extension
 * point for that roll value — no mixin needed.
 *
 * <h2>Sign convention</h2>
 * Inside {@code Camera.setRotation} the roll feeds as {@code -roll*π/180} into the Z slot
 * of {@code rotationYXZ}, effectively rolling around the camera's forward axis. Empirical
 * sign: positive roll values tilt the rendered scene to match a head-tilt in the same
 * direction. We want lean=+1 (player presses D = lean right) to roll with the lean, so we
 * add {@code +lean * LEAN_ROLL_DEG} to the existing roll. If testing shows the roll going
 * the wrong direction, negate this term — easier than re-deriving the YXZ convention.
 */
@EventBusSubscriber(modid = RailGrind.MODID, value = Dist.CLIENT)
public final class RailGrindCameraRoll {
    /**
     * Peak roll angle in degrees at full lean. Smoothed lean scalar in {@code [-1, +1]}
     * scales this linearly, matching {@code LEAN_MAX_DEGREES} in the body-tilt mixin so the
     * camera and 3rd-person body roll in lockstep when the player views their own model.
     */
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
        // Add to the existing roll rather than overwriting, so any other roll source
        // (shader mod, future feature) stacks instead of being clobbered.
        event.setRoll(event.getRoll() + lean * LEAN_ROLL_DEG);
    }
}
