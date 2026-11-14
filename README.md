# Wavefront

A sound physics mod for Minecraft, built on [Sound Physics Perfected](https://github.com/FalseMSP/SoundPhysicsPerfected).

Wavefront expands on Sound Physics Perfected's ray-traced audio system with more detailed sound propagation. It takes block materials, openings, distance, and the surrounding environment into account when calculating how sounds reach the player.

The goal is to make Minecraft's audio behave a bit more like real sound.

**Currently targeting Minecraft 26.3 on Fabric.**

## Features

- **Sound occlusion** — Walls muffle sounds based on their materials and thickness. Stone, wood, wool, and other blocks affect sound differently.
- **Sound diffraction** — Sound can travel around corners and through openings instead of being completely blocked.
- **Better bass** — Low frequencies behave differently from higher frequencies. Sounds behind walls can still have a noticeable thump.
- **Speed of sound** — Distant sounds take time to reach you.
- **Doppler effect** — Moving sounds can change pitch depending on their movement relative to the player.
- **Echoes** — Large surfaces can reflect sound, including music and looping sounds.
- **Underwater audio** — Sound travels differently through water and lava, including changes to speed, volume, and direction.
- **Sound through blocks** — Footsteps, pistons, explosions, and other impacts can travel through connected solid blocks.
- **Modded block support** — Unknown blocks are assigned approximate acoustic properties based on their tags, sounds, and textures.
- **Simple Voice Chat support** — Voice audio uses the same occlusion and diffraction calculations.
- **Sound dots** — An optional accessibility view. Sounds leave marks on the floor and walls they reach, with a shape and colour for each kind of sound and more marks for louder ones. Nothing is drawn through walls. Inspired by Vercidium's ray-traced audio work.

Wavefront keeps Sound Physics Perfected's ray-traced reverb system while adding its own calculations for how sound travels through and around the world.

## Configuration

Settings are available through Mod Menu.

There are four quality presets: Performance, Balanced, Realism, and Overkill. Individual settings can also be changed manually.

There are also debug options for displaying sound paths and the calculated bass field in-game.

## Current state

Wavefront is still experimental. Version 0.1.0 contains the initial implementation, but quite a few things still need proper in-game testing.

Some calculations are approximations, and performance hasn't been thoroughly tested yet. The Realism and Overkill presets may be expensive depending on the situation.

Expect bugs, odd audio behaviour, and changes between versions.

## Building

You'll need JDK 25 and an internet connection for Gradle to download the required dependencies.

Clone the repository:

```bash
git clone https://github.com/Silly-Saka/Wavefront.git
cd Wavefront
```

Build the Fabric version:

```bash
./gradlew :26.3-fabric:build
```

The compiled JAR should appear in:

```text
versions/26.3-fabric/build/libs/
```

You can also download builds from the GitHub Actions artifacts when a build has completed successfully.

## Testing and development

The acoustics code is mostly separated from Minecraft, so some calculations can be tested without launching the game.

The repository includes self-tests for material values, diffraction, sound propagation, and bass simulation.

Research notes and acoustic material data are available in `docs/research/`.

If you find a bug or something doesn't sound right, feel free to open an issue.

## Credits

Wavefront is based on [Sound Physics Perfected](https://github.com/FalseMSP/SoundPhysicsPerfected) by RedLife, with contributions from Souldbminer and Tellinq.

Sound Physics Perfected already uses ray tracing to simulate how sound interacts with the environment, including its reverb system. Wavefront builds on that foundation with material-based occlusion, diffraction, wave-based bass simulation, and other acoustic effects.

The original ray-traced reverb, sound interception, and voice chat integration are largely retained from Sound Physics Perfected.

The ray-traced audio approach also draws from Vercidium's *A First Look At Raytraced Audio*.

Wavefront's code was generated with AI assistance. The project is experimental and is being shared as-is.

## License

Wavefront is licensed under **GPL-3.0-only**.

The included Sound Physics Perfected code retains its original MIT license. See `LICENSE` and `LICENSE-Sound-Physics-Perfected.txt` for details.
