package net.juniknytt.createrailgrinding.client;

import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@EventBusSubscriber(modid = RailGrind.MODID, value = Dist.CLIENT)
public final class RailGrindLeanTracker {

    private static final float TICK_FACTOR = 0.18f;

    private static final Map<UUID, Float> RAW = new ConcurrentHashMap<>();

    private static final Map<UUID, Float> CURR = new HashMap<>();

    private static final Map<UUID, Float> PREV = new HashMap<>();

    private RailGrindLeanTracker() {}

    public static void setRawSign(UUID id, int sign) {
        float clamped = sign > 0 ? 1.0f : (sign < 0 ? -1.0f : 0.0f);
        RAW.put(id, clamped);
    }

    public static void clearAll() {
        RAW.clear();
        CURR.clear();
        PREV.clear();
    }

    public static float getRenderLean(Player player, float partialTicks) {
        UUID id = player.getUUID();
        Float curr = CURR.get(id);
        if (curr == null) return 0.0f;
        Float prev = PREV.get(id);
        if (prev == null) prev = curr;
        return Mth.lerp(partialTicks, prev, curr);
    }

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            clearAll();
            return;
        }

        Iterator<Map.Entry<UUID, Float>> iter = RAW.entrySet().iterator();
        while (iter.hasNext()) {
            UUID id = iter.next().getKey();
            Player p = mc.level.getPlayerByUUID(id);
            if (p == null || !BalancingPoseTracker.isBalancing(p)) {
                iter.remove();
                CURR.remove(id);
                PREV.remove(id);
            }
        }

        for (Map.Entry<UUID, Float> e : RAW.entrySet()) {
            UUID id = e.getKey();
            float raw = e.getValue();
            Float curr = CURR.get(id);
            if (curr == null) {

                CURR.put(id, raw);
                PREV.put(id, raw);
            } else {
                PREV.put(id, curr);
                CURR.put(id, curr + (raw - curr) * TICK_FACTOR);
            }
        }
    }
}
