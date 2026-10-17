package dev.soundconnect.r1;

import java.io.*;

/** MPEG-H standard BRIR interchange for the 5.1.4 (CICP 16) loudspeaker layout. */
public final class MpeghBrir {
    private MpeghBrir() {}
    private static final int[] AZ = {330,30,0,0,250,110,330,30,250,110};
    private static final int[] POLAR = {90,90,90,110,90,90,55,55,55,55};
    public static void write(HkiProfile profile, OutputStream stream) throws IOException {
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(stream));
        Bits b = new Bits(out);
        b.put(0x42524952,32); b.put(0,8); b.put(0,8); // BRIR / reserved / empty label
        b.put(0,1); b.put(1,4); // fixed head; one representation
        b.put(3,5); b.put(0,1); // 48000 Hz; loudspeaker-domain FIR
        b.put(0,2); b.put(16,6); // CICP layout 16
        b.put(9,5); b.put(0,2); b.align(); // ten BRIR pairs; FIR format
        int taps = (int)Math.ceil((double)profile.taps * 48000 / profile.sampleRate);
        if (taps < 1 || taps > 131072) throw new IOException("BRIR length unsupported");
        b.put(taps,24);
        for (int channel = 0; channel < 10; channel++) {
            HkiProfile.Direction d = profile.nearest(AZ[channel], POLAR[channel]);
            int delta = Math.abs(d.azimuth - AZ[channel]); delta = Math.min(delta, 360-delta);
            if (delta > 10 || Math.abs(d.polar - POLAR[channel]) > 10) throw new IOException("Missing 5.1.4 profile direction " + channel);
            float[] left = ProfileFilters.resample(d.filters[0], profile.sampleRate,48000);
            float[] right = ProfileFilters.resample(d.filters[1], profile.sampleRate,48000);
            for (int t=0;t<taps;t++) {out.writeFloat(left[t]);out.writeFloat(right[t]);}
        }
        out.writeInt(24000); out.flush();
    }
    private static final class Bits {
        final OutputStream out; int value, used;
        Bits(OutputStream out) {this.out=out;}
        void put(int value, int n) throws IOException {
            for(int i=n-1;i>=0;i--) {this.value=(this.value<<1)|((value>>>i)&1); if(++used==8){out.write(this.value);used=0;this.value=0;}}
        }
        void align() throws IOException {if(used!=0) put(0,8-used);}
    }
}
