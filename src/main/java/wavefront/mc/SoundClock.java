package wavefront.mc;

import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.SOFTDeviceClock;
import org.lwjgl.openal.SOFTSourceStartDelay;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Starts sounds at an exact moment on the audio device's own clock.
 *
 * Minecraft starts sounds "as soon as possible", which is accurate to a few milliseconds at
 * best. Sound travels 34 cm in one millisecond, so the speed-of-sound delay, and above all the
 * interference between two sources playing the same sound, need better than that. OpenAL Soft
 * can start a source at a given device time, exact to one audio sample.
 *
 * Every sound started in the same game tick is timed from the same reference instant, so the
 * gaps between them are exact even though each is handed to the audio thread a little later
 * than the last.
 */
public final class SoundClock {
    private SoundClock() {}

    /** How far ahead of "now" a tick's reference instant is set, so every sound can be scheduled in the future. */
    public static final long LEAD_NANOS = 30_000_000L;
    private static final long MAX_DELAY_NANOS = 2_000_000_000L;

    private static final Map<Integer, Long> startTimes = new ConcurrentHashMap<>();
    private static volatile int supported = -1; // -1 not checked yet, 0 no, 1 yes

    /** True if this audio device can start sounds at an exact time. */
    public static boolean supported() {
        int s = supported;
        if (s >= 0) return s == 1;
        try {
            long context = ALC10.alcGetCurrentContext();
            if (context == 0) return false; // audio not ready yet: ask again later
            long device = ALC10.alcGetContextsDevice(context);
            boolean ok = AL10.alIsExtensionPresent("AL_SOFT_source_start_delay")
                    && ALC10.alcIsExtensionPresent(device, "ALC_SOFT_device_clock");
            supported = ok ? 1 : 0;
            return ok;
        } catch (Throwable t) {
            supported = 0;
            return false;
        }
    }

    /** The audio device's clock in nanoseconds, or 0 if it cannot be read. */
    public static long now() {
        if (!supported()) return 0;
        try {
            long device = ALC10.alcGetContextsDevice(ALC10.alcGetCurrentContext());
            return SOFTDeviceClock.alcGetInteger64vSOFT(device, SOFTDeviceClock.ALC_DEVICE_CLOCK_SOFT);
        } catch (Throwable t) {
            supported = 0;
            return 0;
        }
    }

    /** Remembers when an OpenAL source should start. Picked up when Minecraft plays that source. */
    public static void schedule(int source, long startNanos) {
        startTimes.put(source, startNanos);
    }

    /** The start time for a sound in the batch timed from {@code reference}, delayed by {@code seconds}. */
    public static long startTime(long reference, double seconds) {
        long delay = (long) (Math.max(0, seconds) * 1_000_000_000.0);
        return reference + Math.min(delay, MAX_DELAY_NANOS);
    }

    /** Called in place of alSourcePlay: starts the source at its scheduled time if it has one. */
    public static void play(int source) {
        Long start = startTimes.remove(source);
        if (start != null && supported == 1) {
            try {
                SOFTSourceStartDelay.alSourcePlayAtTimeSOFT(source, start);
                return;
            } catch (Throwable t) {
                supported = 0; // fall back to ordinary starts from now on
            }
        }
        AL10.alSourcePlay(source);
    }

    /** Forgets everything; called when the sound engine restarts. */
    public static void reset() {
        startTimes.clear();
        supported = -1;
    }
}
