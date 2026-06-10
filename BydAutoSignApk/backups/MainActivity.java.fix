package com.example.bydautosign;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

public class MainActivity extends Activity {
    static final String ACTION_RUN_SIGN_IN = "com.example.bydautosign.RUN_SIGN_IN";
    private TextView scheduleStatus;
    private TimePicker timePicker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int padding = dp(24);
        root.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("比亚迪自动签到");
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);
        root.addView(title, fullWidth());

        TextView desc = new TextView(this);
        desc.setText("先开启无障碍服务，然后可以立即签到，或设置每天自动签到时间。");
        desc.setTextSize(16);
        desc.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams descParams = fullWidth();
        descParams.setMargins(0, dp(18), 0, dp(24));
        root.addView(desc, descParams);

        Button settingsButton = new Button(this);
        settingsButton.setText("开启无障碍服务");
        settingsButton.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });
        root.addView(settingsButton, fullWidth());

        Button runButton = new Button(this);
        runButton.setText("立即签到");
        runButton.setOnClickListener(v -> {
            Intent intent = new Intent(ACTION_RUN_SIGN_IN);
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
            Toast.makeText(this, "已发送签到指令，请确认无障碍服务已开启", Toast.LENGTH_LONG).show();
        });
        LinearLayout.LayoutParams runParams = fullWidth();
        runParams.setMargins(0, dp(12), 0, 0);
        root.addView(runButton, runParams);

        timePicker = new TimePicker(this);
        timePicker.setIs24HourView(true);
        setTimePickerValue(timePicker, 8, 30);
        LinearLayout.LayoutParams pickerParams = fullWidth();
        pickerParams.setMargins(0, dp(18), 0, 0);
        root.addView(timePicker, pickerParams);

        Button scheduleButton = new Button(this);
        scheduleButton.setText("保存每日自动签到时间");
        scheduleButton.setOnClickListener(v -> {
            int hour = getTimePickerHour(timePicker);
            int minute = getTimePickerMinute(timePicker);
            AlarmScheduler.saveAndSchedule(this, hour, minute);
            updateScheduleStatus();
            Toast.makeText(this, "已设置每日自动签到", Toast.LENGTH_LONG).show();
        });
        LinearLayout.LayoutParams scheduleParams = fullWidth();
        scheduleParams.setMargins(0, dp(12), 0, 0);
        root.addView(scheduleButton, scheduleParams);

        Button cancelScheduleButton = new Button(this);
        cancelScheduleButton.setText("取消每日自动签到");
        cancelScheduleButton.setOnClickListener(v -> {
            AlarmScheduler.cancel(this);
            updateScheduleStatus();
            Toast.makeText(this, "已取消每日自动签到", Toast.LENGTH_LONG).show();
        });
        LinearLayout.LayoutParams cancelParams = fullWidth();
        cancelParams.setMargins(0, dp(12), 0, 0);
        root.addView(cancelScheduleButton, cancelParams);

        scheduleStatus = new TextView(this);
        scheduleStatus.setTextSize(14);
        scheduleStatus.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = fullWidth();
        statusParams.setMargins(0, dp(14), 0, 0);
        root.addView(scheduleStatus, statusParams);
        updateScheduleStatus();

        TextView note = new TextView(this);
        note.setText("说明：安卓系统要求你手动授权无障碍权限，APK 才能点击其他 APP。部分手机还需要允许后台运行和自启动。");
        note.setTextSize(13);
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams noteParams = fullWidth();
        noteParams.setMargins(0, dp(18), 0, 0);
        root.addView(note, noteParams);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(root);
        setContentView(scrollView);
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void updateScheduleStatus() {
        if (scheduleStatus != null) {
            scheduleStatus.setText(AlarmScheduler.describe(this));
        }
    }

    @SuppressWarnings("deprecation")
    private void setTimePickerValue(TimePicker picker, int hour, int minute) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            picker.setHour(hour);
            picker.setMinute(minute);
        } else {
            picker.setCurrentHour(hour);
            picker.setCurrentMinute(minute);
        }
    }

    @SuppressWarnings("deprecation")
    private int getTimePickerHour(TimePicker picker) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            return picker.getHour();
        }
        return picker.getCurrentHour();
    }

    @SuppressWarnings("deprecation")
    private int getTimePickerMinute(TimePicker picker) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            return picker.getMinute();
        }
        return picker.getCurrentMinute();
    }
}
