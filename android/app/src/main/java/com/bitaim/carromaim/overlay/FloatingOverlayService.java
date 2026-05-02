package com.bitaim.carromaim.overlay;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.bitaim.carromaim.MainActivity;
import com.bitaim.carromaim.R;
import com.bitaim.carromaim.auto.AutoShootService;
import com.bitaim.carromaim.cv.CarromAI;
import com.bitaim.carromaim.cv.Coin;
import com.bitaim.carromaim.cv.GameState;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FloatingOverlayService — v11.0 eSports Edition
 *
 * Changes vs v10.0 / v8.2:
 *
 *  § showTogglePopup — FULL eSports redesign
 *    - Dark #060A14 background with neon-blue top border strip
 *    - "CARROM BOT" title, status dots (green = ok, red = off)
 *    - Neon-bordered action buttons with separator lines
 *    - Accessibility CTA styled in cyan instead of orange
 *    - Auto-dismiss fixed runnable still in place (no regression)
 *
 *  § buildNotification — updated to v11.0 strings
 *
 *  § dismissPopup — uses named Runnable (BUG FIX from v8.2, preserved)
 *
 *  § All AutoPlay logic unchanged from v8.2 / v10.0
 */
public class FloatingOverlayService extends Service {

    private static final String TAG        = "FloatingOverlayService";
    private static final String CHANNEL_ID = "aimxassist_channel";
    private static final int    NOTIF_ID   = 1001;

    private static final int   STABLE_FRAMES_NEEDED = 6;
    private static final int   PREFETCH_FRAMES      = 2;
    private static final float STABLE_THRESH_PX     = 40f;
    private static final long  SHOOT_COOLDOWN_MS    = 1800L;

    public static volatile FloatingOverlayService INSTANCE;

    private WindowManager  windowManager;
    private View           floatingBtnView;
    private AimOverlayView aimOverlayView;
    private View           popupView;

    private WindowManager.LayoutParams floatingBtnParams;
    private WindowManager.LayoutParams overlayParams;
    private WindowManager.LayoutParams popupParams;

    private float touchStartX, touchStartY;
    private int   viewStartX,  viewStartY;
    private long  touchDownMs;

    private boolean overlayVisible = true;
    private boolean popupShowing   = false;

    private volatile boolean autoPlayEnabled = false;
    private volatile GameState lastState     = null;
    private int     stableFrames    = 0;
    private float   lastStrikerX    = Float.NaN;
    private float   lastStrikerY    = Float.NaN;
    private long    lastShootTimeMs = 0L;
    private int     autoPlayDelayMs = 1800;

    private final ExecutorService physicsThread = Executors.newSingleThreadExecutor();
    private volatile Future<?>    physicsFuture;
    private volatile CarromAI.AiShot precomputedShot;
    private volatile GameState       precomputedState;
    private final AtomicBoolean      computing = new AtomicBoolean(false);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private float dp;
    private int   screenWidth;

    private final Runnable dismissPopupRunnable = this::dismissPopup;

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        dp = getResources().getDisplayMetrics().density;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenWidth = dm.widthPixels;
        createNotificationChannel();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, buildNotification());
        }
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        setupAimOverlay();
        setupFloatingButton();
        mainHandler.postDelayed(this::injectDemoState, 400);
    }

    // ── Demo state ────────────────────────────────────────────────────────────

    private void injectDemoState() {
        if (aimOverlayView == null) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = dm.widthPixels, h = dm.heightPixels;
        GameState s = new GameState();

        float uiTop   = h * 0.14f;
        float uiBot   = h * 0.10f;
        float usableH = h - uiTop - uiBot;
        float side    = w * 0.94f;
        side = Math.min(side, usableH * 0.96f);
        float cx = w / 2f;
        float cy = uiTop + usableH * 0.50f;

        s.board = new RectF(cx-side/2f, cy-side/2f, cx+side/2f, cy+side/2f);
        float r = side * 0.022f;
        float strikerY = s.board.top + side * 0.800f;
        s.striker = new Coin(cx, strikerY, r * 1.28f, Coin.COLOR_STRIKER, true);

        float[][] coins = {
            {cx,              cy,              Coin.COLOR_RED},
            {cx-side*.08f,    cy-side*.10f,    Coin.COLOR_WHITE},
            {cx+side*.08f,    cy-side*.10f,    Coin.COLOR_WHITE},
            {cx-side*.16f,    cy-side*.05f,    Coin.COLOR_WHITE},
            {cx+side*.16f,    cy-side*.05f,    Coin.COLOR_WHITE},
            {cx,              cy-side*.17f,    Coin.COLOR_WHITE},
            {cx-side*.08f,    cy+side*.10f,    Coin.COLOR_WHITE},
            {cx+side*.08f,    cy+side*.10f,    Coin.COLOR_WHITE},
            {cx,              cy+side*.17f,    Coin.COLOR_WHITE},
            {cx-side*.16f,    cy+side*.05f,    Coin.COLOR_WHITE},
            {cx+side*.16f,    cy+side*.05f,    Coin.COLOR_BLACK},
            {cx-side*.24f,    cy-side*.12f,    Coin.COLOR_BLACK},
            {cx+side*.24f,    cy-side*.12f,    Coin.COLOR_BLACK},
            {cx-side*.24f,    cy+side*.12f,    Coin.COLOR_BLACK},
            {cx+side*.24f,    cy+side*.12f,    Coin.COLOR_BLACK},
            {cx,              cy+side*.26f,    Coin.COLOR_BLACK},
            {cx-side*.14f,    cy+side*.26f,    Coin.COLOR_BLACK},
            {cx+side*.14f,    cy+side*.26f,    Coin.COLOR_BLACK},
            {cx,              cy-side*.26f,    Coin.COLOR_BLACK},
        };
        for (float[] c : coins)
            s.coins.add(new Coin(c[0], c[1], r, (int)c[2], false));

        float inset = side * 0.060f;
        s.pockets.add(new PointF(s.board.left  + inset, s.board.top    + inset));
        s.pockets.add(new PointF(s.board.right - inset, s.board.top    + inset));
        s.pockets.add(new PointF(s.board.left  + inset, s.board.bottom - inset));
        s.pockets.add(new PointF(s.board.right - inset, s.board.bottom - inset));

        aimOverlayView.setDemoState(s);
    }

    // ── Floating button ───────────────────────────────────────────────────────

    private void setupFloatingButton() {
        floatingBtnView = LayoutInflater.from(this)
                .inflate(R.layout.view_floating_button, null);
        floatingBtnParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        floatingBtnParams.gravity = Gravity.TOP | Gravity.START;
        floatingBtnParams.x = 16;
        floatingBtnParams.y = 280;

        floatingBtnView.setOnTouchListener(new View.OnTouchListener() {
            boolean wasDrag;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        touchStartX = e.getRawX();
                        touchStartY = e.getRawY();
                        viewStartX  = floatingBtnParams.x;
                        viewStartY  = floatingBtnParams.y;
                        touchDownMs = System.currentTimeMillis();
                        wasDrag = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - touchStartX;
                        float dy = e.getRawY() - touchStartY;
                        if (Math.abs(dx) > 8 || Math.abs(dy) > 8) wasDrag = true;
                        floatingBtnParams.x = (int)(viewStartX + dx);
                        floatingBtnParams.y = (int)(viewStartY + dy);
                        try { windowManager.updateViewLayout(floatingBtnView, floatingBtnParams); }
                        catch (Exception ignored) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!wasDrag) {
                            if (popupShowing) dismissPopup(); else showTogglePopup();
                        } else {
                            snapToEdge();
                        }
                        return true;
                }
                return false;
            }
        });

        windowManager.addView(floatingBtnView, floatingBtnParams);
    }

    private void snapToEdge() {
        if (floatingBtnView == null) return;
        int btnW = floatingBtnView.getWidth();
        if (btnW == 0) btnW = (int)(56 * dp);
        int currentX = floatingBtnParams.x;
        int targetX  = (currentX + btnW / 2 < screenWidth / 2)
                ? 8 : (screenWidth - btnW - 8);
        if (currentX == targetX) return;
        ValueAnimator anim = ValueAnimator.ofInt(currentX, targetX);
        anim.setDuration(240);
        anim.setInterpolator(new DecelerateInterpolator(1.8f));
        anim.addUpdateListener(a -> {
            floatingBtnParams.x = (int) a.getAnimatedValue();
            try { windowManager.updateViewLayout(floatingBtnView, floatingBtnParams); }
            catch (Exception ignored) {}
        });
        anim.start();
    }

    // ── eSports Popup ─────────────────────────────────────────────────────────

    private void showTogglePopup() {
        if (popupShowing) return;
        popupShowing = true;

        // Root container — deep dark blue-black
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setBackgroundColor(0xF2060A14);
        ll.setAlpha(0f);
        int pad = (int)(14 * dp);
        ll.setPadding(pad, (int)(6 * dp), pad, (int)(10 * dp));

        // ── Neon top border bar ───────────────────────────────────────────────
        View topBar = new View(this);
        LinearLayout.LayoutParams topBarLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int)(2.5f * dp));
        topBar.setLayoutParams(topBarLp);
        topBar.setBackgroundColor(0xFF00D4FF);
        ll.addView(topBar);

        // ── CARROM BOT title ──────────────────────────────────────────────────
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL);
        titleRow.setPadding(0, (int)(8 * dp), 0, (int)(5 * dp));

        TextView tCarrom = makeText("CARROM", 0xFFE0EEFF, 14, true);
        tCarrom.setLetterSpacing(0.10f);
        TextView tBot = makeText(" BOT", 0xFF00D4FF, 14, true);
        tBot.setLetterSpacing(0.10f);
        TextView tVer = makeText("  v11", 0xFF3A5070, 9, false);
        titleRow.addView(tCarrom);
        titleRow.addView(tBot);
        titleRow.addView(tVer);
        ll.addView(titleRow);

        addSep(ll);

        // ── Status row ────────────────────────────────────────────────────────
        boolean accessOk  = AutoShootService.isReady();
        boolean captureOk = com.bitaim.carromaim.capture.ScreenCaptureService.INSTANCE != null;

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_HORIZONTAL);
        statusRow.setPadding(0, (int)(6 * dp), 0, (int)(6 * dp));
        statusRow.addView(statusChip("ACCESS", accessOk));
        statusRow.addView(statusSpacer());
        statusRow.addView(statusChip("VISION", captureOk));
        statusRow.addView(statusSpacer());
        statusRow.addView(statusChip("BOT", autoPlayEnabled));
        ll.addView(statusRow);

        addSep(ll);

        // ── Aim lines toggle ──────────────────────────────────────────────────
        addPopupBtn(ll, pad,
                overlayVisible ? "[ LINES OFF ]" : "[ LINES ON ]",
                overlayVisible ? 0xFFFF3355 : 0xFF00FF87,
                v -> { toggleAimOverlay(); dismissPopup(); });

        // ── Accessibility CTA or AutoPlay toggle ──────────────────────────────
        if (!accessOk) {
            addPopupBtn(ll, pad, "> ENABLE ACCESSIBILITY", 0xFF00D4FF,
                v -> {
                    Intent i = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    dismissPopup();
                });
        } else {
            addPopupBtn(ll, pad,
                    autoPlayEnabled ? "[ BOT OFF ]" : "[ BOT ON ]",
                    autoPlayEnabled ? 0xFFFF3355 : 0xFF00FF87,
                    v -> { toggleAutoPlayFromPopup(); dismissPopup(); });
        }

        // ── TEST SHOT button ──────────────────────────────────────────────────
        if (accessOk) {
            addPopupBtn(ll, pad, "> FIRE TEST SHOT", 0xFFFFD700,
                v -> {
                    AutoShootService svc = AutoShootService.INSTANCE;
                    if (svc != null) {
                        GameState gs = lastState;
                        if (gs != null && gs.striker != null) {
                            svc.testFire(gs.striker.pos.x, gs.striker.pos.y);
                        } else {
                            DisplayMetrics dm2 = new DisplayMetrics();
                            ((WindowManager) getSystemService(WINDOW_SERVICE))
                                .getDefaultDisplay().getRealMetrics(dm2);
                            svc.testFire(dm2.widthPixels / 2f, dm2.heightPixels * 0.78f);
                        }
                    }
                    dismissPopup();
                });
        }

        // ── Bot active hint ───────────────────────────────────────────────────
        if (autoPlayEnabled) {
            View hintBar = new View(this);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1);
            hintBar.setLayoutParams(hlp);
            hintBar.setBackgroundColor(0xFF00FF87);
            hintBar.setAlpha(0.25f);
            ll.addView(hintBar);

            TextView hint = makeText("BOT ACTIVE — switch to carrom now!", 0xFF00FF87, 10, true);
            hint.setGravity(Gravity.CENTER_HORIZONTAL);
            hint.setPadding(0, (int)(5 * dp), 0, (int)(2 * dp));
            ll.addView(hint);
        }

        // ── Bottom neon bar ───────────────────────────────────────────────────
        View botBar = new View(this);
        LinearLayout.LayoutParams botLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int)(1.5f * dp));
        botBar.setLayoutParams(botLp);
        botBar.setBackgroundColor(0xFF00D4FF);
        botBar.setAlpha(0.35f);
        ll.addView(botBar);

        popupView = ll;
        popupParams = new WindowManager.LayoutParams(
                (int)(200 * dp),
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        popupParams.gravity = Gravity.TOP | Gravity.START;
        popupParams.x = Math.max(0, floatingBtnParams.x - (int)(70 * dp));
        popupParams.y = floatingBtnParams.y;

        popupView.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_OUTSIDE) dismissPopup();
            return false;
        });
        windowManager.addView(popupView, popupParams);
        ll.animate().alpha(1f).setDuration(160).start();
        mainHandler.postDelayed(dismissPopupRunnable, 8000);
    }

    // ── Popup helpers ─────────────────────────────────────────────────────────

    private TextView makeText(String text, int color, float sizeSp, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(sizeSp);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private void addSep(LinearLayout parent) {
        View sep = new View(this);
        sep.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        sep.setBackgroundColor(0x221A2744);
        parent.addView(sep);
    }

    private View statusSpacer() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams((int)(14 * dp), 1));
        return v;
    }

    private View statusChip(String label, boolean on) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);

        View dot = new View(this);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                (int)(7 * dp), (int)(7 * dp));
        dlp.setMarginEnd((int)(4 * dp));
        dot.setLayoutParams(dlp);
        dot.setBackgroundColor(on ? 0xFF00FF87 : 0xFFFF3355);
        // Round the dot via a simple clip approach
        dot.setElevation(0);
        chip.addView(dot);

        TextView tv = makeText(label, on ? 0xFFB0FFD4 : 0xFF7A9090, 9, true);
        tv.setLetterSpacing(0.08f);
        chip.addView(tv);
        return chip;
    }

    private void addPopupBtn(LinearLayout parent, int pad, String text, int color,
                              View.OnClickListener click) {
        TextView tv = makeText(text, color, 12.5f, true);
        tv.setGravity(Gravity.CENTER_HORIZONTAL);
        tv.setPadding(pad, (int)(9 * dp), pad, (int)(9 * dp));
        tv.setLetterSpacing(0.05f);
        tv.setOnClickListener(click);
        parent.addView(tv);

        // Neon separator
        View sep = new View(this);
        sep.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        sep.setBackgroundColor(0x181A2744);
        parent.addView(sep);
    }

    private void dismissPopup() {
        if (!popupShowing) return;
        popupShowing = false;
        mainHandler.removeCallbacks(dismissPopupRunnable);
        View pv = popupView;
        if (pv != null) {
            pv.animate().alpha(0f).setDuration(130).withEndAction(() -> {
                try { windowManager.removeView(pv); } catch (Exception ignored) {}
            }).start();
        }
        popupView = null;
    }

    // ── Aim overlay setup ─────────────────────────────────────────────────────

    private void setupAimOverlay() {
        aimOverlayView = new AimOverlayView(this);
        aimOverlayView.setAutoplaySwipeListener(
            (fromX, fromY, toX, toY, durationMs, powerFrac) -> {
                AutoShootService svc = AutoShootService.INSTANCE;
                if (svc == null) return;
                svc.shoot(fromX, fromY, toX, toY,
                          Math.min(1.0f, Math.max(0.35f, powerFrac)));
            });
        overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        aimOverlayView.setVisibility(View.VISIBLE);
        windowManager.addView(aimOverlayView, overlayParams);
    }

    // ── External API ──────────────────────────────────────────────────────────

    public void setShotMode(String mode)           { if (aimOverlayView != null) aimOverlayView.setShotMode(mode); }
    public void setMarginOffset(float dx, float dy){}
    public void setSensitivity(float value)        {}
    public void setAutoPlayDelay(int ms)           { autoPlayDelayMs = Math.max(500, ms); }

    public void onDetectedState(GameState s) {
        lastState = s;
        if (s == null) return;
        if (aimOverlayView != null) {
            aimOverlayView.setDetectedState(s);
            if (!overlayVisible) { overlayVisible = true; aimOverlayView.setVisibility(View.VISIBLE); }
        }
        if (autoPlayEnabled && s.striker != null) handleAutoPlay(s);
    }

    public void shootNow() {
        AutoShootService acc = AutoShootService.INSTANCE;
        if (acc == null) { Log.w(TAG, "shootNow: accessibility not connected"); return; }
        AimOverlayView.BestShot best = (aimOverlayView != null) ? aimOverlayView.getLastBestShot() : null;
        if (best == null) { Log.w(TAG, "shootNow: no live shot cached"); return; }
        acc.shoot(best.strikerX, best.strikerY, best.targetX, best.targetY, best.powerFrac);
    }

    public void setAutoPlay(boolean enabled) {
        autoPlayEnabled = enabled;
        stableFrames    = 0;
        lastStrikerX    = Float.NaN;
        lastStrikerY    = Float.NaN;
        precomputedShot = null;
        if (!enabled && physicsFuture != null) physicsFuture.cancel(true);
        Log.i(TAG, "AutoPlay " + (enabled ? "ON" : "OFF"));
    }

    public boolean isAutoPlayEnabled() { return autoPlayEnabled; }

    public void toggleAimOverlay() {
        overlayVisible = !overlayVisible;
        if (aimOverlayView != null) {
            aimOverlayView.setVisibility(overlayVisible ? View.VISIBLE : View.GONE);
            if (overlayVisible) injectDemoState();
        }
    }

    // ── Stability-based autoplay ──────────────────────────────────────────────

    private void handleAutoPlay(GameState s) {
        if (!AutoShootService.isReady()) return;
        long now = System.currentTimeMillis();
        long minGap = Math.max(SHOOT_COOLDOWN_MS, autoPlayDelayMs);
        if (now - lastShootTimeMs < minGap) return;

        float sx = s.striker.pos.x, sy = s.striker.pos.y;
        if (!Float.isNaN(lastStrikerX)) {
            float moved = (float) Math.sqrt(
                (sx-lastStrikerX)*(sx-lastStrikerX) + (sy-lastStrikerY)*(sy-lastStrikerY));
            if (moved > STABLE_THRESH_PX) {
                stableFrames    = 0;
                precomputedShot = null;
                if (physicsFuture != null) physicsFuture.cancel(true);
                computing.set(false);
            } else {
                stableFrames++;
            }
        }
        lastStrikerX = sx;
        lastStrikerY = sy;

        if (stableFrames == PREFETCH_FRAMES && computing.compareAndSet(false, true)) {
            final GameState snapshot = s;
            physicsFuture = physicsThread.submit(() -> {
                try {
                    CarromAI.AiShot shot = CarromAI.findBestShotPhysics(snapshot);
                    precomputedShot  = shot;
                    precomputedState = snapshot;
                    if (aimOverlayView != null && shot != null) {
                        mainHandler.post(() -> aimOverlayView.setPhysicsBestShot(shot, snapshot));
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Physics computation failed: " + e.getMessage());
                } finally {
                    computing.set(false);
                }
            });
        }

        if (stableFrames < STABLE_FRAMES_NEEDED) return;

        CarromAI.AiShot physShot = precomputedShot;
        AutoShootService acc = AutoShootService.INSTANCE;
        if (acc == null) return;

        if (physShot != null) {
            if (!isShotPathClear(s, physShot)) {
                Log.d(TAG, "AutoPlay BLOCKED — waiting for clear lane");
                stableFrames    = Math.max(0, stableFrames - 2);
                precomputedShot  = null;
                precomputedState = null;
                computing.set(false);
                return;
            }
            float dx = physShot.ghostPos.x - s.striker.pos.x;
            float dy = physShot.ghostPos.y - s.striker.pos.y;
            float factor = 1.20f;
            float toX = s.striker.pos.x + dx * factor;
            float toY = s.striker.pos.y + dy * factor;
            float pwr = Math.min(1.0f, Math.max(0.35f, physShot.powerFrac));
            Log.i(TAG, String.format(
                "AutoPlay FIRE v11: stable=%d pwr=%.2f target=(%.0f,%.0f)",
                stableFrames, pwr, toX, toY));
            acc.shoot(s.striker.pos.x, s.striker.pos.y, toX, toY, pwr);
        } else {
            AimOverlayView.BestShot best =
                (aimOverlayView != null) ? aimOverlayView.getLastBestShot() : null;
            if (best == null) return;
            Log.i(TAG, String.format("AutoPlay GEO FALLBACK: stable=%d pwr=%.2f", stableFrames, best.powerFrac));
            acc.shoot(best.strikerX, best.strikerY, best.targetX, best.targetY, best.powerFrac);
        }

        lastShootTimeMs = now;
        stableFrames    = 0;
        precomputedShot = null;
        precomputedState = null;
    }

    // ── Smart path-clear check ────────────────────────────────────────────────

    private boolean isShotPathClear(GameState s, CarromAI.AiShot shot) {
        if (shot == null || s == null || s.striker == null) return false;
        float sX = s.striker.pos.x, sY = s.striker.pos.y;
        float gX = shot.ghostPos.x,  gY = shot.ghostPos.y;
        float margin = 4f;
        for (com.bitaim.carromaim.cv.Coin c : s.coins) {
            if (shot.coin != null
                    && Math.abs(c.pos.x - shot.coin.pos.x) < 2f
                    && Math.abs(c.pos.y - shot.coin.pos.y) < 2f) continue;
            float minDist = c.radius + s.striker.radius + margin;
            if (pointToSegmentDist(c.pos.x, c.pos.y, sX, sY, gX, gY) < minDist) return false;
        }
        return true;
    }

    private static float pointToSegmentDist(float px, float py,
                                            float ax, float ay,
                                            float bx, float by) {
        float dx = bx-ax, dy = by-ay;
        float len2 = dx*dx + dy*dy;
        if (len2 < 1f) return (float) Math.hypot(px-ax, py-ay);
        float t = Math.max(0f, Math.min(1f, ((px-ax)*dx + (py-ay)*dy) / len2));
        return (float) Math.hypot(px-(ax+t*dx), py-(ay+t*dy));
    }

    private void toggleAutoPlayFromPopup() {
        if (!AutoShootService.isReady()) { Log.w(TAG, "Accessibility not ready"); return; }
        setAutoPlay(!autoPlayEnabled);
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private int overlayType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "CarromBot v11.0 Active", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("eSports aim engine running — physics AI ready");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE : 0;
        Intent stopIntent = new Intent(this, FloatingOverlayService.class);
        stopIntent.setAction("ACTION_STOP");
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent, piFlags);
        Intent openIntent    = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 1, openIntent, piFlags);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("CARROM BOT v11.0 — eSports Engine")
                .setContentText("Press+Slide gesture engine | Physics AI | Ghost-ball")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(openPi)
                .addAction(0, "Stop", stopPi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "ACTION_STOP".equals(intent.getAction())) stopSelf();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        INSTANCE = null;
        physicsThread.shutdownNow();
        dismissPopup();
        try { if (floatingBtnView != null) windowManager.removeView(floatingBtnView); } catch (Exception ignored) {}
        try { if (aimOverlayView  != null) windowManager.removeView(aimOverlayView);  } catch (Exception ignored) {}
    }
}
