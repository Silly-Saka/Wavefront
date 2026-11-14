import wavefront.acoustics.*;

import java.io.FileReader;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks the acoustics maths without Minecraft. Run with dev/selftest/run.sh.
 * Every check prints PASS or FAIL; the program exits with an error if any fail.
 */
public class SelfTest {
    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name + "  " + detail);
        if (!ok) failures++;
    }

    static void near(String name, double actual, double expected, double tolerance) {
        check(name, Math.abs(actual - expected) <= tolerance,
                String.format("(got %.3f, expected %.3f +/- %.3f)", actual, expected, tolerance));
    }

    /** A tiny block world: a set of solid full cubes, everything else is air. */
    static final class GridWorld implements PathFinder.World {
        final Set<Long> solid = new HashSet<>();
        int segmentChecks = 0, cellChecks = 0;

        void fill(int x0, int y0, int z0, int x1, int y1, int z1) {
            for (int x = x0; x <= x1; x++)
                for (int y = y0; y <= y1; y++)
                    for (int z = z0; z <= z1; z++) solid.add(key(x, y, z));
        }

        void clear(int x0, int y0, int z0, int x1, int y1, int z1) {
            for (int x = x0; x <= x1; x++)
                for (int y = y0; y <= y1; y++)
                    for (int z = z0; z <= z1; z++) solid.remove(key(x, y, z));
        }

        static long key(int x, int y, int z) {
            return ((long) (x + 512) << 40) | ((long) (y + 512) << 20) | (z + 512);
        }

        boolean isSolid(int x, int y, int z) {
            return solid.contains(key(x, y, z));
        }

        @Override public boolean isOpen(int x, int y, int z) { cellChecks++; return !isSolid(x, y, z); }
        @Override public boolean isEmpty(int x, int y, int z) { return !isSolid(x, y, z); }

        @Override
        public boolean segmentClear(double ax, double ay, double az, double bx, double by, double bz) {
            segmentChecks++;
            // Fine-step march; plenty accurate for a test world of whole cubes.
            double dx = bx - ax, dy = by - ay, dz = bz - az;
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            int steps = Math.max(1, (int) Math.ceil(len / 0.02));
            for (int i = 0; i <= steps; i++) {
                double t = (double) i / steps;
                if (isSolid((int) Math.floor(ax + dx * t), (int) Math.floor(ay + dy * t), (int) Math.floor(az + dz * t)))
                    return false;
            }
            return true;
        }
    }

    public static void main(String[] args) throws Exception {
        AcousticDatabase db;
        try (FileReader r = new FileReader(args[0])) {
            db = AcousticDatabase.load(r);
        }

        // ---------------- database
        check("database loads 41 materials", db.materialNames().size() == 41, "(" + db.materialNames().size() + ")");
        check("database maps 1158 blocks", db.blockCount() == 1158, "(" + db.blockCount() + ")");
        AcousticMaterial stone = db.material("dense_stone"), wool = db.material("wool"),
                planks = db.material("wood_planks"), glass = db.material("glass"), iron = db.material("iron");
        near("stone, 1 block, 125 Hz", stone.gameLoss1m(1), 33.9, 0.1);
        near("stone, 1 block, 2 kHz", stone.gameLoss1m(5), 38.5, 0.1);
        near("glass block behaves like a pane, 500 Hz", glass.gameLoss1m(3), 22.4, 0.1);
        boolean woolBeatsStone = true;
        for (int b = 0; b < Bands.COUNT; b++) woolBeatsStone &= wool.gameLoss1m(b) >= stone.gameLoss1m(b) + 5.99;
        check("wool blocks at least 6 dB more than stone in every band", woolBeatsStone, "");
        check("stone maps by ID", db.materialForBlock("minecraft:stone") == stone, "");
        check("oak door maps to planks", db.materialForBlock("minecraft:oak_door") == planks, "");
        check("unknown modded block has no ID entry", db.materialForBlock("somemod:weird_block") == null, "");
        check("tag fallback lists wool", "wool".equals(db.tagFallbacks().get("minecraft:wool")), "");
        near("banner is ignored", db.fillForBlock("minecraft:white_banner"), 0.0, 1e-9);
        near("iron bars are 25% solid", db.fillForBlock("minecraft:iron_bars"), 0.25, 1e-9);
        near("mangrove roots are partly open", db.fillForBlock("minecraft:mangrove_roots"), 0.4, 1e-9);
        near("stone has no fill entry", db.fillForBlock("minecraft:stone"), -1, 1e-9);

        // ---------------- thickness rule
        near("two blocks of stone = twice one block", stone.gameLoss(3, 2.0), 2 * stone.gameLoss1m(3), 1e-6);
        near("5 cm of planks = thin-panel figure", planks.gameLoss(3, 0.05), planks.gameLossThin(3), 1e-6);
        boolean monotone = true;
        for (String name : db.materialNames()) {
            AcousticMaterial m = db.material(name);
            for (int b = 0; b < Bands.COUNT; b++) {
                double prev = 0;
                for (double d = 0.01; d <= 3.0; d += 0.01) {
                    double v = m.gameLoss(b, d);
                    if (v < prev - 1e-9) monotone = false;
                    prev = v;
                }
            }
        }
        check("thicker never blocks less, for every material and band", monotone, "");

        // ---------------- wall strength slider
        db.setWallStrength(0.5);
        near("wall strength 0.5 halves stone", stone.gameLoss1m(1), 33.9 / 2, 0.1);
        db.setWallStrength(0);
        near("wall strength 0 removes occlusion", wool.gameLoss1m(4), 0, 1e-9);
        db.setWallStrength(1.0);
        near("wall strength back to 1", stone.gameLoss1m(1), 33.9, 0.1);

        // ---------------- occlusion accumulation
        Occlusion o = new Occlusion();
        check("fresh line is unblocked", !o.blocked(), "");
        o.throughSolid(stone, 1.0);
        near("one stone block, 500 Hz", o.lossDb()[3], stone.gameLoss1m(3), 1e-6);
        o.throughSolid(stone, 1.0);
        near("second stone block adds the same again", o.lossDb()[3], 2 * stone.gameLoss1m(3), 1e-6);
        Occlusion bars = new Occlusion();
        bars.throughPartlyOpen(iron, 0.25);
        near("iron bars cost at most 1.25 dB", bars.lossDb()[5], 1.249, 0.01);
        Occlusion fence = new Occlusion();
        fence.throughPartlyOpen(planks, 0.3);
        check("fence costs under 1.6 dB in every band", max(fence.lossDb()) < 1.6, String.format("(max %.2f)", max(fence.lossDb())));
        Occlusion banner = new Occlusion();
        banner.throughPartlyOpen(wool, 0.0);
        check("zero-fill block does not block", !banner.blocked() && max(banner.lossDb()) < 1e-9, "");

        // ---------------- real shapes: length of a line inside boxes
        double[][] cube = {{0, 0, 0, 1, 1, 1}};
        near("straight through a cube", Occlusion.lengthInsideBoxes(-1, .5, .5, 2, .5, .5, cube), 1.0, 1e-9);
        near("diagonal through a cube", Occlusion.lengthInsideBoxes(-1, -1, -1, 2, 2, 2, cube), Math.sqrt(3), 1e-9);
        near("line that misses", Occlusion.lengthInsideBoxes(-1, 1.5, .5, 2, 1.5, .5, cube), 0, 1e-9);
        double[][] slab = {{0, 0, 0, 1, .5, 1}};
        near("down through a bottom slab", Occlusion.lengthInsideBoxes(.5, 2, .5, .5, -1, .5, slab), 0.5, 1e-9);
        near("level line above a bottom slab", Occlusion.lengthInsideBoxes(-1, .75, .5, 2, .75, .5, slab), 0, 1e-9);
        double[][] stairs = {{0, 0, 0, 1, .5, 1}, {0, .5, 0, .5, 1, 1}};
        near("through the tall half of stairs", Occlusion.lengthInsideBoxes(-1, .75, .5, 2, .75, .5, stairs), 0.5, 1e-9);
        double[][] overlap = {{0, 0, 0, 1, 1, 1}, {.25, 0, 0, .75, 1, 1}};
        near("overlapping boxes are not counted twice", Occlusion.lengthInsideBoxes(-1, .5, .5, 2, .5, .5, overlap), 1.0, 1e-9);
        double[][] door = {{0, 0, 0, 1, 1, 0.1875}};
        near("through a door panel", Occlusion.lengthInsideBoxes(.5, .5, -1, .5, .5, 2, door), 0.1875, 1e-9);
        near("segment ending inside a cube", Occlusion.lengthInsideBoxes(-1, .5, .5, .5, .5, .5, cube), 0.5, 1e-9);

        // ---------------- walking the cells along a line
        StringBuilder cells = new StringBuilder();
        VoxelLine.walk(0.5, 0.5, 0.5, 3.5, 0.5, 0.5, 64, (x, y, z) -> { cells.append(x).append(' '); return true; });
        check("line along x visits cells 0 1 2 3", cells.toString().equals("0 1 2 3 "), "(" + cells + ")");
        int[] count = {0};
        VoxelLine.walk(0.5, 0.5, 0.5, 3.5, 2.5, 0.5, 64, (x, y, z) -> { count[0]++; return true; });
        check("diagonal line visits 6 cells (3 across + 2 up + start)", count[0] == 6, "(" + count[0] + ")");
        int[] last = new int[3];
        VoxelLine.walk(-0.2, 5.9, -3.3, -7.6, -2.1, 4.4, 64, (x, y, z) -> { last[0] = x; last[1] = y; last[2] = z; return true; });
        check("line through negative coordinates ends in the right cell", last[0] == -8 && last[1] == -3 && last[2] == 4,
                "(" + last[0] + " " + last[1] + " " + last[2] + ")");
        int[] same = {0};
        VoxelLine.walk(0.2, 0.2, 0.2, 0.8, 0.8, 0.8, 64, (x, y, z) -> { same[0]++; return true; });
        check("line inside one cell visits just that cell", same[0] == 1, "(" + same[0] + ")");
        // Thickness seen by a level line through a stone block, a door and a slab, added up cell by cell.
        Occlusion lineOcc = new Occlusion();
        lineOcc.throughSolid(stone, Occlusion.lengthInsideBoxes(-1, .5, .5, 2, .5, .5, cube));
        lineOcc.throughSolid(planks, Occlusion.lengthInsideBoxes(.5, .5, -1, .5, .5, 2, door));
        near("stone block + door panel, 1 kHz", lineOcc.lossDb()[4], stone.gameLoss1m(4) + planks.gameLoss(4, 0.1875), 1e-6);

        // ---------------- diffraction formula (hand-calculated values)
        near("0.5 m detour at 250 Hz", Diffraction.lossDb(2, 0.5, 1, 0), 10 * Math.log10(3 + 20 * (2 * 0.5 / 1.372)), 1e-6);
        near("0.5 m detour at 250 Hz is about 12.5 dB", Diffraction.lossDb(2, 0.5, 1, 0), 12.45, 0.05);
        near("grazing the edge costs 4.8 dB", Diffraction.lossDb(4, 0, 1, 0), 4.77, 0.01);
        near("one edge is capped at 30 dB", Diffraction.lossDb(7, 5, 1, 0), 30, 1e-9);
        near("two edges are capped at 35 dB", Diffraction.lossDb(7, 50, 2, 3), 35, 1e-9);
        boolean highsLoseMore = true;
        double[] dl = Diffraction.lossDb(0.3, 1, 0);
        for (int b = 1; b < Bands.COUNT; b++) highsLoseMore &= dl[b] >= dl[b - 1];
        check("high notes lose at least as much as low notes", highsLoseMore, "");
        check("no bends means no loss", Diffraction.lossDb(3, 1.0, 0, 0) == 0, "");

        // ---------------- path finding: free-standing wall (the "piston behind a wall" scene)
        // Wall: x = 0, 9 wide (z -4..4), 4 tall (y 0..3). Ground: y = -1. Source and listener 3 blocks either side.
        GridWorld w = new GridWorld();
        w.fill(-20, -1, -20, 20, -1, 20);
        w.fill(0, 0, -4, 0, 3, 4);
        double[] src = {-3.5, 0.5, 0.5}, lis = {4.5, 1.62, 0.5};
        check("wall blocks the straight line", !w.segmentClear(src[0], src[1], src[2], lis[0], lis[1], lis[2]), "");
        PathFinder pf = new PathFinder(w, 6000);
        long t0 = System.nanoTime();
        List<PathFinder.Route> routes = pf.findRoutes(src, lis, 3, 8);
        double ms = (System.nanoTime() - t0) / 1e6;
        check("finds routes round the wall", routes.size() >= 2, "(" + routes.size() + " routes, " + String.format("%.1f", ms) + " ms, "
                + w.segmentChecks + " exact line checks, " + w.cellChecks + " cell lookups)");
        PathFinder.Route top = routes.get(0);
        // Shortest way is over the top: source -> top edge of the wall at y = 4 -> listener (two edges of a 1-thick wall).
        double overTop = Math.hypot(3.5, 3.5) + 1.0 + Math.hypot(3.5, 4 - 1.62);
        near("shortest route goes over the top", top.length, overTop, 0.1);
        check("over-the-top route has 1 or 2 bends", top.bends == 1 || top.bends == 2, "(" + top.bends + ")");
        near("its bend sits at the top of the wall", top.lastBend()[1], 4.0, 0.3);
        // The wall spans z = -4 to 5, so each side edge is 4.5 blocks from the z = 0.5 line.
        double aroundSide = Math.hypot(3.5, 4.5) + 1.0 + Math.hypot(3.5, 4.5);
        boolean sideFound = false;
        for (PathFinder.Route r : routes) if (Math.abs(r.length - aroundSide) < 0.6 && Math.abs(Math.abs(r.lastBend()[2] - 0.5) - 4.5) < 0.8) sideFound = true;
        check("another route goes round the side", sideFound, describe(routes));

        // What the listener gets: quieter, duller, but clearly audible - not the near-silence the old method gave.
        double straight = dist(src, lis);
        Occlusion through = new Occlusion();
        through.throughSolid(stone, 1.0);
        List<Propagation.Arrival> arr = Propagation.list();
        arr.add(Propagation.straight(src, straight, through.energy()));
        for (PathFinder.Route r : routes) arr.add(Propagation.diffracted(r, straight));
        Propagation.Result res = Propagation.combine(lis, src, arr);
        double gainDb = -20 * Math.log10(res.gain);
        check("behind the wall: 12 to 24 dB quieter (audible)", gainDb > 12 && gainDb < 24, String.format("(%.1f dB)", gainDb));
        check("behind the wall: treble cut more than the rest", res.gainHF < 0.9, String.format("(gainHF %.2f)", res.gainHF));
        check("sound appears to come from above the wall, not through it", res.apparentPosition[1] > lis[1] + 1.0,
                String.format("(apparent y %.2f, listener y %.2f)", res.apparentPosition[1], lis[1]));
        Propagation.Result throughOnly = Propagation.combine(lis, src, List.of(Propagation.straight(src, straight, through.energy())));
        check("without diffraction the same scene would be 30+ dB down", -20 * Math.log10(throughOnly.gain) > 30,
                String.format("(%.1f dB)", -20 * Math.log10(throughOnly.gain)));

        // ---------------- path finding: doorway (friend behind a wall with an opening)
        GridWorld d = new GridWorld();
        d.fill(-20, -1, -20, 20, -1, 20);          // floor
        d.fill(-20, 3, -20, 20, 3, 20);            // ceiling
        d.fill(0, 0, -20, 0, 2, 20);               // wall across the whole room
        d.clear(0, 0, 6, 0, 1, 6);                 // 1 wide, 2 tall doorway at z = 6
        double[] friend = {-2.5, 1.62, 0.5}, me = {2.5, 1.62, 0.5};
        routes = new PathFinder(d, 6000).findRoutes(friend, me, 3, 10);
        check("finds the doorway", !routes.isEmpty(), "(" + routes.size() + " routes)");
        PathFinder.Route doorRoute = routes.get(0);
        // Friend is 2.5 from the wall face at x = 0; I am 1.5 from the face at x = 1. Door jamb at z = 6.
        double viaDoor = Math.hypot(2.5, 5.5) + 1.0 + Math.hypot(1.5, 5.5);
        near("route length through the doorway", doorRoute.length, viaDoor, 0.15);
        near("sound appears to come from the doorway (z)", doorRoute.lastBend()[2], 6.0, 0.6);
        check("only one way through", routes.size() == 1, "(" + routes.size() + ")");

        // ---------------- path finding: sealed room
        GridWorld s = new GridWorld();
        s.fill(-3, -1, -3, 3, 3, 3);
        s.clear(-2, 0, -2, 2, 2, 2);
        t0 = System.nanoTime();
        routes = new PathFinder(s, 6000).findRoutes(new double[]{0.5, 0.5, 0.5}, new double[]{10.5, 0.5, 0.5}, 3, 8);
        ms = (System.nanoTime() - t0) / 1e6;
        check("sealed room has no air route", routes.isEmpty(), String.format("(%.1f ms)", ms));

        // ---------------- path finding: balcony (speaker above, listener underneath)
        GridWorld bal = new GridWorld();
        bal.fill(-20, -1, -20, 20, -1, 20);        // ground
        bal.fill(-20, 4, -3, 0, 4, 3);             // balcony floor, edge at x = 1
        double[] speaker = {-2.5, 5.5, 0.5}, under = {-2.5, 1.62, 0.5};
        routes = new PathFinder(bal, 6000).findRoutes(speaker, under, 3, 8);
        check("balcony: finds a way down round the edge", !routes.isEmpty(), "(" + routes.size() + " routes)");
        double roundEdge = Math.hypot(3.5, 0.5) + 1.0 + Math.hypot(3.5, 4 - 1.62);
        near("balcony: route length", routes.get(0).length, roundEdge, 0.1);
        Propagation.Arrival ba = Propagation.diffracted(routes.get(0), dist(speaker, under));
        check("balcony: bass gets through better than treble", ba.energy[1] > ba.energy[6] * 1.5,
                String.format("(125 Hz %.1f dB, 4 kHz %.1f dB)", Bands.energyToDb(ba.energy[1]), Bands.energyToDb(ba.energy[6])));

        // ---------------- path finding: source inside a solid block (block sounds come from block centres)
        GridWorld p = new GridWorld();
        p.fill(-20, -1, -20, 20, -1, 20);
        p.fill(-3, 0, 0, -3, 0, 0);                // the "piston" block itself
        p.fill(0, 0, -2, 0, 2, 2);                 // small wall
        routes = new PathFinder(p, 6000).findRoutes(new double[]{-2.5, 0.5, 0.5}, new double[]{3.5, 1.62, 0.5}, 3, 8);
        check("source inside its own block still finds routes", !routes.isEmpty(), "(" + routes.size() + " routes)");

        // ---------------- combining arrivals
        Propagation.Result open = Propagation.unobstructed(src);
        check("unobstructed sound is untouched", open.gain == 1.0 && open.gainHF == 1.0, "");
        Propagation.Result silent = Propagation.combine(lis, src, Propagation.list());
        check("no arrivals means silence", silent.gain == 0, "");

        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures > 0) System.exit(1);
    }

    static double max(double[] a) {
        double m = a[0];
        for (double v : a) m = Math.max(m, v);
        return m;
    }

    static double dist(double[] a, double[] b) {
        return Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]));
    }

    static String describe(List<PathFinder.Route> routes) {
        StringBuilder sb = new StringBuilder("(");
        for (PathFinder.Route r : routes) {
            sb.append(String.format("len %.2f bends %d last [%.1f %.1f %.1f]; ", r.length, r.bends, r.lastBend()[0], r.lastBend()[1], r.lastBend()[2]));
        }
        return sb.append(")").toString();
    }
}
