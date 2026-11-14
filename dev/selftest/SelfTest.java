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
        Occlusion wallLoss = new Occlusion();
        wallLoss.throughSolid(stone, 1.0);
        List<Propagation.Arrival> arr = Propagation.list();
        arr.add(Propagation.straight(src, straight, wallLoss.energy()));
        for (PathFinder.Route r : routes) arr.add(Propagation.diffracted(r, straight));
        Propagation.Result res = Propagation.combine(lis, src, arr);
        double gainDb = -20 * Math.log10(res.gain);
        check("behind the wall: 12 to 24 dB quieter (audible)", gainDb > 12 && gainDb < 24, String.format("(%.1f dB)", gainDb));
        check("behind the wall: treble cut more than the rest", res.gainHF < 0.9, String.format("(gainHF %.2f)", res.gainHF));
        check("sound appears to come from above the wall, not through it", res.apparentPosition[1] > lis[1] + 1.0,
                String.format("(apparent y %.2f, listener y %.2f)", res.apparentPosition[1], lis[1]));
        Propagation.Result throughOnly = Propagation.combine(lis, src, List.of(Propagation.straight(src, straight, wallLoss.energy())));
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

        // ---------------- opening size
        Aperture.Opening doorOpening = Aperture.tightest(d, doorRoute);
        check("doorway is recognised as an opening", doorOpening != null, "");
        near("doorway area is 2 square blocks", doorOpening == null ? -1 : doorOpening.area, 2.0, 1e-9);
        double doorStraight = dist(friend, me);
        near("doorway even share matches the hand calculation", doorOpening.evenShare(),
                2.0 * doorRoute.length * doorRoute.length / (2 * Math.PI * Math.pow(doorOpening.before, 2) * Math.pow(doorOpening.after, 2)), 1e-9);
        check("doorway limits bass much more than treble", doorOpening.energyLimit(1) < 0.2 && doorOpening.energyLimit(6) == 1.0,
                String.format("(125 Hz %.1f dB, 4 kHz %.1f dB)", Bands.energyToDb(doorOpening.energyLimit(1)), Bands.energyToDb(doorOpening.energyLimit(6))));

        // Nearly in line through the same doorway, from further back: the path barely bends, so the
        // edge formula alone lets almost all the bass through. The doorway's size must cut it.
        double[] far = {-6.5, 1.62, 6.5}, offAxis = {7.5, 1.62, 5.0};
        List<PathFinder.Route> nearAxis = new PathFinder(d, 8000).findRoutes(far, offAxis, 1, 10);
        check("near-axis route through the doorway is found", !nearAxis.isEmpty() && nearAxis.get(0).bends >= 1, "");
        PathFinder.Route na = nearAxis.get(0);
        double naStraight = dist(far, offAxis);
        Propagation.Arrival plain = Propagation.diffracted(na, naStraight);
        Propagation.Arrival sized = Propagation.diffracted(na, naStraight, Aperture.tightest(d, na));
        check("the doorway cuts bass that the edge formula alone lets through", sized.energy[1] < plain.energy[1] * 0.5,
                String.format("(125 Hz: %.1f dB with opening size, %.1f dB without)", Bands.energyToDb(sized.energy[1]), Bands.energyToDb(plain.energy[1])));
        check("treble is not limited by a doorway this size", sized.energy[7] == plain.energy[7], "");
        Propagation.Result through = Propagation.combine(offAxis, far, List.of(sized));
        check("the result asks for a bass cut", through.gainLF < 0.9, String.format("(gainLF %.2f, gain %.2f, gainHF %.2f)", through.gainLF, through.gain, through.gainHF));

        // Same wall, three openings of different size: bigger passes more.
        double[] limits = new double[3];
        int[][] holes = {{1, 1}, {1, 2}, {3, 3}}; // width, height
        for (int i = 0; i < holes.length; i++) {
            GridWorld g = new GridWorld();
            g.fill(-20, -1, -20, 20, -1, 20);
            g.fill(-20, 6, -20, 20, 6, 20);
            g.fill(0, 0, -20, 0, 5, 20);
            g.clear(0, 0, 6, 0, holes[i][1] - 1, 6 + holes[i][0] - 1);
            List<PathFinder.Route> rs = new PathFinder(g, 8000).findRoutes(new double[]{-2.5, 0.5, 0.5}, new double[]{2.5, 0.5, 0.5}, 1, 12);
            Aperture.Opening op = rs.isEmpty() ? null : Aperture.tightest(g, rs.get(0));
            limits[i] = op == null ? -1 : op.energyLimit(2);
            near("hole " + holes[i][0] + "x" + holes[i][1] + " has the right area", op == null ? -1 : op.area, holes[i][0] * holes[i][1], 1e-9);
        }
        check("a bigger opening passes more sound at 250 Hz", limits[0] > 0 && limits[0] < limits[1] && limits[1] < limits[2],
                String.format("(1x1 %.1f dB, 1x2 %.1f dB, 3x3 %.1f dB)", Bands.energyToDb(limits[0]), Bands.energyToDb(limits[1]), Bands.energyToDb(limits[2])));

        check("over a free-standing wall there is no opening to limit the sound", Aperture.tightest(w, top) == null, "");

        // A long 1x2 tunnel guides sound, so it must not be treated as a hole in a wall.
        GridWorld tun = new GridWorld();
        tun.fill(-12, -1, -6, 12, 4, 12);            // solid mass
        tun.clear(-10, 0, 0, 0, 1, 0);               // tunnel along x at z = 0
        tun.clear(0, 0, 0, 0, 1, 8);                 // turns the corner, runs along z
        List<PathFinder.Route> tr = new PathFinder(tun, 8000).findRoutes(new double[]{-9.5, 0.5, 0.5}, new double[]{0.5, 0.5, 7.5}, 1, 6);
        check("tunnel: a route is found round the corner", !tr.isEmpty() && tr.get(0).bends >= 1, "");
        check("tunnel: not treated as a small opening", !tr.isEmpty() && Aperture.tightest(tun, tr.get(0)) == null, "");

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

        // ---------------- air absorption (checked against the table in ISO 9613-2)
        double[] iso20C70 = {0.1, 0.3, 1.1, 2.8, 5.0, 9.0, 22.9, 76.6}; // dB per km
        boolean matchesIso = true;
        StringBuilder isoDetail = new StringBuilder();
        for (int b = 0; b < Bands.COUNT; b++) {
            double perKm = AirAbsorption.dbPerMetre(Bands.HZ[b], 20, 70) * 1000;
            isoDetail.append(String.format("%.2f ", perKm));
            if (Math.abs(perKm - iso20C70[b]) > Math.max(0.06, iso20C70[b] * 0.03)) matchesIso = false;
        }
        check("air absorption formula matches ISO 9613-2 at 20 C, 70 %", matchesIso, "(" + isoDetail.toString().trim() + " dB/km)");
        double[] iso10C70 = {0.1, 0.4, 1.0, 1.9, 3.7, 9.7, 32.8, 117};
        boolean matchesCold = true;
        for (int b = 0; b < Bands.COUNT; b++) {
            double perKm = AirAbsorption.dbPerMetre(Bands.HZ[b], 10, 70) * 1000;
            if (Math.abs(perKm - iso10C70[b]) > Math.max(0.06, iso10C70[b] * 0.03)) matchesCold = false;
        }
        check("and at 10 C, 70 %", matchesCold, "");
        double[] air = new double[Bands.COUNT];
        java.util.Arrays.fill(air, 1.0);
        AirAbsorption.apply(air, 64, 1.0);
        near("64 blocks of air takes about 6.7 dB off 8 kHz", Bands.energyToDb(air[7]), 6.74, 0.1);
        check("and under 0.1 dB off 250 Hz", Bands.energyToDb(air[2]) < 0.1, String.format("(%.3f dB)", Bands.energyToDb(air[2])));
        double[] noAir = new double[Bands.COUNT];
        java.util.Arrays.fill(noAir, 1.0);
        AirAbsorption.apply(noAir, 64, 0);
        check("strength 0 turns air absorption off", noAir[7] == 1.0, "");
        Propagation.Result distant = Propagation.combine(new double[]{0, 0, 0}, new double[]{64, 0, 0},
                List.of(Propagation.straight(new double[]{64, 0, 0}, 64, air)));
        check("a distant sound in plain view keeps its volume and loses a little treble", distant.gain > 0.94 && distant.gainHF < 0.75 && distant.gainHF > 0.55,
                String.format("(gain %.3f, gainHF %.3f)", distant.gain, distant.gainHF));

        // ---------------- distinct echoes
        Echo.Reflection cliff = Echo.reflect(new double[]{0, 1, 0}, new double[]{2, 1, 0}, 0, 20.0);
        near("echo off a wall 20 away: path length", cliff.pathLength, 38.0, 1e-9);
        near("echo off a wall 20 away: extra path", cliff.extraPath, 36.0, 1e-9);
        near("echo bounce point is on the wall", cliff.point[0], 20.0, 1e-9);
        check("36 blocks extra is a distinct echo (105 ms)", cliff.distinct(), "");
        Echo.Reflection nearWall = Echo.reflect(new double[]{0, 1, 0}, new double[]{2, 1, 0}, 0, 6.0);
        check("a wall 6 away is too close to echo (8 blocks extra, 23 ms)", !nearWall.distinct(), String.format("(extra %.1f)", nearWall.extraPath));
        check("no echo off a plane between source and listener", Echo.reflect(new double[]{0, 1, 0}, new double[]{2, 1, 0}, 0, 1.0) == null, "");
        Echo.Reflection slanted = Echo.reflect(new double[]{0, 1, 0}, new double[]{0, 1, 12}, 0, -15.0);
        near("off-centre bounce lands midway along the wall", slanted.point[2], 6.0, 1e-9);
        near("and its path is the mirror-image distance", slanted.pathLength, Math.hypot(30, 12), 1e-9);
        double[] bounce = Echo.bounceEnergy(cliff, stone.absorption, 0);
        near("stone keeps 98 percent of the energy at 500 Hz", bounce[3], 0.98, 1e-9);
        check("wool returns far less than stone", Echo.bounceEnergy(cliff, wool.absorption, 0)[4] < bounce[4] * 0.2, "");
        near("an echo over 38 blocks of a sound 2 away is 19 times weaker", 1.0 / Echo.spreading(2, 38), 19.0, 1e-9);

        // ---------------- echoes off uneven surfaces
        near("head-on bounce has cosine 1", cliff.cosIncidence, 1.0, 1e-9);
        near("off-centre bounce cosine is depth over path", slanted.cosIncidence, 30.0 / Math.hypot(30, 12), 1e-9);
        int X = Echo.NO_SURFACE;
        Echo.Patch flat = Echo.patch(new int[25]);
        check("a flat full patch: coverage 1, roughness 0", flat.coverage == 1.0 && flat.roughness == 0 && flat.meanDepth == 0 && flat.nearest == 0, "");
        boolean flatReturnsAll = true;
        for (int b = 0; b < Bands.COUNT; b++) flatReturnsAll &= Echo.surfaceReturn(b, flat, 1.0) == 1.0;
        check("a flat full patch returns everything in every band", flatReturnsAll, "");
        Echo.Patch steps = Echo.patch(new int[]{0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, X});
        near("half the columns one block deeper: roughness 0.5", steps.roughness, 0.5, 1e-9);
        near("and coverage 24 of 25", steps.coverage, 0.96, 1e-9);
        near("and mean depth 0.5", steps.meanDepth, 0.5, 1e-9);
        // Hand calculation at 63 Hz: wavelength 5.444 m, g = 4*pi*0.5/5.444 = 1.154, exp(-g^2) = 0.264.
        near("roughness 0.5 keeps 26.4 percent mirror-like at 63 Hz", Echo.surfaceReturn(0, steps, 1.0) / steps.coverage, 0.264 + (1 - 0.264) * 0.5, 0.001);
        near("and only the scattered share at 2 kHz", Echo.surfaceReturn(5, steps, 1.0) / steps.coverage, Echo.SCATTERED_SHARE, 1e-6);
        boolean bassSurvives = true;
        for (int b = 1; b < Bands.COUNT; b++) bassSurvives &= Echo.surfaceReturn(b, steps, 1.0) <= Echo.surfaceReturn(b - 1, steps, 1.0) + 1e-12;
        check("an uneven surface returns bass at least as well as treble", bassSurvives, "");
        check("a glancing bounce is scattered less than a head-on one", Echo.surfaceReturn(1, steps, 0.3) > Echo.surfaceReturn(1, steps, 1.0), "");
        Echo.Patch sparse = Echo.patch(new int[]{0, X, X, X, X, 0, X, X, X, X, 0, X, X, X, X, 0, X, X, X, X, 0, X, X, X, X});
        check("a patch with 5 of 25 columns is too sparse to echo", sparse.coverage < Echo.MIN_ROUGH_COVERAGE, "(" + sparse.coverage + ")");
        Echo.Patch proud = Echo.patch(new int[]{-2, 0, 0, 1, 2});
        check("nearest is the part that sticks out furthest", proud.nearest == -2, "(" + proud.nearest + ")");
        check("an empty patch has no coverage", Echo.patch(new int[]{X, X, X}).coverage == 0, "");
        AcousticMaterial leaves = db.material("leaves");
        double[] stoneLost = Echo.notReflected(stone.absorption, stone.realLoss1m);
        near("stone: what is not reflected is just its absorption", stoneLost[3], stone.absorption[3], 1e-6);
        double[] leafLost = Echo.notReflected(leaves.absorption, leaves.realLoss1m);
        check("leaves let nearly everything through, so they reflect under 3 percent", 1 - leafLost[3] < 0.03,
                String.format("(reflects %.3f, absorption alone would say %.2f)", 1 - leafLost[3], 1 - leaves.absorption[3]));

        // ---------------- combining arrivals
        Propagation.Result open = Propagation.unobstructed(src);
        check("unobstructed sound is untouched", open.gain == 1.0 && open.gainLF == 1.0 && open.gainHF == 1.0, "");
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
