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
    /** Bass is never raised by more than this (energy; 4 is 6 dB). */
    public static final double MAX_BOOST = 4.0;
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
     * @param usual            the wave result averaged over everywhere at that distance; null if unknown
     * @param strength         0 ignores the wave result, 1 uses it fully
     */
    public static Result mix(Propagation.Result combined, double[] straightEnergy, double straightDistance,
                             double apparentDistance, double[] wave, double[] usual, double strength) {
        return mix(combined, straightEnergy, straightDistance, apparentDistance, wave, usual, null, strength);
    }

    /**
     * As above, with sound that came through the blocks themselves.
     *
     * @param throughBlocks its bass energy per band, compared with an unblocked sound at the
     *                      apparent distance; null if there is none. The wave simulation knows
     *                      nothing of it, so it is added to the wave figures.
     */
    public static Result mix(Propagation.Result combined, double[] straightEnergy, double straightDistance,
                             double apparentDistance, double[] wave, double[] usual, double[] throughBlocks, double strength) {
        double[] bass = {combined.bandEnergy[0], combined.bandEnergy[1], combined.bandEnergy[2]};
        boolean usedWave = false;
        if (wave != null && strength > 0) {
            // The wave figure is for the straight distance; the sound is placed at the apparent distance.
            double scale = straightDistance > 0.1 ? (apparentDistance / straightDistance) * (apparentDistance / straightDistance) : 1.0;
            for (int b = 0; b < Math.min(wave.length, OPEN_GROUND.length); b++) {
                if (wave[b] != wave[b]) continue; // NaN: nothing known for this band
                // In a tunnel or a small room sound cannot spread, so everything in it is louder,
                // not just the bass. The rest of the sound is not given that rise, so the bass is
                // not either: it is measured against what is usual at that distance in this
                // place. In the open, what is usual is the open-ground figure.
                double reference = OPEN_GROUND[b];
                if (usual != null && b < usual.length && usual[b] == usual[b]) reference = Math.max(reference, usual[b]);
                double airborne = wave[b] / reference;
                // The simulation treats walls as sealed, so what leaks through them is added on top.
                // With nothing much in the way, the straight path is already part of the wave result.
                double total = (straightEnergy[b] >= WALL ? airborne : airborne + straightEnergy[b]) * scale;
                if (throughBlocks != null && b < throughBlocks.length) total += throughBlocks[b];
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

    /**
     * How the bass at one spot compares with what is usual for the place, averaged over the
     * simulated bands: 1 for no change. NaN where nothing is known. For drawing the bass field.
     */
    public static double relative(double[] wave, double[] usual) {
        double sum = 0;
        int known = 0;
        for (int b = 0; b < Math.min(wave.length, OPEN_GROUND.length); b++) {
            if (wave[b] != wave[b]) continue;
            double reference = OPEN_GROUND[b];
            if (usual != null && b < usual.length && usual[b] == usual[b]) reference = Math.max(reference, usual[b]);
            sum += Math.min(MAX_BOOST, wave[b] / reference);
            known++;
        }
        return known == 0 ? Double.NaN : sum / known;
    }

    /** Moves from one energy towards another by a share, measured in decibels. */
    private static double blend(double from, double to, double share) {
        if (share >= 1.0) return to;
        double a = 10 * Math.log10(Math.max(from, 1e-9)), b = 10 * Math.log10(Math.max(to, 1e-9));
        return Math.pow(10, (a + (b - a) * share) / 10);
    }
}
