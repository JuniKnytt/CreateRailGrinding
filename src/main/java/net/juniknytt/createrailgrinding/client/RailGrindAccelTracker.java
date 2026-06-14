package net.juniknytt.createrailgrinding.client;

import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@EventBusSubscriber(modid = RailGrind.MODID, value = Dist.CLIENT)
public final class RailGrindAccelTracker {

    private static final Set<UUID> ACCELERATING = ConcurrentHashMap.newKeySet();

    private RailGrindAccelTracker() {}

    public static void setAccelerating(UUID id, boolean accelerating) {
        if (accelerating) ACCELERATING.add(id);
        else ACCELERATING.remove(id);
    }

    public static boolean isAccelerating(UUID id) {
        return ACCELERATING.contains(id);
    }

    public static void clearAll() {
        ACCELERATING.clear();
    }

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            ACCELERATING.clear();
            return;
        }
        ACCELERATING.removeIf(id -> {
            Player p = mc.level.getPlayerByUUID(id);
            return p == null || !BalancingPoseTracker.isBalancing(p);
        });
    }
}
