package dev.soundconnect.r1;

/** Selects the actual measured left/right loudspeaker directions and resamples their FIRs. */
public final class ProfileFilters {
    private ProfileFilters() {}
    public static float[][][] stereo(HkiProfile profile, int rate) {
        if (rate < 8000 || rate > 192000) throw new IllegalArgumentException("sample rate");
        float[][][] result = new float[2][2][];
        int[] angles = {330, 30};
        for (int c = 0; c < 2; c++) {
            HkiProfile.Direction d = profile.nearest(angles[c], 90);
            int delta = Math.abs(d.azimuth - angles[c]); delta = Math.min(delta, 360 - delta);
            if (delta > 15 || Math.abs(d.polar - 90) > 15) throw new IllegalArgumentException("Missing front stereo directions");
            for (int ear = 0; ear < 2; ear++) result[c][ear] = resample(d.filters[ear], profile.sampleRate, rate);
        }
        return result;
    }
    static float[] resample(float[] source, int from, int to) {
        if (from == to) return source.clone();
        int count = (int) Math.ceil((double) source.length * to / from);
        if (count > 131072) throw new IllegalArgumentException("resampled filter too long");
        float[] result = new float[count];
        double cutoff = Math.min(1.0, (double) to / from), scale = (double) from / to;
        int radius = (int) Math.ceil(24 / cutoff);
        for (int n = 0; n < count; n++) {
            double center = n * scale, total = 0;
            int first = Math.max(0, (int) Math.ceil(center - radius));
            int last = Math.min(source.length - 1, (int) Math.floor(center + radius));
            for (int k = first; k <= last; k++) {
                double delta = k - center, x = delta * cutoff;
                double sinc = Math.abs(x) < 1e-10 ? 1 : Math.sin(Math.PI * x) / (Math.PI * x);
                double window = 0.5 + 0.5 * Math.cos(Math.PI * delta / radius);
                total += source[k] * sinc * window * cutoff;
            }
            result[n] = (float) (total * scale);
        }
        return result;
    }
}
