# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

NeoForge 1.21.1 mod that adds rail-grinding to Create: the player rides Create train tracks while wearing diving boots. Mod ID `createrailgrinding`, package `net.juniknytt.createrailgrinding`. Versions are pinned in `gradle.properties` (Create 6.0.11, Ponder 1.0.82+mc1.21.1, Flywheel 1.0.6, Registrate MC1.21-1.3.0+67, Parchment 2024.11.17, JDK 21).

The feature itself is **not yet implemented**. The intended design is sketched in the comment at the bottom of `RailGrind.java` (right-click rail with empty main hand → check for diving boots → spawn a fake `Train` with a default bogey → seat the player as driver → tear down on dismount). `event/ModEvents.java` currently contains placeholder handlers (a sheep-killing test, and a right-click-track handler that just teleports the player onto the rail) — both will be replaced as the real feature lands.

## Common commands

```bash
./gradlew build              # full build (CI runs this)
./gradlew runClient          # launch dev client
./gradlew runServer          # launch dedicated dev server (--nogui)
./gradlew runGameTestServer  # run gametests in namespace `createrailgrinding`
./gradlew runData            # regenerate datagen output into src/generated/resources/
./gradlew --refresh-dependencies   # if IDE is missing libraries
./gradlew clean              # reset build outputs (does not touch source)
```

There is no test source set yet; gametests would live under `src/main` and run via `runGameTestServer`.

## Build system notes

- Uses the **NeoForge ModDevGradle plugin** (`net.neoforged.moddev`), not ForgeGradle. Run configurations (`client`, `server`, `gameTestServer`, `data`) are defined in `build.gradle` under `neoForge { runs { ... } }`.
- `src/main/templates/META-INF/neoforge.mods.toml` is a **template**, not the final TOML. The `generateModMetadata` task in `build.gradle` expands `${mod_id}`, `${neo_version}`, etc. from `gradle.properties` into `build/generated/sources/modMetadata/` and adds it to the resources source set. Edit the template, not any generated copy.
- Datagen output goes to `src/generated/resources/`, which is also wired in as a resources source dir — generated files are committed alongside hand-written ones.
- Create is pulled in with `transitive = false` (the `:slim` classifier that older 6.0.x builds shipped is no longer published); Ponder, Flywheel (api compileOnly + runtime), and Registrate are declared explicitly. When adding a Create-adjacent dependency, follow the same pattern rather than relying on transitive resolution.
- Maven repos: `maven.createmod.net` (Create/Ponder/Flywheel) and `maven.ithundxr.dev/snapshots` (Registrate).

## Code layout gotchas

- The mod entrypoint is `RailGrind` (annotated `@Mod(RailGrind.MODID)`). The class still carries a large pile of unused imports left over from the template — clean these up as real code lands rather than treating them as a guide to the intended API surface.
- The lang file is still at `src/main/resources/assets/examplemod/lang/en_us.json` with `examplemod`-namespaced keys. It needs to move to `assets/createrailgrinding/` and have its keys renamed before any registered block/item names will localize correctly.
- `Config.java` is the unmodified template config (logDirtBlock, magicNumber, etc.) — the values it defines are not used anywhere in the mod yet.
- Event handlers should go under `net.juniknytt.createrailgrinding.event` and be annotated `@EventBusSubscriber(modid = RailGrind.MODID)` (see `ModEvents.java` for the established pattern).

## Working with Create's train API

The planned feature integrates with Create's train system. Relevant packages already imported in the WIP code:

- `com.simibubi.create.content.trains.graph` — `TrackGraph`, `TrackGraphHelper`, `TrackGraphLocation`, `TrackNode`, `TrackEdge` (the world-wide rail topology).
- `com.simibubi.create.content.trains.entity` — `Train`, `Carriage`, `CarriageBogey`, `CarriageContraption`, `TravellingPoint` (`TravellingPoint.SteerDirection` for input).
- `com.simibubi.create.content.trains.track` — `ITrackBlock`, `TrackMaterial.TrackType` (use `TrackType.STANDARD` to filter to normal Create tracks vs. monorail/etc.), `BezierConnection`, `TrackBlockEntity`.
- `com.simibubi.create.content.trains.bogey.AbstractBogeyBlock` for the default bogey.

Reference implementations called out in `RailGrind.java`'s comment block:
- Layers of Railways handcar: `Layers-of-Railways/Railway` `dev/common/.../content/handcar`
- Create's `CarriageContraption`
- Train discard handling: `Layers-of-Railways/Railway` `.../content/coupling/TrainUtils.java#L57`