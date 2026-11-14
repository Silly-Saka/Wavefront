# Acoustic material database for a Minecraft sound-physics mod (Java 26.3)

Eight octave bands: 63, 125, 250, 500, 1000, 2000, 4000 and 8000 Hz. There are 41 materials, one over the 30–40 target, because wax and resin got their own entry. Every block-form ID in the supplied 26.3 item list (1,129 IDs) maps to a material, as do 35 liquid, crop and wall-variant blocks that have no item of their own.

## Changes in version 2 (after review)

1. **Wall strength is now adjustable.** The JSON has a `gameplay_curve` block (`cap_db`, `wall_strength` and the formula), so the mod can recompute gameplay TL at runtime from a slider. The defaults are unchanged and still need tuning by ear: one block of any solid costs about 26–40 dB, which is much stronger than vanilla.
2. **Blocks that aren't really solid are handled.** A new `acoustic_fill` table gives the fraction of each cell that actually blocks sound.
   - 256 blocks are set to 0, meaning ignore them for occlusion: banners, tripwire, torches, rails, signs, buttons, pressure plates, flowers, crops, coral, candles and small props.
   - 59 are partial, because their collision box looks solid but they are full of holes:
     - fences 0.3
     - bars 0.25
     - chains 0.05
     - copper grates 0.5
     - spawners 0.3
     - vault 0.5
     - scaffolding and ladders 0.2
     - cobweb 0.1
   - Every other block should use its collision shape.
3. **The liquid jump is smaller.** Between half and twice a layer's first thickness resonance, the two liquid models are now blended instead of switched. Honey's drop from 500 Hz to 1 kHz shrank from 24 to 10 dB, and slime's from 17 to 6 dB. The remainder is the real onset of resonant transmission, spread out. Water and lava still drop more, but the mod uses the `media` section for them.
4. **Remaps.**
   - Candles, honeycomb blocks and all resin blocks now map to a new solid material, **wax_resin**. Only the honey block stays on `honey`.
   - Spawners and copper grates keep their metal material, but `acoustic_fill` makes them mostly open.

## Files

| File | What it is |
|---|---|
| `acoustic_materials.json` | The deliverable: materials, water and lava as media, and the block mapping |
| `materials_input.json` | Hand-entered inputs and sources: densities, speeds, loss factors, measured absorption rows, flow resistivities |
| `build_materials.ps1` | Computes every derived value from the inputs and writes the JSON. Run `powershell -ExecutionPolicy Bypass -File build_materials.ps1` |
| `block_rules.ps1` | Ordered regex rules that turn block IDs into materials |
| `block_ids.txt` | Block-form IDs extracted from your item list |
| `generated_summary_table.md` | The table below, regenerated on every build |

## How each number was produced

Each band value carries a code in `provenance.absorption_bands`:

- **m** measured: published table, source named
- **x** extrapolated: 63 Hz and 8 kHz copied from the nearest measured band
- **d** derived: computed from a formula, given in `provenance`
- **e** estimated: judgement, no direct data

Here is the method behind each quantity.

**Absorption.**
- Hard surfaces use measured 125–4000 Hz rows from a compiled table, extended to 63 Hz and 8 kHz by copying the nearest band.
- Porous blocks (wool, sponge, hay, moss, snow, sand, gravel, earth, soul sand) are derived:
  - the Miki (1990) impedance model is fed a flow resistivity σ and porosity φ;
  - from that, the random-incidence absorption of a 1 m layer is computed (the standard angle-weighted average).
- Carpet uses the measured "carpet on foam" row instead.

**Scattering.** All scattering values are estimated. Each material gets one mid-band value (the 500/1000 Hz average), which is spread across the bands with a fixed rising shape `[0.35, 0.45, 0.65, 0.85, 1.15, 1.4, 1.6, 1.7] × s_mid`, clamped to 0.05–0.95. This copies Odeon's practice of entering a single scattering value and expanding it; the shape itself is my assumption.

**Transmission loss (TL)** is computed for a 1 m block and a 5 cm panel, both for sound arriving from all directions (diffuse incidence).

- **Solids** use Sharp's (1978) single-panel method:
  - below half the coincidence frequency, the field mass law `R = 20·log10(m″f) − 47`;
  - above the coincidence frequency `fc`, `R = 20·log10(m″f) + 10·log10(2·η_tot·f/(π·fc)) − 47`;
  - a straight-line plateau in between;
  - `fc = c0²/(1.814·c_plate·h)`;
  - `η_tot = η + m″/(485·√f)`, the ISO 12354-1 laboratory total loss factor.

  The result is capped at the field mass law, because sound transmitted by panel resonance only adds to the mass-law (forced) transmission.
- **Liquids and soft solids** (water, lava, honey, slime, mud, wet sponge, sculk) use a homogeneous lossy layer, averaged over incidence angles 0–78°.
  - Below the layer's first thickness resonance `c/(2d)`: the exact layer formula, which includes sound "tunnelling" past the critical angle.
  - Above it: an energy model with two interfaces, multiple internal reflections and internal loss. Real ponds and lava pools are not perfectly flat slabs, so the razor-sharp resonances of an ideal flat layer would be misleading.
  - Between half and twice the first resonance, the two are blended in log-frequency. That blend is a smoothing choice, not physics.
- **Porous blocks** send sound along two parallel paths:
  - air through the pores (Miki model);
  - vibration of the solid frame (a lossy layer).
- **Leaves** use the ISO 9613-2 foliage attenuation per metre.

Each value is energy-averaged over 61 frequencies per octave (15 for solids).

**Gameplay TL.** By default, `G = wall_strength · cap · (1 − exp(−TL_real/cap))`, with `cap = 45 dB` and `wall_strength = 1`. Both live in the JSON's `gameplay_curve` block. Lowering `wall_strength` scales every material down. Lowering `cap` pushes materials closer together, and raising it spreads them apart. This keeps thin and light blocks close to their real values but squeezes enormous real figures under a 45 dB ceiling. One full block of stone gives 32–40 dB: faint but audible for loud sounds. Three blocks give more than 95 dB: inaudible. There are three exceptions:
- **Glass** is compressed from the 5 cm panel value, so it behaves like a window.
- **Wool and carpet** are set to `max(G, dense_stone + 6 dB)`, which makes wool the most sound-deadening block, matching `#minecraft:occludes_vibration_signals`.
- **Leaves** get a small floor of 0.5–3 dB.

## Summary table

α is the absorption coefficient. TL values are diffuse incidence. For porous materials, *c* is the frame (skeleton) wave speed and the loss factor is the frame's.

| Material | Model | rho kg/m3 | c m/s | loss factor | alpha 125 / 500 / 2k | TL 1 m real @125 / 500 / 2k dB | TL 5 cm @125 / 500 / 2k dB | Gameplay 1 m @125 / 500 / 2k dB | Blocks mapped | Confidence |
|---|---|---|---|---|---|---|---|---|---|---|
| dense_stone | solid | 2650 | 5000 | 0.005 | 0.02 / 0.02 / 0.04 | 63 / 75 / 87 | 36 / 32 / 48 | 34 / 37 / 39 | 55 | medium |
| polished_stone | solid | 2700 | 5800 | 0.003 | 0.01 / 0.01 / 0.02 | 63 / 75 / 87 | 34 / 32 / 48 | 34 / 37 / 39 | 43 | high |
| cobblestone | solid | 2300 | 3000 | 0.015 | 0.03 / 0.04 / 0.06 | 62 / 74 / 86 | 36 / 32 / 47 | 34 / 36 / 38 | 24 | low |
| stone_bricks | solid | 2400 | 3500 | 0.01 | 0.03 / 0.03 / 0.05 | 62 / 74 / 86 | 36 / 31 / 47 | 34 / 36 / 38 | 49 | medium |
| sandstone | solid | 2200 | 2800 | 0.015 | 0.03 / 0.05 / 0.07 | 61 / 73 / 85 | 35 / 32 / 46 | 34 / 36 / 38 | 75 | low |
| brick | solid | 1900 | 3200 | 0.015 | 0.03 / 0.03 / 0.05 | 60 / 72 / 84 | 34 / 29 / 45 | 33 / 36 / 38 | 20 | medium |
| concrete | solid | 2300 | 3800 | 0.01 | 0.01 / 0.02 / 0.02 | 62 / 74 / 86 | 36 / 30 / 47 | 34 / 36 / 38 | 48 | high |
| terracotta | solid | 2000 | 3400 | 0.012 | 0.02 / 0.02 / 0.04 | 61 / 73 / 85 | 35 / 29 / 46 | 33 / 36 / 38 | 45 | medium |
| packed_earth | porous | 1500 | 300 (frame) | 0.1 | 0.09 / 0.19 / 0.39 | 48 / 53 / 69 | 31 / 42 / 52 | 30 / 31 / 35 | 8 | low |
| mud | fluid | 1700 | 1500 | 0.02 | 0.01 / 0.02 / 0.03 | 62 / 76 / 76 | 33 / 45 / 59 | 34 / 37 / 37 | 3 | low |
| sand | porous | 1600 | 250 (frame) | 0.1 | 0.09 / 0.21 / 0.41 | 44 / 51 / 69 | 31 / 40 / 46 | 28 / 31 / 35 | 19 | low |
| gravel | porous | 1700 | 300 (frame) | 0.1 | 0.33 / 0.57 / 0.77 | 50 / 54 / 70 | 11 / 12 / 16 | 30 / 31 / 35 | 2 | low |
| snow | porous | 300 | 800 (frame) | 0.05 | 0.27 / 0.50 / 0.75 | 46 / 49 / 57 | 15 / 20 / 30 | 29 / 30 / 32 | 2 | medium |
| powder_snow | porous | 80 | 150 (frame) | 0.1 | 0.68 / 0.87 / 0.93 | 16 / 23 / 48 | 2.66 / 5.32 / 8.32 | 13 / 18 / 30 | 1 | medium |
| ice | solid | 917 | 3900 | 0.005 | 0.01 / 0.01 / 0.02 | 52 / 66 / 78 | 28 / 19 / 35 | 31 / 35 / 37 | 4 | medium |
| glass | solid | 2500 | 5770 | 0.002 | 0.18 / 0.04 / 0.02 | 63 / 75 / 87 | 33 / 31 / 47 | 23 / 22 / 29 | 40 | high |
| obsidian | solid | 2450 | 5500 | 0.002 | 0.01 / 0.01 / 0.02 | 62 / 74 / 86 | 33 / 30 / 46 | 34 / 36 / 38 | 6 | medium |
| wood_planks | solid | 550 | 3800 | 0.01 | 0.15 / 0.10 / 0.06 | 46 / 61 / 73 | 23 / 16 / 33 | 29 / 33 / 36 | 202 | medium |
| logs | solid | 650 | 3800 | 0.015 | 0.03 / 0.05 / 0.07 | 48 / 63 / 75 | 25 / 19 / 36 | 30 / 34 / 36 | 55 | low |
| leaves | foliage | 5 | 343 | 0.3 | 0.04 / 0.10 / 0.20 | 0.03 / 0.05 / 0.08 | 0.00 / 0.00 / 0.00 | 0.50 / 1.00 / 2.00 | 107 | low |
| wool | porous | 120 | 20 (frame) | 0.3 | 0.60 / 0.82 / 0.91 | 46 / 137 / 232 | 3.42 / 6.39 / 13 | 40 / 43 / 45 | 81 | medium |
| carpet | porous | 120 | 20 (frame) | 0.3 | 0.08 / 0.57 / 0.71 | 46 / 137 / 232 | 3.42 / 6.39 / 13 | 40 / 43 / 45 | 16 | high |
| hay | porous | 120 | 50 (frame) | 0.2 | 0.78 / 0.90 / 0.93 | 22 / 55 / 122 | 1.92 / 3.03 / 6.86 | 17 / 32 / 42 | 5 | medium |
| sponge | porous | 30 | 60 (frame) | 0.2 | 0.76 / 0.90 / 0.93 | 13 / 41 / 136 | 0.28 / 1.44 / 6.31 | 11 / 27 / 43 | 3 | medium |
| wet_sponge *(invented)* | fluid | 1000 | 1400 | 0.05 | 0.03 / 0.05 / 0.07 | 57 / 72 / 74 | 28 / 40 / 54 | 32 / 36 / 36 | 6 | low |
| moss | porous | 150 | 30 (frame) | 0.3 | 0.55 / 0.79 / 0.91 | 36 / 110 / 258 | 5.44 / 7.33 / 14 | 25 / 41 / 45 | 11 | medium |
| iron | solid | 7870 | 5900 | 0.0005 | 0.01 / 0.01 / 0.02 | 72 / 85 / 97 | 44 / 45 / 60 | 36 / 38 / 40 | 30 | high |
| copper | solid | 8960 | 4660 | 0.002 | 0.01 / 0.01 / 0.02 | 74 / 86 / 98 | 47 / 46 / 61 | 36 / 38 / 40 | 121 | medium |
| gold | solid | 19300 | 3240 | 0.001 | 0.01 / 0.01 / 0.02 | 80 / 92 / 104 | 54 / 57 / 68 | 37 / 39 / 41 | 4 | medium |
| netherite *(invented)* | solid | 18000 | 5000 | 0.001 | 0.01 / 0.01 / 0.02 | 80 / 92 / 104 | 53 / 55 / 70 | 37 / 39 / 41 | 3 | low |
| crystal | solid | 2650 | 5760 | 2E-05 | 0.01 / 0.01 / 0.02 | 63 / 75 / 87 | 33 / 31 / 46 | 34 / 37 / 39 | 9 | medium |
| bookshelf | solid | 650 | 1500 | 0.05 | 0.10 / 0.20 / 0.30 | 45 / 61 / 75 | 25 / 35 / 37 | 28 / 33 / 36 | 3 | low |
| slime *(invented)* | fluid | 1050 | 1550 | 0.003 | 0.01 / 0.02 / 0.03 | 58 / 66 / 64 | 28 / 41 / 55 | 33 / 35 / 34 | 5 | low |
| honey | fluid | 1420 | 2125 | 0.0001 | 0.01 / 0.02 / 0.03 | 61 / 69 / 57 | 31 / 43 / 58 | 33 / 35 / 32 | 1 | medium |
| wax_resin | solid | 1000 | 2000 | 0.05 | 0.01 / 0.02 / 0.03 | 51 / 66 / 79 | 29 / 37 / 41 | 30 / 35 / 37 | 25 | low |
| water | fluid | 999.7 | 1447 | 1E-07 | 0.01 / 0.01 / 0.02 | 58 / 53 / 46 | 28 / 40 / 55 | 32 / 31 / 29 | 2 | high |
| lava | fluid | 2600 | 2500 | 5E-05 | 0.02 / 0.03 / 0.05 | 66 / 80 / 63 | 36 / 49 / 63 | 35 / 37 / 34 | 1 | low |
| netherrack *(invented)* | solid | 1700 | 2000 | 0.03 | 0.06 / 0.10 / 0.14 | 58 / 71 / 83 | 33 / 36 / 45 | 33 / 36 / 38 | 6 | low |
| end_stone *(invented)* | solid | 2000 | 2600 | 0.015 | 0.02 / 0.04 / 0.06 | 61 / 73 / 85 | 35 / 32 / 45 | 33 / 36 / 38 | 10 | low |
| soul_sand *(invented)* | porous | 1400 | 150 (frame) | 0.2 | 0.15 / 0.32 / 0.56 | 42 / 56 / 101 | 24 / 28 / 36 | 27 / 32 / 40 | 2 | low |
| sculk *(invented)* | fluid | 1050 | 1540 | 0.1 | 0.06 / 0.18 / 0.38 | 58 / 77 / 79 | 28 / 41 / 55 | 33 / 37 / 37 | 6 | low |

What the real 1 m numbers mean:
- **Stone.** Through 1 m of stone the real TL is 57–99 dB, about the field mass law for 2,650 kg/m². In real buildings, anything above about 70–80 dB can't be observed, because sound leaks around the wall through connected structure (flanking) and measurements bottom out. For very thick panels, theory also overestimates high frequencies (thick-plate effects).
- **Water.** A 1 m layer of water is about 46 dB from 1 kHz up. Only a narrow cone of about 13°, around the perpendicular, gets into water at all, and each air–water crossing costs 29.4 dB.
- **Wool.** Real wool beats stone above 250 Hz but is weaker at 63 Hz, where only the limp fibre mass resists. The gameplay figure deliberately overrides that.

## Invented materials and their real-world stand-ins

| Material | Stand-in | Why |
|---|---|---|
| netherrack | scoria, a bubbly volcanic rock | Soft, crumbly, red and found by lava, so a porous, light, well-damped rock |
| end_stone (also purpur, end portal frame) | porous limestone or chalk | Pale, pitted and lighter-looking than overworld stone |
| soul_sand (also soul soil) | loose, silty, organic-rich sand | Slows entities, so a looser and more damped granular bed than sand |
| sculk (all sculk blocks) | soft hydrated tissue (1050 kg/m³, 1540 m/s) with a velvety surface | Organic and "absorbs" vibrations, so high damping and a fuzzy, absorbing surface |
| slime (also frogspawn, froglights) | gelatin or hydrogel (1520–1650 m/s) | Elastic gel that bounces, so low damping and a hard, reflective surface |
| netherite (also ancient debris, heavy core) | tungsten heavy alloy | Ultra-dense, blast-proof metal alloyed with gold |
| wet_sponge (also melon, pumpkin, cactus) | foam saturated with water, which behaves like water with a lossy frame | Same idea as water-rich plant flesh |

Other mapping choices worth knowing:
- **Cinnabar → dense_stone.** Treated as cinnabar-veined rock. Pure cinnabar mineral is 8,100 kg/m³.
- **Sulfur → sandstone.** Native sulfur is about 2,070 kg/m³ and soft.
- **Coal block → sandstone.** Real coal is lighter, about 1,350 kg/m³.
- **Tuff and coral → sandstone.**
- **Prismarine → stone_bricks.**
- **Nether, mud and packed-mud bricks → brick.** Adobe is lighter in reality.
- **Amethyst, diamond and emerald blocks → crystal.** This is quartz: it almost doesn't lose vibration energy at all, so it rings.
- **Lapis block → polished_stone.**
- **Glowstone, sea lantern and redstone lamp → glass.**
- **Bone, skulls, eggs and pots → terracotta.**
- **Mushroom and wart blocks, shroomlight → moss.**
- **Candles, honeycomb and resin → wax_resin.** This is a solid amorphous polymer: about 1,000 kg/m³, an estimated 2,000 m/s, loss factor 0.05. Low confidence.
- **TNT → bookshelf** (paper).
- **Chests, barrels and shulker boxes → wood_planks.**

**Partly open blocks.** The `acoustic_fill` table gives the fraction of a cell that blocks sound, and the cell's transmission is `τ = (1 − fill) + fill · 10^(−TL/10)`. Small openings dominate: a cell that is 25 % solid can never block more than about 1.2 dB.
- A fill of 0 means "ignore for occlusion".
- Blocks not in the table should use their collision shape: the 5 cm figure for thin parts, and the 1 m figure scaled by thickness for thick parts.
- The material mapping is kept for every block, so the mod can still use it for reflections or footsteps.

All fill values are estimates. Air, barrier, light, structure void and the test blocks are listed under `unmapped_non_physical`.

`block_mapping` lists exact IDs first, then `#` tag fallbacks for modded blocks. Resolve exact IDs before tags. I could not check vanilla tag membership or the `c:` convention tag names against 26.3 data, so verify them in-game.

## Water and lava as media

| Quantity | Fresh water (10 °C) | Seawater (10 °C, 35‰) | Lava (basaltic melt) |
|---|---|---|---|
| Sound speed | 1447.3 m/s (1482.4 at 20 °C), derived (Marczak 1997) | 1489.8 m/s, derived (Mackenzie 1981) | 2500 m/s bubble-free, **estimated**; 62 m/s with 1 % gas, derived (Wood's equation, Kieffer 1977 mechanism) |
| Density | 999.7 kg/m³ | 1027 kg/m³ | 2600 kg/m³, **estimated** |
| Air → medium loss, straight on | 29.4 dB, derived | 29.7 dB, derived | 35.9 dB, derived |
| Absorption at 63 / 125 / 250 / 500 Hz | 1.3e-9 / 5.3e-9 / 2.1e-8 / 8.5e-8 dB/m | 4e-7 / 1.6e-6 / 6.1e-6 / 2.2e-5 dB/m | 2.2e-6 / 8.8e-6 / 3.5e-5 / 1.4e-4 dB/m |
| Absorption at 1 k / 2 k / 4 k / 8 k Hz | 3.4e-7 / 1.4e-6 / 5.4e-6 / 2.2e-5 dB/m | 6.1e-5 / 1.3e-4 / 2.5e-4 / 6.8e-4 dB/m | 5.6e-4 / 2.3e-3 / 9.0e-3 / 0.036 dB/m |
| Absorption method | Ainslie & McColm (1998), S = 0 | Ainslie & McColm (1998), pH 8, surface | Viscous only, μ = 100 Pa·s. A lower bound: crystals and bubbles absorb far more |
| Gameplay absorption, 63 → 8 kHz | 0.02, 0.03, 0.05, 0.1, 0.2, 0.4, 0.8, 1.5 dB/m (design) | same | 0.5, 1, 2, 3, 5, 8, 12, 16 dB/m (design) |

Real water absorbs essentially nothing over Minecraft distances: under 0.1 dB per 100 blocks even at 8 kHz in seawater. The muffled underwater sound has to come from three things:
- the ~30 dB loss crossing the surface;
- the narrow transmission cone;
- a designed low-pass filter, which is the gameplay column.

## Values I trust least

1. **Everything about lava.** I couldn't retrieve melt sound-speed papers this session, so 2,500 m/s and 2,600 kg/m³ are estimates. Real lava carries crystals and gas, and its sound speed can be anywhere from about 60 to 2,900 m/s. Confidence: low.
2. **Frame wave speeds of porous blocks.** Wool 20, moss 30, hay 50, sponge 60, powder snow 150 and snow 800 m/s are all estimates. They alone set the 63–250 Hz TL of those blocks. Confidence: low.
3. **Flow resistivities.** Sand 300k, gravel 30k, wool 25k, hay 8k and packed earth 400k Pa·s/m² are estimates. Only the snow and grass ranges were confirmed from the Embleton et al. abstract. The Miki model also falls outside its fitted range below about 250 Hz for sand and earth. Confidence: low to medium.
4. **All scattering coefficients.** These are estimated, and the band shape is an assumption. Confidence: low.
5. **Leaf TL.** The ISO 9613-2 per-metre values (0.02–0.12 dB/m) are recalled from the standard, which is paywalled, not re-checked. Leaf absorption is estimated.
6. **The six invented materials, wet_sponge and wax_resin.** These are deliberate analogues, not data. The `acoustic_fill` fractions are also estimates from block textures, not measurements.
7. **Real 1 m TL above about 80 dB.** It's theoretical. The ISO 12354-1 loss formula is used far outside its usual mass range, and thick-plate effects are ignored.
8. **Wood thin-panel TL** (doors and trapdoors, 16–19 dB at 250–500 Hz). The model treats wood as the same stiffness in every direction. Real wood isn't, which smears the coincidence dip, so real 45 mm solid doors typically reach roughly 25–30 dB in those bands (typical figure, not sourced this session).
9. **Bookshelf absorption, logs/bark absorption, sandstone and cobblestone absorption.** Estimated by interpolating between measured rows.
10. **Loss factors of ice (0.005), gold (0.001) and crystal (2e-5), and the 63 Hz and 8 kHz values of every measured absorption row.** The loss factors are estimates; the band values are extrapolated.
11. **Glass at 63 and 125 Hz (0.18).** That value comes from a mounted pane resonating, so it is right for panes and too high for a solid glass cube.

## Sources

**Verified this session:**
- **Absorption:** compiled table (marble/tile, unglazed brick, concrete, wood on joists, glass, carpet, water/ice). The original measurement method for each row isn't stated. https://sengpielaudio.com/calculator-RT60Coeff.htm
- **Sharp panel method and the −47 dB field mass law:** Tadeu & Mateus, https://baes.uc.pt/bitstream/10316/4037/1/filee0aec1aa21024e55b37c28755f99fb89.pdf . Mansilla et al. (IOA) compared it with measurements.
- **Loss factors:**
  - scribd damping table, https://www.scribd.com/doc/239287321/Damping-Properties-of-Materials
  - COMSOL damping blog, https://www.comsol.com/blogs/damping-in-structural-dynamics-theory-and-sources
  - *Principles of Vibration*, Appendix C, https://www.globalspec.com/reference/74164/203279/appendix-c-assorted-material-constants
- **Sound speeds:**
  - Olympus/Evident NDT velocity table (copper, steel, cast iron), https://www.olympus-ims.com/en/ndt-tutorials/thickness-gage/appendices-velocities/
  - Karl Deutsch NDT table (window glass, quartz), https://www.karldeutsch.de/ndt-knowledge/basic-knowledge/basic-knowledge-about-ultrasonic-testing/?lang=en
  - CLASS Ltd (ice), https://www.classltd.com/?p=5191
- **Honey, 2125 m/s:** Rufo et al. 2021, *Sensors* 21:6748, https://mdpi.com/1424-8220/21/20/6748
- **Gelatin, 1520–1650 m/s:** https://link.springer.com/10.1007/s10856-026-07020-7
- **Shallow sand P-wave speed, 87–390 m/s:**
  - https://pure.kfupm.edu.sa/en/publications/p-wave-velocity-at-shallow-depths-in-sand-dunes-and-its-effect-on/
  - https://kuscholarworks.ku.edu/bitstream/1808/17259/1/SteeplesD_GRL_26(2)279.pdf
- **Moss absorption:**
  - TU Delft, https://repository.tudelft.nl/record/uuid:f5734366-f4e2-494f-969e-6bc7042a2879
  - LBTU 2026, https://erdev.lbtu.lv/sites/default/files/proceedings/2026/TF090.pdf
- **Straw:**
  - Mas & Everbach 1995, https://acoustics.org/pressroom/httpdocs/130th/lay03.html
  - D'Alessandro et al., https://www.ioa.org.uk/system/files/publications/F%20D%27ALESSANDRO%20S%20SCHIAVONI%20F%20BIANCHI%20STRAW%20AS%20AN%20ACOUSTIC%20MATERIAL.pdf
- **Ground flow resistivity:** Embleton, Piercy & Daigle 1983, JASA 74:1239, doi:10.1121/1.390029. Abstract values only.
- **Snow acoustics:** Zhou et al. 2012, https://publica.fraunhofer.de/handle/publica/228468
- **Seawater absorption:** Ainslie & McColm 1998, JASA 103:1671, doi:10.1121/1.421258 (implementation: https://echopype.readthedocs.io/en/v0.9.1/api/echopype.utils.uwa.calc_absorption.html)
- **Bubbly liquids:** Kieffer 1977, https://www.eri.u-tokyo.ac.jp/people/ichihara/vp2007plan/Kieffer1977jgr.pdf
- **Scattering practice:**
  - https://odeon.dk/pdf/in05_1782.pdf
  - https://www.tvtechnology.com/opinions/coefficients-for-acoustical-materials
- **Foliage method:** https://strutt.arup.com/help/Environmental_Noise/EnviroFoliageAtten.htm
- **Wool and carpet vibration behaviour:** https://minecraft.fandom.com/wiki/Wool

**Recalled from textbooks or standards, not re-verified online:**
- Miki 1990 and Delany–Bazley 1970 model coefficients
- the ISO 12354-1 Annex C loss formula
- the ISO 9613-2 Table A.1 foliage values
- Marczak 1997 and Mackenzie 1981 sound-speed equations
- rock, wood, gold and quartz bulk properties (Kuttruff; Bies & Hansen; Cremer & Heckl; USDA Wood Handbook)
- basaltic melt properties (Ai & Lange 2008; Rivers & Carmichael 1987)

Each JSON material says which of these it relies on.
