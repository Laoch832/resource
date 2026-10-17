package android.media;import java.nio.*;import java.io.*;public class AudioTrack {
 public static final int MODE_STREAM=1,WRITE_BLOCKING=0,WRITE_NON_BLOCKING=1,ERROR_BAD_VALUE=-2,STATE_INITIALIZED=1;
 public final ByteArrayOutputStream captured=new ByteArrayOutputStream();public int acceptLimit=512,zeroEvery=3,sinkError,calls;private final int enc;
 public AudioTrack(AudioAttributes a,AudioFormat f,int b,int m,int s){enc=f.getEncoding();}public AudioTrack(int s,int r,int c,int e,int b,int m){enc=e;}
 public int getState(){return 1;}public void release(){}public void flush(){captured.reset();}
 public int write(byte[] d,int o,int n){return write(d,o,n,0);}public int write(byte[] d,int o,int n,int m){if(enc==4)return -3;return write(ByteBuffer.wrap(d,o,n),n,m);}
 public int write(short[] d,int o,int n){return write(d,o,n,0);}public int write(short[] d,int o,int n,int m){ByteBuffer b=ByteBuffer.allocate(n*2).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<n;i++)b.putShort(d[o+i]);b.flip();return write(b,n*2,m)/2;}
 public int write(float[] d,int o,int n,int m){ByteBuffer b=ByteBuffer.allocate(n*4).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<n;i++)b.putFloat(d[o+i]);b.flip();return write(b,n*4,m)/4;}
 public int write(ByteBuffer d,int n,int m){calls++;if(sinkError!=0)return sinkError;if(m==1 && zeroEvery>0 && calls%zeroEvery==0)return 0;int take=m==0?n:Math.min(n,acceptLimit);byte[] b=new byte[take];d.get(b);captured.write(b,0,b.length);return take;}
}