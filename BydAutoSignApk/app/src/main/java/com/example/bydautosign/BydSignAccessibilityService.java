package com.example.bydautosign;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class BydSignAccessibilityService extends AccessibilityService {
    private static final String APP_NAME = "比亚迪";
    private static final String PREFS = "sign_in_schedule";
    private static final String KEY_PENDING_RUN = "pending_run";
    private static final long LAUNCH_TIMEOUT_MS = 20000L;
    private static final long PAGE_TIMEOUT_MS = 15000L;
    private static final long POLL_INTERVAL_MS = 500L;
    private static final String[] MINE_TEXTS = {"我的", "我"};
    private static final String[] SIGN_IN_TEXTS = {"签到", "每日签到", "立即签到", "去签到"};
    private static final String[] DONE_TEXTS = {"签到成功", "已签到", "今日已签到", "连续签到", "明天再来"};
    private static final String[] DISMISS_TEXTS = {"暂不", "暂不升级", "以后再说", "下次再说", "取消", "关闭", "我知道了"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private BroadcastReceiver runReceiver;
    private PowerManager.WakeLock wakeLock;
    private String bydPackageName;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        registerRunReceiver();
        show("比亚迪自动签到服务已开启");
        if (consumePendingRun()) {
            startSignIn();
        }
    }

    @Override
    public void onDestroy() {
        unregisterRunReceiver();
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 流程由按钮或广播触发，这里只需要保持服务可用。
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
        bydPackageName = findPackageByLabel(APP_NAME);
        if (bydPackageName == null) {
            throw new Exception("未找到名为“" + APP_NAME + "”的 APP");
        }

        show("打开“" + APP_NAME + "”");
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(bydPackageName);
        if (launchIntent == null) {
            throw new Exception("无法启动“" + APP_NAME + "”");
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launchIntent);
        waitForPackage(bydPackageName, LAUNCH_TIMEOUT_MS);
        sleep(1000L);
        closeKnownPopups();

        show("进入“我的”");
        if (!clickTexts(MINE_TEXTS, PAGE_TIMEOUT_MS, true)) {
            clickByRatio(0.88f, 0.94f);
        }
        sleep(1200L);
        closeKnownPopups();

        show("点击“签到”");
        if (!clickTexts(SIGN_IN_TEXTS, PAGE_TIMEOUT_MS, false) && !clickTopRightText(SIGN_IN_TEXTS)) {
            clickByRatio(0.88f, 0.08f);
        }

        waitForSignInResult();
        exitTargetApp();
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
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (packageName.equals(getCurrentPackage())) {
                return;
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
                return clickNode(node);
            }
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
        return nodes.get(0);
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
        return result;
    }

    private void collectByText(
            AccessibilityNodeInfo node,
            String target,
            boolean exactOnly,
            Set<AccessibilityNodeInfo> dedup,
            List<AccessibilityNodeInfo> out
    ) {
        if (node == null) {
            return;
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
            collectByText(node.getChild(i), target, exactOnly, dedup, out);
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
        AccessibilityNodeInfo current = node;
        while (current != null) {
            if (current.isClickable() && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                sleep(800L);
                return true;
            }
            current = current.getParent();
        }

        android.graphics.Rect bounds = new android.graphics.Rect();
        node.getBoundsInScreen(bounds);
        if (!bounds.isEmpty()) {
            return clickPoint(bounds.centerX(), bounds.centerY());
        }
        return false;
    }

    private void closeKnownPopups() {
        for (int i = 0; i < 3; i++) {
            AccessibilityNodeInfo node = findClickableText(DISMISS_TEXTS, false);
            if (node == null || !clickNode(node)) {
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
        return !findCandidateNodes(texts, false).isEmpty();
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
        if (root == null || root.getPackageName() == null) {
            return null;
        }
        return root.getPackageName().toString();
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
        final boolean[] completed = {false};
        dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                completed[0] = true;
            }
        }, handler);
        sleep(400L);
        return completed[0];
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
