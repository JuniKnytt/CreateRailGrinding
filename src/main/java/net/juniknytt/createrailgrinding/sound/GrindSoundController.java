package net.juniknytt.createrailgrinding.sound;

import net.juniknytt.createrailgrinding.Config;
import net.juniknytt.createrailgrinding.RailGrind;
import net.juniknytt.createrailgrinding.client.BalancingPoseTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Client-side controller for rail-grind sound playback.
 * Mirrors {@code RailGrindHandler.TOP_SPEED} via the local constant below — keep them in sync.
 */
@EventBusSubscriber(modid = RailGrind.MODID, value = Dist.CLIENT)
public final class GrindSoundController {
    private static final double MAX_SPEED = 0.84;                   // = RailGrindHandler.TOP_SPEED
    private static final double SLOW_RAMP_END = MAX_SPEED * 0.25;   // slow pitch ramp ends at 25%
    private static final double FAST_RAMP_START = MAX_SPEED * 0.50; // fast loop joins in at 50%
    private static final double FAST_RAMP_END = MAX_SPEED * 0.75;   // fast reaches normal pitch at 75%
    private static final float  SLOW_PITCH_START = 0.5f;            // slow pitch at speed 0
    private static final float  FAST_PITCH_START = 0.5f;            // fast pitch at FAST_RAMP_START
    private static final float  NORMAL_PITCH = 1.0f;                // top of every ramp
    private static final float  LOOP_VOLUME_MAX = 0.35f;            // peak loop volume (lowered from 0.6)
    private static final float  SLOW_VOLUME_START = 0.10f;          // slow volume at speed 0 — quiet hum
    private static final float  FAST_VOLUME_START = 0.05f;          // fast volume at FAST_RAMP_START — near-silent fade-in
    private static final float  COLLIDE_VOLUME = 0.6f;              // one-shot grind-start (lowered from 1.0)
    private static final double SPEED_SMOOTH_RATE = 0.08;           // max per-tick change in audible speed. Server max accel (boosted) ≈ 0.06/tick — anything above that is treated as an abrupt change (snap-to-rail catch-up, network blip) and rate-limited so the sound doesn't tear.

    private static GrindLoop slowLoop;
    private static GrindLoop fastLoop;
    private static Vec3 prevPos;
    private static double smoothedSpeed;

    private GrindSoundController() {}

    /** Fire-and-forget one-shot. Called from {@code ClientPayloadHandler.handleSync} on grind start. */
    public static void playCollide() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.getSoundManager().play(SimpleSoundInstance.forUI(
            ModSounds.GRIND_COLLIDE.get(), 1.0f, COLLIDE_VOLUME * userVolume()));
    }

    @SubscribeEvent
    public static void onClientPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof LocalPlayer local)) return;

        if (!BalancingPoseTracker.isBalancing(local)) {
            stopAllLoops();
            prevPos = null;
            return;
        }

        // Raw speed = 3D position delta per tick. With noPhysics this matches server-side
        // currentSpeed, but a one-tick snap-to-rail catch-up step can briefly spike well past
        // any plausible accel. Detect that abrupt change and rate-limit how fast `smoothedSpeed`
        // tracks the raw reading; sound pitch/volume/loop transitions all key off the smoothed
        // value, so spikes fade in/out instead of slamming.
        Vec3 currentPos = local.position();
        double rawSpeed = (prevPos == null) ? 0.0 : currentPos.subtract(prevPos).length();
        prevPos = currentPos;
        double delta = rawSpeed - smoothedSpeed;
        if (Math.abs(delta) > SPEED_SMOOTH_RATE) {
            smoothedSpeed += Math.signum(delta) * SPEED_SMOOTH_RATE;
        } else {
            smoothedSpeed = rawSpeed;
        }
        double speed = smoothedSpeed;

        // Slow loop runs the entire grind. Pitch and volume both ramp up over the first 25%
        // and then hold steady.
        if (slowLoop == null) {
            slowLoop = new GrindLoop(local, ModSounds.GRIND_SLOW_LOOP.get());
            Minecraft.getInstance().getSoundManager().play(slowLoop);
        }
        slowLoop.setPitch(computeSlowPitch(speed));
        slowLoop.setVolume(computeSlowVolume(speed) * userVolume());

        // Fast loop joins at 50%, layered on top of slow. Pitch and volume both ramp up
        // across [50%, 75%] then hold at peak for any speed beyond.
        if (speed >= FAST_RAMP_START) {
            if (fastLoop == null) {
                fastLoop = new GrindLoop(local, ModSounds.GRIND_FAST_LOOP.get());
                Minecraft.getInstance().getSoundManager().play(fastLoop);
            }
            fastLoop.setPitch(computeFastPitch(speed));
            fastLoop.setVolume(computeFastVolume(speed) * userVolume());
        } else if (fastLoop != null) {
            fastLoop.cancel();
            fastLoop = null;
        }
    }

    private static void stopAllLoops() {
        if (slowLoop != null) {
            slowLoop.cancel();
            slowLoop = null;
        }
        if (fastLoop != null) {
            fastLoop.cancel();
            fastLoop = null;
        }
        smoothedSpeed = 0.0;
    }

    private static float userVolume() {
        return Config.SOUND_VOLUME.get().floatValue();
    }

    private static float computeSlowPitch(double speed) {
        if (speed >= SLOW_RAMP_END) return NORMAL_PITCH;
        // Ramp from SLOW_PITCH_START at speed 0 up to NORMAL_PITCH at 25% of MAX_SPEED.
        float t = (float) Mth.clamp(speed / SLOW_RAMP_END, 0.0, 1.0);
        return Mth.lerp(t, SLOW_PITCH_START, NORMAL_PITCH);
    }

    private static float computeFastPitch(double speed) {
        if (speed >= FAST_RAMP_END) return NORMAL_PITCH;
        // Ramp from FAST_PITCH_START at FAST_RAMP_START up to NORMAL_PITCH at FAST_RAMP_END.
        float t = (float) Mth.clamp(
            (speed - FAST_RAMP_START) / (FAST_RAMP_END - FAST_RAMP_START), 0.0, 1.0);
        return Mth.lerp(t, FAST_PITCH_START, NORMAL_PITCH);
    }

    private static float computeSlowVolume(double speed) {
        if (speed >= SLOW_RAMP_END) return LOOP_VOLUME_MAX;
        float t = (float) Mth.clamp(speed / SLOW_RAMP_END, 0.0, 1.0);
        return Mth.lerp(t, SLOW_VOLUME_START, LOOP_VOLUME_MAX);
    }

    private static float computeFastVolume(double speed) {
        if (speed >= FAST_RAMP_END) return LOOP_VOLUME_MAX;
        float t = (float) Mth.clamp(
            (speed - FAST_RAMP_START) / (FAST_RAMP_END - FAST_RAMP_START), 0.0, 1.0);
        return Mth.lerp(t, FAST_VOLUME_START, LOOP_VOLUME_MAX);
    }

    private static final class GrindLoop extends AbstractTickableSoundInstance {
        final SoundEvent kind;
        final LocalPlayer player;

        GrindLoop(LocalPlayer player, SoundEvent kind) {
            super(kind, SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
            this.kind = kind;
            this.player = player;
            this.looping = true;
            this.delay = 0;
            // Start quiet — first tick will set the proper computed volume/pitch.
            this.volume = FAST_VOLUME_START;
            this.pitch = SLOW_PITCH_START;
            this.x = (float) player.getX();
            this.y = (float) player.getY();
            this.z = (float) player.getZ();
        }

        @Override
        public void tick() {
            if (!player.isAlive() || !BalancingPoseTracker.isBalancing(player)) {
                stop();
                return;
            }
            x = (float) player.getX();
            y = (float) player.getY();
            z = (float) player.getZ();
        }

        // Inner class can touch inherited `protected` members directly; outer class can't,
        // because `pitch` and `stop()` are declared in net.minecraft.client.resources.sounds
        // (a different package). `stop()` is final in AbstractTickableSoundInstance, so wrap
        // it under a different name rather than overriding.
        void cancel() {
            stop();
        }

        void setPitch(float pitch) {
            this.pitch = pitch;
        }

        void setVolume(float volume) {
            this.volume = volume;
        }
    }
}
