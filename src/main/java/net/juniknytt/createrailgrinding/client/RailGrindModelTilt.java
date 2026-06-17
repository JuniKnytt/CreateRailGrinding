package net.juniknytt.createrailgrinding.client;

import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = RailGrind.MODID, value = Dist.CLIENT)
public final class RailGrindModelTilt {

    private static final double HORIZ_EPSILON_SQ = 1.0e-6;

    private static final float TICK_SLERP = 0.20f;

    private static final Map<UUID, Quaternionf> CURR = new HashMap<>();

    private static final Map<UUID, Quaternionf> PREV = new HashMap<>();

    private RailGrindModelTilt() {}

    public static Quaternionf getRenderTilt(Player player, float partialTicks) {
        UUID id = player.getUUID();
        Quaternionf curr = CURR.get(id);
        if (curr == null) return null;
        Quaternionf prev = PREV.get(id);

        Quaternionf result = new Quaternionf(prev != null ? prev : curr);
        return result.slerp(curr, partialTicks);
    }

    @SubscribeEvent
    public static void onClientTickPost(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {

            CURR.clear();
            PREV.clear();
            return;
        }

        Iterator<UUID> iter = CURR.keySet().iterator();
        while (iter.hasNext()) {
            UUID id = iter.next();
            Player p = mc.level.getPlayerByUUID(id);
            if (p == null || !BalancingPoseTracker.isBalancing(p)) {
                iter.remove();
                PREV.remove(id);
            }
        }

        for (Player p : mc.level.players()) {
            if (!BalancingPoseTracker.isBalancing(p)) continue;
            UUID id = p.getUUID();
            Quaternionf raw = computeRawTilt(p);
            Quaternionf curr = CURR.get(id);
            if (curr == null) {

                if (raw == null) continue;
                CURR.put(id, new Quaternionf(raw));
                PREV.put(id, new Quaternionf(raw));
            } else {

                PREV.put(id, new Quaternionf(curr));
                if (raw != null) {
                    curr.slerp(raw, TICK_SLERP);
                }

            }
        }
    }

    private static Quaternionf computeRawTilt(Player player) {
        double dx = player.getX() - player.xo;
        double dy = player.getY() - player.yo;
        double dz = player.getZ() - player.zo;
        double horizSq = dx * dx + dz * dz;
        if (horizSq < HORIZ_EPSILON_SQ) return null;
        double horiz = Math.sqrt(horizSq);
        double mag = Math.sqrt(horizSq + dy * dy);
        double sinPitch = dy / mag;
        double cosPitch = horiz / mag;
        double invHoriz = 1.0 / horiz;
        float nx = (float) (-dx * invHoriz * sinPitch);
        float ny = (float) cosPitch;
        float nz = (float) (-dz * invHoriz * sinPitch);
        return new Quaternionf().rotationTo(0f, 1f, 0f, nx, ny, nz);
    }
}
