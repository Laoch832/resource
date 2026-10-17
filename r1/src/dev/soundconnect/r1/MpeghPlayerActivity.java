package dev.soundconnect.r1;

import android.app.Activity;
import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import android.net.Uri;
import java.io.File;

/** Local native MPEG-H source playback inside the modified music application. */
public final class MpeghPlayerActivity extends Activity {
    private TextView status;
    private volatile MpeghDecoder decoder;
    private MediaPlayer player;
    private File rendered;
    private volatile boolean destroyed;
    protected void onCreate(Bundle state) {
        super.onCreate(state);ProfileStore.initialize(this);setTitle("本机 MPEG-H / 360RA 播放");
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(1);layout.setPadding(30,30,30,30);
        TextView info=new TextView(this);
        info.setText("选择你有权播放的本机 MPEG-H MHAS / MP4 文件。使用 Sound Connect 的耳形参数，经 5.1.4 空间布局渲染为双耳音频，适合耳机聆听。准备完成后播放。网易云在线曲库是否提供原生 360RA 取决于音源服务。");layout.addView(info);
        status=new TextView(this);status.setText("请先在耳形设置中导入参数。");layout.addView(status);
        Button choose=new Button(this);choose.setText("选择本机音源");
        choose.setOnClickListener(new View.OnClickListener(){public void onClick(View v){
            if(decoder!=null){status.setText("正在准备音频，请稍候或停止。");return;}
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,361);
        }});layout.addView(choose);
        Button pause=new Button(this);pause.setText("暂停 / 继续");pause.setOnClickListener(new View.OnClickListener(){public void onClick(View v){
            if(player!=null){if(player.isPlaying())player.pause();else player.start();}
        }});layout.addView(pause);
        Button stop=new Button(this);stop.setText("停止");stop.setOnClickListener(new View.OnClickListener(){public void onClick(View v){stop();status.setText("已停止。");}});layout.addView(stop);
        setContentView(layout);
    }
    protected void onActivityResult(int code,int result,Intent data) {
        super.onActivityResult(code,result,data);
        if(code!=361 || result!=RESULT_OK || data==null || data.getData()==null)return;
        stop();final Uri uri=data.getData();final MpeghDecoder job=new MpeghDecoder();decoder=job;status.setText("正在准备个性化空间音频…");
        new Thread(new Runnable(){public void run(){
            try {
                final File wav=job.decode(MpeghPlayerActivity.this,uri);
                runOnUiThread(new Runnable(){public void run(){
                    if(destroyed || decoder!=job){discard(wav);return;}
                    decoder=null;rendered=wav;
                    try {
                        player=new MediaPlayer();player.setDataSource(wav.getAbsolutePath());
                        player.setOnCompletionListener(new MediaPlayer.OnCompletionListener(){public void onCompletion(MediaPlayer p){status.setText("播放完成。");}});
                        player.prepare();player.start();status.setText("正在播放：MPEG-H → 5.1.4 → Sony 耳形 BRIR → 双耳 PCM。");
                    }catch(Exception e){stop();status.setText("播放失败："+e.getMessage());}
                }});
            } catch(final Exception e) {runOnUiThread(new Runnable(){public void run(){if(!destroyed && decoder==job){decoder=null;status.setText("准备失败："+e.getMessage());}}});}
        }},"MpeghPersonalizedDecode").start();
    }
    private static void discard(File file){if(file!=null){File parent=file.getParentFile();file.delete();parent.delete();}}
    private void stop(){MpeghDecoder job=decoder;decoder=null;if(job!=null)job.cancel();if(player!=null){player.release();player=null;}discard(rendered);rendered=null;}
    protected void onDestroy(){destroyed=true;stop();super.onDestroy();}
}
