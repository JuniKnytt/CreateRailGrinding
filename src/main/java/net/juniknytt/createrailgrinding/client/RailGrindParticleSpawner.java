package net.juniknytt.createrailgrinding.client;

import net.juniknytt.createrailgrinding.network.GrindParticleBurstPayload;
import net.juniknytt.createrailgrinding.particle.ModParticles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;

public final class RailGrindParticleSpawner {
    private static final double GRIND_PARTICLE_SPARK_SPEED_RATIO = 0.50;
    private static final double BACK_OFFSET = 0.4;

    private static final double SPARK_BASE_HORIZONTAL_SPEED = 0.15;
    private static final double SPARK_SPEED_BOOST_PER_RATIO = 0.35;
    private static final double SPARK_UPWARD_KICK           = 0.20;
    private static final double SPARK_UPWARD_JITTER         = 0.08;
    private static final double SPARK_FAN_SPREAD_RADIANS    = Math.PI / 4.0;

    private RailGrindParticleSpawner() {}

    public static void spawn(GrindParticleBurstPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        Player player = level.getPlayerByUUID(payload.playerId());
        if (player == null) return;

        double rx = player.getX();
        double ry = player.getY();
        double rz = player.getZ();

        double tx = payload.tangentX();
        double ty = payload.tangentY();
        double tz = payload.tangentZ();

        double sx = rx - tx * BACK_OFFSET;
        double sy = ry + 0.05 - ty * BACK_OFFSET;
        double sz = rz - tz * BACK_OFFSET;

        double speedRatio = payload.speedRatio();
        int count = payload.count();
        double spread = 0.10 + speedRatio * 0.12;

        RandomSource rng = level.getRandom();
        for (int i = 0; i < count; i++) {
            double ox = rng.nextGaussian() * spread;
            double oy = rng.nextGaussian() * 0.05;
            double oz = rng.nextGaussian() * spread;
            level.addParticle(ParticleTypes.CRIT, sx + ox, sy + oy, sz + oz, 0.0, 0.0, 0.0);
        }

        if (speedRatio >= GRIND_PARTICLE_SPARK_SPEED_RATIO) {
            spawnSparks(level, sx, sy, sz, tx, tz, speedRatio, count, rng);
        }
    }

    private static void spawnSparks(ClientLevel level, double sx, double sy, double sz,
                                    double tx, double tz,
                                    double speedRatio, int count, RandomSource rng) {
        double horizLen = Math.sqrt(tx * tx + tz * tz);
        if (horizLen < 1e-6) return;
        double ntx = tx / horizLen;
        double ntz = tz / horizLen;
        double horizSpeed = SPARK_BASE_HORIZONTAL_SPEED + SPARK_SPEED_BOOST_PER_RATIO * speedRatio;

        for (int i = 0; i < count; i++) {
            double yaw = (rng.nextDouble() - 0.5) * SPARK_FAN_SPREAD_RADIANS;
            double cos = Math.cos(yaw), sin = Math.sin(yaw);
            double dirX = -ntx * cos + ntz * sin;
            double dirZ = -ntz * cos - ntx * sin;
            double vx = dirX * horizSpeed;
            double vz = dirZ * horizSpeed;
            double vy = (SPARK_UPWARD_KICK + rng.nextDouble() * SPARK_UPWARD_JITTER) * speedRatio;
            level.addParticle(ModParticles.SPARK.get(), sx, sy, sz, vx, vy, vz);
        }
    }
}
