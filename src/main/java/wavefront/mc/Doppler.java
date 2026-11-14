package wavefront.mc;

import com.redsmods.wavefront.config.Config;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.openal.AL10;

/**
 * Doppler shift: pitch rises as you and a sound approach each other and falls as you part.
 * OpenAL does the shifting itself once it is told how fast the listener and each source move;
 * Minecraft never tells it, so this class does.
 */
public final class Doppler {
    private Doppler() {}

    private static final double TICKS_PER_SECOND = 20.0;
    /** A jump faster than this in one tick is a teleport or respawn, not movement. */
    private static final double MAX_SPEED = 120.0;

    private static Vec3 lastListener;
    private static double vx, vy, vz;

    /** Call once per game tick with the listener's position. */
    public static void tick(Vec3 listener, boolean paused) {
        Config cfg = Config.getInstance();
        try {
            AL10.alDopplerFactor(cfg.doppler ? Math.max(0f, cfg.dopplerStrength) : 0f);
            if (paused || lastListener == null) {
                vx = vy = vz = 0;
            } else {
                double nx = (listener.x - lastListener.x) * TICKS_PER_SECOND;
                double ny = (listener.y - lastListener.y) * TICKS_PER_SECOND;
                double nz = (listener.z - lastListener.z) * TICKS_PER_SECOND;
                if (nx * nx + ny * ny + nz * nz > MAX_SPEED * MAX_SPEED) {
                    nx = ny = nz = 0;
                }
                // Blend with the last value so one uneven tick does not make the pitch wobble.
                vx = vx * 0.5 + nx * 0.5;
                vy = vy * 0.5 + ny * 0.5;
                vz = vz * 0.5 + nz * 0.5;
            }
            lastListener = listener;
            AL10.alListener3f(AL10.AL_VELOCITY, (float) vx, (float) vy, (float) vz);
        } catch (Throwable ignored) {
            // audio not ready: try again next tick
        }
    }

    /** Tells OpenAL how fast one sound source is moving, in blocks per second. */
    public static void setSourceVelocity(int source, double x, double y, double z) {
        try {
            if (x * x + y * y + z * z > MAX_SPEED * MAX_SPEED) {
                x = y = z = 0;
            }
            AL10.alSource3f(source, AL10.AL_VELOCITY, (float) x, (float) y, (float) z);
        } catch (Throwable ignored) {
            // a failed update must never interrupt audio
        }
    }

    public static void reset() {
        lastListener = null;
        vx = vy = vz = 0;
    }
}
