package wavefront.mc;

import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
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

    /** A source that should join in partway through, a set time behind one that is already playing. */
    private record Follow(int leader, double lagSeconds) {}

    private static final Map<Integer, Follow> follows = new ConcurrentHashMap<>();

    /**
     * Remembers that a source should start partway through its sound, {@code lagSeconds} behind
     * where {@code leader} is at that moment. Used for an echo that begins while its sound is
     * already playing.
     */
    public static void follow(int source, int leader, double lagSeconds) {
        follows.put(source, new Follow(leader, lagSeconds));
    }

    /** Moves a source's starting point to match its leader. Only works for sounds held whole in memory. */
    private static void joinIn(int source, Follow follow) {
        try {
            if (!AL10.alIsSource(follow.leader)) return;
            int buffer = AL10.alGetSourcei(source, AL10.AL_BUFFER);
            if (buffer == 0) return;
            int bytes = AL10.alGetBufferi(buffer, AL10.AL_SIZE), channels = AL10.alGetBufferi(buffer, AL10.AL_CHANNELS);
            int bits = AL10.alGetBufferi(buffer, AL10.AL_BITS), rate = AL10.alGetBufferi(buffer, AL10.AL_FREQUENCY);
            if (bytes <= 0 || channels <= 0 || bits < 8 || rate <= 0) return;
            double length = bytes / (channels * (bits / 8.0)) / rate;
            // The lag is in real seconds; a sound played faster or slower covers more or less of itself in that time.
            double at = AL10.alGetSourcef(follow.leader, AL11.AL_SEC_OFFSET)
                    - follow.lagSeconds * AL10.alGetSourcef(follow.leader, AL10.AL_PITCH);
            if (AL10.alGetSourcei(source, AL10.AL_LOOPING) == AL10.AL_TRUE) {
                at -= Math.floor(at / length) * length; // wrap round the loop
            }
            at = Math.max(0.0, Math.min(length - 0.01, at));
            AL10.alSourcef(source, AL11.AL_SEC_OFFSET, (float) at);
            AL10.alGetError(); // a refused offset just means the copy starts from the beginning
        } catch (Throwable ignored) {
            // the copy still plays, from the beginning
        }
    }

    /** Called in place of alSourcePlay: starts the source at its scheduled time if it has one. */
    public static void play(int source) {
        Follow follow = follows.remove(source);
        if (follow != null) {
            startTimes.remove(source);
            joinIn(source, follow);
            AL10.alSourcePlay(source);
            return;
        }
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
        follows.clear();
        supported = -1;
    }
}
