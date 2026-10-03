package local.enco.lc3;

import android.app.Activity;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.widget.Toast;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** One root operation; the screen streams its output only for the operation's lifetime. */
public final class MainActivity extends Activity {
    private Button action;
    private Button export;
    private LogStore logStore;
    private EditText address;
    private TextView logs;
    private ScrollView scroll;
    private volatile boolean closed;
    private final AtomicBoolean running = new AtomicBoolean();
    private final StringBuilder visibleLog = new StringBuilder();
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + 0.5f); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (getActionBar() != null) getActionBar().hide();
        logStore = new LogStore(new File(getFilesDir(), "latest-lc3-log.txt"));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(24), dp(20), dp(20));
        page.setBackgroundColor(Color.rgb(247, 248, 250));
        TextView title = new TextView(this);
        title.setText("Enco X3 LC3"); title.setTextSize(24); title.setTextColor(Color.rgb(24, 32, 42));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        page.addView(title);
        TextView hint = new TextView(this);
        hint.setText("v0.20 · 公开预览版\n先正常配对 X3，并在 LSPosed 启用模块、勾选蓝牙。双耳取出后点击下方按钮，首次使用请授予 Root 权限。\n蓝牙会短暂重启，请等待流程结束。");
        hint.setTextSize(14); hint.setTextColor(Color.rgb(75, 86, 101));
        hint.setPadding(0, dp(12), 0, dp(16)); page.addView(hint);
        address = new EditText(this);
        address.setSingleLine(true); address.setTextSize(14);
        address.setHint("自动识别失败时填写主地址（可选）");
        address.setVisibility(View.GONE); page.addView(address);
        action = new Button(this);
        action.setText("一键配置并恢复 LC3");
        action.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startSetup(); }
        });
        page.addView(action, new LinearLayout.LayoutParams(-1, dp(56)));
        LinearLayout logHeader = new LinearLayout(this);
        logHeader.setOrientation(LinearLayout.HORIZONTAL);
        logHeader.setGravity(android.view.Gravity.CENTER_VERTICAL);
        logHeader.setPadding(0, dp(12), 0, dp(6));
        TextView label = new TextView(this);
        label.setText("运行日志"); label.setTextSize(14); label.setTextColor(Color.rgb(75, 86, 101));
        logHeader.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        export = new Button(this); export.setText("导出日志"); export.setTextSize(13);
        export.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { exportLog(); }
        });
        logHeader.addView(export, new LinearLayout.LayoutParams(-2, dp(48))); page.addView(logHeader);
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        logs = new TextView(this);
        logs.setTextSize(12); logs.setTypeface(Typeface.MONOSPACE);
        logs.setTextColor(Color.rgb(219, 231, 224)); logs.setBackgroundColor(Color.rgb(25, 34, 31));
        logs.setPadding(dp(12), dp(12), dp(12), dp(12)); logs.setTextIsSelectable(true);
        logs.setText("等待开始。流程结束后停止记录，日志保留在此处，可长按复制。\n");
        scroll.addView(logs); page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);
        try {
            String previous = new String(logStore.snapshot(), "UTF-8");
            if (!previous.isEmpty()) {
                visibleLog.append(previous.length() > 50000 ? previous.substring(previous.length() - 50000) : previous);
                logs.setText(visibleLog.toString());
            }
        } catch (Exception ignored) { }
    }
    private static String quote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
    private void append(final String line) {
        try { logStore.append(line + "\n"); }
        catch (Exception e) { showToast("日志写入失败：" + e.getMessage()); }
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (closed) return;
                visibleLog.append(line).append('\n');
                if (visibleLog.length() > 60000) {
                    int boundary = visibleLog.indexOf("\n", visibleLog.length() - 50000);
                    if (boundary >= 0) visibleLog.delete(0, boundary + 1);
                }
                logs.setText(visibleLog.toString());
                if (line.contains("NEEDS_ADDRESS:")) address.setVisibility(View.VISIBLE);
                scroll.post(new Runnable() { @Override public void run() { scroll.fullScroll(View.FOCUS_DOWN); } });
            }
        });
    }
    private void startSetup() {
        if (!running.compareAndSet(false, true)) return;
        final String requested = address.getText().toString().trim();
        if (!requested.isEmpty()) {
            try { TargetAddress.normalize(requested); }
            catch (IllegalArgumentException e) { running.set(false); append("主地址格式不正确，请检查后重试。"); return; }
        }
        try { logStore.reset(); }
        catch (Exception e) { running.set(false); showToast("无法创建日志：" + e.getMessage()); return; }
        visibleLog.setLength(0); logs.setText(""); action.setEnabled(false); action.setText("正在配置，请等待…");
        new Thread(new Runnable() {
            @Override public void run() {
                Process child = null;
                try {
                    File script = new File(getFilesDir(), "enco-lc3-control.sh");
                    try (InputStream input = getAssets().open("enco-lc3-control.sh"); FileOutputStream output = new FileOutputStream(script)) {
                        byte[] buffer = new byte[8192]; int count;
                        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    }
                    append("请求 Root 权限，准备开始。日志只在本次流程中读取，不会上传。");
                    child = new ProcessBuilder("su", "-c", "sh " + quote(script.getAbsolutePath()) + " setup " + quote(requested.isEmpty() ? "auto" : requested)).redirectErrorStream(true).start();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(child.getInputStream(), "UTF-8"))) {
                        String line;
                        StringBuilder pending = new StringBuilder();
                        long lastFlush = 0;
                        while ((line = reader.readLine()) != null) {
                            pending.append(line).append('\n');
                            long now = System.currentTimeMillis();
                            if (now - lastFlush >= 120 || pending.length() >= 8000) {
                                append(pending.toString()); pending.setLength(0); lastFlush = now;
                            }
                        }
                        if (pending.length() > 0) append(pending.toString());
                    }
                    int result = child.waitFor();
                    if (result == 0) append("流程完成 · 实时日志已停止。");
                    else if (result == 124 || result == 137 || result == 143) append("流程超时 · 实时日志已停止，请检查上方记录。");
                    else append("流程未完成（退出码 " + result + "）· 实时日志已停止，请检查上方原因。");
                } catch (Exception e) {
                    append("无法完成操作：" + e.getMessage() + "\n实时日志已停止，请检查 Root 授权。");
                } finally {
                    if (child != null) child.destroy();
                    running.set(false);
                    runOnUiThread(new Runnable() {
                        @Override public void run() { if (!closed) { action.setEnabled(true); action.setText("一键配置并恢复 LC3"); } }
                    });
                }
            }
        }, "EncoSetup").start();
    }
    private void showToast(final String text) {
        runOnUiThread(new Runnable() {
            @Override public void run() { if (!closed) Toast.makeText(MainActivity.this, text, Toast.LENGTH_LONG).show(); }
        });
    }
    private void exportLog() {
        if (Build.VERSION.SDK_INT < 29) {
            try {
                int granted = (Integer)getClass().getMethod("checkSelfPermission", String.class).invoke(this, "android.permission.WRITE_EXTERNAL_STORAGE");
                if (granted != PackageManager.PERMISSION_GRANTED) {
                    getClass().getMethod("requestPermissions", String[].class, int.class).invoke(this, new String[] {"android.permission.WRITE_EXTERNAL_STORAGE"}, 18);
                    return;
                }
            } catch (Exception e) { showToast("无法申请保存权限：" + e.getMessage()); return; }
        }
        export.setEnabled(false);
        final boolean inProgress = running.get();
        new Thread(new Runnable() {
            @Override public void run() {
                Uri created = null;
                File legacy = null;
                try {
                    byte[] body = logStore.snapshot();
                    if (body.length == 0) { showToast("还没有运行日志，请先执行一次配置。"); return; }
                    String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date());
                    String name = "enco-lc3-v0.20-" + stamp + ".txt";
                    byte[] header = ("Enco X3 LC3 v0.20\n导出状态：" + (inProgress ? "运行中的日志快照" : "流程已停止") + "\n\n").getBytes("UTF-8");
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentValues values = new ContentValues();
                        values.put("_display_name", name); values.put("mime_type", "text/plain");
                        values.put("relative_path", "Download/"); values.put("is_pending", 1);
                        created = getContentResolver().insert(Uri.parse("content://media/external_primary/downloads"), values);
                        if (created == null) throw new java.io.IOException("无法创建 Download 文件");
                        try (OutputStream out = getContentResolver().openOutputStream(created)) {
                            if (out == null) throw new java.io.IOException("无法写入日志文件");
                            out.write(header); out.write(body);
                        }
                        ContentValues done = new ContentValues(); done.put("is_pending", 0);
                        if (getContentResolver().update(created, done, null, null) != 1)
                            throw new java.io.IOException("无法完成日志保存");
                    } else {
                        File directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                        if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("无法创建 Download 目录");
                        File candidate = new File(directory, name);
                        if (!candidate.createNewFile()) throw new java.io.IOException("文件已存在，请重试");
                        legacy = candidate;
                        try (OutputStream out = new FileOutputStream(legacy)) { out.write(header); out.write(body); }
                    }
                    showToast("已保存：Download/" + name);
                } catch (Exception e) {
                    if (created != null) try { getContentResolver().delete(created, null, null); } catch (Exception ignored) { }
                    if (legacy != null) legacy.delete();
                    showToast("导出失败：" + e.getMessage());
                } finally {
                    runOnUiThread(new Runnable() { @Override public void run() { if (!closed) export.setEnabled(true); } });
                }
            }
        }, "EncoLogExport").start();
    }
    // The compile-only legacy API jar predates this public Activity callback.
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code == 18 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) exportLog();
        else if (code == 18) showToast("未授予保存权限，日志仍保留在应用内。");
    }
    @Override public void onBackPressed() {
        if (running.get()) { append("流程仍在进行，请等待完成。最长约 90 秒，不需要重复点击。"); return; }
        super.onBackPressed();
    }
    @Override protected void onDestroy() { closed = true; super.onDestroy(); }
}
