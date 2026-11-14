package wavefront.mc;

import com.redsmods.wavefront.config.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import wavefront.acoustics.BassField;
import wavefront.acoustics.BassMix;
import wavefront.acoustics.StructureBorne;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Draws what the mod worked out, in the world: the way each recent sound reached the listener,
 * and the bass field from the wave simulation. It uses the game's own debug drawing ("gizmos"),
 * handed over once a game tick. Off unless switched on in the settings.
 */
public final class Visualiser {
    private Visualiser() {}

    private static final long SHOWN_NANOS = 1_500_000_000L;
    private static final int MAX_TRACES = 48;
    private static final int CLEAR = 0xFF40E060, BLOCKED = 0xFFE04040, BENT = 0xFFFFE040, SOLID = 0xFFFF9020;
    /** The bass dots cover this many blocks each way from the listener. */
    private static final int FIELD_REACH = 10;

    /** How one sound reached the listener. */
    private record Trace(Vec3 source, Vec3 listener, boolean blocked, List<double[][]> routes, StructureBorne.Path solid, long at) {}

    private static final ConcurrentLinkedDeque<Trace> TRACES = new ConcurrentLinkedDeque<>();

    /** True if the solver should keep a note of each sound's paths. */
    public static boolean wantsPaths() {
        return Config.getInstance().showSoundPaths;
    }

    /** Called by the solver, from any thread. */
    static void record(Vec3 source, Vec3 listener, boolean blocked, List<double[][]> routes, StructureBorne.Path solid) {
        TRACES.addLast(new Trace(source, listener, blocked, routes, solid, System.nanoTime()));
        while (TRACES.size() > MAX_TRACES) TRACES.pollFirst();
    }

    /** Called once a game tick on the game thread. */
    public static void tick(Level level, Vec3 listener) {
        Config cfg = Config.getInstance();
        if (!cfg.showSoundPaths) TRACES.clear();
        if (!cfg.showSoundPaths && !cfg.showBassField) return;
        try {
            if (cfg.showSoundPaths) drawPaths();
            if (cfg.showBassField) drawBassField(level, listener);
        } catch (Throwable t) {
            // Drawing is a tool, not part of the sound: never let it break anything.
        }
    }

    private static void drawPaths() {
        long now = System.nanoTime();
        TRACES.removeIf(t -> now - t.at > SHOWN_NANOS);
        for (Trace t : TRACES) {
            // Stop a little short of the eye so the lines do not fill the view.
            Vec3 ear = t.listener.add(t.source.subtract(t.listener).normalize().scale(0.4)).add(0, -0.3, 0);
            Gizmos.line(t.source, ear, t.blocked ? BLOCKED : CLEAR, 2f).setAlwaysOnTop();
            if (t.routes != null) {
                for (double[][] points : t.routes) {
                    for (int i = 1; i < points.length; i++) {
                        Vec3 a = new Vec3(points[i - 1][0], points[i - 1][1], points[i - 1][2]);
                        Vec3 b = i == points.length - 1 ? ear : new Vec3(points[i][0], points[i][1], points[i][2]);
                        Gizmos.line(a, b, BENT, 2f).setAlwaysOnTop();
                    }
                }
            }
            if (t.solid != null) {
                for (int[] cell : t.solid.cells) {
                    Gizmos.cuboid(new BlockPos(cell[0], cell[1], cell[2]), GizmoStyle.stroke(SOLID)).setAlwaysOnTop();
                }
                Gizmos.line(new Vec3(t.solid.from[0], t.solid.from[1], t.solid.from[2]), ear, SOLID, 2f).setAlwaysOnTop();
            }
        }
    }

    private static void drawBassField(Level level, Vec3 listener) {
        BassField field = BassLayer.fieldFor(level, listener);
        if (field == null) return;
        double y = listener.y - 0.9;
        int cx = (int) Math.floor(listener.x), cz = (int) Math.floor(listener.z);
        for (int x = cx - FIELD_REACH; x <= cx + FIELD_REACH; x++) {
            for (int z = cz - FIELD_REACH; z <= cz + FIELD_REACH; z++) {
                double px = x + 0.5, pz = z + 0.5;
                double[] wave = field.sample(px, y, pz);
                if (wave == null) continue;
                double relative = BassMix.relative(wave, field.usual(px, y, pz));
                if (relative != relative) continue; // NaN: nothing known here
                Gizmos.point(new Vec3(px, y, pz), colour(10 * Math.log10(Math.max(relative, 1e-6))), 8f);
            }
        }
    }

    /** Blue at 12 dB less bass or lower, white at no change, red at 6 dB more or higher. */
    private static int colour(double db) {
        if (db >= 0) {
            int fade = (int) (255 * (1 - Math.min(1.0, db / 6.0)));
            return 0xFF000000 | (255 << 16) | (fade << 8) | fade;
        }
        int fade = (int) (255 * (1 - Math.min(1.0, -db / 12.0)));
        return 0xFF000000 | (fade << 16) | (fade << 8) | 255;
    }
}
