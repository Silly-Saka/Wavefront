package wavefront.acoustics;

import java.util.ArrayList;
import java.util.List;

/**
 * Combines every way a sound reaches the listener (straight through walls, and bent round
 * edges) into the three things the audio engine can act on: where the sound seems to come
 * from, how loud it is, and how much of its treble survives.
 */
public final class Propagation {
    private Propagation() {}

    /** One way the sound arrives. */
    public static final class Arrival {
        /** Energy per band relative to an unobstructed path of the same length (0..1). */
        public final double[] energy;
        /** Length of this path in blocks. */
        public final double length;
        /** The point the listener hears it arrive from. */
        public final double[] from;
        /** Travel time in seconds. Starts out as the time through air; water and lava change it. */
        public double seconds;
        /** How much of the path is in air, in blocks. Air absorption acts on this part only. */
        public double airLength;

        public Arrival(double[] energy, double length, double[] from) {
            this.energy = energy;
            this.length = Math.max(length, 0.1);
            this.from = from;
            this.seconds = this.length / Bands.SPEED_OF_SOUND;
            this.airLength = this.length;
        }
    }

    public static final class Result {
        /** Where to place the sound so its direction and distance match what would be heard. */
        public final double[] apparentPosition;
        /** Overall volume factor, 0..1 (linear amplitude). */
        public final double gain;
        /** Extra factor applied to treble (above about 5 kHz), 0..1 (linear amplitude). */
        public final double gainHF;
        /** Extra factor applied to bass (below about 250 Hz), 0..1 (linear amplitude). */
        public final double gainLF;
        /** Energy per band relative to an unobstructed sound at the apparent distance. */
        public final double[] bandEnergy;
        /** Travel time in seconds, averaged over the arrivals by loudness. */
        public double seconds;

        Result(double[] apparentPosition, double gain, double gainLF, double gainHF, double[] bandEnergy) {
            this.apparentPosition = apparentPosition;
            this.gain = gain;
            this.gainLF = gainLF;
            this.gainHF = gainHF;
            this.bandEnergy = bandEnergy;
        }
    }

    /** Builds the arrival for a route that bends round one or more edges. */
    public static Arrival diffracted(PathFinder.Route route, double straightDistance) {
        double detour = Math.max(0, route.length - straightDistance);
        double[] loss = Diffraction.lossDb(detour, route.bends, route.bendSpan);
        double[] energy = new double[Bands.COUNT];
        for (int b = 0; b < Bands.COUNT; b++) energy[b] = Bands.dbToEnergy(loss[b]);
        return new Arrival(energy, route.length, route.lastBend());
    }

    /**
     * As {@link #diffracted(PathFinder.Route, double)}, but also limited by the size of the
     * smallest opening the route passes through (null for none).
     */
    public static Arrival diffracted(PathFinder.Route route, double straightDistance, Aperture.Opening opening) {
        Arrival arrival = diffracted(route, straightDistance);
        if (opening != null) {
            for (int b = 0; b < Bands.COUNT; b++) arrival.energy[b] = Math.min(arrival.energy[b], opening.energyLimit(b));
        }
        return arrival;
    }

    /** Builds the arrival for the straight line, given what survives the blocks in the way. */
    public static Arrival straight(double[] source, double straightDistance, double[] energyThroughWalls) {
        return new Arrival(energyThroughWalls, straightDistance, source);
    }

    public static Result combine(double[] listener, double[] source, List<Arrival> arrivals) {
        // Loudness weight of each arrival: mid-band energy, falling off with distance squared.
        double[] weight = new double[arrivals.size()];
        double total = 0;
        for (int i = 0; i < arrivals.size(); i++) {
            Arrival a = arrivals.get(i);
            double mid = 0;
            for (int b = 2; b <= 6; b++) mid += a.energy[b];
            weight[i] = (mid / 5.0) / (a.length * a.length);
            total += weight[i];
        }
        if (arrivals.isEmpty() || total < 1e-14) {
            return new Result(source.clone(), 0, 0, 0, Bands.zeros());
        }

        // Direction: loudness-weighted average of where each arrival comes from.
        double dx = 0, dy = 0, dz = 0, distance = 0, seconds = 0;
        for (int i = 0; i < arrivals.size(); i++) {
            Arrival a = arrivals.get(i);
            double ux = a.from[0] - listener[0], uy = a.from[1] - listener[1], uz = a.from[2] - listener[2];
            double len = Math.sqrt(ux * ux + uy * uy + uz * uz);
            if (len > 1e-6) {
                dx += weight[i] * ux / len;
                dy += weight[i] * uy / len;
                dz += weight[i] * uz / len;
            }
            distance += weight[i] * a.length;
            seconds += weight[i] * a.seconds;
        }
        distance /= total;
        double dirLen = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dirLen < 1e-9) {
            // Arrivals cancel out directionally (e.g. equal paths round both sides): fall back to the source.
            dx = source[0] - listener[0];
            dy = source[1] - listener[1];
            dz = source[2] - listener[2];
            dirLen = Math.max(1e-9, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        double[] apparent = {
                listener[0] + dx / dirLen * distance,
                listener[1] + dy / dirLen * distance,
                listener[2] + dz / dirLen * distance};

        // Energy per band, relative to an unobstructed sound at the apparent distance.
        double[] bandEnergy = new double[Bands.COUNT];
        for (Arrival a : arrivals) {
            double spread = (distance / a.length) * (distance / a.length);
            for (int b = 0; b < Bands.COUNT; b++) bandEnergy[b] += a.energy[b] * spread;
        }
        for (int b = 0; b < Bands.COUNT; b++) bandEnergy[b] = Math.min(1.0, bandEnergy[b]);

        // The audio engine's per-sound filter has three knobs: overall gain, and how much less
        // the bass and the treble get. It can only cut bass and treble, never lift them.
        double bass = (bandEnergy[0] + bandEnergy[1] + bandEnergy[2]) / 3.0;   // 63 - 250 Hz
        double body = (bandEnergy[3] + bandEnergy[4] + bandEnergy[5]) / 3.0;   // 500 Hz - 2 kHz
        double treble = (bandEnergy[6] + bandEnergy[7]) / 2.0;                 // 4 - 8 kHz
        double gain = Math.sqrt(body);
        double gainLF = gain < 1e-5 ? 0 : Math.min(1.0, Math.sqrt(bass) / gain);
        double gainHF = gain < 1e-5 ? 0 : Math.min(1.0, Math.sqrt(treble) / gain);
        Result result = new Result(apparent, Math.min(1.0, gain), gainLF, gainHF, bandEnergy);
        result.seconds = seconds / total;
        return result;
    }

    /** Convenience for the common case of an unobstructed sound. */
    public static Result unobstructed(double[] source) {
        double[] ones = new double[Bands.COUNT];
        java.util.Arrays.fill(ones, 1.0);
        return new Result(source.clone(), 1.0, 1.0, 1.0, ones);
    }

    public static List<Arrival> list() {
        return new ArrayList<>();
    }
}
