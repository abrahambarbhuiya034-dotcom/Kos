package com.bitaim.carromaim.auto;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * AutoShootService — v8.5
 *
 * ROOT CAUSE of v8.4 failure
 * ─────────────────────────
 * The gesture started BEHIND the striker (opposite to the shot direction).
 * Carrom Disc Pool's touch handler only registers a shot when:
 *   (a) the touch-DOWN point is ON or very close to the striker circle, AND
 *   (b) the subsequent drag moves in the desired shot direction.
 * Starting behind the striker meant the game never associated the gesture
 * with the striker at all — it was ignored entirely.
 *
 * v8.5 fixes
 * ──────────
 * 1. Gesture now starts EXACTLY AT the striker centre (touch-down on striker).
 *    The drag goes straight toward the target direction — the way a real
 *    player's finger moves when shooting.
 *
 * 2. Duration drastically reduced: 30–55 ms (was 80–140 ms).
 *    Shorter duration → higher gesture velocity → harder shot perceived by game.
 *    The game measures velocity to determine shot power, not distance alone.
 *
 * 3. Swipe distance extended: 250–550 px (was 160–460 px).
 *    Combined with shorter duration this doubles the peak gesture velocity.
 *
 * 4. Retry mechanism: if dispatchGesture returns false (gesture engine busy),
 *    a second attempt fires 80 ms later via Handler — catches rare cases where
 *    the accessibility service is momentarily occupied.
 */
public class AutoShootService extends AccessibilityService {

    private static final String TAG = "AutoShootService";

    public static volatile AutoShootService INSTANCE;

    private final Handler retryHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        INSTANCE = this;
        Log.i(TAG, "AutoShootService v8.5 connected — gesture engine ready");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() { Log.w(TAG, "AutoShootService interrupted"); }

    @Override
    public void onDestroy() {
        super.onDestroy();
        retryHandler.removeCallbacksAndMessages(null);
        INSTANCE = null;
        Log.i(TAG, "AutoShootService destroyed");
    }

    /**
     * Inject a swipe gesture that fires the striker.
     *
     * Gesture profile:
     *   - Touch DOWN at (strikerX, strikerY)  — exactly on the striker circle
     *   - Drag toward target direction at high velocity
     *   - Touch UP after 30–55 ms
     *
     * @param strikerX  striker centre X in screen pixels
     * @param strikerY  striker centre Y in screen pixels
     * @param targetX   aim direction reference X (ghost-ball extended point)
     * @param targetY   aim direction reference Y
     * @param powerFrac shot power 0.0 (soft) → 1.0 (max), typically 0.65–0.85
     */
    public void shoot(final float strikerX, final float strikerY,
                      final float targetX,  final float targetY,
                      final float powerFrac) {

        float power = Math.min(1.0f, Math.max(0.0f, powerFrac));

        float dx  = targetX - strikerX;
        float dy  = targetY - strikerY;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f) {
            Log.w(TAG, "shoot: degenerate direction — skipping");
            return;
        }
        float nx = dx / len;
        float ny = dy / len;

        // ── Gesture parameters ────────────────────────────────────────────────
        //
        // Start: exactly at striker centre so the game's touch handler
        //        recognises this as a striker interaction.
        float fromX = strikerX;
        float fromY = strikerY;

        // End: 250 px (soft) → 550 px (max power) forward from striker
        float swipeDist = 250f + power * 300f;
        float toX = strikerX + nx * swipeDist;
        float toY = strikerY + ny * swipeDist;

        // Duration: 30 ms (max power) → 55 ms (soft)
        // Shorter = higher velocity = more power perceived by game.
        long durationMs = (long)(55f - power * 25f);   // 30–55 ms

        Log.i(TAG, String.format(
            "shoot v8.5 — from=(%.0f,%.0f) to=(%.0f,%.0f) dist=%.0f dur=%dms pwr=%.2f",
            fromX, fromY, toX, toY, swipeDist, durationMs, power));

        boolean dispatched = fireGesture(fromX, fromY, toX, toY, durationMs);

        // Retry once if gesture engine was momentarily busy
        if (!dispatched) {
            Log.w(TAG, "shoot: dispatchGesture busy — will retry in 80 ms");
            retryHandler.postDelayed(() ->
                fireGesture(fromX, fromY, toX, toY, durationMs), 80);
        }
    }

    private boolean fireGesture(float fromX, float fromY,
                                 float toX,   float toY,
                                 long durationMs) {
        Path path = new Path();
        path.moveTo(fromX, fromY);
        path.lineTo(toX, toY);

        GestureDescription.Builder gb = new GestureDescription.Builder();
        gb.addStroke(new GestureDescription.StrokeDescription(path, 0L, durationMs));

        boolean ok = dispatchGesture(gb.build(), new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) {
                Log.i(TAG, "Gesture completed — shot fired OK");
            }
            @Override public void onCancelled(GestureDescription g) {
                Log.w(TAG, "Gesture cancelled — check overlay is on top and game is focused");
            }
        }, null);

        if (!ok) {
            Log.e(TAG, "dispatchGesture returned false — " +
                "ensure AIMxASSIST is enabled in Accessibility Settings");
        }
        return ok;
    }

    public static boolean isReady() { return INSTANCE != null; }
}
