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

        // ---------------- path finding: time limit
        PathFinder late = new PathFinder(w, 6000).deadline(System.nanoTime() - 1);
        List<PathFinder.Route> lateRoutes = late.findRoutes(src, lis, 3, 8);
        check("a search whose time is already up finds nothing and says so", lateRoutes.isEmpty() && late.timedOut(), "");
        PathFinder unhurried = new PathFinder(w, 6000).deadline(System.nanoTime() + 5_000_000_000L);
        check("a search with time to spare is not marked as cut short", !unhurried.findRoutes(src, lis, 3, 8).isEmpty() && !unhurried.timedOut(), "");

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

        // ---------------- water and lava
        Medium water = db.water(), lava = db.lava();
        near("water: speed of sound from the database", water.speed, 1447.29, 0.01);
        near("air to water costs 29.4 dB", Medium.crossingLossDb(Medium.AIR, water), 29.43, 0.02);
        near("and the same from water to air", Medium.crossingLossDb(water, Medium.AIR), Medium.crossingLossDb(Medium.AIR, water), 1e-9);
        near("air to lava costs 35.9 dB", Medium.crossingLossDb(Medium.AIR, lava), 35.9, 0.1);
        near("water to lava costs about 2.2 dB", Medium.crossingLossDb(water, lava), 2.2, 0.1);
        near("a medium loses nothing meeting itself", Medium.crossingLossDb(water, water), 0, 1e-9);
        MediaPath dive = new MediaPath();
        dive.step(Medium.AIR, 10);
        dive.step(water, 14.4729);
        near("10 blocks of air then 14.47 of water: one crossing", dive.crossings(), 1, 0);
        near("its loss is one surface", dive.crossingLossDb(), 29.43, 0.02);
        near("its travel time is 10/343 + 0.01 s", dive.seconds(), 10 / 343.0 + 0.01, 1e-6);
        near("only the 10 blocks of air count for air absorption", dive.airLength(), 10, 1e-9);
        MediaPath pond = new MediaPath();
        pond.step(Medium.AIR, 5);
        pond.step(water, 3);
        pond.step(Medium.AIR, 5);
        near("in and out of a pond is two crossings, 58.9 dB", pond.crossingLossDb(), 58.85, 0.05);
        MediaPath swim = new MediaPath();
        for (int i = 0; i < 40; i++) swim.step(water, 0.25);
        check("all under water: no crossing, no loss", swim.crossings() == 0 && swim.crossingLossDb() == 0, "");
        near("10 blocks under water take 6.9 ms, against 29.2 ms in air", swim.seconds() * 1000, 6.91, 0.01);
        // A note block under water: the path starts inside the block itself, then water all the way.
        MediaPath sunk = new MediaPath();
        sunk.step(null, 0.5);
        sunk.step(water, 4.5);
        check("a source inside a solid block under water starts in water: no crossing", sunk.crossings() == 0 && sunk.crossingLossDb() == 0, "");
        near("and the whole 5 blocks are timed as water", sunk.seconds(), 5 / water.speed, 1e-9);
        // Two pools with a stone wall between them: the wall is not air.
        MediaPath walled = new MediaPath();
        walled.step(water, 3);
        walled.step(null, 1);
        walled.step(water, 3);
        check("a solid wall between two pools is not counted as a surface", walled.crossings() == 0, "");
        MediaPath shore = new MediaPath();
        shore.step(null, 0.5);
        shore.step(Medium.AIR, 4);
        shore.step(water, 2);
        near("a block on the shore heard from under water: one crossing", shore.crossings(), 1, 0);
        near("with 4.5 blocks counted as air", shore.airLength(), 4.5, 1e-9);
        MediaPath buried = new MediaPath();
        buried.step(null, 3);
        near("a path wholly inside solid blocks is timed as air", buried.seconds(), 3 / 343.0, 1e-9);

        Propagation.Arrival wet = new Propagation.Arrival(new double[]{1, 1, 1, 1, 1, 1, 1, 1}, 10, new double[]{10, 0, 0});
        near("an arrival starts with its travel time through air", wet.seconds, 10 / 343.0, 1e-9);
        wet.seconds = swim.seconds();
        Propagation.Result heard = Propagation.combine(new double[]{0, 0, 0}, new double[]{10, 0, 0}, List.of(wet));
        near("the combined result carries the travel time", heard.seconds, swim.seconds(), 1e-9);

        // ---------------- telling left from right under water
        near("in air the whole left-right cue survives", EarCues.sidewaysKept(343, 1.0), 1.0, 1e-9);
        double keptInWater = EarCues.sidewaysKept(water.speed, 1.0);
        near("in water 23.7 percent of it survives", keptInWater, 343 / 1447.29, 1e-6);
        near("at strength 0 nothing changes", EarCues.sidewaysKept(water.speed, 0), 1.0, 1e-9);
        double[] ear = {10, 64, 10}, facing = {0, 0, 1}, rightSide = {-1, 0, 0}; // facing south, right hand to the west
        double[] side = EarCues.compress(ear, rightSide, facing, 2, 64, 10, keptInWater); // 8 blocks to the right
        double sideAngle = Math.toDegrees(Math.asin((side[0] - ear[0]) * rightSide[0] / 8));
        near("a sound directly to the right is heard 13.7 degrees right of centre", sideAngle, 13.7, 0.1);
        near("and stays 8 blocks away", dist(side, ear), 8.0, 1e-9);
        check("and is leant towards the front", side[2] > ear[2], "");
        double[] ahead = EarCues.compress(ear, rightSide, facing, 10, 64, 20, keptInWater);
        check("a sound straight ahead does not move", Math.abs(ahead[0] - 10) < 1e-9 && Math.abs(ahead[2] - 20) < 1e-9, "");
        double[] behindLeft = EarCues.compress(ear, rightSide, facing, 15, 64, 5, keptInWater);
        check("a sound behind and to the left stays behind and to the left", behindLeft[2] < ear[2] && behindLeft[0] > ear[0], "");
        near("but only a quarter as far to the left", (behindLeft[0] - ear[0]) / 5.0, keptInWater, 1e-6);
        double[] above = EarCues.compress(ear, rightSide, facing, 10, 70, 10, keptInWater);
        check("a sound straight overhead does not move", Math.abs(above[1] - 70) < 1e-9 && Math.abs(above[0] - 10) < 1e-9, "");
        double[] unmoved = EarCues.compress(ear, rightSide, facing, 2, 64, 10, 1.0);
        check("with the full cue kept nothing moves", Math.abs(unmoved[0] - 2) < 1e-9 && Math.abs(unmoved[2] - 10) < 1e-9, "");

        // ---------------- combining arrivals
        Propagation.Result open = Propagation.unobstructed(src);
        check("unobstructed sound is untouched", open.gain == 1.0 && open.gainLF == 1.0 && open.gainHF == 1.0, "");
        Propagation.Result silent = Propagation.combine(lis, src, Propagation.list());
        check("no arrivals means silence", silent.gain == 0, "");

        waveChecks();

        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures > 0) System.exit(1);
    }

    // ---------------- the wave simulation of bass

    static final float STONE = WaveGrid.admittanceFor(0.02);

    static double db(double energy) {
        return 10 * Math.log10(Math.max(energy, 1e-12));
    }

    /** A 32 x 20 x 32 block space with a stone floor 6 thick, and optionally a wall across it at x = 20. */
    static WaveGrid waveRoom(int n, int wallHeight, boolean doorway, WaveShape inDoorway) {
        WaveGrid g = new WaveGrid(0, 0, 0, 32, 20, 32, n);
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                for (int y = 0; y < 6; y++) g.setFull(x, y, z, STONE);
                if (x != 20) continue;
                for (int y = 6; y < 6 + wallHeight; y++) {
                    boolean opening = doorway && z == 16 && y < 8;
                    if (!opening) g.setFull(x, y, z, STONE);
                    else if (inDoorway != null) g.setShape(x, y, z, inDoorway, STONE);
                }
            }
        }
        g.build();
        return g;
    }

    static void waveChecks() {
        // ---- how blocks that are not full cubes sit in the grid
        double[][] door = {{0, 0, 0, 0.1875, 1, 1}}, pane = {{0.4375, 0, 0, 0.5625, 1, 1}}, slab = {{0, 0, 0, 1, 0.5, 1}};
        double[][] carpet = {{0, 0, 0, 1, 0.0625, 1}}, post = {{0.375, 0, 0.375, 0.625, 1, 0.625}}, stairs = {{0, 0, 0, 1, 0.5, 1}, {0.5, 0.5, 0, 1, 1, 1}};
        WaveGrid g = new WaveGrid(0, 0, 0, 3, 3, 3, 1);
        g.setShape(1, 1, 1, WaveShape.of(door, 1), STONE);
        g.build();
        check("a door fills no cell at 1 cell per block", g.isOpen(1.5, 1.5, 1.5), "");
        check("a door closes the way through it and leaves the other sides open", g.mask[(2 * 5 + 2) * 5 + 2] == (63 & ~1) && (g.mask[(2 * 5 + 2) * 5 + 1] & 2) == 0,
                "(mask " + g.mask[(2 * 5 + 2) * 5 + 2] + ")");
        g = new WaveGrid(0, 0, 0, 3, 3, 3, 2);
        g.setShape(1, 1, 1, WaveShape.of(pane, 2), STONE);
        g.build();
        check("a glass pane in the middle of a block closes the way across it", g.isOpen(1.25, 1.5, 1.5) && g.isOpen(1.75, 1.5, 1.5)
                && (g.mask[(3 * 8 + 3) * 8 + 3] & 2) == 0 && (g.mask[(3 * 8 + 3) * 8 + 4] & 1) == 0, "");
        check("a fence post leaves everything open", WaveShape.of(post, 1).open && WaveShape.of(post, 2).open, "");
        g = new WaveGrid(0, 0, 0, 3, 3, 3, 2);
        g.setShape(1, 1, 1, WaveShape.of(slab, 2), STONE);
        g.setShape(0, 1, 1, WaveShape.of(stairs, 2), STONE);
        g.setShape(2, 1, 1, WaveShape.of(carpet, 2), STONE);
        g.build();
        check("a slab fills its lower half and leaves the upper half open", !g.isOpen(1.25, 1.25, 1.25) && g.isOpen(1.25, 1.75, 1.25), "");
        check("stairs fill the step and leave the open quarter", !g.isOpen(0.75, 1.75, 1.25) && g.isOpen(0.25, 1.75, 1.25), "");
        check("a carpet takes up no room", g.isOpen(2.25, 1.25, 1.25) && (g.mask[(3 * 8 + 3) * 8 + 5] & 8) != 0, "");

        // ---- open air: the same at every distance and in every direction
        double worst = 0;
        for (int n = 1; n <= 2; n++) {
            WaveGrid open = new WaveGrid(0, 0, 0, 32, 20, 32, n);
            open.build();
            BassField field = open.solveAll(16.5, 10.5, 16.5, n, 1, WaveGrid.SERIAL);
            for (double[] p : new double[][]{{20.5, 10.5, 16.5}, {28.5, 10.5, 16.5}, {16.5, 3.5, 16.5}, {24.5, 16.5, 24.5}, {26.5, 10.5, 26.5}, {21.3, 13.1, 12.2}}) {
                for (double v : field.sample(p[0], p[1], p[2])) worst = Math.max(worst, Math.abs(db(v)));
            }
            if (n == 1) check("one cell per block gives one band", field.bands() == 1, "");
        }
        check("open air comes out as open air, within 2 dB at any distance and direction", worst < 2.0, String.format("(worst %.2f dB)", worst));

        // ---- hard ground: the bounce off the ground adds to the direct sound
        BassField ground = waveRoom(2, 0, false, null).solveAll(8.5, 7.62, 16.5, 2, 1, WaveGrid.SERIAL);
        double[] onGround = ground.sample(16.5, 6.5, 16.5);
        check("over hard ground bass is 3.5 to 6.5 dB above open air", db(onGround[0]) > 3.5 && db(onGround[0]) < 6.5 && db(onGround[1]) > 3.5 && db(onGround[1]) < 6.5,
                String.format("(63 Hz %.1f dB, 125 Hz %.1f dB)", db(onGround[0]), db(onGround[1])));
        double[] coarseGround = waveRoom(1, 0, false, null).solveAll(8.5, 7.62, 16.5, 1, 1, WaveGrid.SERIAL).sample(16.5, 6.5, 16.5);
        near("the open-ground reference matches the coarse simulation at 63 Hz", db(coarseGround[0]), db(BassMix.OPEN_GROUND[0]), 1.0);
        near("the open-ground reference matches the simulation at 63 Hz", db(onGround[0]), db(BassMix.OPEN_GROUND[0]), 1.0);
        near("the open-ground reference matches the simulation at 125 Hz", db(onGround[1]), db(BassMix.OPEN_GROUND[1]), 1.0);

        // ---- a wall to the ceiling: sealed, then with a doorway, then with the doorway shut
        double[] src = {26.5, 6.5, 16.5};
        double[] lis = {14.5, 7.62, 16.5};
        check("behind a sealed wall nothing is known, so the wall figures stand",
                waveRoom(2, 14, false, null).solveAll(lis[0], lis[1], lis[2], 2, 1, WaveGrid.SERIAL).sample(src[0], src[1], src[2]) == null, "");
        double[] through = waveRoom(2, 14, true, null).solveAll(lis[0], lis[1], lis[2], 2, 1, WaveGrid.SERIAL).sample(src[0], src[1], src[2]);
        double[] openRoom = waveRoom(2, 0, false, null).solveAll(lis[0], lis[1], lis[2], 2, 1, WaveGrid.SERIAL).sample(src[0], src[1], src[2]);
        check("a doorway lets bass through, less than with no wall", through != null && through[0] < openRoom[0] && through[1] < openRoom[1] && db(through[0]) > -20,
                String.format("(63 Hz %.1f dB, 125 Hz %.1f dB; no wall %.1f, %.1f)", db(through[0]), db(through[1]), db(openRoom[0]), db(openRoom[1])));
        check("the lower the note, the more of it gets through the doorway", through[0] > through[1], "");
        check("glass panes in the doorway seal it", waveRoom(2, 14, true, WaveShape.of(pane, 2)).solveAll(lis[0], lis[1], lis[2], 2, 1, WaveGrid.SERIAL).sample(src[0], src[1], src[2]) == null, "");
        check("a door in the doorway seals it", waveRoom(1, 14, true, WaveShape.of(door, 1)).solveAll(lis[0], lis[1], lis[2], 1, 1, WaveGrid.SERIAL).sample(src[0], src[1], src[2]) == null, "");
        check("a fence in the doorway changes nothing", Math.abs(db(waveRoom(2, 14, true, WaveShape.of(post, 2)).solveAll(lis[0], lis[1], lis[2], 2, 1, WaveGrid.SERIAL).sample(src[0], src[1], src[2])[0]) - db(through[0])) < 0.01, "");
        double[] back = waveRoom(2, 14, true, null).solveAll(src[0], src[1], src[2], 2, 1, WaveGrid.SERIAL).sample(lis[0], lis[1], lis[2]);
        check("from A to B is the same as from B to A, within 1 dB", Math.abs(db(back[0]) - db(through[0])) < 1.0 && Math.abs(db(back[1]) - db(through[1])) < 1.0,
                String.format("(63 Hz %.1f against %.1f dB, 125 Hz %.1f against %.1f dB)", db(through[0]), db(back[0]), db(through[1]), db(back[1])));

        // ---- a wall 6 high: bass bends over the top
        BassField over = waveRoom(2, 6, false, null).solveAll(lis[0], lis[1], lis[2], 2, 1, WaveGrid.SERIAL);
        double[] close = over.sample(22.5, 6.5, 16.5), far = over.sample(29.5, 6.5, 16.5);
        check("behind a wall 6 high bass is at least 8 dB down on open ground", db(close[0]) < db(onGround[0]) - 8 && db(close[1]) < db(onGround[1]) - 8,
                String.format("(63 Hz %.1f dB, 125 Hz %.1f dB)", db(close[0]), db(close[1])));
        check("further behind the wall the shadow fills in", far[0] > close[0] && far[1] > close[1],
                String.format("(63 Hz %.1f then %.1f dB)", db(close[0]), db(far[0])));

        // ---- splitting the work over threads changes nothing
        WaveGrid.Runner two = (parts, body) -> java.util.stream.IntStream.range(0, parts).parallel().forEach(body::accept);
        double[] split = waveRoom(2, 6, false, null).solveAll(lis[0], lis[1], lis[2], 2, 4, two).sample(22.5, 6.5, 16.5);
        check("splitting the work over threads gives exactly the same answer", split[0] == close[0] && split[1] == close[1], "");

        // ---- odd places for the listener
        check("a listener outside the grid gets no field", waveRoom(1, 0, false, null).solveAll(40, 8, 16, 1, 1, WaveGrid.SERIAL) == null, "");
        check("a listener inside a solid block is moved to the nearest air", waveRoom(1, 0, false, null).solveAll(8.5, 5.8, 16.5, 1, 1, WaveGrid.SERIAL) != null, "");
        check("a source buried in stone has no wave result", ground.sample(16.5, 2.5, 16.5) == null, "");
        check("a block sound starts inside its own block and is read from the air round it", ground.sample(16.5, 5.5, 16.5) != null, "");

        // ---- turning the wave result into filter settings
        double[] ones = {1, 1, 1, 1, 1, 1, 1, 1};
        List<Propagation.Arrival> clear = Propagation.list();
        clear.add(Propagation.straight(new double[]{10, 0, 0}, 10, ones.clone()));
        Propagation.Result inView = Propagation.combine(new double[]{0, 0, 0}, new double[]{10, 0, 0}, clear);
        BassMix.Result plain = BassMix.mix(inView, ones, 10, 10, null, 1.0);
        check("a sound in plain view with no wave result is left alone", plain.gainLF == 1.0 && plain.send == 0.0 && !plain.usedWave, "");
        BassMix.Result onOpenGround = BassMix.mix(inView, ones, 10, 10, BassMix.OPEN_GROUND.clone(), 1.0);
        check("on open flat ground the wave result changes nothing", Math.abs(onOpenGround.gainLF - 1.0) < 1e-9 && onOpenGround.send < 1e-9 && onOpenGround.usedWave, "");
        BassMix.Result inTheAir = BassMix.mix(inView, ones, 10, 10, new double[]{1.0, 1.0}, 1.0);
        check("high in the air, away from any surface, there is less bass", inTheAir.gainLF < 0.9 && inTheAir.send == 0.0, String.format("(bass factor %.2f)", inTheAir.gainLF));
        BassMix.Result inRoom = BassMix.mix(inView, ones, 10, 10, new double[]{12.0, 9.0}, 1.0);
        check("in a small room the extra bass goes to the bass channel", inRoom.gainLF == 1.0 && inRoom.send > 0.3 && inRoom.send < 1.0, String.format("(extra %.2f)", inRoom.send));
        check("bass is never raised by more than 9 dB", BassMix.mix(inView, ones, 10, 10, new double[]{1000, 1000}, 1.0).bass[0] <= BassMix.MAX_BOOST, "");
        check("half strength lands half way in decibels", Math.abs(db(BassMix.mix(inView, ones, 10, 10, new double[]{BassMix.OPEN_GROUND[0] * 3.1623, 1.0}, 0.5).bass[0]) - 2.5) < 0.05, "");
        check("zero strength ignores the wave result", !BassMix.mix(inView, ones, 10, 10, new double[]{12.0, 9.0}, 0.0).usedWave, "");
        check("a band with no wave result keeps its wall figure", BassMix.mix(inView, ones, 10, 10, new double[]{Double.NaN, 9.0}, 1.0).bass[0] == 1.0, "");

        // behind a stone wall: the middle of the sound loses far more than the bass
        double[] wallLeak = {1e-3, 4e-4, 1e-4, 3e-5, 1e-5, 1e-5, 1e-5, 1e-5};
        List<Propagation.Arrival> blocked = Propagation.list();
        blocked.add(Propagation.straight(new double[]{10, 0, 0}, 10, wallLeak.clone()));
        Propagation.Result behind = Propagation.combine(new double[]{0, 0, 0}, new double[]{10, 0, 0}, blocked);
        BassMix.Result thump = BassMix.mix(behind, wallLeak, 10, 10, null, 1.0);
        check("behind a wall the bass that the filter cannot give goes to the bass channel", thump.gainLF == 1.0 && thump.send > behind.gain,
                String.format("(middle %.4f, extra bass %.4f)", behind.gain, thump.send));
        BassMix.Result leakPlusDoor = BassMix.mix(behind, wallLeak, 10, 10, new double[]{0.4, 0.2}, 1.0);
        check("what leaks through the wall is added to what the wave carries round it",
                Math.abs(leakPlusDoor.bass[0] - (0.4 / BassMix.OPEN_GROUND[0] + wallLeak[0])) < 1e-9, "");
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
