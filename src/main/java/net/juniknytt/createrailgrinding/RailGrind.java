package net.juniknytt.createrailgrinding;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.AssemblyException;

import com.simibubi.create.content.trains.entity.*;
import com.simibubi.create.content.trains.entity.TravellingPoint.SteerDirection;
import com.simibubi.create.content.trains.graph.*;
import com.simibubi.create.content.trains.track.*;
import com.simibubi.create.content.trains.track.TrackMaterial.TrackType;
import com.simibubi.create.content.trains.track.TrackTargetingBlockItem.OverlapResult;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
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

//DNE import com.simibubi.create.content.schematics.SchematicWorld;
//DNE import com.simibubi.create.foundation.utility.Components;
//DNE import com.simibubi.create.foundation.utility.Couple;
//DNE import com.simibubi.create.foundation.utility.Lang;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(RailGrind.MODID)
public class RailGrind {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "createrailgrinding";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();

    public RailGrind(IEventBus modBus, ModContainer container) {
        net.juniknytt.createrailgrinding.sound.ModSounds.register(modBus);
        net.juniknytt.createrailgrinding.effect.ModEffects.register(modBus);
        net.juniknytt.createrailgrinding.particle.ModParticles.register(modBus);
        container.registerConfig(ModConfig.Type.CLIENT, Config.SPEC);
        container.registerConfig(ModConfig.Type.SERVER, Config.SERVER_SPEC);
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // Do something when the server starts
        LOGGER.info("Rolling around at the speed of sound!");
    }





}

/*
Resources:
https://github.com/Layers-of-Railways/Railway/tree/1.20/dev/common/src/main/java/com/railwayteam/railways/content/handcar
https://github.com/Creators-of-Create/Create/blob/2fcc6a706478d6a015880bd4c81f216d2379dc1f/src/main/java/com/simibubi/create/content/trains/entity/CarriageContraption.java
https://github.com/Layers-of-Railways/Railway/blob/1.20/dev/common/src/main/java/com/railwayteam/railways/content/coupling/TrainUtils.java#L57 DISCARDS

STEPS
Right click with fist
    -Check player is wearing either diving boots
    -Check for rail graph
    -Create fake Train
Fake Train locs
    -Seat offset to a side
On Fake Train
    -Make owner driver
    -Seat Owner
    -Delete
    -add grinding sound
    -Delete on dismount
On Dismount
    -if driver null and or dismount delete train.

TO DO:
Make a fake train with default bogey pop up when wearing diving boots and right click rail
 */