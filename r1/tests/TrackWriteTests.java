package dev.soundconnect.r1;

import android.content.Context;
import android.media.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

public final class TrackWriteTests {
    private static int checks;
    private static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
    public static void main(String[] args) throws Exception {
        File directory=Files.createTempDirectory("r1-track-test").toFile();
        Files.copy(Paths.get(args[0]),new File(directory,"sony-360ra-profile.bin").toPath());
        ProfileStore.initialize(new Context(directory));ProfileStore.enable(true);
        HkiProfile profile=ProfileStore.read();Random random=new Random(42);
        for(int encoding:new int[]{2,4}) for(int kind:new int[]{0,1,2,3}) {
            int width=encoding==4?4:2,count=13000;
            float[] values=new float[count*2];byte[] bytes=new byte[count*2*width];
            ByteBuffer raw=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            for(int i=0;i<values.length;i++) {
                if(encoding==4){values[i]=(random.nextFloat()-.5f)*.5f;raw.putFloat(values[i]);}
                else{short value=(short)random.nextInt(16384);values[i]=value/32768f;raw.putShort(value);}
            }
            byte[] originalBytes=bytes.clone();float[] originalValues=values.clone();
            float[] filtered=new float[values.length];
            new PartitionedConvolver(ProfileFilters.stereo(profile,48000)).process(values,0,filtered,0,count);
            ByteBuffer expected=ByteBuffer.allocate(bytes.length).order(ByteOrder.LITTLE_ENDIAN);
            for(float v:filtered){v=Math.max(-1,Math.min(1,v*.5f));if(encoding==4)expected.putFloat(v);else expected.putShort((short)Math.max(-32768,Math.min(32767,Math.round(v*32768f))));}
            SoundAudioTrack track=new SoundAudioTrack(new AudioAttributes(),new AudioFormat.Builder().setEncoding(encoding).setSampleRate(48000).setChannelMask(12).build(),16384,1,0);
            ByteBuffer buffer=kind==2?ByteBuffer.allocateDirect(bytes.length):ByteBuffer.allocate(bytes.length);buffer.put(bytes);buffer.flip();
            short[] shorts=new short[values.length];for(int i=0;i<shorts.length;i++)shorts[i]=(short)Math.round(values[i]*32768);
            int sent=0,iterations=0;
            while(sent<bytes.length) {
                if(++iterations>10000)throw new AssertionError("write stalled");
                int result;
                if(kind==0)result=track.write(bytes,sent,bytes.length-sent,1);
                else if(kind==1)result=encoding==4?track.write(values,sent/4,(bytes.length-sent)/4,1)*4:track.write(shorts,sent/2,(bytes.length-sent)/2,1)*2;
                else result=track.write(buffer,buffer.remaining(),1);
                check(result>=0,"valid partial write");sent+=result;
            }
            for(int i=0;i<10000 && track.captured.size()<bytes.length;i++)track.write(new byte[0],0,0,1);
            check(Arrays.equals(expected.array(),track.captured.toByteArray()),"partial/retry PCM exact encoding="+encoding+" kind="+kind);
            check(Arrays.equals(bytes,originalBytes) && Arrays.equals(values,originalValues),"caller PCM unchanged");
            track.flush();track.zeroEvery=0;track.acceptLimit=16384;
            check(track.write(bytes,0,bytes.length,0)==bytes.length,"blocking accepted");
            check(Arrays.equals(expected.array(),track.captured.toByteArray()),"flush clears pending and FIR history");
        }
        SoundAudioTrack failed=new SoundAudioTrack(3,48000,12,2,16384,1);failed.sinkError=-6;
        check(failed.write(new byte[1024],0,1024,1)==1024,"accepted input retained on sink error");
        check(failed.write(new byte[1024],0,1024,1)==-6,"sink error propagated before accepting more");
        ProfileStore.enable(false);
        SoundAudioTrack disabled=new SoundAudioTrack(3,48000,12,2,16384,1);
        byte[] dry={1,2,3,4};disabled.write(dry,0,dry.length,0);
        check(Arrays.equals(dry,disabled.captured.toByteArray()),"disabled leaves original PCM");
        System.out.println("PASS "+checks+" transport checks across partial PCM16/float writes, retries, errors and flush");
    }
}
