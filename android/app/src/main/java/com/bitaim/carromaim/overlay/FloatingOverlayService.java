package com.bitaim.carromaim.overlay;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
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
 * FloatingOverlayService — v12.0 CARROM ENGINE
 *
 * ═══════════════════════════════════════════════════════════════════
 * ENGINE MODE — v12.0
 * ═══════════════════════════════════════════════════════════════════
 *
 * • Auto-launches Carrom Disc Pool when the engine starts
 * • Floating button: tap = open control panel, long-press = PAUSE/RUN
 * • Control panel shows ENGINE STATUS + PAUSE/RUN + LAUNCH GAME + TEST SHOT
 * • Engine state machine:
 *     IDLE → LAUNCHING → DETECTING → COMPUTING → FIRING → COOLDOWN
 *
 * Architecture:
 *   FloatingOverlayService  ← engine coordinator
 *   ScreenCaptureService    ← frame grabber
 *   BoardDetector           ← CV circle detection
 *   CarromAI                ← physics minimax shot selection
 *   AutoShootService        ← gesture injection
 *
 * Gesture fix (v12.0):
 *   FORWARD drag is now PRIMARY (correct for Carrom Disc Pool)
 *   Slingshot is SECONDARY fallback
 */
public class FloatingOverlayService extends Service {

    private static final String TAG        = "CarromEngine";
    private static final String CHANNEL_ID = "carromengine_ch";
    private static final int    NOTIF_ID   = 1001;

    // Stability threshold: 6 stable frames before the bot fires
    private static final int   STABLE_FRAMES_NEEDED = 6;
    private static final int   PREFETCH_FRAMES      = 2;
    private static final float STABLE_THRESH_PX     = 40f;
    private static final long  SHOOT_COOLDOWN_MS    = 2000L;

    // Carrom Disc Pool package names to try (in order)
    private static final String[] GAME_PACKAGES = {
        "com.miniclip.pool.carrom",
        "com.Miniclip.pool.carrom",
        "carrom.pool.disc.carrom.board.game",
        "com.miniclip.carromdiscpool",
        "com.fungame.carrombd"
    };

    public static volatile FloatingOverlayService INSTANCE;

    // ── Engine state ──────────────────────────────────────────────────────────
    public enum EngineState { IDLE, LAUNCHING, DETECTING, COMPUTING, FIRING, PAUSED }
    private volatile EngineState engineState = EngineState.IDLE;

    // ── Views ─────────────────────────────────────────────────────────────────
    private WindowManager  wm;
    private View           fabView;        // floating action button
    private AimOverlayView aimView;
    private View           panelView;      // control panel popup

    private WindowManager.LayoutParams fabParams;
    private WindowManager.LayoutParams aimParams;
    private WindowManager.LayoutParams panelParams;

    // FAB drag state
    private float touchStartX, touchStartY;
    private int   fabStartX,   fabStartY;
    private boolean wasDrag;

    private boolean panelShowing = false;

    // ── AutoPlay state ────────────────────────────────────────────────────────
    private volatile boolean engineRunning    = false;
    private volatile GameState lastState      = null;
    private int   stableFrames   = 0;
    private float lastStrikerX   = Float.NaN;
    private float lastStrikerY   = Float.NaN;
    private long  lastShootMs    = 0L;
    private int   autoPlayDelayMs = 1800;

    // ── Background physics ────────────────────────────────────────────────────
    private final ExecutorService physicsPool = Executors.newSingleThreadExecutor();
    private volatile Future<?>       physicsFuture;
    private volatile CarromAI.AiShot preShot;
    private volatile GameState       preState;
    private final AtomicBoolean      computing = new AtomicBoolean(false);

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable dismissPanel = this::closePanelAnimated;

    private float dp;
    private int   screenW;

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    // ═════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        dp = getResources().getDisplayMetrics().density;
        screenW = getResources().getDisplayMetrics().widthPixels;

        createNotifChannel();
        startForegroundCompat();

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        buildAimOverlay();
        buildFab();

        // Inject demo board so overlay looks alive immediately
        ui.postDelayed(this::injectDemo, 350);

        // Auto-launch Carrom Disc Pool
        ui.postDelayed(this::launchGame, 800);
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
        physicsPool.shutdownNow();
        closePanelInstant();
        removeViewSafe(fabView);
        removeViewSafe(aimView);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Game launcher
    // ═════════════════════════════════════════════════════════════════════════

    private void launchGame() {
        PackageManager pm = getPackageManager();
        for (String pkg : GAME_PACKAGES) {
            try {
                Intent launch = pm.getLaunchIntentForPackage(pkg);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    startActivity(launch);
                    setEngineState(EngineState.LAUNCHING);
                    Log.i(TAG, "Launched game: " + pkg);
                    return;
                }
            } catch (Exception e) { /* try next */ }
        }
        Log.w(TAG, "Carrom Disc Pool not installed — engine waiting");
        // Not installed — stay IDLE, user can try manually
    }

    // ═════════════════════════════════════════════════════════════════════════
    // FAB (Floating Action Button)
    // ═════════════════════════════════════════════════════════════════════════

    private void buildFab() {
        fabView = LayoutInflater.from(this).inflate(R.layout.view_floating_button, null);
        fabParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        fabParams.gravity = Gravity.TOP | Gravity.START;
        fabParams.x = 14;
        fabParams.y = 300;

        fabView.setOnTouchListener((v, e) -> {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    touchStartX = e.getRawX();  touchStartY = e.getRawY();
                    fabStartX   = fabParams.x;  fabStartY   = fabParams.y;
                    wasDrag = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - touchStartX;
                    float dy = e.getRawY() - touchStartY;
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) wasDrag = true;
                    fabParams.x = (int)(fabStartX + dx);
                    fabParams.y = (int)(fabStartY + dy);
                    try { wm.updateViewLayout(fabView, fabParams); } catch (Exception ignored) {}
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!wasDrag) {
                        if (panelShowing) closePanelAnimated();
                        else openPanel();
                    } else {
                        snapFabToEdge();
                    }
                    return true;
            }
            return false;
        });

        wm.addView(fabView, fabParams);
    }

    private void snapFabToEdge() {
        int w = fabView.getWidth();
        if (w == 0) w = (int)(62 * dp);
        int target = (fabParams.x + w / 2 < screenW / 2) ? 8 : (screenW - w - 8);
        if (fabParams.x == target) return;
        ValueAnimator a = ValueAnimator.ofInt(fabParams.x, target);
        a.setDuration(220); a.setInterpolator(new DecelerateInterpolator(1.8f));
        a.addUpdateListener(an -> {
            fabParams.x = (int) an.getAnimatedValue();
            try { wm.updateViewLayout(fabView, fabParams); } catch (Exception ignored) {}
        });
        a.start();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Control Panel
    // ═════════════════════════════════════════════════════════════════════════

    private void openPanel() {
        if (panelShowing) return;
        panelShowing = true;

        // Root container
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xF5060A14);
        root.setAlpha(0f);
        int p = (int)(14 * dp);
        root.setPadding(p, (int)(5*dp), p, (int)(10*dp));

        // ── Neon top bar ──────────────────────────────────────────────────────
        root.addView(neonBar(0xFF00D4FF, 2.5f));

        // ── Title ─────────────────────────────────────────────────────────────
        LinearLayout titleRow = hRow(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
        titleRow.setPadding(0, (int)(7*dp), 0, (int)(4*dp));
        titleRow.addView(label("CARROM", 0xFFD0E8FF, 15, true, 0.10f));
        titleRow.addView(label(" ENGINE", 0xFF00D4FF, 15, true, 0.10f));
        titleRow.addView(label("  v12", 0xFF2A4060, 9, false, 0f));
        root.addView(titleRow);

        root.addView(thinSep());

        // ── Engine state ──────────────────────────────────────────────────────
        boolean accOk  = AutoShootService.isReady();
        boolean capOk  = com.bitaim.carromaim.capture.ScreenCaptureService.INSTANCE != null;
        boolean botOn  = engineRunning;

        LinearLayout stRow = hRow(Gravity.CENTER_HORIZONTAL);
        stRow.setPadding(0, (int)(5*dp), 0, (int)(5*dp));
        stRow.addView(statChip("ACCESS", accOk));
        stRow.addView(spacer(12));
        stRow.addView(statChip("VISION", capOk));
        stRow.addView(spacer(12));
        stRow.addView(statChip("ENGINE", botOn));
        root.addView(stRow);

        // Engine state text
        TextView stTv = label("[" + engineState.name() + "]",
            stateColor(engineState), 10, true, 0.08f);
        stTv.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(stTv);

        root.addView(thinSep());

        // ── PAUSE / RUN  (primary control) ────────────────────────────────────
        addBtn(root, p,
            botOn ? "⏸  PAUSE ENGINE" : "▶  RUN ENGINE",
            botOn ? 0xFFFF3355 : 0xFF00FF87,
            v -> { toggleEngine(); closePanelAnimated(); });

        // ── LAUNCH GAME ───────────────────────────────────────────────────────
        addBtn(root, p, "⚡  LAUNCH CARROM GAME", 0xFFFFD700,
            v -> { launchGame(); closePanelAnimated(); });

        // ── TEST SHOT ─────────────────────────────────────────────────────────
        if (accOk) {
            addBtn(root, p, "◉  FIRE TEST SHOT", 0xFF00D4FF,
                v -> {
                    doTestShot();
                    closePanelAnimated();
                });
        }

        // ── Accessibility CTA ─────────────────────────────────────────────────
        if (!accOk) {
            addBtn(root, p, ">  ENABLE ACCESSIBILITY", 0xFFFF9500,
                v -> {
                    Intent i = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    closePanelAnimated();
                });
        }

        // ── Hint when engine is running ───────────────────────────────────────
        if (botOn) {
            root.addView(neonBar(0xFF00FF87, 1f));
            TextView hint = label("ENGINE ACTIVE — switch to the game now!",
                0xFF00FF87, 10, true, 0.05f);
            hint.setGravity(Gravity.CENTER_HORIZONTAL);
            hint.setPadding(0, (int)(5*dp), 0, (int)(2*dp));
            root.addView(hint);
        }

        root.addView(neonBar(0xFF00D4FF, 1.2f));

        panelView = root;
        panelParams = new WindowManager.LayoutParams(
                (int)(210 * dp),
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.TOP | Gravity.START;
        panelParams.x = Math.max(4, fabParams.x - (int)(75 * dp));
        panelParams.y = fabParams.y;

        panelView.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_OUTSIDE) closePanelAnimated();
            return false;
        });

        wm.addView(panelView, panelParams);
        root.animate().alpha(1f).setDuration(150).start();
        ui.postDelayed(dismissPanel, 10000);
    }

    private void closePanelAnimated() {
        if (!panelShowing) return;
        panelShowing = false;
        ui.removeCallbacks(dismissPanel);
        View pv = panelView; panelView = null;
        if (pv != null) {
            pv.animate().alpha(0f).setDuration(120).withEndAction(() ->
                removeViewSafe(pv)).start();
        }
    }

    private void closePanelInstant() {
        panelShowing = false;
        ui.removeCallbacks(dismissPanel);
        removeViewSafe(panelView);
        panelView = null;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Engine toggle
    // ═════════════════════════════════════════════════════════════════════════

    private void toggleEngine() {
        if (engineRunning) {
            engineRunning = false;
            stableFrames  = 0;
            preShot       = null;
            if (physicsFuture != null) physicsFuture.cancel(true);
            computing.set(false);
            setEngineState(EngineState.PAUSED);
            Log.i(TAG, "Engine PAUSED");
        } else {
            engineRunning = true;
            lastStrikerX  = Float.NaN;
            lastStrikerY  = Float.NaN;
            setEngineState(EngineState.DETECTING);
            Log.i(TAG, "Engine RUNNING");
        }
    }

    private void setEngineState(EngineState s) {
        engineState = s;
        Log.d(TAG, "State → " + s.name());
    }

    // ═════════════════════════════════════════════════════════════════════════
    // External API  (called from OverlayModule / React Native bridge)
    // ═════════════════════════════════════════════════════════════════════════

    public void setShotMode(String mode)              { if (aimView != null) aimView.setShotMode(mode); }
    public void setMarginOffset(float dx, float dy)   {}
    public void setSensitivity(float v)               {}
    public void setAutoPlayDelay(int ms)              { autoPlayDelayMs = Math.max(500, ms); }
    public boolean isAutoPlayEnabled()                { return engineRunning; }

    public void setAutoPlay(boolean on) {
        engineRunning = on;
        if (!on) {
            stableFrames = 0;
            preShot = null;
            if (physicsFuture != null) physicsFuture.cancel(true);
            setEngineState(EngineState.PAUSED);
        } else {
            lastStrikerX = Float.NaN;
            lastStrikerY = Float.NaN;
            setEngineState(EngineState.DETECTING);
        }
        Log.i(TAG, "setAutoPlay(" + on + ")");
    }

    public void shootNow() {
        AutoShootService acc = AutoShootService.INSTANCE;
        if (acc == null) { Log.w(TAG, "shootNow: accessibility not connected"); return; }
        AimOverlayView.BestShot best = aimView != null ? aimView.getLastBestShot() : null;
        if (best == null) { Log.w(TAG, "shootNow: no cached shot"); return; }
        acc.shoot(best.strikerX, best.strikerY, best.targetX, best.targetY, best.powerFrac);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Detected-state pipeline  (called ~30× per second from ScreenCaptureService)
    // ═════════════════════════════════════════════════════════════════════════

    public void onDetectedState(GameState s) {
        lastState = s;
        if (s == null) return;
        if (aimView != null) aimView.setDetectedState(s);
        if (engineRunning && s.striker != null) runAutoPlay(s);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // AutoPlay core  — stability accumulator → prefetch → fire
    // ═════════════════════════════════════════════════════════════════════════

    private void runAutoPlay(GameState s) {
        if (!AutoShootService.isReady()) return;

        long now    = System.currentTimeMillis();
        long minGap = Math.max(SHOOT_COOLDOWN_MS, (long) autoPlayDelayMs);
        if (now - lastShootMs < minGap) return;

        float sx = s.striker.pos.x, sy = s.striker.pos.y;

        if (!Float.isNaN(lastStrikerX)) {
            float moved = (float) Math.hypot(sx - lastStrikerX, sy - lastStrikerY);
            if (moved > STABLE_THRESH_PX) {
                stableFrames = 0;
                preShot = null;
                if (physicsFuture != null) physicsFuture.cancel(true);
                computing.set(false);
                setEngineState(EngineState.DETECTING);
            } else {
                stableFrames++;
            }
        }
        lastStrikerX = sx; lastStrikerY = sy;

        // Prefetch physics shot at frame PREFETCH_FRAMES
        if (stableFrames == PREFETCH_FRAMES && computing.compareAndSet(false, true)) {
            setEngineState(EngineState.COMPUTING);
            final GameState snap = s;
            physicsFuture = physicsPool.submit(() -> {
                try {
                    CarromAI.AiShot shot = CarromAI.findBestShotPhysics(snap);
                    preShot  = shot;
                    preState = snap;
                    if (aimView != null && shot != null)
                        ui.post(() -> aimView.setPhysicsBestShot(shot, snap));
                } catch (Exception e) {
                    Log.w(TAG, "Physics error: " + e.getMessage());
                } finally {
                    computing.set(false);
                    if (engineRunning) setEngineState(EngineState.DETECTING);
                }
            });
        }

        if (stableFrames < STABLE_FRAMES_NEEDED) return;

        // Board is stable — fire!
        AutoShootService acc = AutoShootService.INSTANCE;
        if (acc == null) return;

        CarromAI.AiShot physShot = preShot;

        if (physShot != null) {
            if (!pathClear(s, physShot)) {
                Log.d(TAG, "BLOCKED — coin on shot path, waiting");
                stableFrames = Math.max(0, stableFrames - 2);
                preShot = null;
                preState = null;
                computing.set(false);
                return;
            }

            float dx = physShot.ghostPos.x - sx;
            float dy = physShot.ghostPos.y - sy;
            float len = (float) Math.hypot(dx, dy);
            if (len < 1f) return;

            // Shoot toward the ghost-ball contact point (FORWARD for CarromDP)
            float toX = sx + (dx / len) * len * 1.2f;
            float toY = sy + (dy / len) * len * 1.2f;
            float pwr = Math.min(1.0f, Math.max(0.35f, physShot.powerFrac));

            setEngineState(EngineState.FIRING);
            Log.i(TAG, String.format(
                "ENGINE FIRE — stable=%d pwr=%.2f ghost=(%.0f,%.0f)",
                stableFrames, pwr, toX, toY));
            acc.shoot(sx, sy, toX, toY, pwr);

        } else {
            AimOverlayView.BestShot best = aimView != null ? aimView.getLastBestShot() : null;
            if (best == null) return;
            setEngineState(EngineState.FIRING);
            Log.i(TAG, "ENGINE FIRE (geo fallback) — stable=" + stableFrames);
            acc.shoot(best.strikerX, best.strikerY, best.targetX, best.targetY, best.powerFrac);
        }

        lastShootMs  = now;
        stableFrames = 0;
        preShot      = null;
        preState     = null;
        ui.postDelayed(() -> {
            if (engineRunning) setEngineState(EngineState.DETECTING);
        }, SHOOT_COOLDOWN_MS);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private boolean pathClear(GameState s, CarromAI.AiShot shot) {
        if (shot == null || s.striker == null) return false;
        float sX = s.striker.pos.x, sY = s.striker.pos.y;
        float gX = shot.ghostPos.x,  gY = shot.ghostPos.y;
        for (Coin c : s.coins) {
            if (shot.coin != null
                    && Math.abs(c.pos.x - shot.coin.pos.x) < 2f
                    && Math.abs(c.pos.y - shot.coin.pos.y) < 2f) continue;
            if (segDist(c.pos.x, c.pos.y, sX, sY, gX, gY)
                    < c.radius + s.striker.radius + 4f) return false;
        }
        return true;
    }

    private static float segDist(float px, float py,
                                  float ax, float ay, float bx, float by) {
        float dx = bx-ax, dy = by-ay, l2 = dx*dx+dy*dy;
        if (l2 < 1f) return (float) Math.hypot(px-ax, py-ay);
        float t = Math.max(0f, Math.min(1f, ((px-ax)*dx+(py-ay)*dy)/l2));
        return (float) Math.hypot(px-(ax+t*dx), py-(ay+t*dy));
    }

    private void doTestShot() {
        AutoShootService svc = AutoShootService.INSTANCE;
        if (svc == null) return;
        GameState gs = lastState;
        if (gs != null && gs.striker != null) {
            svc.testFire(gs.striker.pos.x, gs.striker.pos.y);
        } else {
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            svc.testFire(dm.widthPixels / 2f, dm.heightPixels * 0.80f);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Demo board (shown until real CV data arrives)
    // ═════════════════════════════════════════════════════════════════════════

    private void injectDemo() {
        if (aimView == null) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = dm.widthPixels, h = dm.heightPixels;
        GameState s = new GameState();

        float uiTop = h * 0.14f, uiBot = h * 0.10f;
        float usableH = h - uiTop - uiBot;
        float side = Math.min(w * 0.94f, usableH * 0.96f);
        float cx = w / 2f, cy = uiTop + usableH * 0.50f;

        s.board = new RectF(cx-side/2f, cy-side/2f, cx+side/2f, cy+side/2f);
        float r = side * 0.022f;
        s.striker = new Coin(cx, s.board.top + side * 0.800f, r*1.28f, Coin.COLOR_STRIKER, true);

        float[][] coins = {
            {cx, cy, Coin.COLOR_RED},
            {cx-side*.08f, cy-side*.10f, Coin.COLOR_WHITE}, {cx+side*.08f, cy-side*.10f, Coin.COLOR_WHITE},
            {cx-side*.16f, cy-side*.05f, Coin.COLOR_WHITE}, {cx+side*.16f, cy-side*.05f, Coin.COLOR_WHITE},
            {cx, cy-side*.17f, Coin.COLOR_WHITE},
            {cx-side*.08f, cy+side*.10f, Coin.COLOR_WHITE}, {cx+side*.08f, cy+side*.10f, Coin.COLOR_WHITE},
            {cx, cy+side*.17f, Coin.COLOR_WHITE}, {cx-side*.16f, cy+side*.05f, Coin.COLOR_WHITE},
            {cx+side*.16f, cy+side*.05f, Coin.COLOR_BLACK}, {cx-side*.24f, cy-side*.12f, Coin.COLOR_BLACK},
            {cx+side*.24f, cy-side*.12f, Coin.COLOR_BLACK}, {cx-side*.24f, cy+side*.12f, Coin.COLOR_BLACK},
            {cx+side*.24f, cy+side*.12f, Coin.COLOR_BLACK}, {cx, cy+side*.26f, Coin.COLOR_BLACK},
            {cx-side*.14f, cy+side*.26f, Coin.COLOR_BLACK}, {cx+side*.14f, cy+side*.26f, Coin.COLOR_BLACK},
            {cx, cy-side*.26f, Coin.COLOR_BLACK},
        };
        for (float[] c : coins) s.coins.add(new Coin(c[0], c[1], r, (int)c[2], false));

        float inset = side * 0.060f;
        s.pockets.add(new PointF(s.board.left+inset,  s.board.top+inset));
        s.pockets.add(new PointF(s.board.right-inset, s.board.top+inset));
        s.pockets.add(new PointF(s.board.left+inset,  s.board.bottom-inset));
        s.pockets.add(new PointF(s.board.right-inset, s.board.bottom-inset));

        aimView.setDemoState(s);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Aim overlay
    // ═════════════════════════════════════════════════════════════════════════

    private void buildAimOverlay() {
        aimView = new AimOverlayView(this);
        aimView.setAutoplaySwipeListener((fromX, fromY, toX, toY, durMs, pwr) -> {
            AutoShootService svc = AutoShootService.INSTANCE;
            if (svc != null)
                svc.shoot(fromX, fromY, toX, toY, Math.min(1f, Math.max(0.35f, pwr)));
        });
        aimParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        wm.addView(aimView, aimParams);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Notification + foreground
    // ═════════════════════════════════════════════════════════════════════════

    private int overlayType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, buildNotif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, buildNotif());
        }
    }

    private void createNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Carrom Engine", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Physics AI bot — forward-drag gesture engine active");
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotif() {
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent openPi = PendingIntent.getActivity(this, 1,
                new Intent(this, MainActivity.class), flags);
        PendingIntent stopPi = PendingIntent.getService(this, 0,
                new Intent(this, FloatingOverlayService.class).setAction("ACTION_STOP"), flags);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("CARROM ENGINE v12 — Running")
                .setContentText("Bot: " + engineState.name() + " | Tap FAB to control")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(openPi)
                .addAction(0, "Stop", stopPi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // View helpers
    // ═════════════════════════════════════════════════════════════════════════

    private TextView label(String text, int color, float sp, boolean bold, float ls) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(sp);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        if (ls != 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            tv.setLetterSpacing(ls);
        return tv;
    }

    private void addBtn(LinearLayout parent, int p, String text, int color, View.OnClickListener l) {
        TextView tv = label(text, color, 12.5f, true, 0.05f);
        tv.setGravity(Gravity.CENTER_HORIZONTAL);
        tv.setPadding(p, (int)(8*dp), p, (int)(8*dp));
        tv.setOnClickListener(l);
        parent.addView(tv);
        parent.addView(thinSep());
    }

    private LinearLayout hRow(int gravity) {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.HORIZONTAL);
        ll.setGravity(gravity);
        return ll;
    }

    private View neonBar(int color, float heightDp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int)(heightDp * dp)));
        v.setBackgroundColor(color);
        return v;
    }

    private View thinSep() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        v.setBackgroundColor(0x1A1A2744);
        return v;
    }

    private View spacer(float widthDp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams((int)(widthDp * dp), 1));
        return v;
    }

    private View statChip(String label, boolean on) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);

        View dot = new View(this);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                (int)(7*dp), (int)(7*dp));
        dlp.setMarginEnd((int)(4*dp));
        dot.setLayoutParams(dlp);
        dot.setBackgroundColor(on ? 0xFF00FF87 : 0xFFFF3355);

        chip.addView(dot);
        chip.addView(label(label, on ? 0xFFB0FFD8 : 0xFF7A9090, 9, true, 0.06f));
        return chip;
    }

    private int stateColor(EngineState s) {
        switch (s) {
            case FIRING:    return 0xFFFF6B35;
            case COMPUTING: return 0xFF00D4FF;
            case DETECTING: return 0xFF00FF87;
            case LAUNCHING: return 0xFFFFD700;
            case PAUSED:    return 0xFFFF3355;
            default:        return 0xFF3A5070;
        }
    }

    private void removeViewSafe(View v) {
        if (v == null) return;
        try { wm.removeView(v); } catch (Exception ignored) {}
    }

    public void toggleAimOverlay() {
        if (aimView != null) {
            boolean vis = aimView.getVisibility() == View.VISIBLE;
            aimView.setVisibility(vis ? View.GONE : View.VISIBLE);
            if (!vis) injectDemo();
        }
    }
}
