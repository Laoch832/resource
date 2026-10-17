package dev.soundconnect.r1;

import android.app.Activity;
import android.os.Bundle;
import android.media.*;
import android.net.Uri;
import android.util.Log;
import java.io.*;
import java.nio.*;

/** Private runtime test APK with an independently generated synthetic coefficient fixture. */
public final class AndroidProbeActivity extends Activity {
    private void copy(String asset,File file) throws IOException {
        try(InputStream in=getAssets().open(asset);OutputStream out=new FileOutputStream(file)) {
            byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
        }
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    protected void onCreate(Bundle state){super.onCreate(state);new Thread(new Runnable(){public void run(){probe();}},"R1AndroidProbe").start();}
    private void probe() {
        String result;
        try {
            ProfileStore.initialize(this);
            File profile=new File(getFilesDir(),"sony-360ra-profile.bin");copy("test-profile.riff",profile);ProfileStore.enable(true);
            RendererTests.main(new String[]{profile.getAbsolutePath()});
            int buffer=Math.max(16384,AudioTrack.getMinBufferSize(48000,12,2));
            SoundAudioTrack track=new SoundAudioTrack(3,48000,12,2,buffer,1);
            require(track.getState()==1,"PCM16 initialized");track.play();
            byte[] pcm=new byte[4096];pcm[0]=64;
            require(track.write(pcm,0,pcm.length)==pcm.length,"PCM16 byte write");
            short[] shorts=new short[2048];shorts[0]=4096;
            require(track.write(shorts,0,shorts.length,AudioTrack.WRITE_BLOCKING)==shorts.length,"PCM16 short write");
            for(boolean direct:new boolean[]{false,true}) {
                ByteBuffer data=direct?ByteBuffer.allocateDirect(4096):ByteBuffer.allocate(4096);
                require(track.write(data,4096,0)==4096 && data.position()==4096,"PCM16 ByteBuffer write");
            }
            require(track.write(pcm,0,3)==AudioTrack.ERROR_BAD_VALUE,"unaligned frame rejected");
            track.pause();track.flush();track.release();
            SoundAudioTrack floats=new SoundAudioTrack(new AudioAttributes.Builder().setUsage(1).setContentType(2).build(),new AudioFormat.Builder().setEncoding(4).setSampleRate(48000).setChannelMask(12).build(),buffer,1,0);
            require(floats.getState()==1,"PCM float initialized");floats.play();
            float[] f=new float[2048];f[0]=.1f;
            require(floats.write(f,0,f.length,0)==f.length,"float array write");
            ByteBuffer data=ByteBuffer.allocateDirect(8192).order(ByteOrder.LITTLE_ENDIAN);data.putFloat(.1f);data.position(0);
            require(floats.write(data,8192,0)==8192,"float direct buffer write");
            floats.pause();floats.flush();floats.release();
            Log.i("SonyR1Probe","AudioTrack PCM16 and float PASS");
            testInstalledMusicEntries();
            File source=new File(getFilesDir(),"test.mhas");copy("test.mhas",source);
            File decoded=new MpeghDecoder().decode(this,Uri.fromFile(source));
            require(decoded.length()>48000*4,"MPEG-H decoded PCM");
            int peak=0;
            try(RandomAccessFile wav=new RandomAccessFile(decoded,"r")) {
                wav.seek(44);while(wav.getFilePointer()+2<=wav.length())peak=Math.max(peak,Math.abs((int)Short.reverseBytes(wav.readShort())));
            }
            require(peak>0,"native MPEG-H output must contain nonzero PCM");
            MediaPlayer playback=new MediaPlayer();
            try {playback.setDataSource(decoded.getAbsolutePath());playback.prepare();playback.start();Thread.sleep(100);require(playback.isPlaying(),"decoded MPEG-H WAV playback");}
            finally {playback.release();}
            result="PASS: ART convolution tests; AudioTrack PCM16 byte/short/heap/direct and float array/direct; installed NetEase three patched entry points; MPEG-H+BRIR bytes="+decoded.length()+" peak="+peak;
        }catch(Throwable e){result="FAIL: "+e.toString();Log.e("SonyR1Probe","probe failed",e);}
        Log.i("SonyR1Probe",result);
        try(OutputStream out=new FileOutputStream(new File(getFilesDir(),"probe-result.txt"))){out.write(result.getBytes("UTF-8"));}catch(IOException ignored){}
    }
    private void testInstalledMusicEntries() throws Exception {
        android.content.pm.ApplicationInfo info=getPackageManager().getApplicationInfo("com.netease.cloudmusic",0);
        ClassLoader loader=new InstalledLoader(info.sourceDir,getClassLoader());
        loader.loadClass("dev.soundconnect.r1.ProfileStore").getMethod("initialize",android.content.Context.class).invoke(null,this);
        Class<?> builder=loader.loadClass("com.netease.cloudmusic.media.audioplayer.NCMNativeAudioTrackBuilder");
        AudioTrack created=(AudioTrack)builder.getMethod("create",AudioAttributes.class,int.class,int.class,int.class,int.class,int.class,int.class,int.class).invoke(null,null,0,48000,12,2,16384,1,0);
        require(created.getClass().getName().equals("dev.soundconnect.r1.SoundAudioTrack"),"installed native AudioTrack builder hook");created.play();
        require(created.write(new byte[4096],0,4096)==4096,"installed builder PCM write");processed(created);created.pause();created.flush();created.release();
        Class<?> deviceClass=loader.loadClass("com.netease.cloudmusic.media.NEAudioDevice");
        Object device=deviceClass.getConstructor(int.class,int.class,int.class,int.class,int.class,int.class,long.class).newInstance(3,48000,12,2,16384,1,0L);
        java.lang.reflect.Field field=deviceClass.getDeclaredField("mInstance");field.setAccessible(true);
        require(field.get(device).getClass().getName().equals("dev.soundconnect.r1.SoundAudioTrack"),"installed NEAudioDevice constructor hook");
        deviceClass.getMethod("play").invoke(device);
        require(((Integer)deviceClass.getMethod("write",byte[].class,int.class,int.class).invoke(device,new byte[4096],0,4096))==4096,"installed NEAudioDevice PCM write");
        processed((AudioTrack)field.get(device));
        deviceClass.getMethod("pause").invoke(device);deviceClass.getMethod("flush").invoke(device);deviceClass.getMethod("release").invoke(device);
        Class<?> oldClass=loader.loadClass("com.netease.cloudmusic.media.player.nmAudioTrack");Object old=oldClass.newInstance();
        java.lang.reflect.Method open=oldClass.getDeclaredMethod("createAudioTrack",int.class,int.class);open.setAccessible(true);
        require(((Integer)open.invoke(old,48000,2))==0,"installed legacy AudioTrack open");
        field=oldClass.getDeclaredField("mAudioTrack");field.setAccessible(true);
        require(field.get(old).getClass().getName().equals("dev.soundconnect.r1.SoundAudioTrack"),"installed legacy constructor hook");
        AudioTrack legacy=(AudioTrack)field.get(old);legacy.play();require(legacy.write(new byte[4096],0,4096)==4096,"legacy PCM write");processed(legacy);legacy.pause();legacy.flush();legacy.release();
        Log.i("SonyR1Probe","Installed NetEase all three patched AudioTrack entry points PASS");
    }
    private static void processed(AudioTrack track) throws Exception {
        java.lang.reflect.Field field=track.getClass().getDeclaredField("frames");field.setAccessible(true);
        require(field.getLong(track)>0,"installed track must execute convolution");
    }
    private static final class InstalledLoader extends dalvik.system.PathClassLoader {
        InstalledLoader(String path,ClassLoader parent){super(path,parent);}
        protected synchronized Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
            if(name.startsWith("dev.soundconnect.r1.") || name.startsWith("com.netease.cloudmusic.media.")) {
                Class<?> type=findLoadedClass(name);
                if(type==null)try{type=findClass(name);}catch(ClassNotFoundException ignored){}
                if(type!=null){if(resolve)resolveClass(type);return type;}
            }
            return super.loadClass(name,resolve);
        }
    }
}
