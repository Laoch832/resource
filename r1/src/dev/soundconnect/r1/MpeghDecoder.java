package dev.soundconnect.r1;

import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs the open-source MPEG-H decoder as a separate app process, without media network access. */
public final class MpeghDecoder {
    private volatile Process process;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    public void cancel() {cancelled.set(true);Process p=process;if(p!=null)p.destroy();}
    public File decode(Context context, Uri source) throws Exception {
        HkiProfile profile = ProfileStore.read();
        File work = File.createTempFile("r1-mpegh-", "", context.getCacheDir());
        if(!work.delete() || !work.mkdir())throw new IOException("Cannot create audio workspace");
        File input=new File(work,"input.mp4"), brir=new File(work,"ears.brir"), output=new File(work,"audio.wav");
        boolean success=false;
        try {
            try(InputStream in=context.getContentResolver().openInputStream(source); OutputStream out=new FileOutputStream(input)) {
                if(in==null)throw new IOException("Cannot open selected audio");
                byte[] buffer=new byte[65536];long count=0;int n;
                while((n=in.read(buffer))!=-1) {
                    if(cancelled.get())throw new InterruptedIOException("Cancelled");
                    count+=n;if(count>512L*1024*1024)throw new IOException("文件超过 512 MiB");
                    out.write(buffer,0,n);
                }
                if(count==0)throw new IOException("音源文件为空");
            }
            try(OutputStream out=new FileOutputStream(brir)) {MpeghBrir.write(profile,out);}
            File executable=new File(context.getApplicationInfo().nativeLibraryDir,"libr1mpegh_exec.so");
            if(!executable.isFile())throw new IOException("MPEG-H 解码器未安装到本机 ABI 目录");
            ProcessBuilder command=new ProcessBuilder(executable.getAbsolutePath(),"-ifile:"+input.getAbsolutePath(),"-ofile:"+output.getAbsolutePath(),"-ibrir:"+brir.getAbsolutePath(),"-cicp:16");
            command.directory(work);command.redirectErrorStream(true);
            if(cancelled.get())throw new InterruptedIOException("Cancelled");
            final Process decoder=command.start();process=decoder;
            final ByteArrayOutputStream log=new ByteArrayOutputStream();
            Thread drain=new Thread(new Runnable(){public void run(){
                try(InputStream stream=decoder.getInputStream()) {
                    byte[] bytes=new byte[4096];int n;
                    while((n=stream.read(bytes))!=-1)if(log.size()<65536)log.write(bytes,0,Math.min(n,65536-log.size()));
                }catch(IOException ignored){}
            }},"MpeghDecoderLog");drain.setDaemon(true);drain.start();
            long deadline=System.nanoTime()+300000000000L;int result;
            while(true) {
                if(cancelled.get())throw new InterruptedIOException("Cancelled");
                if(System.nanoTime()>deadline)throw new IOException("音源准备超过 5 分钟");
                if(output.length()>1024L*1024*1024)throw new IOException("解码音频超过 1 GiB");
                try {result=decoder.exitValue();break;}catch(IllegalThreadStateException running){Thread.sleep(100);}
            }
            drain.join(2000);
            if(result!=0)throw new IOException("MPEG-H 解码器返回错误："+result);
            validateWav(output);
            success=true;return output;
        } finally {
            Process decoder=process;if(decoder!=null)decoder.destroy();process=null;
            input.delete();brir.delete();
            if(!success){output.delete();work.delete();}
        }
    }
    private static void validateWav(File file) throws IOException {
        if(!file.isFile() || file.length()<44)throw new IOException("未生成可播放音频；请选择未加密的 MPEG-H MHAS / MP4 文件");
        try(RandomAccessFile in=new RandomAccessFile(file,"r")) {
            if(in.readInt()!=0x52494646)throw new IOException("Invalid decoded RIFF");
            long riff=Integer.reverseBytes(in.readInt())&0xffffffffL;
            if(in.readInt()!=0x57415645 || riff+8!=file.length())throw new IOException("Incomplete decoded WAV");
            boolean format=false,data=false;
            while(in.getFilePointer()+8<=file.length()) {
                int id=in.readInt();long size=Integer.reverseBytes(in.readInt())&0xffffffffL,end=in.getFilePointer()+size;
                if(end>file.length())throw new IOException("Truncated decoded WAV");
                if(id==0x666d7420) {
                    if(size<16 || Short.reverseBytes(in.readShort())!=1 || Short.reverseBytes(in.readShort())!=2 || Integer.reverseBytes(in.readInt())!=48000)throw new IOException("Unexpected decoder PCM format");
                    in.skipBytes(6);if(Short.reverseBytes(in.readShort())!=16)throw new IOException("Unexpected decoder sample width");format=true;
                }
                if(id==0x64617461 && size>0 && size%4==0)data=true;
                in.seek(end+(size&1));
            }
            if(!format || !data)throw new IOException("MPEG-H 码流不受支持或解码失败");
        }
    }
}
