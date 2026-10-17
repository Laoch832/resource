package dev.soundconnect.bridge;

import android.app.Activity;
import android.content.Intent;
import android.content.ComponentName;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import dev.soundconnect.r1.HkiProfile;
import dev.soundconnect.r1.ProfileStore;

/** Profile transfer and explicit controls for the independently implemented audio renderer. */
public final class BridgeActivity extends Activity {
    private TextView status;
    private final AtomicBoolean busy = new AtomicBoolean();

    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ProfileStore.initialize(this);
        setTitle("Sony 耳形音频处理 · R1 验证");
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        TextView title = new TextView(this);
        title.setTextSize(22);
        title.setText("Sound Connect → 网易云音乐");
        layout.addView(title);
        TextView description = new TextView(this);
        description.setText("导入 Sound Connect 的耳形 BRIR 后，可将网易云双声道 PCM 按前方左右音箱位置渲染为个性化双耳声音。普通双声道歌曲保持原有音源信息。使用耳机聆听；建议先关闭其他音效。原生 MPEG-H 文件使用下方独立播放入口。此构建仍在进行运行验证。");
        layout.addView(description);
        status = new TextView(this);
        status.setTextIsSelectable(true);
        status.setPadding(0, padding, 0, padding);
        File saved = new File(getFilesDir(), "sony-360ra-profile.bin");
        status.setText(saved.isFile() ? "已保存耳形参数：" + saved.length() + " 字节。耳形渲染开关：" + (ProfileStore.enabled() ? "开" : "关") + "。更改后请重新开始播放以创建新的音频输出。"
                : "参数尚未导入。请先在配套 Sound Connect 实验版中完成耳形分析。");
        layout.addView(status);
        Button importButton = new Button(this);
        importButton.setText("从 Sound Connect 导入参数");
        importButton.setOnClickListener(new View.OnClickListener() { public void onClick(View view) { importProfile(); } });
        layout.addView(importButton);
        Button enable = new Button(this);
        enable.setText("开启耳形渲染");
        enable.setOnClickListener(new View.OnClickListener() {public void onClick(View v) {setRenderer(true);}});
        layout.addView(enable);
        Button disable = new Button(this);
        disable.setText("关闭耳形渲染");
        disable.setOnClickListener(new View.OnClickListener() {public void onClick(View v) {setRenderer(false);}});
        layout.addView(disable);
        Button nativePlayer = new Button(this);
        nativePlayer.setText("播放本机 MPEG-H / 360RA 文件");
        nativePlayer.setOnClickListener(new View.OnClickListener() {public void onClick(View v) {
            startActivity(new Intent(BridgeActivity.this, dev.soundconnect.r1.MpeghPlayerActivity.class));
        }});
        layout.addView(nativePlayer);
        Button exportButton = new Button(this);
        exportButton.setText("导出已保存的耳形参数");
        exportButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                if (!new File(getFilesDir(), "sony-360ra-profile.bin").isFile()) {
                    status.setText("请先导入参数。");
                    return;
                }
                Intent export = new Intent("android.intent.action.CREATE_DOCUMENT");
                export.addCategory(Intent.CATEGORY_OPENABLE);
                export.setType("application/octet-stream");
                export.putExtra(Intent.EXTRA_TITLE, "sony-360ra-profile.bin");
                try { startActivityForResult(export, 360); }
                catch (android.content.ActivityNotFoundException e) { status.setText("此系统没有可用的文件保存界面。"); }
            }
        });
        layout.addView(exportButton);
        Button open = new Button(this);
        open.setText("打开网易云音乐");
        open.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) {
                Intent query = new Intent(Intent.ACTION_MAIN);
                query.addCategory(Intent.CATEGORY_LAUNCHER);
                query.setPackage(getPackageName());
                for (ResolveInfo candidate : getPackageManager().queryIntentActivities(query, 0)) {
                    String name = candidate.activityInfo.name;
                    if (name.startsWith("dev.soundconnect.bridge.")) continue;
                    Intent intent = new Intent(Intent.ACTION_MAIN);
                    intent.addCategory(Intent.CATEGORY_LAUNCHER);
                    intent.setComponent(new ComponentName(getPackageName(), name));
                    startActivity(intent);
                    break;
                }
            }
        });
        layout.addView(open);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        setContentView(scroll);
        Uri link = getIntent().getData();
        if (link != null) {
            if (!"netease360ra".equals(link.getScheme()) || !"immersive-audio.sony.com".equals(link.getHost())
                    || !"/start-optimize".equals(link.getPath()) || !SourceBridge.PROFILE_URI.equals(link.getQueryParameter("hrtf"))) {
                status.setText("链接不匹配本机参数桥，未读取任何数据。");
                importButton.setEnabled(false);
            } else importProfile();
        }
    }

    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 360 || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri destination = data.getData();
        if (!busy.compareAndSet(false, true)) { status.setText("导入尚未完成，请稍后再导出。"); return; }
        new Thread(new Runnable() {
            public void run() {
                String result;
                try {
                    byte[] bytes;
                    InputStream input = new FileInputStream(new File(getFilesDir(), "sony-360ra-profile.bin"));
                    try { bytes = ProfileInfo.read(input); } finally { input.close(); }
                    OutputStream output = getContentResolver().openOutputStream(destination, "w");
                    if (output == null) throw new java.io.IOException("无法打开导出位置");
                    try { output.write(bytes); output.flush(); } finally { output.close(); }
                    result = "参数已导出。SHA-256：" + ProfileInfo.sha256(bytes);
                } catch (Exception e) { result = "导出失败：" + e.getClass().getSimpleName() + "\n" + String.valueOf(e.getMessage()); }
                final String message = result;
                runOnUiThread(new Runnable() { public void run() { if (!isFinishing()) status.setText(message); busy.set(false); } });
            }
        }, "SonyProfileExport").start();
    }

    private void importProfile() {
        if (!busy.compareAndSet(false, true)) return;
        status.setText("正在读取本机耳形参数…");
        new Thread(new Runnable() {
            public void run() {
                String result;
                File temporary = new File(getFilesDir(), "sony-360ra-profile.tmp");
                try {
                    byte[] data;
                    InputStream stream = getContentResolver().openInputStream(Uri.parse(SourceBridge.PROFILE_URI));
                    if (stream == null) throw new java.io.IOException("无法读取 Sound Connect 参数");
                    try { data = ProfileInfo.read(stream); } finally { stream.close(); }
                    HkiProfile parsed = HkiProfile.parse(data);
                    FileOutputStream output = new FileOutputStream(temporary);
                    try { output.write(data); output.getFD().sync(); } finally { output.close(); }
                    File destination = new File(getFilesDir(), "sony-360ra-profile.bin");
                    if (!temporary.renameTo(destination)) throw new java.io.IOException("无法保存参数文件");
                    result = "参数已接收并保存在网易云私有目录。\n大小：" + data.length + " 字节\n容器："
                            + ProfileInfo.container(data) + "\nSHA-256：" + ProfileInfo.sha256(data)
                            + "\n采样率：" + parsed.sampleRate + "；方向数：" + parsed.directions.length + "；滤波器长度：" + parsed.taps
                            + "\n可点击开启耳形渲染，然后重新开始播放。";
                } catch (Exception e) {
                    temporary.delete();
                    result = "导入失败：" + e.getClass().getSimpleName() + "\n" + String.valueOf(e.getMessage())
                            + "\n请确认已安装同一套签名的两个 R1 安装包，并在 Sound Connect 完成耳形分析。";
                }
                final String message = result;
                runOnUiThread(new Runnable() { public void run() { if (!isFinishing()) status.setText(message); busy.set(false); } });
            }
        }, "SonyProfileImport").start();
    }

    private void setRenderer(boolean enabled) {
        if (!busy.compareAndSet(false, true)) return;
        final boolean value = enabled;
        new Thread(new Runnable() {public void run() {
            String result;
            try {ProfileStore.enable(value);result = value ? "耳形渲染已开启。请重新开始播放以创建新输出；仅处理双声道 PCM16 / 浮点 PCM。额外延迟 256 帧，保留 6 dB 音量余量。" : "耳形渲染已关闭。请重新开始播放以创建新输出。";}
            catch(Exception e) {result = "开关设置失败：" + e.getMessage();}
            final String message=result;
            runOnUiThread(new Runnable(){public void run(){if(!isFinishing())status.setText(message);busy.set(false);}});
        }},"SonyRendererToggle").start();
    }
}
