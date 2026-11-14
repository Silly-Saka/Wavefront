# Wavefront

Sound physics for Minecraft (Fabric 26.3). Walls muffle sound according to what they are made of, and sound bends round corners and through openings instead of being cut off.

> "Wavefront" is a working title and may change before the first release.

## What it does today (0.1.0)

- **Occlusion from a block database.** 41 researched materials across 8 frequency bands, covering every 26.3 block. Loss depends on material and on how much solid the sound actually crosses, using real block shapes (slabs, stairs, doors).
- **Partly open blocks.** Bars, chains, fences, grates and spawners let most sound through. Banners, torches, flowers and other non-solid blocks are ignored.
- **Edge diffraction.** When the straight line is blocked, the mod finds routes through air, pulls them tight round the block edges, and applies the standard barrier formula per band. High notes lose more than low notes.
- **Sound direction.** A blocked sound appears to come from the edge or opening it travelled through.
- **Voice chat.** Simple Voice Chat voices get the same treatment.
- **Reverb.** Unchanged from Sound Physics Perfected.

Settings open from Mod Menu. The **Quick Setup** tab has a quality preset and the main switches; turn **Sound Physics** off there to get the original Sound Physics Perfected behaviour back.

## What is planned

Speed-of-sound delay, Doppler, air absorption, distinct echoes, underwater sound, a wave-simulated bass layer (true interference and room modes below about 200 Hz), structure-borne sound, and a beginner settings screen.

## Known limits

- OpenAL's built-in per-sound filter has two controls (overall gain and treble gain). The 8-band result is reduced to those two, so the tone shaping is coarser than the calculation behind it.
- Opening size does not yet change how sound spreads after a gap.
- The default wall strength (0.6) is a starting point and needs tuning by ear.
- The game-facing code is new and has had little in-game testing.

## Building

Needs JDK 25. The build downloads Minecraft and Fabric, so it needs internet access.

```
./gradlew :26.3-fabric:build
```

The jar appears in `versions/26.3-fabric/build/libs/`. GitHub also builds it on every push: open the **Actions** tab, pick the latest run, and download the `wavefront-jar` artifact.

## Checking the maths without Minecraft

The acoustics code in `src/main/java/wavefront/acoustics` has no Minecraft imports. `dev/selftest/run.sh <path to a Gson jar>` compiles it and runs 68 checks: database values, thickness rules, the diffraction formula against hand calculations, and route finding in small test worlds (free-standing wall, doorway, balcony, sealed room).

## Editing the block database

The mod reads `assets/wavefront/acoustics/acoustic_materials.json` from the jar. To override it, put an edited copy at `config/wavefront/acoustic_materials.json`. The research behind the numbers, with sources and confidence levels, is in `docs/research/`.

## Credits and licence

Wavefront is built on [Sound Physics Perfected](https://github.com/FalseMSP/SoundPhysicsPerfected) by RedLife, with contributions from Souldbminer and Tellinq. That mod's ray-traced reverb, sound interception and voice chat integration are used here largely unchanged. It follows the approach in Vercidium's video "A First Look At Raytraced Audio".

- Wavefront's own code is licensed under GPL-3.0-only (`LICENSE`).
- The Sound Physics Perfected code it includes is MIT-licensed; its notice is kept in `LICENSE-Sound-Physics-Perfected.txt`.

Parts of Wavefront were written with AI assistance. Sound Physics Perfected's maintainer asks that fully AI-written pull requests not be sent to that project; please respect that and do not submit Wavefront code upstream without reviewing it yourself.
