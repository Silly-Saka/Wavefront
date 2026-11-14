package wavefront.acoustics;

/**
 * Follows a sound along its path through air, water and lava, one short step at a time, and
 * adds up what that does to it: how long the trip takes, and how much is lost each time the
 * path crosses from one medium into another.
 */
public final class MediaPath {
    private Medium current;
    private double seconds, length, airLength, crossingLossDb;
    private int crossings;

    /** The next stretch of the path: {@code metres} long, all inside {@code medium}. */
    public void step(Medium medium, double metres) {
        if (metres <= 0) return;
        if (current != null && medium != current) {
            crossingLossDb += Medium.crossingLossDb(current, medium);
            crossings++;
        }
        current = medium;
        seconds += metres / medium.speed;
        length += metres;
        if (medium == Medium.AIR) airLength += metres;
    }

    /** Travel time for the whole path. */
    public double seconds() {
        return seconds;
    }

    public double length() {
        return length;
    }

    /** How much of the path is in air, which is the only part air absorption acts on. */
    public double airLength() {
        return airLength;
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
