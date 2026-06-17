package net.juniknytt.createrailgrinding.event;

import net.juniknytt.createrailgrinding.Config;
import net.juniknytt.createrailgrinding.RailGrind;
import net.juniknytt.createrailgrinding.advancement.ModTriggers;
import net.juniknytt.createrailgrinding.cosmetic.CustomBootSkin;
import net.juniknytt.createrailgrinding.effect.ModEffects;
import net.juniknytt.createrailgrinding.rail.RailGrindHandler;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityTeleportEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.living.LivingEquipmentChangeEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = RailGrind.MODID)
public class ModEvents
{
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Player player = event.player;
        if (player.level().isClientSide) return;

        RailGrindHandler.tickCooldown(player);

        RailGrindHandler.tickPortalTransitCooldown(player);

        RailGrindHandler.tickTrainOverlap(player);

        RailGrindHandler.tickPendingRegrind(player);
        RailGrindHandler.tick(player);

        if (Config.SYNC_DEBUG_TO_CLIENTS.get() && player instanceof ServerPlayer sp) {
            RailGrindHandler.broadcastDebugSnapshot(sp);
        }
    }

    @SubscribeEvent
    public static void onPlayerTickGrantBootsAdvancement(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer sp)) return;
        if (sp.tickCount % 20 != 0) return;
        if (!hasCustomBoots(sp)) return;
        ModTriggers.OBTAIN_CUSTOM_BOOTS.trigger(sp);
    }

    @SubscribeEvent
    public static void onPlayerTickGrantEffectAdvancement(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer sp)) return;
        if (!RailGrindHandler.isGrinding(sp)) return;
        if (!sp.hasEffect(ModEffects.SONIC_WIND.get())) return;
        ModTriggers.RAIL_GRIND_EFFECT.trigger(sp);
    }

    private static boolean hasCustomBoots(ServerPlayer sp) {
        Inventory inventory = sp.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (CustomBootSkin.matches(inventory.getItem(i))) return true;
        }
        return false;
    }

    @SubscribeEvent
    public static void onIncomingDamage(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        if (RailGrindHandler.isInReattachGrace(player)) {
            event.setCanceled(true);
            return;
        }
        if (!event.getSource().is(DamageTypes.FALL)) return;
        if (RailGrindHandler.hasFallImmunity(player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onIncomingDamageStopGrind(LivingHurtEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide) return;
        if (!RailGrindHandler.isGrinding(player)) return;

        if (RailGrindHandler.isInReattachGrace(player)) return;
        RailGrindHandler.stop(player, RailGrindHandler.StopReason.DAMAGE);
    }

    @SubscribeEvent
    public static void onBootsChangeStopGrind(LivingEquipmentChangeEvent event) {
        if (event.getSlot() != EquipmentSlot.FEET) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide) return;
        if (!RailGrindHandler.isGrinding(player)) return;
        RailGrindHandler.stop(player, RailGrindHandler.StopReason.BOOTS_SWAP);
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        clearGrindState(event.getEntity());
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        clearGrindState(event.getEntity());
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        clearGrindState(event.getEntity());
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (!RailGrindHandler.isGrinding(sp)) return;
        RailGrindHandler.handleDimensionChange(sp);
    }

    @SubscribeEvent
    public static void onTeleportStopGrind(EntityTeleportEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide) return;
        if (!RailGrindHandler.isGrinding(player)) return;
        RailGrindHandler.stop(player, RailGrindHandler.StopReason.EXTERNAL_TELEPORT);
    }

    @SubscribeEvent
    public static void onStartTrackingPlayer(PlayerEvent.StartTracking event) {
        if (!(event.getEntity() instanceof ServerPlayer observer)) return;
        if (!(event.getTarget() instanceof ServerPlayer target)) return;
        RailGrindHandler.syncStateToObserver(observer, target);
    }

    private static void clearGrindState(Player player) {

        RailGrindHandler.stop(player, RailGrindHandler.StopReason.SESSION_BOUNDARY);

        player.setNoGravity(false);
        player.noPhysics = false;
        player.fallDistance = 0.0F;
    }

    private static final ResourceLocation BAR_OF_CHOCOLATE = new ResourceLocation("create", "bar_of_chocolate");

    @SubscribeEvent
    public static void onItemUseFinish(LivingEntityUseItemEvent.Finish event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide) return;
        ItemStack stack = event.getItem();
        if (stack.isEmpty()) return;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!BAR_OF_CHOCOLATE.equals(id)) return;
        player.addEffect(new MobEffectInstance(
                ModEffects.SONIC_WIND.get(),
                ModEffects.SONIC_WIND_DURATION_TICKS,
                0,
                false,
                false,
                true));
    }
}
