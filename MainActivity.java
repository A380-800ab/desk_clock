package com.geek.clock;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class MainActivity extends Activity {

    private TextView tvTime, tvDate, tvStatus;
    private ImageView ivSettingsIcon;
    private View overlay;
    private ScrollView sidebar;
    private Switch swNetwork;
    private SeekBar sbFontSize, sbRed, sbGreen, sbBlue;
    private Button btnTimezone;
    private View colorPreview;
    private Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences sp;
    private KeyguardManager keyguardManager;

    private SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd EEEE", Locale.getDefault());

    private boolean isNetworkTime = true;
    private String selectedTimeZoneId = TimeZone.getDefault().getID();
    private long timeOffset = 0;
    private boolean isSidebarOpen = false;
    private int currentFontColor = 0xFF00FFCC;

    // 【原汁原味】的三秒隐藏齿轮 Runnable
    private final Runnable hideIconRunnable = () -> ivSettingsIcon.setVisibility(View.GONE);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        
        keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        setContentView(R.layout.activity_main);
        
        sp = getSharedPreferences("clock_config", Context.MODE_PRIVATE);

        initViews();
        setupListeners();
        loadSettings();

        tvStatus.setText("状态：同步中...");
        if (isNetworkTime) syncNetworkTime();
        else tvStatus.setText("状态：本地");

        startClock();

        // 【原汁原味】启动时显示齿轮，3秒后隐藏
        ivSettingsIcon.setVisibility(View.VISIBLE);
        handler.postDelayed(hideIconRunnable, 3000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyTouchableLogic();
        if (sp != null) loadSettings();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyTouchableLogic();
        }
    }

    // 【核心修复】：锁屏时穿透，解锁时不穿透
    private void applyTouchableLogic() {
        if (keyguardManager != null && keyguardManager.isKeyguardLocked()) {
            // 锁屏：幽灵模式，全穿透
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        } else {
            // 正常状态：实体模式，可点击
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        }
    }

    private void initViews() {
        tvTime = findViewById(R.id.tv_time);
        tvDate = findViewById(R.id.tv_date);
        tvStatus = findViewById(R.id.tv_status);
        ivSettingsIcon = findViewById(R.id.iv_settings_icon);
        overlay = findViewById(R.id.view_overlay);
        sidebar = findViewById(R.id.layout_sidebar);
        swNetwork = findViewById(R.id.sw_network);
        sbFontSize = findViewById(R.id.sb_font_size);
        btnTimezone = findViewById(R.id.btn_timezone);
        sbRed = findViewById(R.id.sb_red);
        sbGreen = findViewById(R.id.sb_green);
        sbBlue = findViewById(R.id.sb_blue);
        colorPreview = findViewById(R.id.view_color_preview);

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) sidebar.getLayoutParams();
        params.width = screenWidth / 4;
        sidebar.setLayoutParams(params);
        
        updateColorPreview();
    }

    private void setupListeners() {
        // 【原汁原味】点击屏幕显示齿轮并重置3秒倒计时
        findViewById(android.R.id.content).setOnClickListener(v -> {
            if (!isSidebarOpen) {
                ivSettingsIcon.setVisibility(View.VISIBLE);
                handler.removeCallbacks(hideIconRunnable);
                handler.postDelayed(hideIconRunnable, 3000);
            }
        });

        // 【原汁原味】点齿轮打开侧边栏
        ivSettingsIcon.setOnClickListener(v -> {
            sidebar.setVisibility(View.VISIBLE);
            overlay.setVisibility(View.VISIBLE);
            isSidebarOpen = true;
            ivSettingsIcon.setVisibility(View.GONE);
        });

        overlay.setOnClickListener(v -> {
            sidebar.setVisibility(View.GONE);
            overlay.setVisibility(View.GONE);
            isSidebarOpen = false;
        });

        swNetwork.setOnCheckedChangeListener((v, isChecked) -> {
            isNetworkTime = isChecked;
            if (isNetworkTime) {
                btnTimezone.setEnabled(true);
                btnTimezone.setAlpha(1.0f);
                syncNetworkTime();
            } else {
                btnTimezone.setEnabled(false);
                btnTimezone.setAlpha(0.4f);
                timeOffset = 0;
                selectedTimeZoneId = TimeZone.getDefault().getID(); 
                tvStatus.setText("状态：本地");
                updateDisplay();
            }
            saveSettings();
        });

        sbFontSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int screenWidth = getResources().getDisplayMetrics().widthPixels;
                float baseSize = screenWidth * 0.15f;
                float scale = progress / 50f;
                tvTime.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, baseSize * scale);
                tvDate.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, baseSize * scale * 0.3f);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) { saveSettings(); }
        });

        SeekBar.OnSeekBarChangeListener colorListener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentFontColor = Color.rgb(sbRed.getProgress(), sbGreen.getProgress(), sbBlue.getProgress());
                updateColorPreview();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) { saveSettings(); }
        };
        sbRed.setOnSeekBarChangeListener(colorListener);
        sbGreen.setOnSeekBarChangeListener(colorListener);
        sbBlue.setOnSeekBarChangeListener(colorListener);

        btnTimezone.setOnClickListener(v -> showTimezoneDialog());
    }

    private void updateColorPreview() {
        colorPreview.setBackgroundColor(currentFontColor);
        tvTime.setTextColor(currentFontColor);
        tvTime.setShadowLayer(20, 0, 0, currentFontColor);
    }

    private void startClock() {
        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000);
                    runOnUiThread(this::updateDisplay);
                } catch (InterruptedException e) { break; }
            }
        }).start();
    }

    private void updateDisplay() {
        long currentTime = System.currentTimeMillis() + (isNetworkTime ? timeOffset : 0);
        Date now = new Date(currentTime);
        String tzId = isNetworkTime ? selectedTimeZoneId : TimeZone.getDefault().getID();
        TimeZone tz = TimeZone.getTimeZone(tzId);
        timeFormat.setTimeZone(tz);
        dateFormat.setTimeZone(tz);
        tvTime.setText(timeFormat.format(now));
        tvDate.setText(dateFormat.format(now));
    }

    private void syncNetworkTime() {
        new Thread(() -> {
            boolean success = false;
            try {
                DatagramSocket socket = new DatagramSocket();
                socket.setSoTimeout(3000);
                InetAddress address = InetAddress.getByName("ntp.aliyun.com");
                byte[] buffer = new byte[48];
                buffer[0] = 0x1B;
                DatagramPacket request = new DatagramPacket(buffer, buffer.length, address, 123);
                socket.send(request);
                DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                socket.receive(response);
                socket.close();
                long ntpTime = ((long) (buffer[40] & 0xFF) << 24) | ((long) (buffer[41] & 0xFF) << 16) | ((long) (buffer[42] & 0xFF) << 8) | (buffer[43] & 0xFF);
                long ntpFraction = ((long) (buffer[44] & 0xFF) << 24) | ((long) (buffer[45] & 0xFF) << 16) | ((long) (buffer[46] & 0xFF) << 8) | (buffer[47] & 0xFF);
                long epoch = (ntpTime - 2208988800L) * 1000L + (ntpFraction * 1000L / 0x100000000L);
                timeOffset = epoch - System.currentTimeMillis();
                success = true;
            } catch (Exception ignored) {}
            if (!success) {
                try {
                    URL url = new URL("http://connect.rom.miui.com/generate_204");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("HEAD");
                    conn.setConnectTimeout(3000);
                    conn.setReadTimeout(3000);
                    conn.connect();
                    long serverDate = conn.getHeaderFieldDate("Date", 0);
                    if (serverDate != 0) {
                        timeOffset = serverDate - System.currentTimeMillis();
                        success = true;
                    }
                    conn.disconnect();
                } catch (Exception ignored) {}
            }
            final boolean successFinal = success;
            runOnUiThread(() -> {
                if (isNetworkTime) tvStatus.setText(successFinal ? "状态：联网" : "状态：本地(网络异常)");
            });
        }).start();
    }

    private void showTimezoneDialog() {
        if (!isNetworkTime) return;
        String[] allIds = TimeZone.getAvailableIDs();
        final List<String> displayList = new ArrayList<>();
        final List<String> idList = new ArrayList<>();
        for (String id : allIds) {
            if (id.contains("/") && !id.startsWith("SystemV") && !id.startsWith("US/")) {
                TimeZone tz = TimeZone.getTimeZone(id);
                int offset = tz.getRawOffset();
                int hours = Math.abs(offset / 3600000);
                int minutes = Math.abs((offset % 3600000) / 60000);
                String sign = offset >= 0 ? "+" : "-";
                String gmt = "GMT" + sign + String.format(Locale.US, "%02d", hours) + ":" + String.format(Locale.US, "%02d", minutes);
                String city = id.substring(id.indexOf("/") + 1).replace("_", " ");
                String region = id.substring(0, id.indexOf("/"));
                displayList.add(city + " (" + region + ") " + gmt);
                idList.add(id);
            }
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("选择时区");
        final ListView listView = new ListView(this);
        listView.setBackgroundColor(Color.parseColor("#111111"));
        listView.setDivider(null);
        final ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, displayList) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                tv.setTextColor(Color.WHITE);
                tv.setPadding(40, 30, 40, 30);
                return tv;
            }
        };
        listView.setAdapter(adapter);
        builder.setView(listView);
        final AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.parseColor("#111111")));
        listView.setOnItemClickListener((parent, view, position, id) -> {
            selectedTimeZoneId = idList.get(position);
            updateDisplay();
            saveSettings();
            dialog.dismiss();
        });
        dialog.show();
    }

    private void saveSettings() {
        SharedPreferences.Editor editor = sp.edit();
        editor.putInt("font_color", currentFontColor);
        editor.putInt("font_size", sbFontSize.getProgress());
        editor.putString("time_zone", selectedTimeZoneId);
        editor.putBoolean("is_network", isNetworkTime);
        editor.putInt("red", sbRed.getProgress());
        editor.putInt("green", sbGreen.getProgress());
        editor.putInt("blue", sbBlue.getProgress());
        editor.apply();
    }

    private void loadSettings() {
        currentFontColor = sp.getInt("font_color", 0xFF00FFCC);
        int fontProgress = sp.getInt("font_size", 50);
        selectedTimeZoneId = sp.getString("time_zone", TimeZone.getDefault().getID());
        isNetworkTime = sp.getBoolean("is_network", true);
        int red = sp.getInt("red", 0);
        int green = sp.getInt("green", 255);
        int blue = sp.getInt("blue", 204);

        sbRed.setProgress(red);
        sbGreen.setProgress(green);
        sbBlue.setProgress(blue);
        sbFontSize.setProgress(fontProgress);
        swNetwork.setChecked(isNetworkTime);
        
        updateColorPreview();
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        float baseSize = screenWidth * 0.15f;
        float scale = fontProgress / 50f;
        tvTime.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, baseSize * scale);
        tvDate.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, baseSize * scale * 0.3f);

        if (isNetworkTime) {
            btnTimezone.setEnabled(true);
            btnTimezone.setAlpha(1.0f);
        } else {
            btnTimezone.setEnabled(false);
            btnTimezone.setAlpha(0.4f);
        }
    }
}