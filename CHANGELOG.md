# Changelog

## 0.1.0

First version, forked from Sound Physics Perfected 1.17.7 (26.x branch).

- Added the acoustic block database (41 materials, 8 bands, every 26.3 block).
- Added occlusion from real block shapes and materials, with a Wall Strength setting.
- Added partly open blocks (bars, fences, grates) and ignored non-solid blocks.
- Added edge diffraction with up to three routes per sound.
- Opening size: narrow gaps pass less bass than wide ones.
- The per-sound filter can now cut bass as well as treble.
- Blocked sounds now appear to come from the edge or opening they travelled through.
- Voice chat uses the same occlusion and diffraction.
- Own settings screen: a Quality switch and expandable rows for Walls, Diffraction, Reverb, Voice chat and General. Every setting is in these rows.
- Performance, Balanced and Realism are fixed presets covering every setting; changing one asks first, then switches to Custom starting from that preset.
- Defaults are the most realistic values.
- Every tooltip says how the setting affects realism and performance, with a recommended value for each.
- Plain-language tooltips on the main settings.
- New icon.
