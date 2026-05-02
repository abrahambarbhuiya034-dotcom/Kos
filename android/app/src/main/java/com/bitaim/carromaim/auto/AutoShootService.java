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
 * AutoShootService — v12.0 CARROM ENGINE
 *
 * ═══════════════════════════════════════════════════════════════════
 * CRITICAL FIX v12.0 — GESTURE DIRECTION WAS BACKWARDS
 * ═══════════════════════════════════════════════════════════════════
 *
 * Carrom Disc Pool uses FORWARD drag:
 *   You touch the striker → drag TOWARD the target → release
 *   Power = drag distance.  Angle = drag direction.
 *
 * Previous versions used SLINGSHOT (backward) which is wrong for
 * this game. Fixed: S1 is now FORWARD toward target.
 *
 * Strategy waterfall (tries each in sequence, stops on first success):
 *
 *  S1  FORWARD smooth  — hold 80 ms + smooth multi-point slide 260 ms
 *                         toward target   [PRIMARY — correct for CarromDP]
 *  S2  FORWARD long    — single 320 ms stroke toward target (API<26 safe)
 *  S3  SLINGSHOT       — backward 300 ms  (for pull-back style games)
 *  S4  Root/ADB shell  — `input swipe`   (rooted devices)
 *
 * Monitor: adb logcat -s CarromEngine
 */
public class AutoShootService extends AccessibilityService {

    private static final String TAG = "CarromEngine";

    /** Gesture travel distance in pixels. 280px = strong directional drag. */
    private static final float DRAG_DIST_BASE  = 240f;
    private static final float DRAG_DIST_BONUS = 80f;  // added at full power

    public static volatile AutoShootService INSTANCE;
    public static volatile String           lastResult = "none";

    private final Handler h = new Handler(Looper.getMainLooper());

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        INSTANCE   = this;
        lastResult = "CONNECTED";
        Log.i(TAG, "=== CarromEngine v12.0 CONNECTED — forward-drag engine ready ===");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() {}

    @Override
    public void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
        INSTANCE   = null;
        lastResult = "DISCONNECTED";
        Log.i(TAG, "CarromEngine disconnected");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // shoot()  — called by the engine once a stable board is detected
    //
    //  strikerX/Y  screen coordinates of the striker disk centre
    //  targetX/Y   screen coordinates of the ghost-ball contact point
    //  powerFrac   0.0–1.0 shot power
    // ═════════════════════════════════════════════════════════════════════════

    public void shoot(final float strikerX, final float strikerY,
                      final float targetX,  final float targetY,
                      final float powerFrac) {

        float power = Math.max(0f, Math.min(1f, powerFrac));

        float dx  = targetX - strikerX;
        float dy  = targetY - strikerY;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f) { Log.w(TAG, "shoot: degenerate direction — skipped"); return; }

        // Unit vectors
        final float fwdX = dx / len;   // TOWARD target  (correct for CarromDP)
        final float fwdY = dy / len;
        final float bkX  = -fwdX;      // AWAY from target (slingshot fallback)
        final float bkY  = -fwdY;

        float dragDist = DRAG_DIST_BASE + power * DRAG_DIST_BONUS;

        Log.i(TAG, String.format(
            "v12 SHOOT — striker=(%.0f,%.0f) dir=(%.2f,%.2f) dist=%.0f pwr=%.2f",
            strikerX, strikerY, fwdX, fwdY, dragDist, power));

        // ── S1: FORWARD smooth hold+slide (PRIMARY) ───────────────────────────
        float ex = strikerX + fwdX * dragDist;
        float ey = strikerY + fwdY * dragDist;

        boolean s1 = fireSmooth(strikerX, strikerY, ex, ey, "S1-FwdSmooth");
        if (s1) return;

        // ── S2: FORWARD single long stroke ────────────────────────────────────
        h.postDelayed(() -> {
            boolean s2 = fireSingle(strikerX, strikerY, ex, ey, 320L, "S2-FwdLong");
            if (s2) return;

            // ── S3: SLINGSHOT backward (fallback for pull-back games) ─────────
            h.postDelayed(() -> {
                float bx = strikerX + bkX * dragDist;
                float by = strikerY + bkY * dragDist;
                boolean s3 = fireSmooth(strikerX, strikerY, bx, by, "S3-Slingshot");
                if (s3) return;

                // ── S4: Root shell ────────────────────────────────────────────
                h.postDelayed(() -> rootSwipe(strikerX, strikerY, ex, ey), 90);
            }, 90);
        }, 90);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // fireSmooth — Phase 1: hold 80ms | Phase 2: smooth multi-point slide 260ms
    //
    // The multi-point path makes the gesture look like a real finger drag
    // (velocity profile) rather than an instant jump.  Games that inspect
    // intermediate move events (Carrom Disc Pool does) will correctly register
    // this as a power-drag rather than a tap.
    // ═════════════════════════════════════════════════════════════════════════

    private boolean fireSmooth(float sx, float sy, float ex, float ey, String label) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // API 24–25: continueStroke not available — fall through to fireSingle
            return fireSingle(sx, sy, ex, ey, 320L, label + "-api25fb");
        }
        try {
            // Phase 1: press and hold 80 ms at striker (no movement)
            Path holdPath = new Path();
            holdPath.moveTo(sx, sy);
            holdPath.lineTo(sx, sy);
            GestureDescription.StrokeDescription hold =
                new GestureDescription.StrokeDescription(holdPath, 0L, 80L, true);

            // Phase 2: smooth quad-bezier slide over 260 ms
            // Add a mid-point to create a smooth curved path —
            // this mimics human finger acceleration and gives the game
            // the realistic velocity profile it needs to register as a drag.
            Path slidePath = new Path();
            slidePath.moveTo(sx, sy);
            float midX = (sx + ex) / 2f;
            float midY = (sy + ey) / 2f;
            slidePath.quadTo(
                midX - (ey - sy) * 0.05f,
                midY + (ex - sx) * 0.05f,
                ex, ey);
            GestureDescription.StrokeDescription slide =
                hold.continueStroke(slidePath, 0L, 260L, false);

            GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(hold)
                .addStroke(slide)
                .build();

            boolean ok = dispatchGesture(gesture, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription g) {
                    lastResult = label + ":OK";
                    Log.i(TAG, "*** " + label + " COMPLETED — striker should move! ***");
                }
                @Override public void onCancelled(GestureDescription g) {
                    lastResult = label + ":CANCELLED";
                    Log.w(TAG, label + " CANCELLED (game may block accessibility input)");
                }
            }, null);

            Log.d(TAG, label + " dispatch=" + ok
                + " (" + Math.round(sx) + "," + Math.round(sy) + ")"
                + "→(" + Math.round(ex) + "," + Math.round(ey) + ")"
                + " [hold80+slide260=340ms]");

            if (!ok) lastResult = label + ":REJECTED";
            return ok;

        } catch (Exception e) {
            Log.e(TAG, label + " EXCEPTION: " + e.getMessage());
            return fireSingle(sx, sy, ex, ey, 320L, label + "-exFB");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // fireSingle — single long stroke, all API levels
    // ─────────────────────────────────────────────────────────────────────────

    private boolean fireSingle(float sx, float sy, float ex, float ey,
                                long durationMs, String label) {
        try {
            Path path = new Path();
            path.moveTo(sx, sy);
            // Add intermediate waypoint for better velocity profile
            path.lineTo((sx * 2 + ex) / 3f, (sy * 2 + ey) / 3f);
            path.lineTo((sx + ex * 2) / 3f, (sy + ey * 2) / 3f);
            path.lineTo(ex, ey);

            GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0L, durationMs))
                .build();

            boolean ok = dispatchGesture(g, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription gd) {
                    lastResult = label + ":OK"; Log.i(TAG, label + " COMPLETED"); }
                @Override public void onCancelled(GestureDescription gd) {
                    lastResult = label + ":CANCELLED"; Log.w(TAG, label + " CANCELLED"); }
            }, null);

            Log.d(TAG, label + " dispatch=" + ok + " dur=" + durationMs + "ms"
                + " (" + Math.round(sx) + "," + Math.round(sy) + ")"
                + "→(" + Math.round(ex) + "," + Math.round(ey) + ")");

            if (!ok) lastResult = label + ":REJECTED";
            return ok;
        } catch (Exception e) {
            Log.e(TAG, label + " EXCEPTION: " + e.getMessage());
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // testFire — called by TEST SHOT button
    // Fires a clear forward swipe so you can see the striker move
    // ─────────────────────────────────────────────────────────────────────────

    public boolean testFire(float x, float y) {
        Log.i(TAG, "TEST FIRE — forward slide UP 250px from ("
            + Math.round(x) + "," + Math.round(y) + ")");
        // Shoot straight up (toward coins above striker)
        return fireSmooth(x, y, x, y - 250f, "TEST-FwdUp");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // rootSwipe — shell fallback (rooted/ADB devices)
    // ─────────────────────────────────────────────────────────────────────────

    private void rootSwipe(float sx, float sy, float ex, float ey) {
        String cmd = String.format("input swipe %.0f %.0f %.0f %.0f 300", sx, sy, ex, ey);
        Log.i(TAG, "S4-Root: " + cmd);
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            int rc = p.waitFor();
            lastResult = "S4-root:rc=" + rc;
            Log.i(TAG, "S4-root exit=" + rc);
        } catch (Exception e1) {
            try {
                Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
                lastResult = "S4-sh:sent";
            } catch (Exception e2) {
                lastResult = "S4:no-shell";
                Log.w(TAG, "S4: no shell access — device is not rooted");
            }
        }
    }

    public static boolean isReady()   { return INSTANCE != null; }
    public static String  getStatus() { return INSTANCE == null ? "NOT CONNECTED" : "OK last=" + lastResult; }
}
