package net.juniknytt.createrailgrinding.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.juniknytt.createrailgrinding.client.BalancingPoseTracker;
import net.juniknytt.createrailgrinding.client.RailGrindLeanTracker;
import net.juniknytt.createrailgrinding.client.RailGrindModelTilt;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tilts a grinding player's rendered body in two composed rotations:
 * <ol>
 *   <li><b>Slope tilt</b> — aligns the model's local +Y with the upward-facing spline normal
 *       so the body pitches up when climbing and down when descending. Source axis derived
 *       from observed per-tick motion in {@link RailGrindModelTilt}.</li>
 *   <li><b>Lean tilt</b> — rolls the model 10° about its forward axis when the player holds
 *       the strafe keys. Source magnitude in {@code [-1, +1]} from {@link RailGrindLeanTracker};
 *       axis is the player's horizontal view direction (matches the camera roll the local
 *       player sees, and stays consistent for remote observers since the entity's yaw is
 *       synced).</li>
 * </ol>
 *
 * <h2>Composition order</h2>
 * The vanilla {@code Axis.YP.rotationDegrees(180 - yBodyRot)} body-yaw rotation runs after our
 * HEAD-injected pushes (since vanilla pushes it inside the same method). Pose-stack semantics:
 * {@code mulPose} post-multiplies, so the EARLIEST push is the OUTERMOST transform in the
 * model→world chain, applied LAST to a model-space vertex. We push lean first, slope second,
 * which means the vertex order is:
 * <pre>
 *   model_vertex → yaw → slope → lean → world
 * </pre>
 * Slope therefore acts on the world-aligned body (after yaw) — its axis is in world frame —
 * and lean then rolls the already-pitched body around its forward direction. The slope axis
 * is horizontal in world frame and the lean axis is horizontal in world frame; the two
 * commute on the +Y component but slope's pitch component changes how lean's roll lands, so
 * the order (slope-first, lean-second) matters for the visual.
 *
 * <h2>First-person view</h2>
 * Unaffected, same as the slope tilt: {@code PlayerRenderer.render} doesn't draw the full
 * body in 1st person, and {@code renderRightHand} calls only {@code setupAnim}, not
 * {@code setupRotations}, so this mixin never fires on the 1st-person hand. The local player
 * sees their own lean only via the camera mixin + viewport-event roll, never via this model
 * tilt.
 *
 * <h2>Why mixin on LivingEntityRenderer, not PlayerRenderer</h2>
 * {@code PlayerRenderer} inherits {@code setupRotations} from {@code LivingEntityRenderer}
 * without overriding it, so the LivingEntity-level injection is the only target that
 * actually fires for player rendering. The {@code instanceof Player} gate is the same
 * cost either way.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererTiltMixin {

    /**
     * Peak lean angle (model roll) at full steer-input magnitude. Degrees. Smoothed lean
     * scalar in {@code [-1, +1]} scales this linearly, so a half-pressed-then-released key
     * sequence produces a smoothly tapered roll between 0° and 10°.
     */
    private static final float LEAN_MAX_DEGREES = 10.0f;

    /**
     * Below this absolute lean scalar we skip building/pushing the lean quaternion. Keeps
     * the common case of straight-line grinding (lean ≈ 0) cheap — no quaternion alloc, no
     * pose-stack push.
     */
    private static final float LEAN_EPSILON = 1e-4f;

    @Inject(
        method = "setupRotations(Lnet/minecraft/world/entity/LivingEntity;Lcom/mojang/blaze3d/vertex/PoseStack;FFFF)V",
        at = @At("HEAD")
    )
    private void createrailgrinding$applyGrindTilt(
            LivingEntity entity, PoseStack poseStack,
            float ageInTicks, float rotationYaw, float partialTicks, float scale,
            CallbackInfo ci) {
        if (!(entity instanceof Player player)) return;
        if (!BalancingPoseTracker.isBalancing(player)) return;

        // Push lean FIRST so it's the outermost transform — applied to the slope-pitched
        // body, rolling the already-tilted figure around its forward axis. See class javadoc.
        float lean = RailGrindLeanTracker.getRenderLean(player, partialTicks);
        if (Math.abs(lean) >= LEAN_EPSILON) {
            float angleRad = lean * (float) Math.toRadians(LEAN_MAX_DEGREES);
            // Horizontal forward direction from the entity's interpolated yaw. Using yRot
            // (view yaw) rather than yBodyRot keeps the model-space lean axis identical to
            // the camera-roll axis the local player sees through ViewportEvent, so the 3rd-
            // person body and the 1st-person camera lean in lockstep on the same player.
            float yRotDeg = player.getViewYRot(partialTicks);
            float yRotRad = yRotDeg * (float) (Math.PI / 180.0);
            float fx = -Mth.sin(yRotRad);
            float fz = Mth.cos(yRotRad);
            // Unit axis already (fx² + fz² = 1). rotationAxis expects a normalized axis.
            poseStack.mulPose(new Quaternionf().rotationAxis(angleRad, fx, 0f, fz));
        }

        // Push slope SECOND so it's between yaw and lean in the vertex chain.
        Quaternionf slope = RailGrindModelTilt.getRenderTilt(player, partialTicks);
        if (slope != null) {
            poseStack.mulPose(slope);
        }
    }
}
