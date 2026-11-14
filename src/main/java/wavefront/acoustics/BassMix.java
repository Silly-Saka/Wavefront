package wavefront.acoustics;

/**
 * Decides how loud the bass of one sound is. Where the wave simulation has an answer it is used
 * for the bands it covers; everywhere else the figures from walls and diffraction stand.
 *
 * The audio engine's filter can only make bass quieter than the rest of a sound. Behind a wall
 * the opposite happens: bass gets through and round far better than the middle of the sound.
 * So the result has two parts: a bass cut for the filter, and an amount of extra bass to be
 * added on a separate channel.
 */
public final class BassMix {
    private BassMix() {}

    /**
     * What the wave simulation gives for a sound on the ground heard by someone standing on the
     * same open flat ground: about 5.7 dB more than open air, because the bounce off the ground
     * adds to the direct sound (the textbook figure for a perfect mirror is 6). Wave results are
     * divided by this, so a sound in the open plays as it was recorded and only the surroundings
     * change it.
     */
    public static final double[] OPEN_GROUND = {3.7, 3.7};
    /** Bass is never raised by more than this (energy; 8 is 9 dB). */
    public static final double MAX_BOOST = 8.0;
    /** Below this share of bass getting straight through, the blocks in the way count as a wall (6 dB). */
    static final double WALL = 0.25;

    public static final class Result {
        /** Factor for the bass part of the main filter, 0..1. */
        public final double gainLF;
        /** Extra bass to add on the bass channel, as a factor of the sound's unblocked loudness (0 = none). */
        public final double send;
        /** Bass energy at 63, 125 and 250 Hz, compared with an unblocked sound at the apparent distance. */
        public final double[] bass;
        /** True if the wave simulation supplied at least one band. */
        public final boolean usedWave;

        Result(double gainLF, double send, double[] bass, boolean usedWave) {
            this.gainLF = gainLF;
            this.send = send;
            this.bass = bass;
            this.usedWave = usedWave;
        }
    }

    /**
     * @param combined         the result from walls and diffraction
     * @param straightEnergy   what gets straight through the blocks in the way, per band (1 = nothing in the way)
     * @param straightDistance distance from listener to source in a straight line, in blocks
     * @param apparentDistance distance at which the sound is placed, in blocks
     * @param wave             wave result per simulated band, compared with open air at the straight
     *                         distance; null or NaN entries where nothing is known
     * @param strength         0 ignores the wave result, 1 uses it fully
     */
    public static Result mix(Propagation.Result combined, double[] straightEnergy, double straightDistance,
                             double apparentDistance, double[] wave, double strength) {
        double[] bass = {combined.bandEnergy[0], combined.bandEnergy[1], combined.bandEnergy[2]};
        boolean usedWave = false;
        if (wave != null && strength > 0) {
            // The wave figure is for the straight distance; the sound is placed at the apparent distance.
            double scale = straightDistance > 0.1 ? (apparentDistance / straightDistance) * (apparentDistance / straightDistance) : 1.0;
            for (int b = 0; b < Math.min(wave.length, OPEN_GROUND.length); b++) {
                if (wave[b] != wave[b]) continue; // NaN: nothing known for this band
                double airborne = wave[b] / OPEN_GROUND[b];
                // The simulation treats walls as sealed, so what leaks through them is added on top.
                // With nothing much in the way, the straight path is already part of the wave result.
                double total = (straightEnergy[b] >= WALL ? airborne : airborne + straightEnergy[b]) * scale;
                total = Math.min(total, MAX_BOOST);
                bass[b] = blend(bass[b], total, Math.min(1.0, strength));
                usedWave = true;
            }
        }
        double bassLevel = Math.sqrt((bass[0] + bass[1] + bass[2]) / 3.0);
        double gain = combined.gain;
        if (gain < 1e-5) return new Result(1.0, bassLevel, bass, usedWave); // nothing but bass gets here
        double gainLF = Math.min(1.0, bassLevel / gain);
        return new Result(gainLF, Math.max(0.0, bassLevel - gain * gainLF), bass, usedWave);
    }

    /** Moves from one energy towards another by a share, measured in decibels. */
    private static double blend(double from, double to, double share) {
        if (share >= 1.0) return to;
        double a = 10 * Math.log10(Math.max(from, 1e-9)), b = 10 * Math.log10(Math.max(to, 1e-9));
        return Math.pow(10, (a + (b - a) * share) / 10);
    }
}
