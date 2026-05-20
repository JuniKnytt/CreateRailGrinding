package net.juniknytt.createrailgrinding.sound;

import net.juniknytt.createrailgrinding.Config;
import net.juniknytt.createrailgrinding.RailGrind;
import net.juniknytt.createrailgrinding.client.BalancingPoseTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@EventBusSubscriber(modid = RailGrind.MODID, value = Dist.CLIENT)
public final class GrindSoundController {
    private static double maxSpeed()      { return Config.TOP_GRIND_SPEED.get(); }
    private static double slowRampEnd()   { return maxSpeed() * 0.50; }
    private static double fastRampStart() { return maxSpeed() * 0.50; }
    private static double fastRampEnd()   { return maxSpeed() * 0.75; }

    private static final double SPEED_CEILING = 2.0;
    private static final float  SLOW_PITCH_START = 0.25f;
    private static final float  NORMAL_PITCH = 1.0f;
    private static final float  HIGH_PITCH_MAX = 1.25f;
    private static final float  SLOW_VOLUME_START = 0.25f;
    private static final float  SLOW_VOLUME_MAX = 0.5f;
    private static final float  FAST_VOLUME_MAX = 0.35f;
    private static final float  COLLIDE_VOLUME = 0.6f;
    private static final double SPEED_SMOOTH_RATE = 0.08;

    private static final float  SLOW_PITCH_EASE_RATE = 0.10f;

    private static final float  FAST_VOLUME_EASE_RATE = 0.10f;

    private static final Map<UUID, PlayerSoundState> STATES = new HashMap<>();

    private GrindSoundController() {}

    public static void playCollide(Player player) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        mc.level.playLocalSound(
            player.getX(), player.getY(), player.getZ(),
            ModSounds.GRIND_COLLIDE.get(), SoundSource.PLAYERS,
            COLLIDE_VOLUME * userVolume(), 1.0f, false);
    }

    @SubscribeEvent
    public static void onClientPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();

        if (!player.level().isClientSide()) return;

        if (!Config.SERVER_SPEC.isLoaded()) return;

        UUID id = player.getUUID();
        if (!BalancingPoseTracker.isBalancing(player)) {
            PlayerSoundState s = STATES.remove(id);
            if (s != null) s.stopAllLoops();
            return;
        }

        STATES.computeIfAbsent(id, k -> new PlayerSoundState()).tick(player);
    }

    public static void clearAll() {
        for (PlayerSoundState s : STATES.values()) s.stopAllLoops();
        STATES.clear();
    }

    private static float userVolume() {
        return Config.SOUND_VOLUME.get().floatValue();
    }

    private static float computeSlowPitch(double speed) {
        double lowRampEnd = slowRampEnd();
        if (speed < lowRampEnd) {

            float t = (float) (speed / lowRampEnd);
            return Mth.lerp(t, SLOW_PITCH_START, NORMAL_PITCH);
        }
        double cruise = maxSpeed();
        if (speed <= cruise) return NORMAL_PITCH;

        if (speed >= SPEED_CEILING) return HIGH_PITCH_MAX;
        float t = (float) ((speed - cruise) / (SPEED_CEILING - cruise));
        return Mth.lerp(t, NORMAL_PITCH, HIGH_PITCH_MAX);
    }

    private static float computeSlowVolume(double speed) {
        double rampEnd = slowRampEnd();
        if (speed >= rampEnd) return SLOW_VOLUME_MAX;
        float t = (float) Mth.clamp(speed / rampEnd, 0.0, 1.0);
        return Mth.lerp(t, SLOW_VOLUME_START, SLOW_VOLUME_MAX);
    }

    private static float computeFastVolume(double speed) {
        double rampStart = fastRampStart();
        double rampEnd = fastRampEnd();
        if (speed >= rampEnd) return FAST_VOLUME_MAX;
        if (speed <= rampStart) return 0.0f;
        float t = (float) ((speed - rampStart) / (rampEnd - rampStart));
        return t * FAST_VOLUME_MAX;
    }

    private static final class PlayerSoundState {
        GrindLoop slowLoop;
        GrindLoop fastLoop;
        Vec3 prevPos;
        double smoothedSpeed;
        float smoothedSlowPitch = SLOW_PITCH_START;
        float smoothedFastVolume = 0.0f;

        boolean needsSeeding = true;

        void tick(Player player) {

            Vec3 currentPos = player.position();
            double rawSpeed = (prevPos == null) ? 0.0 : currentPos.subtract(prevPos).length();
            prevPos = currentPos;

            if (needsSeeding && rawSpeed > 0.01) {

                smoothedSpeed = rawSpeed;
                smoothedSlowPitch = computeSlowPitch(rawSpeed);
                smoothedFastVolume = computeFastVolume(rawSpeed);
                needsSeeding = false;
            } else {

                double delta = rawSpeed - smoothedSpeed;
                if (Math.abs(delta) > SPEED_SMOOTH_RATE) {
                    smoothedSpeed += Math.signum(delta) * SPEED_SMOOTH_RATE;
                } else {
                    smoothedSpeed = rawSpeed;
                }

                smoothedSlowPitch = Mth.lerp(SLOW_PITCH_EASE_RATE, smoothedSlowPitch, computeSlowPitch(smoothedSpeed));
                smoothedFastVolume = Mth.lerp(FAST_VOLUME_EASE_RATE, smoothedFastVolume, computeFastVolume(smoothedSpeed));
            }

            if (slowLoop == null) {
                slowLoop = new GrindLoop(player, ModSounds.GRIND_SLOW_LOOP.get());
                Minecraft.getInstance().getSoundManager().play(slowLoop);
            }
            if (fastLoop == null) {
                fastLoop = new GrindLoop(player, ModSounds.GRIND_FAST_LOOP.get());
                fastLoop.setPitch(NORMAL_PITCH);
                Minecraft.getInstance().getSoundManager().play(fastLoop);
            }
            slowLoop.setPitch(smoothedSlowPitch);
            slowLoop.setVolume(computeSlowVolume(smoothedSpeed) * userVolume());
            fastLoop.setVolume(smoothedFastVolume * userVolume());
        }

        void stopAllLoops() {
            if (slowLoop != null) {
                slowLoop.cancel();
                slowLoop = null;
            }
            if (fastLoop != null) {
                fastLoop.cancel();
                fastLoop = null;
            }
            smoothedSpeed = 0.0;
            smoothedSlowPitch = SLOW_PITCH_START;
            smoothedFastVolume = 0.0f;
            needsSeeding = true;
        }
    }

    private static final class GrindLoop extends AbstractTickableSoundInstance {
        final SoundEvent kind;
        final Player player;

        GrindLoop(Player player, SoundEvent kind) {
            super(kind, SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
            this.kind = kind;
            this.player = player;
            this.looping = true;
            this.delay = 0;

            this.volume = 0.05f;
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
