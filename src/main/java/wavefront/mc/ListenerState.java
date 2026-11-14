package wavefront.mc;

import com.redsmods.wavefront.config.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import wavefront.acoustics.EarCues;

/**
 * What the listener's surroundings do to their sense of direction. Under water the left-right
 * cue shrinks to about a quarter (see {@link EarCues}); every processed sound asks here where
 * it should be placed so that it is heard that much nearer the middle.
 */
public final class ListenerState {
    private ListenerState() {}

    /** Share of the left-right cue that survives where the listener is: 1 in air. */
    private static volatile double keep = 1.0;

    /** Call once per game tick with the speed of sound at the listener's ears. */
    public static void tick(double speedOfSound) {
        Config cfg = Config.getInstance();
        keep = cfg.water ? EarCues.sidewaysKept(speedOfSound, cfg.submergedDirection) : 1.0;
    }

    /**
     * Where to place a sound that is really at (x, y, z), or null if it should stay where it is.
     * Uses the player's position and facing at this moment, so turning the head is followed
     * every tick.
     */
    public static double[] heardFrom(double x, double y, double z) {
        double share = keep;
        if (share >= 0.999) return null;
        var player = Minecraft.getInstance().player;
        if (player == null) return null;
        Vec3 eye = player.getEyePosition();
        // Minecraft's yaw: 0 faces south (+z), 90 faces west (-x).
        double yaw = Math.toRadians(player.getYRot());
        double[] forward = {-Math.sin(yaw), 0, Math.cos(yaw)};
        double[] right = {-Math.cos(yaw), 0, -Math.sin(yaw)};
        return EarCues.compress(new double[]{eye.x, eye.y, eye.z}, right, forward, x, y, z, share);
    }

    public static void reset() {
        keep = 1.0;
    }
}
