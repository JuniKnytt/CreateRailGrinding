package net.juniknytt.createrailgrinding.ponder;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.ParrotElement;
import net.createmod.ponder.api.element.ParrotPose;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.foundation.PonderScene;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Parrot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

public final class RailGrindScenes {

    private RailGrindScenes() {}

    public static void intro(SceneBuilder scene, SceneBuildingUtil util) {

        scene.title("rail_grinding_intro", "Rail Grinding");
        scene.configureBasePlate(0, 0, 15);
        scene.scaleSceneView(.5f);
        scene.showBasePlate();
        scene.idle(10);

        ElementLink<WorldSectionElement> railSection = scene.world()
                .showIndependentSection(util.select().position(0, 1, 7), Direction.DOWN);
        for (int x = 0; x <= 15; x++) {
            scene.world().showSectionAndMerge(util.select().position(x, 1, 7), Direction.DOWN, railSection);
            scene.idle(1);
        }
        scene.idle(10);

        scene.world().createEntity(level -> spawnBootStand(level, new Vec3(6.5, 2.0, 12.5), AllItems.COPPER_DIVING_BOOTS.get()));
        scene.world().createEntity(level -> spawnBootStand(level, new Vec3(8.5, 2.0, 12.5), AllItems.NETHERITE_DIVING_BOOTS.get()));
        scene.idle(10);

        scene.overlay().showText(60)
                .pointAt(new Vec3(7.5, 2.5, 12.5))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Diving Boots are suitable Grind Shoes and can used for Rail-Grinding");
        scene.idle(60);

        Vec3 parrotStart = new Vec3(1.5, 2.0, 7.5);
        ElementLink<ParrotElement> parrot = scene.special()
                .createBirb(parrotStart, WingsDownFacingPose::new);
        scene.special().movePointOfInterest(util.grid().at(20, 2, 7));
        scene.idle(15);

        scene.overlay().showControls(util.vector().of(1.5, 3, 7.5), Pointing.DOWN, 60)
                .withItem(AllItems.COPPER_DIVING_BOOTS.asStack())
                .rightClick();

        scene.idle(20);
        scene.overlay().showText(80)
                .pointAt(new Vec3(1.5, 3.0, 7.5))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Right-click Train Tracks with an empty hand to start Rail-Grinding");
        scene.idle(80);

        scene.special().changeBirbPose(parrot, WingsOutFacingPose::new);
        scene.special().moveParrot(parrot, util.vector().of(10, 0, 0), 80);
        scene.idle(60);

        scene.overlay().showControls(util.vector().of(11.3, 3, 7.5), Pointing.DOWN, 40)
                .withItem(AllBlocks.TRACK.asStack())
                .rightClick();

        scene.overlay().showText(80)
                .pointAt(new Vec3(11.3, 3.0, 7.5))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Right-click Train Tracks with an empty hand to stop Rail-Grinding");
        scene.idle(20);
        scene.special().movePointOfInterest(util.grid().at(-20, 2, 7));
        scene.special().changeBirbPose(parrot, WingsDownFacingPose::new);

        scene.idle(80);

        scene.overlay().showText(80)
                .pointAt(new Vec3(11.3, 3.0, 7.5))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Sneak and Jump near Train Tracks is another way to start Rail-Grinding");
        scene.special().movePointOfInterest(util.grid().at(-20, 2, 7));
        scene.special().changeBirbPose(parrot, WingsDownFacingPose::new);

        scene.overlay().showControls(util.vector().of(11.3, 3, 7.5), Pointing.DOWN, 60)
                .showing(SneakJumpKeyHint.SNEAKPLUSJUMP);
        scene.idle(60);
        scene.special().changeBirbPose(parrot, WingsOutFacingPose::new);
        scene.special().moveParrot(parrot, util.vector().of(-10, 0, 0), 80);
        scene.idle(80);
        scene.special().changeBirbPose(parrot, WingsDownFacingPose::new);
        scene.special().movePointOfInterest(util.grid().at(20, 2, 7));
        scene.idle(20);

        scene.overlay().showText(80)
                .pointAt(new Vec3(1.5, 3.0, 7.5))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Hold Sneak to Speed up");
        scene.idle(20);
        scene.overlay().showControls(util.vector().of(1.5, 3, 7.5), Pointing.DOWN, 60)
                .showing(SneakKeyHint.SNEAK);
        scene.idle(20);

        scene.special().changeBirbPose(parrot, WingsOutFacingPose::new);
        scene.special().moveParrot(parrot, util.vector().of(10, 0, 0), 40);
        scene.idle(40);
        scene.special().changeBirbPose(parrot, WingsDownFacingPose::new);
        scene.special().movePointOfInterest(util.grid().at(-20, 2, 7));
        scene.idle(20);

        scene.overlay().showText(60)
                .pointAt(new Vec3(11.3, 3.0, 7.5))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Hold and Release Jump to leap off Rails");
        scene.special().changeBirbPose(parrot, WingsDownFacingPose::new);

        scene.overlay().showControls(util.vector().of(11.3, 3, 7.5), Pointing.DOWN, 60)
                .showing(JumpKeyHint.JUMP);
        scene.idle(60);
        scene.special().changeBirbPose(parrot, WingsOutFacingPose::new);
        scene.special().moveParrot(parrot, util.vector().of(-2, 0, 0), 4);
        scene.idle(5);

        scene.special().moveParrot(parrot, util.vector().of(-0.5, 2, 0), 3);
        scene.idle(1);
        scene.special().moveParrot(parrot, util.vector().of(-0.5, 1, 0), 3);
        scene.idle(1);
        scene.special().moveParrot(parrot, util.vector().of(-0.5, 0, 0), 3);
        scene.idle(1);

        scene.special().moveParrot(parrot, util.vector().of(-0.5, -0, 0), 3);
        scene.idle(1);
        scene.special().moveParrot(parrot, util.vector().of(-0.5, -1, 0), 3);
        scene.idle(1);
        scene.special().moveParrot(parrot, util.vector().of(-0.5, -2, 0), 3);
        scene.idle(1);

        scene.special().moveParrot(parrot, util.vector().of(-2, 0, 0), 4);
        scene.idle(4);
        scene.special().moveParrot(parrot, util.vector().of(0, 1, 1), 4);
        scene.special().changeBirbPose(parrot, WingsDownFacingPose::new);
        scene.special().movePointOfInterest(util.grid().at(20, 2, 7));
        scene.idle(20);

        scene.overlay().showText(160)
                .independent()
                .attachKeyFrame()
                .text("Holding Shift+Sneak will automatically make you latch on to Rails");
        scene.idle(40);
        scene.overlay().showText(120)
                .independent(40)
                .attachKeyFrame()
                .text("Fall Damage is negated and Momentum is maintained when landing on Rails");
        scene.idle(160);
    }

    private static ArmorStand spawnBootStand(net.minecraft.world.level.Level level, Vec3 pos, net.minecraft.world.item.Item boots) {
        ArmorStand stand = new ArmorStand(level, pos.x, pos.y, pos.z);
        stand.setYRot(180f);
        stand.setYBodyRot(180f);
        stand.setYHeadRot(180f);
        stand.setItemSlot(EquipmentSlot.FEET, new ItemStack(boots));
        return stand;
    }

    private static class WingsOutFacingPose extends ParrotPose.FacePointOfInterestPose {
        @Override
        public void tick(PonderScene scene, Parrot entity, Vec3 location) {
            super.tick(scene, entity, location);
            entity.flapSpeed = 2f;
        }
    }

    private static class WingsDownFacingPose extends ParrotPose.FacePointOfInterestPose {
        @Override
        public void tick(PonderScene scene, Parrot entity, Vec3 location) {
            super.tick(scene, entity, location);
            entity.flapSpeed = 0f;
        }
    }
}
