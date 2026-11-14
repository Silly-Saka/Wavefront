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
- **Distinct echoes.** Sounds repeat off large flat surfaces that are far enough away, from the direction of the surface. Music and looping sounds echo too. Loops such as a moving minecart pick up new surfaces as they go; a music disc only echoes off the surfaces in range when it started. An optional setting lets uneven surfaces such as natural cliffs echo, weakened by how rough and patchy they are.
- **Water and lava.** Sound travels about four times faster under water, loses about 29 dB each time it crosses the surface (36 dB for lava), and loses treble to a submerged listener, who can also barely tell left from right. Doppler shift uses the speed of sound where the listener is.
- **Bass waves.** The lowest notes (two bands, around 63 and 125 Hz) are worked out by stepping a real pressure wave through the blocks round the listener, on half-block cells. Doorways, walls, side passages and the walls and corners of rooms shape the bass the way they do in air, with no formula for any of it. Runs on background threads; one run answers for every sound nearby.
- **Full bass.** Bass can be louder than the rest of a sound, through a second effect channel. Behind a wall you hear mostly thump.
- **Voice chat.** Simple Voice Chat voices get the same occlusion and diffraction.
- **Reverb.** Unchanged from Sound Physics Perfected.

Settings open from Mod Menu: a Quality switch and expandable rows for Walls, Diffraction, Distance and motion, Water and lava, Bass, Reverb, Voice chat and General. Open the **Walls** row and turn **Sound Physics** off to get the original Sound Physics Perfected behaviour back.

## What is planned

Structure-borne sound (footsteps and machines heard through the floor), a way to see the sound paths, and the wave simulation extended upwards if it proves fast enough.

## Known limits

- OpenAL's built-in per-sound filter has three controls (overall gain, a bass cut and a treble cut). The 8-band result is reduced to those, so the tone shaping is coarser than the calculation behind it. Extra bass goes through a second channel with a fixed shape (everything below about 200 Hz).
- Bass waves cover sounds in air within 24 blocks (32 on Overkill) and stop at about 180 Hz. Above that, and further away or under water, bass comes from the wall and diffraction figures. Walls are sealed in the simulation; what leaks through them is added from the wall figures. Only the first 50 ms after a sound arrives is counted, the rest being left to the reverb. The result lags a moving listener by up to a second.
- Bass is compared with what is usual at the same distance in the place the listener is in, and never with less than open flat ground (one fixed figure, 5.7 dB above open air). So sounds in the open are unchanged, and a tunnel or small room does not make bass boom. In real life enclosed spaces make all of a sound louder; the mod leaves that to the reverb and does not raise the direct sound, at any pitch. Extra bass is capped at 6 dB.
- A grid of cubes carries a wave slightly differently along its rows than across its diagonals. This is corrected for sound that travels straight; for sound that has bent round something an error of 1 to 2 dB remains at the top of each band.
- The water surface costs the same whatever angle sound meets it at. Real water lets less through at a slant.
- A sound in plain view through an opening is treated as unobstructed; the opening's size only affects sounds that have to bend to reach you.
- Defaults aim for realism: Wall Strength 1.0 and the Realism quality preset. Walls are strong (one stone block takes off about 35 dB), and the preset's performance cost has not been measured. The Balanced and Performance presets are lighter.
- The game-facing code is new and has had little in-game testing.
- Sound calculations run on worker threads and the game never waits for them. When many blocked sounds play at once, a new sound can start up to about one round late (the Search Time setting) and reach its full level a moment after.

## Building

Needs JDK 25. The build downloads Minecraft and Fabric, so it needs internet access.

```
./gradlew :26.3-fabric:build
```

The jar appears in `versions/26.3-fabric/build/libs/`. GitHub also builds it on every push: open the **Actions** tab, pick the latest run, and download the `wavefront-jar` artifact.

## Checking the maths without Minecraft

The acoustics code in `src/main/java/wavefront/acoustics` has no Minecraft imports. `dev/selftest/run.sh <path to a Gson jar>` compiles it and runs 191 checks: database values, thickness rules, the diffraction formula against hand calculations, route finding in small test worlds (free-standing wall, doorway, balcony, sealed room), and the wave simulation against open air, hard ground, walls, doorways, doors and panes.

## Editing the block database

The mod reads `assets/wavefront/acoustics/acoustic_materials.json` from the jar. To override it, put an edited copy at `config/wavefront/acoustic_materials.json`. The research behind the numbers, with sources and confidence levels, is in `docs/research/`.

## Credits and licence

Wavefront is built on [Sound Physics Perfected](https://github.com/FalseMSP/SoundPhysicsPerfected) by RedLife, with contributions from Souldbminer and Tellinq. That mod's ray-traced reverb, sound interception and voice chat integration are used here largely unchanged. It follows the approach in Vercidium's video "A First Look At Raytraced Audio".

- Wavefront's own code is licensed under GPL-3.0-only (`LICENSE`).
- The Sound Physics Perfected code it includes is MIT-licensed; its notice is kept in `LICENSE-Sound-Physics-Perfected.txt`.

Parts of Wavefront were written with AI assistance. Sound Physics Perfected's maintainer asks that fully AI-written pull requests not be sent to that project; please respect that and do not submit Wavefront code upstream without reviewing it yourself.
