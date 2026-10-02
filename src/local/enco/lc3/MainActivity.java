package local.enco.lc3;

import android.app.Activity;
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
import java.util.concurrent.atomic.AtomicBoolean;

/** One root operation; the screen streams its output only for the operation's lifetime. */
public final class MainActivity extends Activity {
    private Button action;
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
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(24), dp(20), dp(20));
        page.setBackgroundColor(Color.rgb(247, 248, 250));
        TextView title = new TextView(this);
        title.setText("Enco X3 LC3"); title.setTextSize(24); title.setTextColor(Color.rgb(24, 32, 42));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        page.addView(title);
        TextView hint = new TextView(this);
        hint.setText("v0.17 · 公开预览版\n先正常配对 X3，并在 LSPosed 启用模块、勾选蓝牙。双耳取出后点击下方按钮，首次使用请授予 Root 权限。\n蓝牙会短暂重启，请等待流程结束。");
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
        TextView label = new TextView(this);
        label.setText("运行日志"); label.setTextSize(14); label.setTextColor(Color.rgb(75, 86, 101));
        label.setPadding(0, dp(20), 0, dp(8)); page.addView(label);
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        logs = new TextView(this);
        logs.setTextSize(12); logs.setTypeface(Typeface.MONOSPACE);
        logs.setTextColor(Color.rgb(219, 231, 224)); logs.setBackgroundColor(Color.rgb(25, 34, 31));
        logs.setPadding(dp(12), dp(12), dp(12), dp(12)); logs.setTextIsSelectable(true);
        logs.setText("等待开始。流程结束后停止记录，日志保留在此处，可长按复制。\n");
        scroll.addView(logs); page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);
    }
    private static String quote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
    private void append(final String line) {
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
                            pending.append(line.length() > 6000 ? line.substring(0, 6000) : line).append('\n');
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
    @Override public void onBackPressed() {
        if (running.get()) { append("流程仍在进行，请等待完成。最长约 90 秒，不需要重复点击。"); return; }
        super.onBackPressed();
    }
    @Override protected void onDestroy() { closed = true; super.onDestroy(); }
}
