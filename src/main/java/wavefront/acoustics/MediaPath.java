package wavefront.acoustics;

/**
 * Follows a sound along its path through air, water and lava, one short step at a time, and
 * adds up what that does to it: how long the trip takes, and how much is lost each time the
 * path crosses from one medium into another.
 */
public final class MediaPath {
    private Medium current;
    private double seconds, length, airLength, crossingLossDb, pending;
    private int crossings;

    /**
     * The next stretch of the path: {@code metres} long, all inside {@code medium}.
     *
     * A null medium means a solid block. Solids are dealt with as walls elsewhere, so here the
     * stretch simply counts as more of whatever came before it. That way a note block sitting
     * under water starts in water, and a stone wall between two pools is not mistaken for air.
     * A path that begins inside a solid takes on the first real medium it meets.
     */
    public void step(Medium medium, double metres) {
        if (metres <= 0) return;
        if (medium == null) {
            if (current == null) pending += metres;
            else step(current, metres);
            return;
        }
        if (pending > 0) {
            double start = pending;
            pending = 0;
            current = medium;
            step(medium, start);
        }
        if (current != null && medium != current) {
            crossingLossDb += Medium.crossingLossDb(current, medium);
            crossings++;
        }
        current = medium;
        seconds += metres / medium.speed;
        length += metres;
        if (medium == Medium.AIR) airLength += metres;
    }

    /** Travel time for the whole path. A path that never left solid blocks is timed as air. */
    public double seconds() {
        return seconds + pending / Medium.AIR.speed;
    }

    public double length() {
        return length + pending;
    }

    /** How much of the path is in air, which is the only part air absorption acts on. */
    public double airLength() {
        return airLength + pending;
    }

    /** Total loss in dB from every boundary crossed. */
    public double crossingLossDb() {
        return crossingLossDb;
    }

    public int crossings() {
        return crossings;
    }

    /** The medium at the end of the path so far, or null if nothing has been added. */
    public Medium last() {
        return current;
    }
}
