package com.example.bydautosign;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class SignInAlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!AlarmScheduler.ACTION_ALARM_SIGN_IN.equals(intent.getAction())) {
            return;
        }

        context.getSharedPreferences("sign_in_schedule", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("pending_run", true)
                .apply();

        Intent runIntent = new Intent(MainActivity.ACTION_RUN_SIGN_IN);
        runIntent.setPackage(context.getPackageName());
        context.sendBroadcast(runIntent);

        AlarmScheduler.scheduleSavedIfEnabled(context);
    }
}
