package com.bitaim.carromaim.auto;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * AutoShootService — v10.0  SLINGSHOT + FORWARD DUAL ENGINE
 *
 * ROOT CAUSE FIX (v10.0):
 *   Carrom Disc Pool uses a SLINGSHOT / PULL-BACK mechanic.
 *   You drag the striker BACKWARD (away from target) then release — the
 *   game flings it forward.  Every version before v10 was swiping FORWARD,
 *   which the game ignores because you never pulled anything.
 *
 * Strategy order
 * ──────────────────────────────────────────────────────────────────────────
 *  S1  SLINGSHOT  150 px BACKWARD from striker, 40 ms   ← PRIMARY FIX
 *  S2  FORWARD    150 px FORWARD  from striker, 35 ms   ← in case game is forward
 *  S3  SLINGSHOT  200 px BACKWARD, 55 ms (harder pull)
 *  S4  Root shell `input swipe` backward direction
 *
 * All strategies log to TAG "CarromBot" — use:
 *   adb logcat -s CarromBot
 * to watch exactly which strategy fired and whether it completed.
 */
public class AutoShootService extends AccessibilityService {

    private static final String TAG = "CarromBot";

    public static volatile AutoShootService INSTANCE;
    public static volatile String lastShotResult = "none";

    private final Handler handler = new Handler(Looper.getMainLooper());

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        INSTANCE = this;
        lastShotResult = "connected";
        Log.i(TAG, "=== CarromBot v10.0 CONNECTED — slingshot engine ready ===");
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
    //
    //  strikerX/Y  = striker centre in screen pixels (from CV, already scaled)
    //  targetX/Y   = point in SHOT direction from striker (ghost ball position)
    //  powerFrac   = 0.0 … 1.0
    // ─────────────────────────────────────────────────────────────────────────

    public void shoot(final float strikerX, final float strikerY,
                      final float targetX,  final float targetY,
                      final float powerFrac) {

        float power = Math.min(1.0f, Math.max(0.0f, powerFrac));

        // Unit vector FROM striker TOWARD target (forward direction)
        float dx  = targetX - strikerX;
        float dy  = targetY - strikerY;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f) { Log.w(TAG, "shoot: zero-length direction, skipping"); return; }

        final float fwdX = dx / len;   // forward direction (toward target)
        final float fwdY = dy / len;
        final float bkX  = -fwdX;      // backward direction (pull = slingshot)
        final float bkY  = -fwdY;

        Log.i(TAG, String.format(
            "CarromBot shoot — striker=(%.0f,%.0f) fwd=(%.2f,%.2f) pwr=%.2f",
            strikerX, strikerY, fwdX, fwdY, power));

        // ── S1: PRIMARY — Slingshot backward 150 px in 40 ms ─────────────────
        // Drag striker AWAY from target → game launches it toward target
        float s1dist = 120f + power * 50f;   // 120–170 px
        boolean s1 = fireSwipe(
            strikerX, strikerY,
            strikerX + bkX * s1dist,
            strikerY + bkY * s1dist,
            40L, "S1-Slingshot-" + Math.round(s1dist) + "px");
        if (s1) return;

        // ── S2: FALLBACK — Forward flick 150 px in 35 ms (some carrom games) ─
        handler.postDelayed(() -> {
            float s2dist = 120f + power * 50f;
            boolean s2 = fireSwipe(
                strikerX, strikerY,
                strikerX + fwdX * s2dist,
                strikerY + fwdY * s2dist,
                35L, "S2-Forward-" + Math.round(s2dist) + "px");
            if (s2) return;

            // ── S3: Harder slingshot 200 px 55 ms ────────────────────────────
            handler.postDelayed(() -> {
                float s3dist = 180f + power * 50f;   // 180–230 px
                boolean s3 = fireSwipe(
                    strikerX, strikerY,
                    strikerX + bkX * s3dist,
                    strikerY + bkY * s3dist,
                    55L, "S3-HardSling-" + Math.round(s3dist) + "px");
                if (s3) return;

                // ── S4: Root shell fallback ───────────────────────────────────
                handler.postDelayed(() ->
                    tryRootSwipe(strikerX, strikerY, bkX, bkY, power), 80);

            }, 80);
        }, 80);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // testFire() — called by popup "TEST SHOT" button
    // Fires a visible slingshot swipe at the given position so you can see
    // if the accessibility service is actually injecting gestures.
    // ─────────────────────────────────────────────────────────────────────────

    public boolean testFire(float x, float y) {
        Log.i(TAG, "TEST FIRE (slingshot DOWN) at (" + Math.round(x) + "," + Math.round(y) + ")");
        // Pull downward 150 px — the striker should fling upward
        return fireSwipe(x, y, x, y + 150f, 40L, "TEST-Sling");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core gesture engine
    // ─────────────────────────────────────────────────────────────────────────

    private boolean fireSwipe(float x0, float y0, float x1, float y1,
                               long durationMs, final String label) {
        try {
            Path path = new Path();
            path.moveTo(x0, y0);
            path.lineTo(x1, y1);

            GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0L, durationMs))
                .build();

            boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription g) {
                    lastShotResult = label + ":OK";
                    Log.i(TAG, label + " -> COMPLETED (gesture delivered to game)");
                }
                @Override public void onCancelled(GestureDescription g) {
                    lastShotResult = label + ":CANCELLED";
                    Log.w(TAG, label + " -> CANCELLED (game window may have FLAG_SECURE)");
                }
            }, null);

            Log.d(TAG, label
                + " dispatch=" + accepted
                + " (" + Math.round(x0) + "," + Math.round(y0) + ")"
                + "->(" + Math.round(x1) + "," + Math.round(y1) + ")"
                + " " + durationMs + "ms");

            if (!accepted) lastShotResult = label + ":REJECTED";
            return accepted;

        } catch (Exception e) {
            Log.e(TAG, label + " EXCEPTION: " + e.getMessage());
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Root shell fallback (rooted devices)
    // ─────────────────────────────────────────────────────────────────────────

    private void tryRootSwipe(float sx, float sy,
                               float bkX, float bkY, float power) {
        float dist = 150f + power * 50f;
        float ex = sx + bkX * dist;
        float ey = sy + bkY * dist;
        String cmd = String.format("input swipe %.0f %.0f %.0f %.0f 40", sx, sy, ex, ey);
        Log.i(TAG, "S4-Root: " + cmd);
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            int exit = p.waitFor();
            lastShotResult = "S4-Root:exit=" + exit;
            Log.i(TAG, "S4-Root exit=" + exit);
        } catch (Exception e) {
            try {
                Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
                lastShotResult = "S4-Shell:sent";
            } catch (Exception e2) {
                lastShotResult = "S4:no-root";
                Log.w(TAG, "S4: root unavailable");
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Status helpers
    // ─────────────────────────────────────────────────────────────────────────

    public static boolean isReady()   { return INSTANCE != null; }
    public static String  getStatus() {
        if (INSTANCE == null) return "NOT CONNECTED";
        return "CONNECTED last=" + lastShotResult;
    }
}
