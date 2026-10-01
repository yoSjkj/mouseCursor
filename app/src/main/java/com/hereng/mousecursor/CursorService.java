package com.hereng.mousecursor;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class CursorService extends AccessibilityService {

    // 디버그창 표시 여부
    private static final boolean DEBUG = false;

    // 커서 모드를 켤 앱 패키지명
    private static final Set<String> TARGET_PACKAGES = new HashSet<>(Arrays.asList(
            "com.mintrocket.drmobile"
    ));

    // 앞에 떠도 모드를 바꾸지 않을 앱 (알림창, 키보드 등)
    private static final Set<String> IGNORED_PACKAGES = new HashSet<>(Arrays.asList(
            "com.android.systemui",
            "com.samsung.android.honeyboard"
    ));

    private static final int CURSOR_SIZE_DP = 24;    // 커서 크기
    private static final float CURSOR_ALPHA = 0.75f; // 평소 투명도
    private static final float PRESSED_SCALE = 0.8f; // 누를 때 크기 비율
    private static final long HIDE_DELAY = 3000;     // 자동 숨김까지 시간(ms)
    private static final long FADE_DURATION = 400;   // 사라지는 속도(ms)
    private static final long POLL_INTERVAL = 1000;  // 주기 확인 간격(ms)

    private static final float DRAG_THRESHOLD = 15f;
    private static final long MAX_DURATION = 10000;

    private WindowManager windowManager;
    private ImageView cursorView;
    private WindowManager.LayoutParams params;
    private int halfSize;
    private TextView statusView;

    private boolean active = false;
    private String currentPackage = "-";
    private String detectedBy = "-";
    private String lastAction = "-";

    private boolean pressing = false;
    private float downX, downY;
    private long downTime;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable hideCursor = () -> {
        if (cursorView != null && !pressing) {
            cursorView.animate().alpha(0f).setDuration(FADE_DURATION).start();
        }
    };

    private final Runnable pollForeground = new Runnable() {
        @Override
        public void run() {
            checkForeground("주기 확인");
            handler.postDelayed(this, POLL_INTERVAL);
        }
    };

    @Override
    protected void onServiceConnected() {
        windowManager = getSystemService(WindowManager.class);
        int flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;

        // 디버그창
        if (DEBUG) {
            statusView = new TextView(this);
            statusView.setTextColor(Color.WHITE);
            statusView.setBackgroundColor(0x99000000);
            statusView.setTextSize(12);
            statusView.setPadding(12, 6, 12, 6);
            WindowManager.LayoutParams statusParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    flags,
                    PixelFormat.TRANSLUCENT);
            statusParams.gravity = Gravity.TOP | Gravity.START;
            statusParams.x = 40;
            statusParams.y = 600;
            windowManager.addView(statusView, statusParams);
        }

        // 커서
        int size = (int) (CURSOR_SIZE_DP * getResources().getDisplayMetrics().density);
        halfSize = size / 2;

        cursorView = new ImageView(this);
        cursorView.setImageResource(R.drawable.cursor);
        cursorView.setAlpha(CURSOR_ALPHA);
        cursorView.setVisibility(View.GONE);

        params = new WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        windowManager.addView(cursorView, params);

        // 켜자마자 한 번 확인하고, 이후 주기 확인 시작
        checkForeground("서비스 연결");
        handler.postDelayed(pollForeground, POLL_INTERVAL);
        updateStatus();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            checkForeground("창 뜸");
        } else if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            checkForeground("창 변경");
        }
    }

    // 현재 활성 창이 어느 앱 것인지 확인해서 모드 결정 (패키지명만 읽음)
    private void checkForeground(String source) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) return;

        String pkg = root.getPackageName().toString();
        if (IGNORED_PACKAGES.contains(pkg) || pkg.equals(getPackageName())) return;

        boolean shouldBeActive = TARGET_PACKAGES.contains(pkg);
        if (!pkg.equals(currentPackage) || shouldBeActive != active) {
            currentPackage = pkg;
            detectedBy = source;
        }
        setActive(shouldBeActive);
        updateStatus();
    }

    // 게임일 때만 마우스를 가져오고, 아니면 시스템에 돌려줌
    private void setActive(boolean on) {
        if (active == on) return;
        active = on;
        setPressed(false);

        AccessibilityServiceInfo info = getServiceInfo();
        info.setMotionEventSources(on ? InputDevice.SOURCE_MOUSE : 0);
        setServiceInfo(info);

        if (cursorView != null) {
            handler.removeCallbacks(hideCursor);
            cursorView.setVisibility(on ? View.VISIBLE : View.GONE);
            if (on) showCursor();
        }
    }

    @Override
    public void onMotionEvent(MotionEvent event) {
        if (cursorView == null || !active) return;

        float x = event.getRawX();
        float y = event.getRawY();

        // 원의 중심이 실제 좌표에 오도록
        params.x = (int) x - halfSize;
        params.y = (int) y - halfSize;
        windowManager.updateViewLayout(cursorView, params);
        showCursor();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                setPressed(true);
                downX = x;
                downY = y;
                downTime = event.getEventTime();
                break;

            case MotionEvent.ACTION_UP:
                if (pressing) {
                    setPressed(false);
                    long duration = event.getEventTime() - downTime;
                    duration = Math.max(50, Math.min(duration, MAX_DURATION));
                    performTouch(downX, downY, x, y, duration);
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                setPressed(false);
                break;
        }
        updateStatus();
    }

    // 커서를 보이게 하고 자동 숨김 타이머를 다시 시작
    private void showCursor() {
        cursorView.animate().cancel();
        cursorView.setAlpha(CURSOR_ALPHA);
        handler.removeCallbacks(hideCursor);
        handler.postDelayed(hideCursor, HIDE_DELAY);
    }

    // 누름 상태 표시: 안쪽 채움 + 살짝 축소
    private void setPressed(boolean on) {
        pressing = on;
        if (cursorView == null) return;
        cursorView.setImageResource(on ? R.drawable.cursor_pressed : R.drawable.cursor);
        float scale = on ? PRESSED_SCALE : 1f;
        cursorView.setScaleX(scale);
        cursorView.setScaleY(scale);
    }

    // 누른 위치부터 뗀 위치까지, 누른 시간만큼 터치를 재현
    private void performTouch(float x1, float y1, float x2, float y2, long duration) {
        // 음수 좌표는 제스처 생성에서 예외가 나므로 화면 안으로 보정
        x1 = Math.max(0f, x1);
        y1 = Math.max(0f, y1);
        x2 = Math.max(0f, x2);
        y2 = Math.max(0f, y2);

        Path path = new Path();
        path.moveTo(x1, y1);
        boolean isDrag = Math.hypot(x2 - x1, y2 - y1) > DRAG_THRESHOLD;
        if (isDrag) {
            path.lineTo(x2, y2);
        }

        String kind = isDrag ? "드래그 " : "탭 ";
        try {
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, duration))
                    .build();
            boolean sent = dispatchGesture(gesture, null, null);
            lastAction = kind + duration + "ms" + (sent ? "" : " (전송 실패)");
        } catch (RuntimeException e) {
            // 터치 하나를 놓치더라도 서비스가 죽지 않게 함
            lastAction = kind + "실패: " + e.getMessage();
        }
    }

    private void updateStatus() {
        if (statusView == null) return;
        statusView.setText("현재 앱: " + currentPackage
                + "\n커서 모드: " + (active ? "켜짐" : "꺼짐")
                + "\n감지 경로: " + detectedBy
                + "\n마지막 동작: " + lastAction);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(hideCursor);
        handler.removeCallbacks(pollForeground);
        if (cursorView != null) {
            windowManager.removeView(cursorView);
            cursorView = null;
        }
        if (statusView != null) {
            windowManager.removeView(statusView);
            statusView = null;
        }
        super.onDestroy();
    }
}