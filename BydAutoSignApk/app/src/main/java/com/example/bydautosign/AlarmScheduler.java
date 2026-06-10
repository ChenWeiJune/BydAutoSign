package com.example.bydautosign;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.Calendar;

final class AlarmScheduler {
    static final String ACTION_ALARM_SIGN_IN = "com.example.bydautosign.ALARM_SIGN_IN";
    private static final String PREFS = "sign_in_schedule";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_HOUR = "hour";
    private static final String KEY_MINUTE = "minute";
    private static final String KEY_PENDING_RUN = "pending_run";
    private static final int REQUEST_CODE = 1001;

    private AlarmScheduler() {
    }

    static void saveAndSchedule(Context context, int hour, int minute) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_ENABLED, true)
                    .putInt(KEY_HOUR, hour)
                    .putInt(KEY_MINUTE, minute)
                    .apply();
            scheduleNext(context, hour, minute);
        } catch (Exception ignored) {
            // 某些 OEM ROM 可能抛出异常，静默处理
        }
    }

    static void scheduleSavedIfEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_ENABLED, false)) {
            return;
        }
        scheduleNext(context, prefs.getInt(KEY_HOUR, 8), prefs.getInt(KEY_MINUTE, 30));
    }

    static void cancel(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, false)
                .putBoolean(KEY_PENDING_RUN, false)
                .apply();
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            PendingIntent pendingIntent = buildPendingIntent(context, PendingIntent.FLAG_NO_CREATE);
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent);
            }
        }
    }

    static String describe(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_ENABLED, false)) {
            return "未设置每日自动签到";
        }
        return String.format("每日 %02d:%02d 自动签到", prefs.getInt(KEY_HOUR, 8), prefs.getInt(KEY_MINUTE, 30));
    }

    private static void scheduleNext(Context context, int hour, int minute) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }

        long triggerAtMillis = nextTriggerAtMillis(hour, minute);
        PendingIntent pendingIntent = buildPendingIntent(context, PendingIntent.FLAG_UPDATE_CURRENT);
        boolean useExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || alarmManager.canScheduleExactAlarms();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && useExact) {
            Intent showIntent = new Intent(context, MainActivity.class);
            PendingIntent showPendingIntent = PendingIntent.getActivity(
                    context,
                    REQUEST_CODE + 1,
                    showIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag()
            );
            alarmManager.setAlarmClock(
                    new AlarmManager.AlarmClockInfo(triggerAtMillis, showPendingIntent),
                    pendingIntent
            );
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
        }
    }

    private static long nextTriggerAtMillis(int hour, int minute) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        if (calendar.getTimeInMillis() <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1);
        }
        return calendar.getTimeInMillis();
    }

    private static PendingIntent buildPendingIntent(Context context, int baseFlag) {
        Intent intent = new Intent(context, SignInAlarmReceiver.class);
        intent.setAction(ACTION_ALARM_SIGN_IN);
        return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                baseFlag | immutableFlag()
        );
    }

    private static int immutableFlag() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
    }
}
