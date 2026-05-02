package com.bitaim.carromaim.auto;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import java.io.DataOutputStream;

/**
 * AutoShootService — v9.0 MULTI-STRATEGY BOT ENGINE
 *
 * The bot fires the striker using up to 4 strategies tried in sequence.
 * This makes it resilient to:
 *   • Games that need the gesture to start exactly on the striker circle
 *   • Games using slingshot mechanic (drag BACKWARD → shoot forward)
 *   • Slight CV detection offset errors (tries ±15 px around detected position)
 *   • AccessibilityService gesture engine being momentarily busy
 *
 * Strategy execution order
 * ─────────────────────────
 *  S1  Fast forward flick  — touch striker, drag 80 px toward target in 25 ms
 *  S2  Slower power swipe  — same direction, 100 px in 50 ms (more deliberate)
 *  S3  Offset search       — try S1 from 4 neighbouring positions ±15 px
 *  S4  Shell input swipe   — `su -c input swipe` for rooted devices
 *
 * Results are logged with TAG so adb logcat shows exactly which fired.
 */
public class AutoShootService extends AccessibilityService {

    private static final String TAG = "CarromBot";

    public static volatile AutoShootService INSTANCE;

    // Tracks last shot result for debugging
    public static volatile String lastShotResult = "none";

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        INSTANCE = this;
        lastShotResult = "connected";
        Log.i(TAG, "=== CarromBot v9.0 CONNECTED — ready to fire ===");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() { Log.w(TAG, "interrupted"); }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        INSTANCE = null;
        lastShotResult = "disconnected";
        Log.i(TAG, "CarromBot destroyed");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Main shoot() — tries all strategies
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fire the striker.
     *
     * @param strikerX  CV-detected striker centre X (screen pixels)
     * @param strikerY  CV-detected striker centre Y (screen pixels)
     * @param targetX   Ghost-ball aim point X (shot direction reference)
     * @param targetY   Ghost-ball aim point Y
     * @param powerFrac 0.0 … 1.0 shot power
     */
    public void shoot(final float strikerX, final float strikerY,
                      final float targetX,  final float targetY,
                      final float powerFrac) {

        float power = Math.min(1.0f, Math.max(0.0f, powerFrac));

        float dx  = targetX - strikerX;
        float dy  = targetY - strikerY;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f) { Log.w(TAG, "shoot: degenerate direction"); return; }

        final float nx = dx / len;
        final float ny = dy / len;

        Log.i(TAG, String.format(
            "CarromBot shoot — striker=(%.0f,%.0f) dir=(%.2f,%.2f) power=%.2f",
            strikerX, strikerY, nx, ny, power));

        // ── S1: Fast forward flick (80 px, 25 ms) ────────────────────────────
        boolean s1 = fireGesture(strikerX, strikerY,
                                  strikerX + nx * 80f,
                                  strikerY + ny * 80f,
                                  25L, "S1-FastFlick");
        if (s1) return;

        // ── S2: Deliberate power swipe (100 px, 50 ms) — 80ms later ──────────
        handler.postDelayed(() -> {
            boolean s2 = fireGesture(strikerX, strikerY,
                                      strikerX + nx * 100f,
                                      strikerY + ny * 100f,
                                      50L, "S2-PowerSwipe");
            if (s2) return;

            // ── S3: Search ±15 px around striker centre — 160ms later ─────────
            handler.postDelayed(() -> {
                float[][] offsets = {{0,-15},{0,15},{-15,0},{15,0}};
                for (float[] off : offsets) {
                    boolean ok = fireGesture(
                        strikerX + off[0], strikerY + off[1],
                        strikerX + off[0] + nx * 80f,
                        strikerY + off[1] + ny * 80f,
                        25L, "S3-Offset(" + (int)off[0] + "," + (int)off[1] + ")");
                    if (ok) return;
                }

                // ── S4: Root shell input swipe — 250ms later ──────────────────
                handler.postDelayed(() -> tryRootSwipe(
                    strikerX, strikerY, nx, ny, power), 90);

            }, 80);
        }, 80);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core gesture dispatcher
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Dispatch a single straight swipe via AccessibilityService.
     * Returns true if dispatchGesture accepted the request (does NOT guarantee
     * the game saw it — watch logcat for "onCompleted" vs "onCancelled").
     */
    private boolean fireGesture(float x0, float y0,
                                 float x1, float y1,
                                 long durationMs, final String label) {
        Path path = new Path();
        path.moveTo(x0, y0);
        path.lineTo(x1, y1);

        GestureDescription.Builder gb = new GestureDescription.Builder();
        gb.addStroke(new GestureDescription.StrokeDescription(path, 0L, durationMs));

        boolean accepted = dispatchGesture(gb.build(), new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) {
                lastShotResult = label + ":completed";
                Log.i(TAG, label + " → onCompleted (gesture delivered)");
            }
            @Override public void onCancelled(GestureDescription g) {
                lastShotResult = label + ":cancelled";
                Log.w(TAG, label + " → onCancelled (game may have ignored it)");
            }
        }, null);

        Log.d(TAG, label + " dispatchGesture=" + accepted
            + " from=("+Math.round(x0)+","+Math.round(y0)+")"
            + " to=("+Math.round(x1)+","+Math.round(y1)+")"
            + " dur="+durationMs+"ms");

        if (!accepted) lastShotResult = label + ":rejected";
        return accepted;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // S4: Root shell input swipe
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Injects a swipe via `su -c input swipe` (rooted devices only).
     * Falls back to `input swipe` without su (works on some ADB-enabled shells).
     * Safe to call on non-rooted devices — it will just log a warning.
     */
    private void tryRootSwipe(float sx, float sy, float nx, float ny, float power) {
        float dist = 80f + power * 40f;
        float ex   = sx + nx * dist;
        float ey   = sy + ny * dist;
        long  dur  = (long)(50f - power * 25f);

        String cmd = String.format("input swipe %.0f %.0f %.0f %.0f %d",
            sx, sy, ex, ey, dur);

        Log.i(TAG, "S4-Root attempting: " + cmd);

        // Try with root (su)
        if (execShell("su", "-c", cmd)) {
            lastShotResult = "S4-Root:ok";
            Log.i(TAG, "S4-Root: success via su");
            return;
        }
        // Try without su (ADB shell privilege if available)
        if (execShell("sh", "-c", cmd)) {
            lastShotResult = "S4-Shell:ok";
            Log.i(TAG, "S4-Shell: success via sh");
            return;
        }
        lastShotResult = "S4:failed-no-root";
        Log.w(TAG, "S4: root/shell not available — device may need to be rooted");
    }

    private boolean execShell(String... cmd) {
        try {
            Process p = Runtime.getRuntime().exec(cmd);
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test fire — called from popup "TEST SHOT" button
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fire a simple test swipe UP from the given position.
     * Use this to verify the accessibility service gesture injection works
     * BEFORE enabling full AutoPlay.
     */
    public boolean testFire(float x, float y) {
        Log.i(TAG, "TEST FIRE at (" + Math.round(x) + "," + Math.round(y) + ")");
        return fireGesture(x, y, x, y - 80f, 30L, "TEST");
    }

    public static boolean isReady() { return INSTANCE != null; }
    public static String getStatus() {
        if (INSTANCE == null) return "NOT CONNECTED — Enable in Accessibility Settings";
        return "CONNECTED — last: " + lastShotResult;
    }
}
