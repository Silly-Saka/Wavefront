package wavefront.acoustics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The block acoustics database: which material each block is made of, how much
 * of each block is actually solid, and how strongly walls block sound.
 * Loaded from acoustic_materials.json (see docs/research for where the numbers come from).
 */
public final class AcousticDatabase {
    /** Material used when a block is in neither the ID list nor any known tag. */
    public static final String FALLBACK_MATERIAL = "dense_stone";

    private final Map<String, AcousticMaterial> materials = new LinkedHashMap<>();
    private final Map<String, String> blockToMaterial = new HashMap<>();
    /** Tag fallbacks in file order, e.g. "minecraft:wool" -> "wool". Keys have no leading '#'. */
    private final Map<String, String> tagToMaterial = new LinkedHashMap<>();
    private final Map<String, Double> fill = new HashMap<>();
    private final Set<String> nonPhysical = new HashSet<>();

    private double capDb = 45.0;
    private double wallStrength = 1.0;

    private static final double[] LEAF_FLOOR = {0.5, 0.5, 1, 1, 1.5, 2, 2.5, 3};
    private static final double WOOL_BONUS_DB = 6.0;

    private AcousticDatabase() {}

    public static AcousticDatabase load(Reader json) {
        JsonObject root = JsonParser.parseReader(json).getAsJsonObject();
        AcousticDatabase db = new AcousticDatabase();

        int bandCount = root.getAsJsonArray("bands_hz").size();
        if (bandCount != Bands.COUNT) {
            throw new IllegalArgumentException("Expected " + Bands.COUNT + " bands, file has " + bandCount);
        }

        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("materials").entrySet()) {
            JsonObject m = e.getValue().getAsJsonObject();
            db.materials.put(e.getKey(), new AcousticMaterial(
                    e.getKey(),
                    bands(m, "absorption"),
                    bands(m, "scattering"),
                    bands(m, "transmission_loss_db_1m"),
                    bands(m, "transmission_loss_db_thin"),
                    m.get("density_kg_m3").getAsDouble(),
                    m.get("sound_speed_m_s").getAsDouble(),
                    m.get("loss_factor").getAsDouble()));
        }
        if (!db.materials.containsKey(FALLBACK_MATERIAL)) {
            throw new IllegalArgumentException("Database has no '" + FALLBACK_MATERIAL + "' material");
        }

        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("block_mapping").entrySet()) {
            String material = e.getValue().getAsString();
            if (!db.materials.containsKey(material)) continue; // ignore entries for unknown materials
            String key = e.getKey();
            if (key.startsWith("#")) db.tagToMaterial.put(key.substring(1), material);
            else db.blockToMaterial.put(key, material);
        }

        if (root.has("acoustic_fill")) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("acoustic_fill").entrySet()) {
                db.fill.put(e.getKey(), clamp01(e.getValue().getAsDouble()));
            }
        }
        if (root.has("unmapped_non_physical")) {
            for (JsonElement e : root.getAsJsonArray("unmapped_non_physical")) db.nonPhysical.add(e.getAsString());
        }
        if (root.has("gameplay_curve")) {
            JsonObject g = root.getAsJsonObject("gameplay_curve");
            if (g.has("cap_db")) db.capDb = g.get("cap_db").getAsDouble();
            if (g.has("wall_strength")) db.wallStrength = g.get("wall_strength").getAsDouble();
        }
        db.recomputeGameplay();
        return db;
    }

    private static double[] bands(JsonObject m, String key) {
        JsonArray a = m.getAsJsonArray(key);
        if (a == null || a.size() != Bands.COUNT) {
            throw new IllegalArgumentException("Material field '" + key + "' must have " + Bands.COUNT + " values");
        }
        double[] out = new double[Bands.COUNT];
        for (int i = 0; i < Bands.COUNT; i++) out[i] = a.get(i).getAsDouble();
        return out;
    }

    /**
     * Sets how strongly walls block sound. 1.0 is the researched default, 0.5 halves every
     * wall's loss in dB, 0 turns occlusion off.
     */
    public void setWallStrength(double strength) {
        double s = Math.max(0, strength);
        if (s == wallStrength) return;
        wallStrength = s;
        recomputeGameplay();
    }

    public double wallStrength() {
        return wallStrength;
    }

    /** Squeezes a huge real-world loss under a ceiling so walls stay audible-but-quiet. */
    private double curve(double realDb) {
        return wallStrength * capDb * (1.0 - Math.exp(-realDb / capDb));
    }

    private void recomputeGameplay() {
        AcousticMaterial stone = materials.get(FALLBACK_MATERIAL);
        double[] stoneFull = new double[Bands.COUNT];
        for (int b = 0; b < Bands.COUNT; b++) stoneFull[b] = curve(stone.realLoss1m[b]);

        for (AcousticMaterial m : materials.values()) {
            double[] full = new double[Bands.COUNT];
            double[] thin = new double[Bands.COUNT];
            for (int b = 0; b < Bands.COUNT; b++) {
                thin[b] = curve(m.realLossThin[b]);
                switch (m.name) {
                    // Glass blocks behave like windows, not like a metre of solid glass.
                    case "glass" -> full[b] = thin[b];
                    // Wool is the game's sound-deadening block (it blocks sculk vibrations).
                    case "wool", "carpet" -> full[b] = Math.max(curve(m.realLoss1m[b]),
                            stoneFull[b] + WOOL_BONUS_DB * wallStrength);
                    case "leaves" -> full[b] = Math.max(m.realLoss1m[b], LEAF_FLOOR[b]) * wallStrength;
                    default -> full[b] = curve(m.realLoss1m[b]);
                }
            }
            m.gameLoss1m = full;
            m.gameLossThin = thin;
        }
    }

    public AcousticMaterial material(String name) {
        return materials.get(name);
    }

    public AcousticMaterial fallbackMaterial() {
        return materials.get(FALLBACK_MATERIAL);
    }

    public List<String> materialNames() {
        return new ArrayList<>(materials.keySet());
    }

    /** Material for an exact block ID such as "minecraft:stone", or null if the ID is not listed. */
    public AcousticMaterial materialForBlock(String blockId) {
        String name = blockToMaterial.get(blockId);
        return name == null ? null : materials.get(name);
    }

    /** Tag fallbacks for modded blocks, in priority order: tag ID (no '#') to material name. */
    public Map<String, String> tagFallbacks() {
        return Collections.unmodifiableMap(tagToMaterial);
    }

    /**
     * How much of the block's cell actually blocks sound: 0 means ignore the block,
     * 1 means trust its collision shape, in between means it is full of holes.
     * Returns -1 when the block has no entry (use its collision shape).
     */
    public double fillForBlock(String blockId) {
        Double f = fill.get(blockId);
        return f == null ? -1 : f;
    }

    public boolean isNonPhysical(String blockId) {
        return nonPhysical.contains(blockId);
    }

    public int blockCount() {
        return blockToMaterial.size();
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
