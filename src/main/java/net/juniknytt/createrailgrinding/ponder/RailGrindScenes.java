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


        // ─── Parrot movement / facing API (cite for hand-authored beats below) ────
        // All parrot controls live on scene.special() — the SpecialInstructions interface
        // at net.createmod.ponder.api.scene.SpecialInstructions:
        //
        //   moveParrot(ElementLink<ParrotElement> link, Vec3 offset, int duration)
        //       Translates the parrot by `offset` over `duration` ticks. Non-blocking;
        //       follow with scene.idle(duration) to wait the move out.
        //   rotateParrot(ElementLink<ParrotElement> link,
        //                double xRot, double yRot, double zRot, int duration)
        //       Direct rotation. Usually steer facing via the POI instead, since the
        //       FacePointOfInterestPose handles smooth lerp for you.
        //   movePointOfInterest(Vec3 | BlockPos location)
        //       Moves the scene's POI. FacePointOfInterestPose lerps the parrot's
        //       yaw/pitch toward this each tick (interp factor 0.4 — converges in
        //       ~10 ticks). Place the POI well past where you want the bird to face.
        //   changeBirbPose(ElementLink<ParrotElement> link, Supplier<? extends ParrotPose>)
        //       Swap the active pose mid-scene (e.g. into DancePose for a flourish).
        //
        // Pose subclasses (net.createmod.ponder.api.element.ParrotPose):
        //   ParrotPose.DancePose                 — head-bobbing dance (sets RecordPlayingNearby + spins yaw -2°/tick).
        //   ParrotPose.FlappyPose                — wings flap dynamically: flapSpeed = sin(t * speed-of-motion) + 1,
        //                               where speed-of-motion is the per-tick travel distance. Stationary
        //                               parrot = wings folded; moving parrot = visible wing-flap cycle.
        //   ParrotPose.FacePointOfInterestPose   — yaw/pitch lerp toward scene POI; wings inactive.
        //   ParrotPose.FaceCursorPose            — yaw/pitch lerp toward the player's mouse cursor; wings inactive.
        //   WingsOutFacingPose        — local subclass at the bottom of the file. Combines POI facing with
        //                               flapSpeed pinned at 2 so the parrot glides with wings fully extended.
        //
        //
        // Other animation knobs available on the Parrot entity inside a ParrotPose.tick():
        //   entity.setOnGround(false)           keeps the bird in flying-arm-up posture (FlappyPose does this)
        //   entity.setRecordPlayingNearby(...)  toggles the head-bobbing dance jiggle (DancePose uses this)
        //   entity.setVariant(Parrot.Variant.X) re-skin (RED_BLUE / GREEN / YELLOW_BLUE / GRAY chosen at create)
        //   entity.setXRot / setYRot            override pitch/yaw if you don't want the FaceVec lerp
        //   entity.yBodyRot / yHeadRot          decouple body vs. head facing
        //   entity.tickCount                    tie any animation to a steady tick counter
        //
        // Vec3 / BlockPos helpers (passed in as `util`):
        //   util.vector().of(x, y, z)            → Vec3 (raw coords)
        //   util.vector().centerOf(x, y, z)      → Vec3 at the block centre
        //   util.vector().topOf(x, y, z)         → Vec3 on the block's top face
        //   util.grid().at(x, y, z)              → BlockPos
        //
        // Schematic frame of reference: 15x15 base plate, rail along +X at z=7,
        // y=1 (rail surface y=2). Parrot spawns at (1.5, 2, 7.5) below.
        // ──────────────────────────────────────────────────────────────────────────


        scene.title("rail_grinding_intro", "Rail Grinding");
        scene.configureBasePlate(0, 0, 15);
        scene.scaleSceneView(.5f);
        scene.showBasePlate();
        scene.idle(10);

        // Lay the rail block-by-block
        ElementLink<WorldSectionElement> railSection = scene.world()
                .showIndependentSection(util.select().position(0, 1, 7), Direction.DOWN);
        for (int x = 0; x <= 15; x++) {
            scene.world().showSectionAndMerge(util.select().position(x, 1, 7), Direction.DOWN, railSection);
            scene.idle(1);
        }
        scene.idle(10);

        //boots on armor stands
        scene.world().createEntity(level -> spawnBootStand(level, new Vec3(6.5, 2.0, 12.5), AllItems.COPPER_DIVING_BOOTS.get()));
        scene.world().createEntity(level -> spawnBootStand(level, new Vec3(8.5, 2.0, 12.5), AllItems.NETHERITE_DIVING_BOOTS.get()));
        scene.idle(10);

        //Start 1.
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


        //2. Movement and stop
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


        //3 space and jump
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

        //3. speed up
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


        //4. jump
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
        // Per-step y delta = derivative of y(x) = -(20/49)(x - 3.5)^2 + 5,
        // i.e. y'(x) = -(40/49)(x - 3.5). Sampled at integer x = 1..6 so the
        // first three steps climb (positive y') and the last three fall.
        //arc up
        scene.special().moveParrot(parrot, util.vector().of(-0.5, 2, 0), 3);
        scene.idle(1);
        scene.special().moveParrot(parrot, util.vector().of(-0.5, 1, 0), 3);
        scene.idle(1);
        scene.special().moveParrot(parrot, util.vector().of(-0.5, 0, 0), 3);
        scene.idle(1);
        //arc down
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

    /**
     * Like {@link ParrotPose.FacePointOfInterestPose} but pins the parrot's wings open every
     * tick, matching the static "wings extended" frame Create's ChainConveyorParrotElement
     * paints by setting flapSpeed = 2 just before render.
     */
    private static class WingsOutFacingPose extends ParrotPose.FacePointOfInterestPose {
        @Override
        public void tick(PonderScene scene, Parrot entity, Vec3 location) {
            super.tick(scene, entity, location);
            entity.flapSpeed = 2f;
        }
    }

    /**
     * Counterpart to {@link WingsOutFacingPose} — faces the POI but pins flapSpeed to 0
     * each tick so the wings stay folded against the body. Use after a WingsOutFacingPose
     * beat to "land" the parrot, since vanilla FacePointOfInterestPose never writes flapSpeed
     * and would otherwise leave the wings stuck at whatever value the previous pose set.
     */
    private static class WingsDownFacingPose extends ParrotPose.FacePointOfInterestPose {
        @Override
        public void tick(PonderScene scene, Parrot entity, Vec3 location) {
            super.tick(scene, entity, location);
            entity.flapSpeed = 0f;
        }
    }
}
