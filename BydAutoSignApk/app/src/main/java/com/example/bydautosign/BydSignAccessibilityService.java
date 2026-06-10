package com.example.bydautosign;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.app.KeyguardManager;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class BydSignAccessibilityService extends AccessibilityService {
    private static final String APP_NAME = "比亚迪";
    private static final String PREFS = "sign_in_schedule";
    private static final String KEY_PENDING_RUN = "pending_run";
    private static final long LAUNCH_TIMEOUT_MS = 30000L;
    private static final long WAKE_WARMUP_MS = 2000L;
    private static final long PAGE_TIMEOUT_MS = 15000L;
    private static final long POLL_INTERVAL_MS = 500L;
    private static final String[] MINE_TEXTS = {"我的", "我"};
    private static final String[] SIGN_IN_TEXTS = {"签到", "每日签到", "立即签到", "去签到"};
    private static final String[] DONE_TEXTS = {"签到成功", "已签到", "今日已签到", "连续签到", "明天再来"};
    private static final String[] DISMISS_TEXTS = {"暂不", "暂不升级", "以后再说", "下次再说", "取消", "关闭", "我知道了"};
    private static final String NOTIFICATION_CHANNEL_ID = "byd_auto_sign_channel";
    private static final int NOTIFICATION_ID = 1001;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private BroadcastReceiver runReceiver;
    private PowerManager.WakeLock wakeLock;
    private String bydPackageName;
    private String cachedBydPackageName;
    private long cachedPackageNameTime;
    private static final long PACKAGE_CACHE_TTL_MS = 600000L;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        startForegroundNotification();
        registerRunReceiver();
        show("比亚迪自动签到服务已开启");
        if (consumePendingRun()) {
            startSignIn();
        }
    }

    private void startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "签到服务",
                    NotificationManager.IMPORTANCE_MIN
            );
            channel.setDescription("保持签到服务后台运行");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, openIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE : 0
        );
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        Notification notification = builder
                .setContentTitle("比亚迪自动签到")
                .setContentText("签到服务运行中")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, notification);
    }

    @Override
    public void onDestroy() {
        unregisterRunReceiver();
        releaseWakeLock();
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
        show("比亚迪自动签到服务被中断");
    }

    private void registerRunReceiver() {
        if (runReceiver != null) {
            return;
        }
        runReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (MainActivity.ACTION_RUN_SIGN_IN.equals(intent.getAction())) {
                    clearPendingRun();
                    startSignIn();
                }
            }
        };
        IntentFilter filter = new IntentFilter(MainActivity.ACTION_RUN_SIGN_IN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(runReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(runReceiver, filter);
        }
    }

    private void unregisterRunReceiver() {
        if (runReceiver == null) {
            return;
        }
        try {
            unregisterReceiver(runReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        runReceiver = null;
    }

    private void startSignIn() {
        if (running) {
            show("签到流程正在执行");
            return;
        }
        running = true;
        new Thread(() -> {
            try {
                keepScreenAwake();
                runSignInFlow();
                show("签到流程完成");
            } catch (Exception e) {
                show("执行失败：" + e.getMessage());
                exitTargetApp();
            } finally {
                releaseWakeLock();
                running = false;
            }
        }, "byd-sign-in").start();
    }

    private boolean consumePendingRun() {
        boolean pending = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_PENDING_RUN, false);
        if (pending) {
            clearPendingRun();
        }
        return pending;
    }

    private void clearPendingRun() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PENDING_RUN, false)
                .apply();
    }

    private void runSignInFlow() throws Exception {
        bydPackageName = resolveBydPackageName();
        if (bydPackageName == null) {
            throw new Exception("未找到名为" + APP_NAME + "的 APP");
        }

        show("打开" + APP_NAME + "");
        ensureAppForeground();

        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(bydPackageName);
        if (launchIntent == null) {
            throw new Exception("无法启动" + APP_NAME + "");
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(launchIntent);
        waitForPackage(bydPackageName, LAUNCH_TIMEOUT_MS);
        sleep(1000L);
        closeKnownPopups();

        show("进入我的");
        if (!clickTexts(MINE_TEXTS, PAGE_TIMEOUT_MS, true)) {
            clickByRatio(0.88f, 0.94f);
        }
        sleep(1200L);
        closeKnownPopups();

        show("点击签到");
        if (!clickTexts(SIGN_IN_TEXTS, PAGE_TIMEOUT_MS, false) && !clickTopRightText(SIGN_IN_TEXTS)) {
            clickByRatio(0.88f, 0.08f);
        }

        waitForSignInResult();
        exitTargetApp();
    }

    private String resolveBydPackageName() {
        if (cachedBydPackageName != null
                && System.currentTimeMillis() - cachedPackageNameTime < PACKAGE_CACHE_TTL_MS) {
            return cachedBydPackageName;
        }
        String pkg = findPackageByLabel(APP_NAME);
        if (pkg != null) {
            cachedBydPackageName = pkg;
            cachedPackageNameTime = System.currentTimeMillis();
        }
        return pkg;
    }

    private void ensureAppForeground() {
        // 1) 唤醒屏幕并尝试解除锁屏
        try {
            KeyguardManager keyguardManager = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (keyguardManager != null && keyguardManager.isKeyguardLocked()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    performGlobalAction(GLOBAL_ACTION_DISMISS_KEYGUARD);
                    sleep(1000L);
                }
            }
        } catch (Exception ignored) {
        }

        if (getRootInActiveWindow() == null) {
            sleep(WAKE_WARMUP_MS);
        }

        // 2) 确保 APP 在前台，绕过后台 Activity 启动限制
        String currentPkg = getCurrentPackage();
        if (currentPkg != null && getPackageName().equals(currentPkg)) {
            return;
        }

        Intent mainIntent = new Intent(this, MainActivity.class);
        mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(mainIntent);

        long end = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < end) {
            if (getPackageName().equals(getCurrentPackage())) {
                return;
            }
            sleep(POLL_INTERVAL_MS);
        }
        show("未检测到 APP 前台，继续执行...");
    }

    private String findPackageByLabel(String label) {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        String fuzzyMatch = null;
        for (ApplicationInfo info : apps) {
            CharSequence appLabel = pm.getApplicationLabel(info);
            if (appLabel == null) {
                continue;
            }
            String name = appLabel.toString();
            if (label.equals(name)) {
                return info.packageName;
            }
            if (fuzzyMatch == null && name.contains(label)) {
                fuzzyMatch = info.packageName;
            }
        }
        return fuzzyMatch;
    }

    private void waitForPackage(String packageName, long timeoutMs) throws Exception {
        int consecutiveNullCount = 0;
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            String currentPkg = getCurrentPackage();
            if (packageName.equals(currentPkg)) {
                return;
            }
            if (currentPkg == null) {
                consecutiveNullCount++;
                if (consecutiveNullCount >= 6) {
                    ensureAppForeground();
                    consecutiveNullCount = 0;
                }
            } else {
                consecutiveNullCount = 0;
            }
            sleep(POLL_INTERVAL_MS);
        }
        throw new Exception("等待 APP 启动超时");
    }

    private boolean clickTexts(String[] texts, long timeoutMs, boolean preferBottom) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            AccessibilityNodeInfo node = findClickableText(texts, preferBottom);
            if (node != null && clickNode(node)) {
                return true;
            }
            sleep(POLL_INTERVAL_MS);
        }
        return false;
    }

    private boolean clickTopRightText(String[] texts) {
        List<AccessibilityNodeInfo> nodes = findCandidateNodes(texts, true);
        if (nodes.isEmpty()) {
            nodes = findCandidateNodes(texts, false);
        }
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        android.graphics.Rect bounds = new android.graphics.Rect();
        for (AccessibilityNodeInfo node : nodes) {
            node.getBoundsInScreen(bounds);
            if (bounds.centerX() > width * 0.55f && bounds.centerY() < height * 0.25f) {
                for (AccessibilityNodeInfo other : nodes) {
                    if (other != node) other.recycle();
                }
                return clickNode(node);
            }
        }
        for (AccessibilityNodeInfo node : nodes) {
            node.recycle();
        }
        return false;
    }

    private AccessibilityNodeInfo findClickableText(String[] texts, boolean preferBottom) {
        List<AccessibilityNodeInfo> nodes = findCandidateNodes(texts, true);
        if (nodes.isEmpty()) {
            nodes = findCandidateNodes(texts, false);
        }
        if (nodes.isEmpty()) {
            return null;
        }
        android.graphics.Rect a = new android.graphics.Rect();
        android.graphics.Rect b = new android.graphics.Rect();
        nodes.sort((left, right) -> {
            left.getBoundsInScreen(a);
            right.getBoundsInScreen(b);
            return preferBottom ? Integer.compare(b.centerY(), a.centerY()) : Integer.compare(a.top, b.top);
        });
        AccessibilityNodeInfo result = nodes.get(0);
        for (int i = 1; i < nodes.size(); i++) {
            nodes.get(i).recycle();
        }
        return result;
    }

    private List<AccessibilityNodeInfo> findCandidateNodes(String[] texts, boolean exactOnly) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        List<AccessibilityNodeInfo> result = new ArrayList<>();
        if (root == null) {
            return result;
        }
        Set<AccessibilityNodeInfo> dedup = new HashSet<>();
        for (String text : texts) {
            collectByText(root, text, exactOnly, dedup, result);
        }
        root.recycle();
        return result;
    }

    private void collectByText(
            AccessibilityNodeInfo root,
            String target,
            boolean exactOnly,
            Set<AccessibilityNodeInfo> dedup,
            List<AccessibilityNodeInfo> out
    ) {
        Deque<AccessibilityNodeInfo> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            AccessibilityNodeInfo node = stack.pop();
            if (node == null) {
                continue;
            }
            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();
            if (matches(text, target, exactOnly) || matches(desc, target, exactOnly)) {
                android.graphics.Rect bounds = new android.graphics.Rect();
                node.getBoundsInScreen(bounds);
                if (!bounds.isEmpty() && dedup.add(node)) {
                    out.add(node);
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    stack.push(child);
                }
            }
        }
    }

    private boolean matches(CharSequence source, String target, boolean exactOnly) {
        if (source == null) {
            return false;
        }
        String value = source.toString();
        return exactOnly ? target.equals(value) : value.contains(target);
    }

    private boolean clickNode(AccessibilityNodeInfo node) {
        android.graphics.Rect nodeBounds = new android.graphics.Rect();
        node.getBoundsInScreen(nodeBounds);

        AccessibilityNodeInfo current = node;
        AccessibilityNodeInfo previous = null;
        try {
            while (current != null) {
                if (current.isClickable() && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    sleep(800L);
                    return true;
                }
                if (previous != null) {
                    previous.recycle();
                }
                previous = current;
                current = current.getParent();
            }
            if (previous != null) {
                previous.recycle();
            }

            if (!nodeBounds.isEmpty()) {
                return clickPoint(nodeBounds.centerX(), nodeBounds.centerY());
            }
            return false;
        } finally {
            node.recycle();
        }
    }

    private void closeKnownPopups() {
        for (int i = 0; i < 3; i++) {
            AccessibilityNodeInfo node = findClickableText(DISMISS_TEXTS, false);
            if (node == null) {
                return;
            }
            boolean clicked = clickNode(node);
            if (!clicked) {
                return;
            }
            sleep(700L);
        }
    }

    private void waitForSignInResult() {
        long end = System.currentTimeMillis() + PAGE_TIMEOUT_MS;
        while (System.currentTimeMillis() < end) {
            if (existsAnyText(DONE_TEXTS)) {
                show("检测到签到完成");
                sleep(3000L);
                return;
            }
            sleep(POLL_INTERVAL_MS);
        }
        show("未读取到完成提示，可能已完成");
        sleep(3000L);
    }

    private boolean existsAnyText(String[] texts) {
        List<AccessibilityNodeInfo> nodes = findCandidateNodes(texts, false);
        boolean found = !nodes.isEmpty();
        for (AccessibilityNodeInfo node : nodes) {
            node.recycle();
        }
        return found;
    }

    private void exitTargetApp() {
        for (int i = 0; i < 5; i++) {
            if (bydPackageName == null || !bydPackageName.equals(getCurrentPackage())) {
                break;
            }
            performGlobalAction(GLOBAL_ACTION_BACK);
            sleep(700L);
        }
        performGlobalAction(GLOBAL_ACTION_HOME);
    }

    private String getCurrentPackage() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        try {
            if (root == null || root.getPackageName() == null) {
                return null;
            }
            return root.getPackageName().toString();
        } finally {
            if (root != null) {
                root.recycle();
            }
        }
    }

    private boolean clickByRatio(float xRatio, float yRatio) {
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        return clickPoint(Math.round(width * xRatio), Math.round(height * yRatio));
    }

    private boolean clickPoint(int x, int y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false;
        }
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0L, 80L);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] success = {false};
        dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                success[0] = true;
                latch.countDown();
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                latch.countDown();
            }
        }, handler);
        try {
            latch.await(1500L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return success[0];
    }

    private void keepScreenAwake() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            if (powerManager == null) {
                return;
            }
            wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "BydAutoSign:WakeLock"
            );
            wakeLock.acquire(120000L);
        } catch (Exception ignored) {
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
    }

    private void show(String message) {
        handler.post(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
