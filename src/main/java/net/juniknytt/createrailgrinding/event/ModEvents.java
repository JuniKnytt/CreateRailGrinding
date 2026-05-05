package net.juniknytt.createrailgrinding.event;

import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackMaterial;
import net.juniknytt.createrailgrinding.RailGrind;
import net.juniknytt.createrailgrinding.network.Networking;
import net.juniknytt.createrailgrinding.rail.RailGrindHandler;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.createmod.catnip.levelWrappers.SchematicLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageTypes;
import net.createmod.catnip.data.Couple;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageBogey;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.entity.TravellingPoint.SteerDirection;
import com.simibubi.create.content.equipment.armor.DivingBootsItem;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackGraphHelper;
import com.simibubi.create.content.trains.graph.TrackGraphLocation;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.BezierTrackPointLocation;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.content.trains.track.TrackBlockOutline;
import com.simibubi.create.content.trains.track.TrackBlockOutline.BezierPointSelection;
import net.minecraft.world.entity.EquipmentSlot;
import com.simibubi.create.content.trains.track.TrackMaterial.TrackType;
import com.simibubi.create.content.trains.track.TrackTargetingBlockItem.OverlapResult;
import com.simibubi.create.foundation.utility.CreateLang;
import net.createmod.catnip.data.Couple;
import net.createmod.catnip.levelWrappers.SchematicLevel;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.mutable.MutableObject;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;


@EventBusSubscriber(modid = RailGrind.MODID)
public class ModEvents
{
    @SubscribeEvent
    public static void livingDamage(LivingDamageEvent.Pre event) {
        if (event.getEntity() instanceof Sheep sheep && event.getSource().getDirectEntity() instanceof Player player) {
            if (player.getMainHandItem().getItem() == Items.END_ROD) {
                player.sendSystemMessage(Component.literal("Worked"));
                sheep.remove(Entity.RemovalReason.KILLED);
                player.getMainHandItem().grow(1);


            }
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide) return;
        // Decrement the post-grind cooldown counter every server tick. Has to run for
        // grinding *and* non-grinding players — once stop() seeds the counter, only
        // tickCooldown can drain it back to zero so a new grind can start. Cheap when
        // the player has no cooldown entry (single map lookup, early return).
        RailGrindHandler.tickCooldown(player);
        // Sustained-overlap kick check. Has to run every tick for every player (grinder or
        // not) because the resulting "crushed" gate also blocks new grind starts — a player
        // standing inside a parked carriage shouldn't be allowed to initiate from there, and
        // the only way the gate flips is via this tick.
        RailGrindHandler.tickTrainOverlap(player);
        RailGrindHandler.tick(player);
    }

    /**
     * Cancel fall damage during the post-dismount immunity window. Without this, stepping off
     * an elevated rail or landing from a stopWithLaunch arc lands hard enough to kill at
     * TOP_SPEED. LivingIncomingDamageEvent fires before invuln/shield checks so cancelling
     * here prevents the damage entirely (no sound, no damage tick).
     */
    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!event.getSource().is(DamageTypes.FALL)) return;
        if (RailGrindHandler.hasFallImmunity(player)) {
            event.setCanceled(true);
        }
    }

    /**
     * Damage taken while grinding is amplified by 10× the current grind speed (in blocks/tick)
     * and rendered as a vanilla critical hit (particle burst + crit sound). Then the grind is
     * dropped. Runs at LOWEST priority so the fall-immunity cancel above gets first dibs —
     * cancelled events skip out here, which keeps post-dismount fall damage from immediately
     * re-stopping a fresh grind. Server-side only because {@link RailGrindHandler}'s active
     * map is server-authoritative; the client just follows along via the pose-sync packet
     * {@code stop()} emits.
     *
     * <p>The amount is mutated before vanilla applies armor/invuln, so armor still mitigates
     * the boosted hit (matches how outgoing crits behave). Speed-scaling means a low-speed
     * grind (~MIN_SPEED 0.10) leaves damage roughly unchanged, while TOP_SPEED (~0.84) hits
     * the player for ≈ 8.4× the original.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onIncomingDamageStopGrind(LivingIncomingDamageEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (player.level().isClientSide) return;
        if (!RailGrindHandler.isGrinding(player)) return;

        // Skip the blanket speed×10 multiplier for our own train-collision damage source —
        // RailGrindHandler.checkTrainCollision already scaled the amount by relative velocity,
        // so multiplying again would compound. Crit feedback + grind exit still apply below.
        if (!event.getSource().is(RailGrindHandler.FLATTENED_DAMAGE)) {
            double speed = RailGrindHandler.getCurrentSpeed(player);
            event.setAmount((float) (event.getAmount() * speed * 10.0));
        }

        // Incoming damage has no native "is critical" flag — fake the visual by broadcasting
        // the same animate packet vanilla sends from Player.crit(...) for outgoing crits.
        if (player instanceof ServerPlayer sp) {
            sp.serverLevel().getChunkSource().broadcastAndSend(sp,
                new ClientboundAnimatePacket(sp, ClientboundAnimatePacket.CRITICAL_HIT));
        }
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
            SoundEvents.PLAYER_ATTACK_CRIT, player.getSoundSource(), 1.0F, 1.0F);

        RailGrindHandler.stop(player);
    }

    /**
     * Wipe leftover grind state on login/logout/respawn. Without this, a player
     * who quits or dies mid-grind rejoins with {@code noGravity = true} (which
     * is persisted to the player's NBT via SynchedEntityData) and floats forever.
     */
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

    private static void clearGrindState(Player player) {
        // Removes from ACTIVE map and broadcasts the false sync packet
        RailGrindHandler.stop(player);
        // Defensive: even if stop() short-circuited (player wasn't in ACTIVE),
        // explicitly clear the persisted flags so the player can't be left floating.
        player.setNoGravity(false);
        player.noPhysics = false;
        player.fallDistance = 0.0F;
    }
}
