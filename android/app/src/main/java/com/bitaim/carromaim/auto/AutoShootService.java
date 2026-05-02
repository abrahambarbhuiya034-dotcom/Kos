package com.bitaim.carromaim.auto;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * AutoShootService — v11.0  PROPER PRESS-AND-SLIDE ENGINE
 *
 * ROOT CAUSE FIX v11.0:
 *   "Only tapping, not sliding" — the previous 40 ms gestures were registering
 *   as TAPS because any gesture shorter than ~120 ms that travels < 10 px is
 *   treated as a tap by Android games.
 *
 *   Fix: Multi-phase gesture:
 *     Phase 1 — Press & hold on striker for 80 ms  (willContinue = true)
 *     Phase 2 — Smooth slide 250 px backward in 260 ms (willContinue = false)
 *   Total contact time: 340 ms — impossible to misread as a tap.
 *
 *   Fallback for API < 26: single 320 ms stroke over 250 px.
 *
 * Strategy order
 * ─────────────────────────────────────────────────────────────────────────
 *  S1  SLINGSHOT hold+slide  backward 250 px  (340 ms total)  [PRIMARY]
 *  S2  FORWARD   hold+slide  forward  250 px  (340 ms total)
 *  S3  SLINGSHOT single-stroke backward 250 px (320 ms)       [API<26 safe]
 *  S4  Root shell `input swipe`                               [rooted only]
 *
 * Watch: adb logcat -s CarromBot
 */
public class AutoShootService extends AccessibilityService {

    private static final String TAG = "CarromBot";

    public static volatile AutoShootService INSTANCE;
    public static volatile String lastShotResult = "none";

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        INSTANCE = this;
        lastShotResult = "connected";
        Log.i(TAG, "=== CarromBot v11.0 CONNECTED — press-and-slide engine ready ===");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() {}

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        INSTANCE = null;
        lastShotResult = "disconnected";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // shoot() — called by FloatingOverlayService once board is stable
    // ─────────────────────────────────────────────────────────────────────────

    public void shoot(final float strikerX, final float strikerY,
                      final float targetX,  final float targetY,
                      final float powerFrac) {

        float power = Math.min(1.0f, Math.max(0.0f, powerFrac));

        float dx  = targetX - strikerX;
        float dy  = targetY - strikerY;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f) { Log.w(TAG, "shoot: zero direction"); return; }

        final float fwdX = dx / len;
        final float fwdY = dy / len;
        final float bkX  = -fwdX;  // backward = slingshot direction
        final float bkY  = -fwdY;

        float dist = 200f + power * 50f;  // 200–250 px

        Log.i(TAG, String.format(
            "v11 shoot — striker=(%.0f,%.0f) back=(%.2f,%.2f) dist=%.0f pwr=%.2f",
            strikerX, strikerY, bkX, bkY, dist, power));

        // ── S1: PRIMARY — Press-hold + slingshot slide backward ──────────────
        boolean s1 = fireHoldSlide(strikerX, strikerY,
                                    strikerX + bkX * dist,
                                    strikerY + bkY * dist,
                                    "S1-HoldSling");
        if (s1) return;

        // ── S2: Forward hold+slide (in case game uses forward swipe) — 90ms ─
        handler.postDelayed(() -> {
            boolean s2 = fireHoldSlide(strikerX, strikerY,
                                        strikerX + fwdX * dist,
                                        strikerY + fwdY * dist,
                                        "S2-HoldFwd");
            if (s2) return;

            // ── S3: Single long slingshot stroke (API<26 fallback) — 90ms ──
            handler.postDelayed(() -> {
                boolean s3 = fireSingleStroke(strikerX, strikerY,
                                              strikerX + bkX * dist,
                                              strikerY + bkY * dist,
                                              320L, "S3-LongSling");
                if (s3) return;

                // ── S4: Root shell — 90ms ────────────────────────────────
                handler.postDelayed(() ->
                    tryRootSwipe(strikerX, strikerY, bkX, bkY, power), 90);

            }, 90);
        }, 90);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Multi-phase: Press-hold 80 ms → Slide 260 ms  (API 26+)
    // Falls back to single long stroke if API < 26 or build fails.
    // ─────────────────────────────────────────────────────────────────────────

    private boolean fireHoldSlide(float sx, float sy, float ex, float ey, String label) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // API 24–25: continueStroke not available, use long single stroke
            return fireSingleStroke(sx, sy, ex, ey, 320L, label + "-fallback");
        }
        try {
            // Phase 1: touch down, hold 80 ms at striker (no movement)
            Path holdPath = new Path();
            holdPath.moveTo(sx, sy);
            holdPath.lineTo(sx, sy);
            GestureDescription.StrokeDescription hold =
                new GestureDescription.StrokeDescription(holdPath, 0L, 80L, true);

            // Phase 2: continue from same position, slide to end over 260 ms
            Path slidePath = new Path();
            slidePath.moveTo(sx, sy);
            slidePath.lineTo(ex, ey);
            GestureDescription.StrokeDescription slide =
                hold.continueStroke(slidePath, 0L, 260L, false);

            GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(hold)
                .addStroke(slide)
                .build();

            boolean ok = dispatchGesture(gesture, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription g) {
                    lastShotResult = label + ":COMPLETED";
                    Log.i(TAG, label + " COMPLETED — striker should have moved!");
                }
                @Override public void onCancelled(GestureDescription g) {
                    lastShotResult = label + ":CANCELLED";
                    Log.w(TAG, label + " CANCELLED — check FLAG_SECURE or wrong coords");
                }
            }, null);

            Log.d(TAG, label + " dispatch=" + ok
                + " from=(" + Math.round(sx) + "," + Math.round(sy) + ")"
                + " to=(" + Math.round(ex) + "," + Math.round(ey) + ")"
                + " [hold80ms + slide260ms = 340ms total]");

            if (!ok) lastShotResult = label + ":REJECTED";
            return ok;
        } catch (Exception e) {
            Log.e(TAG, label + " EXCEPTION: " + e.getMessage());
            return fireSingleStroke(sx, sy, ex, ey, 320L, label + "-exFallback");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Single long stroke — works on all API levels
    // ─────────────────────────────────────────────────────────────────────────

    private boolean fireSingleStroke(float sx, float sy, float ex, float ey,
                                      long durationMs, String label) {
        try {
            Path path = new Path();
            path.moveTo(sx, sy);
            path.lineTo(ex, ey);

            GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0L, durationMs))
                .build();

            boolean ok = dispatchGesture(gesture, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription g) {
                    lastShotResult = label + ":COMPLETED";
                    Log.i(TAG, label + " COMPLETED");
                }
                @Override public void onCancelled(GestureDescription g) {
                    lastShotResult = label + ":CANCELLED";
                    Log.w(TAG, label + " CANCELLED");
                }
            }, null);

            Log.d(TAG, label + " dispatch=" + ok + " dur=" + durationMs + "ms"
                + " (" + Math.round(sx) + "," + Math.round(sy) + ")"
                + "->(" + Math.round(ex) + "," + Math.round(ey) + ")");

            if (!ok) lastShotResult = label + ":REJECTED";
            return ok;
        } catch (Exception e) {
            Log.e(TAG, label + " EXCEPTION: " + e.getMessage());
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test fire — popup TEST SHOT button
    // ─────────────────────────────────────────────────────────────────────────

    public boolean testFire(float x, float y) {
        Log.i(TAG, "TEST FIRE hold+slide DOWN 200px at (" + Math.round(x) + "," + Math.round(y) + ")");
        return fireHoldSlide(x, y, x, y + 200f, "TEST-HoldSlide");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Root shell fallback
    // ─────────────────────────────────────────────────────────────────────────

    private void tryRootSwipe(float sx, float sy, float bkX, float bkY, float power) {
        float dist = 200f + power * 50f;
        float ex = sx + bkX * dist;
        float ey = sy + bkY * dist;
        String cmd = String.format("input swipe %.0f %.0f %.0f %.0f 320", sx, sy, ex, ey);
        Log.i(TAG, "S4-Root: " + cmd);
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            int exit = p.waitFor();
            lastShotResult = "S4-Root:exit=" + exit;
        } catch (Exception e) {
            try { Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd}); lastShotResult = "S4-sh:sent"; }
            catch (Exception e2) { lastShotResult = "S4:no-root"; Log.w(TAG, "No root available"); }
        }
    }

    public static boolean isReady()   { return INSTANCE != null; }
    public static String  getStatus() {
        if (INSTANCE == null) return "NOT CONNECTED";
        return "CONNECTED last=" + lastShotResult;
    }
}
