package wavefront.mc;

import com.redsmods.wavefront.config.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shows sound to the eye, for playing without hearing. When a sound plays, marks appear on the
 * floor and walls it reaches, so footsteps spill out of a doorway the way their sound does.
 * The marks sit on the surfaces and are hidden by anything in front of them, like any other
 * object: nothing is drawn through walls. How many there are, and how bright, follows how loud
 * the sound is to the listener after walls, diffraction and distance have had their say, and
 * a sound too faint to hear leaves none. The idea comes from Vercidium's ray-traced audio work.
 */
public final class SoundDots {
    private SoundDots() {}

    private static final int MAX_MARKS = 700;
    private static final long LIFE_NANOS = 1_200_000_000L;
    /** A sound this many dB under full loudness leaves no marks. */
    private static final double FAINTEST_DB = 40.0;
    private static final int MARKS_AT_FULL = 45;
    private static final double SEEN_WITHIN = 48.0;

    private static final byte DOT = 0, CROSS = 1, TRIANGLE = 2, DIAMOND = 3;

    /** One mark on a surface. axis: which way the surface faces (0 x, 1 y, 2 z). */
    private record Mark(double x, double y, double z, byte axis, float angle, float size, int rgb, byte shape, float strength, long born) {}

    private static final ConcurrentLinkedDeque<Mark> MARKS = new ConcurrentLinkedDeque<>();
    private static final AtomicInteger COUNT = new AtomicInteger();

    public static boolean on() {
        return Config.getInstance().soundDots;
    }

    /**
     * Leaves marks for one sound. Safe to call from the sound threads.
     *
     * @param category the game's own sound category (HOSTILE, PLAYERS, BLOCKS, ...), which picks shape and colour
     * @param volume   the sound's own volume before distance and walls
     * @param update   true for a sound that is still playing and was marked before: it gets a quarter as many
     */
    public static void emit(Level level, Player player, Vec3 listener, Vec3 source, String category, float volume,
                            AcousticSolver.Solved solved, boolean update) {
        try {
            if (listener.distanceToSqr(source) < 4.0) return; // the listener's own steps and blows
            float range = 16f * Math.max(volume, 1f);
            double heard = Math.min(1f, volume) * Math.max(0f, 1f - solved.pathLength / range) * Math.min(1f, Math.max(solved.gain, solved.bassSend));
            if (heard <= 0) return;
            double strength = 1.0 + 20 * Math.log10(heard) / FAINTEST_DB; // 1 at full loudness, 0 at the faintest
            if (strength <= 0.02) return;

            float amount = Math.max(0f, Config.getInstance().soundDotAmount);
            int marks = (int) Math.ceil(MARKS_AT_FULL * amount * strength * Math.sqrt(Math.max(1f, volume)));
            if (update) marks = Math.max(1, marks / 4);
            marks = Math.min(120, marks);
            double reach = Math.max(4.0, Math.min(14.0, 3.0 + 3.0 * Math.max(volume, 0.5f)));
            long born = System.nanoTime() + (long) (solved.seconds * 1e9); // marks appear when the sound arrives
            int rgb = colour(category);
            byte shape = shape(category);

            BlockPos sourceCell = BlockPos.containing(source);
            boolean inside = !level.getBlockState(sourceCell).getCollisionShape(level, sourceCell).isEmpty();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < marks; i++) {
                // A random direction, the same chance for every part of the sphere.
                double up = random.nextDouble(-1, 1), turn = random.nextDouble(0, 2 * Math.PI), flat = Math.sqrt(1 - up * up);
                Vec3 dir = new Vec3(flat * Math.cos(turn), up, flat * Math.sin(turn));
                Vec3 from = inside ? source.add(dir.scale(0.9)) : source; // block sounds start inside their own block
                BlockHitResult hit = level.clip(new ClipContext(from, from.add(dir.scale(reach)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
                if (hit.getType() != HitResult.Type.BLOCK || hit.isInside()) continue;
                double fade = 1.0 - 0.6 * hit.getLocation().distanceTo(source) / reach;
                add(hit, 0, 0, rgb, shape, (float) (strength * fade), born, random);
            }

            // Where the sound comes at the listener through or out of a surface, mark that surface too.
            int few = Math.max(2, marks / 4);
            cluster(level, player, listener, solved.position, few, rgb, shape, (float) strength, born, random);
            if (solved.thudFrom != null) cluster(level, player, listener, solved.thudFrom, few, rgb, shape, (float) strength, born, random);
        } catch (Throwable t) {
            // A drawing aid must never get in the way of the sound.
        }
    }

    /** Marks round the point where the line from the listener towards a place first meets a surface, if it does. */
    private static void cluster(Level level, Player player, Vec3 listener, Vec3 towards, int marks, int rgb, byte shape,
                                float strength, long born, ThreadLocalRandom random) {
        Vec3 line = towards.subtract(listener);
        double length = line.length();
        if (length < 1.0) return;
        Vec3 end = towards.add(line.scale(0.5 / length));
        BlockHitResult hit = level.clip(new ClipContext(listener, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK || hit.isInside()) return;
        if (hit.getLocation().distanceTo(listener) > length - 0.75 && hit.getLocation().distanceTo(towards) > 0.75) return;
        for (int i = 0; i < marks; i++) add(hit, random.nextDouble(-0.45, 0.45), random.nextDouble(-0.45, 0.45), rgb, shape, strength, born, random);
    }

    private static void add(BlockHitResult hit, double alongA, double alongB, int rgb, byte shape, float strength, long born, ThreadLocalRandom random) {
        int nx = hit.getDirection().getStepX(), ny = hit.getDirection().getStepY(), nz = hit.getDirection().getStepZ();
        byte axis = (byte) (nx != 0 ? 0 : ny != 0 ? 1 : 2);
        Vec3 at = hit.getLocation();
        // Just off the surface so it is not hidden by it, and shifted along the surface for clusters.
        double x = at.x + nx * 0.03, y = at.y + ny * 0.03, z = at.z + nz * 0.03;
        if (axis == 0) { y += alongA; z += alongB; } else if (axis == 1) { x += alongA; z += alongB; } else { x += alongA; y += alongB; }
        MARKS.addLast(new Mark(x, y, z, axis, (float) random.nextDouble(0, 2 * Math.PI), (float) random.nextDouble(0.09, 0.16), rgb, shape, Math.min(1f, strength), born));
        if (COUNT.incrementAndGet() > MAX_MARKS && MARKS.pollFirst() != null) COUNT.decrementAndGet();
    }

    /** Called once a game tick on the game thread, while the game is collecting its per-tick drawings. */
    public static void tick(Vec3 listener) {
        if (!on()) {
            if (!MARKS.isEmpty()) {
                MARKS.clear();
                COUNT.set(0);
            }
            return;
        }
        long now = System.nanoTime();
        try {
            for (java.util.Iterator<Mark> it = MARKS.iterator(); it.hasNext(); ) {
                Mark m = it.next();
                long age = now - m.born;
                if (age > LIFE_NANOS) {
                    it.remove();
                    COUNT.decrementAndGet();
                    continue;
                }
                if (age < 0) continue; // the sound has not arrived yet
                double dx = m.x - listener.x, dy = m.y - listener.y, dz = m.z - listener.z;
                if (dx * dx + dy * dy + dz * dz > SEEN_WITHIN * SEEN_WITHIN) continue;
                float left = 1f - (float) age / LIFE_NANOS;
                int alpha = (int) (255 * Math.max(0.12f, Math.min(1f, m.strength * 1.3f)) * left);
                draw(m, (alpha << 24) | m.rgb);
            }
        } catch (Throwable t) {
            // Outside the game's drawing window, or a drawing call failed: skip this tick.
        }
    }

    private static void draw(Mark m, int colour) {
        Vec3 centre = new Vec3(m.x, m.y, m.z);
        if (m.shape == DOT) {
            Gizmos.point(centre, colour, 7f);
            return;
        }
        // Two directions lying in the surface, turned by the mark's own angle.
        double c = Math.cos(m.angle) * m.size, s = Math.sin(m.angle) * m.size;
        Vec3 u, v;
        if (m.axis == 0) { u = new Vec3(0, c, s); v = new Vec3(0, -s, c); }
        else if (m.axis == 1) { u = new Vec3(c, 0, s); v = new Vec3(-s, 0, c); }
        else { u = new Vec3(c, s, 0); v = new Vec3(-s, c, 0); }
        if (m.shape == CROSS) {
            Gizmos.line(centre.subtract(u), centre.add(u), colour, 3f);
            Gizmos.line(centre.subtract(v), centre.add(v), colour, 3f);
        } else if (m.shape == TRIANGLE) {
            Vec3 a = centre.add(u), b = centre.add(u.scale(-0.5)).add(v.scale(0.87)), d = centre.add(u.scale(-0.5)).add(v.scale(-0.87));
            Gizmos.line(a, b, colour, 3f);
            Gizmos.line(b, d, colour, 3f);
            Gizmos.line(d, a, colour, 3f);
        } else {
            Vec3 a = centre.add(u), b = centre.add(v), d = centre.subtract(u), e = centre.subtract(v);
            Gizmos.line(a, b, colour, 3f);
            Gizmos.line(b, d, colour, 3f);
            Gizmos.line(d, e, colour, 3f);
            Gizmos.line(e, a, colour, 3f);
        }
    }

    // The game already sorts every sound into a category (the volume sliders in Options, Music & Sounds).
    private static int colour(String category) {
        switch (category) {
            case "HOSTILE": return 0xFF4030;  // red
            case "PLAYERS": return 0xFFFFFF;  // white
            case "NEUTRAL": return 0x40E080;  // green
            case "VOICE": return 0xFFD040;    // yellow
            case "RECORDS": return 0xC070FF;  // purple
            case "BLOCKS": return 0x30C8F0;   // light blue
            case "WEATHER": return 0xA0A8B0;  // grey
            case "AMBIENT": return 0x60A0A0;  // dull teal
            default: return 0xE0E0E0;
        }
    }

    private static byte shape(String category) {
        switch (category) {
            case "HOSTILE": return CROSS;
            case "PLAYERS":
            case "NEUTRAL":
            case "VOICE": return TRIANGLE;
            case "RECORDS": return DIAMOND;
            default: return DOT;
        }
    }
}
