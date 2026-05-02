package com.bitaim.carromaim.auto;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * AutoShootService — v8.6 ENGINE
 *
 * Previous versions swept the gesture 250–550 px across the whole screen
 * (a full-screen swipe). Carrom Disc Pool does NOT need that. Its touch
 * handler reads only:
 *   1. WHERE the gesture starts  (must be on/near the striker circle)
 *   2. The DIRECTION of the drag (determines shot angle)
 *   3. The VELOCITY of the drag  (determines shot power — NOT total distance)
 *
 * v8.6 "local flick" engine
 * ─────────────────────────
 *   • Gesture stays LOCAL to the striker — maximum 90 px travel
 *   • Very fast (20–35 ms) → high velocity → registers as a real shot
 *   • Two-phase: tiny 8-px pre-touch THEN the directional flick
 *     This pre-touch ensures the game "sees" the striker before the swipe,
 *     matching exactly how a human finger touches-then-drags the striker.
 *   • Shot angle is derived purely from the CarromAI physics engine output:
 *     the angle from striker centre → ghost-ball contact point.
 *
 * No screen-wide swipes. No full-board gestures. Pure local striker control.
 */
public class AutoShootService extends AccessibilityService {

    private static final String TAG = "AutoShootService";

    public static volatile AutoShootService INSTANCE;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        INSTANCE = this;
        Log.i(TAG, "AutoShootService v8.6 ENGINE connected");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() { Log.w(TAG, "interrupted"); }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        INSTANCE = null;
        Log.i(TAG, "AutoShootService destroyed");
    }

    /**
     * Fire the striker using a LOCAL flick gesture.
     *
     * The gesture is a two-stroke sequence:
     *
     *   Stroke 0 — Pre-touch (8 px, 18 ms):
     *     Touch down at striker centre, hold briefly so the game recognises
     *     the striker is being interacted with. Ends just 8 px forward.
     *     willContinue = true  (stroke 1 immediately follows).
     *
     *   Stroke 1 — Power flick (60–90 px, 20–35 ms):
     *     Fast drag in the shot direction. This is the actual shot gesture.
     *     Starts where stroke 0 ended (8 px ahead), ends at 60–90 px total.
     *     willContinue = false (releases the finger here).
     *
     * Total finger travel: ~70–100 px — completely local to the striker.
     * Velocity: ~2 000–4 500 px/s — registers as a strong, fast shot.
     *
     * @param strikerX   striker centre X (screen pixels, from CV detection)
     * @param strikerY   striker centre Y
     * @param targetX    ghost-ball aim point X (determines shot angle only)
     * @param targetY    ghost-ball aim point Y
     * @param powerFrac  0.0 (soft) … 1.0 (maximum power)
     */
    public void shoot(final float strikerX, final float strikerY,
                      final float targetX,  final float targetY,
                      final float powerFrac) {

        float power = Math.min(1.0f, Math.max(0.0f, powerFrac));

        // Compute normalised shot direction (striker → ghost-ball)
        float dx  = targetX - strikerX;
        float dy  = targetY - strikerY;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f) {
            Log.w(TAG, "shoot: degenerate direction — skip");
            return;
        }
        float nx = dx / len;
        float ny = dy / len;

        // ── Phase geometry ────────────────────────────────────────────────────

        // Pre-touch: tiny 8 px forward movement so game recognises striker
        float preTouchDist = 8f;

        // Flick distance: 60 px (soft) → 90 px (max power)
        // Kept LOCAL — never leaves the striker area on any screen size.
        float flickDist = 60f + power * 30f;           // 60–90 px

        // Flick duration: 20 ms (max power) → 35 ms (soft)
        // Velocity at max power: 90 px / 20 ms = 4 500 px/s (hard shot)
        // Velocity at soft:      60 px / 35 ms = 1 700 px/s (soft shot)
        long flickMs = (long)(35f - power * 15f);      // 20–35 ms

        // Coordinates
        float x0 = strikerX;
        float y0 = strikerY;
        float x1 = strikerX + nx * preTouchDist;
        float y1 = strikerY + ny * preTouchDist;
        float x2 = strikerX + nx * flickDist;
        float y2 = strikerY + ny * flickDist;

        Log.i(TAG, String.format(
            "shoot v8.6 — pwr=%.2f dir=(%.2f,%.2f) flickDist=%.0f flickMs=%d",
            power, nx, ny, flickDist, flickMs));

        try {
            // Stroke 0: pre-touch (18 ms hold, willContinue = true)
            Path prePath = new Path();
            prePath.moveTo(x0, y0);
            prePath.lineTo(x1, y1);
            GestureDescription.StrokeDescription preStroke =
                new GestureDescription.StrokeDescription(prePath, 0L, 18L, true);

            // Stroke 1: power flick (willContinue = false → finger-up)
            Path flickPath = new Path();
            flickPath.moveTo(x1, y1);
            flickPath.lineTo(x2, y2);
            GestureDescription.StrokeDescription flickStroke =
                preStroke.continueStroke(flickPath, 0L, flickMs, false);

            GestureDescription.Builder gb = new GestureDescription.Builder();
            gb.addStroke(preStroke);
            gb.addStroke(flickStroke);

            boolean ok = dispatchGesture(gb.build(), new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription g) {
                    Log.i(TAG, "ENGINE: shot fired OK");
                }
                @Override public void onCancelled(GestureDescription g) {
                    Log.w(TAG, "ENGINE: gesture cancelled — retrying in 100 ms");
                    handler.postDelayed(() -> fireSingleStroke(
                        x0, y0, x2, y2, flickMs), 100);
                }
            }, null);

            if (!ok) {
                Log.w(TAG, "dispatchGesture busy — fallback single stroke");
                fireSingleStroke(x0, y0, x2, y2, flickMs);
            }

        } catch (Exception e) {
            // continueStroke may throw on older APIs — fall back gracefully
            Log.w(TAG, "Two-stroke not supported, using single stroke: " + e.getMessage());
            fireSingleStroke(x0, y0, x2, y2, flickMs);
        }
    }

    /**
     * Single-stroke fallback: one fast swipe from striker centre to flick endpoint.
     * Used on devices where continueStroke() is unavailable (API < 26 edge cases)
     * or when the two-stroke gesture is cancelled.
     */
    private void fireSingleStroke(float fromX, float fromY,
                                   float toX,   float toY,
                                   long durationMs) {
        Path path = new Path();
        path.moveTo(fromX, fromY);
        path.lineTo(toX, toY);

        GestureDescription.Builder gb = new GestureDescription.Builder();
        gb.addStroke(new GestureDescription.StrokeDescription(path, 0L, durationMs));

        boolean ok = dispatchGesture(gb.build(), new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) {
                Log.i(TAG, "Fallback stroke: shot fired OK");
            }
            @Override public void onCancelled(GestureDescription g) {
                Log.w(TAG, "Fallback stroke cancelled");
            }
        }, null);

        if (!ok) Log.e(TAG, "fireSingleStroke: dispatchGesture returned false");
    }

    public static boolean isReady() { return INSTANCE != null; }
}
