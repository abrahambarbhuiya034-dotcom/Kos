package com.bitaim.carromaim.auto;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * AutoShootService — v8.4 FIXED
 *
 * Fixes vs v8.3:
 *  1. Gesture now starts BEHIND the striker (opposite to shot direction) and
 *     flicks THROUGH the striker toward the target. This matches how a real
 *     carrom shot is executed and is far more reliably detected by Carrom
 *     Disc Pool's touch handler than a swipe starting at the exact centre.
 *
 *  2. Swipe distance formula retuned: 160 px base + 300 px × powerFrac.
 *     Old formula (120 + 240) was too short and produced weak shots on
 *     high-density screens. New range: 160 px (soft) → 460 px (full power).
 *
 *  3. Duration now 80–140 ms (was 60–120 ms). Slightly slower gestures
 *     avoid being treated as "spam taps" by the game's input filter.
 *
 *  4. Backstep distance scales with power: 25 px (soft) → 55 px (hard),
 *     so the total gesture feels proportional across all shot strengths.
 */
public class AutoShootService extends AccessibilityService {

    private static final String TAG = "AutoShootService";

    /** Singleton — set on connection, cleared on destroy. */
    public static volatile AutoShootService INSTANCE;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        INSTANCE = this;
        Log.i(TAG, "AutoShootService connected — gesture injection ready");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // We only inject gestures — no event observation needed.
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "AutoShootService interrupted");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        INSTANCE = null;
        Log.i(TAG, "AutoShootService destroyed");
    }

    /**
     * Inject a swipe gesture that shoots the striker.
     *
     * The gesture starts slightly BEHIND the striker (opposite to shot direction)
     * and flicks forward through the striker toward the target. This produces a
     * natural "flick" motion that Carrom Disc Pool reliably registers as a shot.
     *
     * @param strikerX  striker centre X on screen (pixels)
     * @param strikerY  striker centre Y on screen (pixels)
     * @param targetX   aim target X on screen (ghost-ball direction)
     * @param targetY   aim target Y on screen
     * @param powerFrac shot power 0.0 (soft) … 1.0 (hard), typically 0.7
     */
    public void shoot(float strikerX, float strikerY,
                      float targetX,  float targetY,
                      float powerFrac) {
        // Clamp power
        powerFrac = Math.min(1.0f, Math.max(0.0f, powerFrac));

        // Direction from striker toward target (normalised)
        float dx  = targetX - strikerX;
        float dy  = targetY - strikerY;
        float len = (float) Math.sqrt(dx*dx + dy*dy);
        if (len < 1f) {
            Log.w(TAG, "shoot: target too close to striker, skipping");
            return;
        }
        float nx = dx / len;
        float ny = dy / len;

        // ── FIX: start BEHIND the striker, flick through and past it ──────────
        //
        // backstep: how far behind the striker the gesture starts
        //   Scales with power so the total arc length feels proportional.
        float backstep = 25f + powerFrac * 30f;          // 25–55 px

        // forward reach: how far past the striker toward target the gesture ends
        //   160 px base + 300 px × power → total forward reach 160–460 px
        float fwdDist = 160f + powerFrac * 300f;

        // Gesture start: behind the striker (opposite to shot direction)
        float fromX = strikerX - nx * backstep;
        float fromY = strikerY - ny * backstep;

        // Gesture end: forward past the striker in shot direction
        float toX = strikerX + nx * fwdDist;
        float toY = strikerY + ny * fwdDist;

        // Duration: 80 ms (hard) → 140 ms (soft) — slightly slower than v8.3
        // to avoid the game's input debounce filter.
        long durationMs = (long)(140 - powerFrac * 60);

        Path path = new Path();
        path.moveTo(fromX, fromY);
        path.lineTo(toX,   toY);

        GestureDescription.Builder gb = new GestureDescription.Builder();
        gb.addStroke(new GestureDescription.StrokeDescription(path, 0L, durationMs));

        boolean ok = dispatchGesture(gb.build(), new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) {
                Log.d(TAG, "Gesture completed — shot fired");
            }
            @Override public void onCancelled(GestureDescription g) {
                Log.w(TAG, "Gesture cancelled by system");
            }
        }, null);

        if (!ok) {
            Log.e(TAG, "dispatchGesture returned false — " +
                "check that AIMxASSIST is enabled in Accessibility Settings " +
                "and has 'Perform gestures' permission granted");
        }
    }

    /** @return true if this service is connected and ready to inject gestures. */
    public static boolean isReady() { return INSTANCE != null; }
}
