package wavefront.acoustics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Sound that travels through the solid blocks themselves. A footstep, a piston or an explosion
 * shakes the block it touches; the shaking spreads through whatever solid is connected to it,
 * and the surfaces near the listener give it off again as a dull thud. This is how you hear
 * someone walking on the floor above when the floor blocks nearly all the sound in the air.
 *
 * What comes from the block database: how well the shaking crosses from one material to
 * another (from each material's density and speed of sound) and how quickly each material
 * soaks it up (from its loss factor). What is an estimate, not a measured figure: how much of
 * an impact goes into the structure and comes out again as sound ({@link #BASE_DB} and
 * {@link #TILT_DB}), and the small loss per block ({@link #PER_BLOCK_DB}).
 */
public final class StructureBorne {
    private StructureBorne() {}

    /** What the search needs to know about the world. */
    public interface World {
        /** The material of the solid block in this cell, or null if shaking cannot travel there (air, water, plants). */
        AcousticMaterial solidAt(int x, int y, int z);

        /** Is the straight line from A to B through air unobstructed? */
        boolean segmentClear(double ax, double ay, double az, double bx, double by, double bz);
    }

    /**
     * How loud the thud from a full-strength impact is at 63 Hz, compared with hearing the
     * impact itself through open air over the same distance, before any losses on the way.
     * An estimate: footsteps on a bare floor are plainly heard in the room below.
     */
    public static final double BASE_DB = 12.0;
    /** How much weaker than that each band is. Structure-borne sound is nearly all bass. An estimate. */
    static final double[] TILT_DB = {0, 2, 6, 12, 20, 28, 36, 44};
    /**
     * Lost per block travelled, on top of what the material soaks up. Stands in for what a real
     * structure loses at joints and gives off along the way. An estimate.
     */
    public static final double PER_BLOCK_DB = 0.5;
    /** A light, stiff floor (planks) shakes more under the same blow than a heavy one (stone): up to this much. */
    private static final double MAX_LIGHTNESS_DB = 6.0;
    private static final double REFERENCE_DENSITY = 2000.0;
    /** Below this stiffness (density times speed squared, in pascals) a material is soft and gets no lightness bonus. */
    private static final double STIFF = 1.0e9;
    /** No material takes more than this off per block. */
    private static final double MAX_DAMPING_DB = 40.0;
    /** The search follows the shaking until it has lost this much (at 125 Hz). */
    private static final double MAX_LOSS_DB = 45.0;
    /** How many surfaces are tried for a clear line to the listener, best first. */
    private static final int MAX_SIGHT_CHECKS = 160;
    /** Band used to decide which way through the blocks is best. */
    private static final int SEARCH_BAND = 1;

    private static final int[][] SIDES = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};

    /** The way one sound gets to the listener through the blocks. */
    public static final class Path {
        /** Energy per band, compared with an unblocked sound through air over the same total length. */
        public final double[] energy;
        /** The point on the surface that gives the sound off, just inside the air. */
        public final double[] from;
        /** Blocks travelled through solid, and through air after that. */
        public final double solidLength, airLength;
        /** Travel time. Shaking moves through stone about ten times faster than sound through air. */
        public final double seconds;
        /** The blocks passed through, from the one that was struck to the one that gives the sound off. */
        public final int[][] cells;

        Path(double[] energy, double[] from, double solidLength, double airLength, double seconds, int[][] cells) {
            this.energy = energy;
            this.from = from;
            this.solidLength = solidLength;
            this.airLength = airLength;
            this.seconds = seconds;
            this.cells = cells;
        }
    }

    /**
     * How much of a sound goes into the block it touches, from its name: 1 for a blow (steps,
     * falls, pistons, blocks being hit, broken or placed, explosions), less for things that
     * knock or rumble, a little for a note block or jukebox standing on the floor, and 0 for
     * everything that is only in the air (voices, animals, music, weather).
     */
    public static double coupling(String soundId) {
        if (soundId == null) return 0;
        String id = soundId.toLowerCase(java.util.Locale.ROOT);
        int colon = id.indexOf(':');
        if (colon >= 0) id = id.substring(colon + 1);
        if (id.contains("step") || id.contains("gallop") || id.contains("_fall") || id.endsWith(".fall") || id.contains(".land")
                || id.contains("explode") || id.contains("explosion") || id.contains("piston") || id.contains("anvil")) return 1.0;
        if (id.startsWith("block.") && (id.endsWith(".hit") || id.endsWith(".break") || id.endsWith(".place"))) return 1.0;
        if (id.contains("door") || id.contains("fence_gate")) return 0.7;
        if (id.contains("minecart")) return 0.5;
        if (id.contains("chest") || id.contains("barrel") || id.contains("shulker_box")) return 0.3;
        if (id.contains("note_block") || id.contains("jukebox") || id.contains("music_disc")) return 0.1;
        return 0;
    }

    /** What is lost, in dB, when shaking crosses from one material into another. Like to like loses nothing. */
    public static double crossingLossDb(AcousticMaterial a, AcousticMaterial b) {
        if (a == b) return 0;
        double za = a.density * a.soundSpeed, zb = b.density * b.soundSpeed;
        if (za <= 0 || zb <= 0) return MAX_DAMPING_DB;
        double through = 4 * za * zb / ((za + zb) * (za + zb));
        return Math.min(MAX_DAMPING_DB, -10 * Math.log10(Math.max(through, 1e-6)));
    }

    /** What one block of a material soaks up, in dB, at one band. Stone takes almost nothing; wool takes nearly all. */
    public static double dampingDb(AcousticMaterial m, int band) {
        if (m.soundSpeed <= 0) return MAX_DAMPING_DB;
        return Math.min(MAX_DAMPING_DB, 27.3 * m.lossFactor * Bands.HZ[band] / m.soundSpeed);
    }

    /** How much more a light, stiff material shakes under a blow than a heavy one, in dB (0 for soft materials). */
    static double lightnessDb(AcousticMaterial m) {
        if (m.density * m.soundSpeed * m.soundSpeed < STIFF) return 0;
        return Math.max(0, Math.min(MAX_LIGHTNESS_DB, 10 * Math.log10(REFERENCE_DENSITY / m.density)));
    }

    private static final class Node {
        final int x, y, z;
        final AcousticMaterial material;
        final Node parent;
        final double loss;
        final int blocks;
        boolean done;

        Node(int x, int y, int z, AcousticMaterial material, Node parent, double loss, int blocks) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.material = material;
            this.parent = parent;
            this.loss = loss;
            this.blocks = blocks;
        }
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /**
     * Finds the best way for a sound to reach the listener through solid blocks.
     *
     * @param coupling how much of the sound goes into the block it touches (see {@link #coupling(String)}), times any strength setting
     * @param maxNodes how many blocks the search may look at
     * @param range    how far from the source the search may go, and how far a surface may be from the listener, in blocks
     * @return the path, or null if the sound touches nothing solid or no surface in view of the listener is reached
     */
    public static Path find(World world, double[] source, double[] listener, double coupling, int maxNodes, int range) {
        if (coupling <= 0) return null;
        int sx = (int) Math.floor(source[0]), sy = (int) Math.floor(source[1]), sz = (int) Math.floor(source[2]);
        AcousticMaterial struck = world.solidAt(sx, sy, sz);
        if (struck == null) {
            // Not inside a block: a footstep or a landing, which strikes the block underneath.
            sy = (int) Math.floor(source[1] - 0.51);
            struck = world.solidAt(sx, sy, sz);
            if (struck == null) return null;
        }

        Map<Long, Node> seen = new HashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.loss, b.loss));
        Node start = new Node(sx, sy, sz, struck, null, 0, 0);
        seen.put(key(sx, sy, sz), start);
        open.add(start);

        // The surfaces reached: solid blocks with air on one side. Only the best one is kept for each
        // patch of 4 x 4 x 4 blocks and each facing, since neighbours are in view of the same places.
        Map<Long, Object[]> patches = new HashMap<>();
        int looked = 0;
        while (!open.isEmpty() && looked < maxNodes) {
            Node node = open.poll();
            if (node.done || seen.get(key(node.x, node.y, node.z)) != node) continue; // a better way to this block was found since
            node.done = true;
            looked++;
            for (int[] side : SIDES) {
                int x = node.x + side[0], y = node.y + side[1], z = node.z + side[2];
                if (Math.abs(x - sx) > range || Math.abs(y - sy) > range || Math.abs(z - sz) > range) continue;
                long k = key(x, y, z);
                Node known = seen.get(k);
                if (known != null && known.done) continue;
                AcousticMaterial material = known != null ? known.material : world.solidAt(x, y, z);
                if (material == null) {
                    // Air on this side: the block can give its shaking off here.
                    double px = node.x + 0.5 + side[0] * 0.55, py = node.y + 0.5 + side[1] * 0.55, pz = node.z + 0.5 + side[2] * 0.55;
                    double air = Math.sqrt(sq(px - listener[0]) + sq(py - listener[1]) + sq(pz - listener[2]));
                    // A face that points away from the listener has its own block in the way.
                    boolean faces = (listener[0] - px) * side[0] + (listener[1] - py) * side[1] + (listener[2] - pz) * side[2] > 0;
                    if (faces && air <= range) {
                        double score = node.loss + 20 * Math.log10(Math.max(1.0, node.blocks + air));
                        long patch = key(node.x >> 2, node.y >> 2, node.z >> 2) * 6 + sideIndex(side);
                        Object[] best = patches.get(patch);
                        if (best == null || score < (Double) best[0]) patches.put(patch, new Object[]{score, node, new double[]{px, py, pz}, air});
                    }
                    continue;
                }
                double loss = node.loss + crossingLossDb(node.material, material) + dampingDb(material, SEARCH_BAND) + PER_BLOCK_DB;
                if (loss > MAX_LOSS_DB) continue;
                if (known == null || loss < known.loss) {
                    Node next = new Node(x, y, z, material, node, loss, node.blocks + 1);
                    seen.put(k, next);
                    open.add(next);
                }
            }
        }
        if (patches.isEmpty()) return null;

        List<Object[]> surfaces = new ArrayList<>(patches.values());
        surfaces.sort((a, b) -> Double.compare((Double) a[0], (Double) b[0]));
        for (int i = 0; i < Math.min(MAX_SIGHT_CHECKS, surfaces.size()); i++) {
            Object[] surface = surfaces.get(i);
            double[] point = (double[]) surface[2];
            if (!world.segmentClear(point[0], point[1], point[2], listener[0], listener[1], listener[2])) continue;
            return build((Node) surface[1], point, (Double) surface[3], struck, coupling);
        }
        return null;
    }

    private static Path build(Node last, double[] point, double air, AcousticMaterial struck, double coupling) {
        int count = last.blocks + 1;
        int[][] cells = new int[count][];
        double[] lost = new double[Bands.COUNT];
        double crossings = 0, solidSeconds = 0;
        Node node = last;
        for (int i = count - 1; i >= 0; i--, node = node.parent) {
            cells[i] = new int[]{node.x, node.y, node.z};
            solidSeconds += 1.0 / Math.max(50.0, node.material.soundSpeed);
            if (node.parent == null) continue; // the struck block itself: the blow goes straight into it
            crossings += crossingLossDb(node.parent.material, node.material) + PER_BLOCK_DB;
            for (int b = 0; b < Bands.COUNT; b++) lost[b] += dampingDb(node.material, b);
        }
        double[] energy = new double[Bands.COUNT];
        double gained = lightnessDb(struck) + 10 * Math.log10(Math.min(4.0, coupling));
        for (int b = 0; b < Bands.COUNT; b++) {
            energy[b] = Math.min(1.0, Bands.dbToEnergy(BASE_DB + TILT_DB[b] + crossings + lost[b] - gained));
        }
        double solid = Math.max(0.5, last.blocks);
        return new Path(energy, point, solid, air, solidSeconds + air / Bands.SPEED_OF_SOUND, cells);
    }

    private static int sideIndex(int[] side) {
        return side[0] != 0 ? (side[0] < 0 ? 0 : 1) : side[1] != 0 ? (side[1] < 0 ? 2 : 3) : (side[2] < 0 ? 4 : 5);
    }

    private static double sq(double v) {
        return v * v;
    }
}
