import android.media.AudioTrack;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import java.nio.ByteBuffer;
class AudioTrackSubclassProbe extends AudioTrack {
 AudioTrackSubclassProbe(AudioAttributes a, AudioFormat f, int b, int m, int s) { super(a,f,b,m,s); }
 @Override public int write(byte[] a,int o,int n) { return super.write(a,o,n); }
 @Override public int write(byte[] a,int o,int n,int m) { return super.write(a,o,n,m); }
 @Override public int write(float[] a,int o,int n,int m) { return super.write(a,o,n,m); }
 @Override public int write(ByteBuffer a,int n,int m) { return super.write(a,n,m); }
}
