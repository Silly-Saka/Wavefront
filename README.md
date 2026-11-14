# Wavefront

Sound physics for Minecraft (Fabric 26.3). Walls muffle sound according to what they are made of, and sound bends round corners and through openings instead of being cut off.

> "Wavefront" is a working title and may change before the first release.

## What it does today (0.1.0)

- **Occlusion from a block database.** 41 researched materials across 8 frequency bands, covering every 26.3 block. Loss depends on material and on how much solid the sound actually crosses, using real block shapes (slabs, stairs, doors).
- **Partly open blocks.** Bars, chains, fences, grates and spawners let most sound through. Banners, torches, flowers and other non-solid blocks are ignored.
- **Edge diffraction.** When the straight line is blocked, the mod finds routes through air, pulls them tight round the block edges, and applies the standard barrier formula per band. High notes lose more than low notes.
- **Opening size.** A doorway or small hole only passes the sound that lands on it, so narrow gaps let less bass through than wide ones. Long passages are treated as guides, not holes.
- **Sound direction.** A blocked sound appears to come from the edge or opening it travelled through.
- **Speed of sound.** Sounds arrive late over distance, started at an exact moment on the audio device's clock. Two sources playing the same sound at the same instant interfere with each other depending on where you stand.
- **Air absorption.** High notes fade over distance, from the ISO 9613-1 formula.
- **Doppler shift.** Pitch changes with how fast you and a sound move relative to each other.
- **Voice chat.** Simple Voice Chat voices get the same occlusion and diffraction.
- **Reverb.** Unchanged from Sound Physics Perfected.

Settings open from Mod Menu: a Quality switch and expandable rows for Walls, Diffraction, Reverb, Voice chat and General. Open the **Walls** row and turn **Sound Physics** off to get the original Sound Physics Perfected behaviour back.

## What is planned

Distinct echoes, underwater sound, a wave-simulated bass layer (true interference and room modes below about 200 Hz), structure-borne sound, and a beginner settings screen.

## Known limits

- OpenAL's built-in per-sound filter has three controls (overall gain, a bass cut and a treble cut). The 8-band result is reduced to those, so the tone shaping is coarser than the calculation behind it, and bass can be cut but never lifted.
- A sound in plain view through an opening is treated as unobstructed; the opening's size only affects sounds that have to bend to reach you.
- Defaults aim for realism: Wall Strength 1.0 and the Realism quality preset. Walls are strong (one stone block takes off about 35 dB), and the preset's performance cost has not been measured. The Balanced and Performance presets are lighter.
- The game-facing code is new and has had little in-game testing.

## Building

Needs JDK 25. The build downloads Minecraft and Fabric, so it needs internet access.

```
./gradlew :26.3-fabric:build
```

The jar appears in `versions/26.3-fabric/build/libs/`. GitHub also builds it on every push: open the **Actions** tab, pick the latest run, and download the `wavefront-jar` artifact.

## Checking the maths without Minecraft

The acoustics code in `src/main/java/wavefront/acoustics` has no Minecraft imports. `dev/selftest/run.sh <path to a Gson jar>` compiles it and runs 89 checks: database values, thickness rules, the diffraction formula against hand calculations, and route finding in small test worlds (free-standing wall, doorway, balcony, sealed room).

## Editing the block database

The mod reads `assets/wavefront/acoustics/acoustic_materials.json` from the jar. To override it, put an edited copy at `config/wavefront/acoustic_materials.json`. The research behind the numbers, with sources and confidence levels, is in `docs/research/`.

## Credits and licence

Wavefront is built on [Sound Physics Perfected](https://github.com/FalseMSP/SoundPhysicsPerfected) by RedLife, with contributions from Souldbminer and Tellinq. That mod's ray-traced reverb, sound interception and voice chat integration are used here largely unchanged. It follows the approach in Vercidium's video "A First Look At Raytraced Audio".

- Wavefront's own code is licensed under GPL-3.0-only (`LICENSE`).
- The Sound Physics Perfected code it includes is MIT-licensed; its notice is kept in `LICENSE-Sound-Physics-Perfected.txt`.

Parts of Wavefront were written with AI assistance. Sound Physics Perfected's maintainer asks that fully AI-written pull requests not be sent to that project; please respect that and do not submit Wavefront code upstream without reviewing it yourself.
