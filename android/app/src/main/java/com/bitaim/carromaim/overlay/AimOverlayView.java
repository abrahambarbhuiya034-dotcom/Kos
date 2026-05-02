package com.bitaim.carromaim.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.view.View;

import com.bitaim.carromaim.cv.CarromAI;
import com.bitaim.carromaim.cv.Coin;
import com.bitaim.carromaim.cv.GameState;

import java.util.ArrayList;
import java.util.List;

/**
 * AimOverlayView — v8.6 CLEAN
 *
 * All aim lines, arrows, and coin circles have been removed.
 * The overlay is now a minimal transparent HUD:
 *   • Thin dashed board border  (orientation only)
 *   • Small pocket indicator dots
 *   • Thin gold ring around the striker  (shows CV-detected position)
 *   • Watermark text
 *
 * All physics / shot-computation still runs internally for AutoPlay.
 * The CarromAI engine computes the best shot every frame and exposes it
 * via getLastBestShot() → AutoShootService uses it to fire.
 *
 * NO visual lines are drawn on the screen.
 */
public class AimOverlayView extends View {

    public static final String MODE_ALL    = "ALL";
    public static final String MODE_DIRECT = "DIRECT";
    public static final String MODE_AI     = "AI";
    public static final String MODE_GOLDEN = "GOLDEN";
    public static final String MODE_LUCKY  = "LUCKY";

    private static final float EMA_ALPHA       = 0.25f;
    private static final float CACHE_THRESH_PX = 12f;

    // ── Paints ────────────────────────────────────────────────────────────────

    private final Paint boardPaint;       // thin dashed border
    private final Paint pocketPaint;      // small pocket dots
    private final Paint strikerRingPaint; // ring around detected striker
    private final Paint watermarkPaint;

    private final float dp;

    // ── State ─────────────────────────────────────────────────────────────────

    private String    shotMode   = MODE_ALL;
    private GameState detected;
    private GameState smoothed;
    private boolean   hasLiveData = false;

    // ── Shot cache (for AutoPlay — no visual output) ───────────────────────────

    private float cacheStrikerX  = Float.NaN;
    private float cacheStrikerY  = Float.NaN;
    private int   cacheCoinsHash = -1;
    private List<CarromAI.AiShot> cachedShots = new ArrayList<>();

    // ── BestShot ──────────────────────────────────────────────────────────────

    public static class BestShot {
        public final float strikerX, strikerY, targetX, targetY, powerFrac;
        public BestShot(float sx, float sy, float tx, float ty, float pw) {
            strikerX = sx; strikerY = sy;
            targetX  = tx; targetY  = ty;
            powerFrac = pw;
        }
    }

    private volatile BestShot lastBestShot;

    public BestShot getLastBestShot() {
        return hasLiveData ? lastBestShot : null;
    }

    public void setPhysicsBestShot(CarromAI.AiShot shot, GameState state) {
        if (shot == null || state == null || state.striker == null) return;
        float dx = shot.ghostPos.x - state.striker.pos.x;
        float dy = shot.ghostPos.y - state.striker.pos.y;
        lastBestShot = new BestShot(
            state.striker.pos.x, state.striker.pos.y,
            state.striker.pos.x + dx * 1.20f,
            state.striker.pos.y + dy * 1.20f,
            shot.powerFrac);
    }

    // ── AutoPlay swipe listener ───────────────────────────────────────────────

    public interface AutoplaySwipeListener {
        void onPerformSwipe(float fromX, float fromY,
                            float toX,   float toY,
                            int durationMs, float powerFrac);
    }

    private AutoplaySwipeListener autoplaySwipeListener;

    public void setAutoplaySwipeListener(AutoplaySwipeListener l) {
        autoplaySwipeListener = l;
    }

    public void performBestSwipe() {
        if (!hasLiveData) return;
        BestShot bs = lastBestShot;
        if (bs == null || autoplaySwipeListener == null) return;
        autoplaySwipeListener.onPerformSwipe(
            bs.strikerX, bs.strikerY,
            bs.targetX,  bs.targetY,
            25, bs.powerFrac);  // 25 ms — fast local flick
    }

    // ── Constructor ───────────────────────────────────────────────────────────

    public AimOverlayView(Context context) {
        super(context);
        dp = context.getResources().getDisplayMetrics().density;

        // Thin dashed board border
        boardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boardPaint.setColor(0x55FFD700);
        boardPaint.setStyle(Paint.Style.STROKE);
        boardPaint.setStrokeWidth(1.5f * dp);
        boardPaint.setPathEffect(new DashPathEffect(new float[]{5*dp, 7*dp}, 0));

        // Pocket dots
        pocketPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pocketPaint.setColor(0x882ECC71);
        pocketPaint.setStyle(Paint.Style.FILL);

        // Striker ring
        strikerRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strikerRingPaint.setColor(0xCCFFD700);
        strikerRingPaint.setStyle(Paint.Style.STROKE);
        strikerRingPaint.setStrokeWidth(2f * dp);

        // Watermark
        watermarkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        watermarkPaint.setColor(0x33FFFFFF);
        watermarkPaint.setTextSize(8f * dp);
        watermarkPaint.setTextAlign(Paint.Align.CENTER);

        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setShotMode(String mode) {
        this.shotMode = mode;
        invalidateCache();
        postInvalidate();
    }

    public void setDetectedState(GameState s) { setStateInternal(s, true); }
    public void setDemoState(GameState s)     { setStateInternal(s, false); }

    private void setStateInternal(GameState s, boolean live) {
        if (s == null) return;
        if (live) hasLiveData = true;
        detected = s;
        applySmoothing(s);
        rebuildCacheIfNeeded();
        postInvalidate();
    }

    // ── Cache ─────────────────────────────────────────────────────────────────

    private void invalidateCache() {
        cacheStrikerX  = Float.NaN;
        cacheStrikerY  = Float.NaN;
        cacheCoinsHash = -1;
    }

    private void rebuildCacheIfNeeded() {
        GameState raw = detected;
        GameState s   = smoothed != null ? smoothed : detected;
        if (s == null || s.striker == null || raw == null) { invalidateCache(); return; }

        float sx = raw.striker.pos.x, sy = raw.striker.pos.y;
        int   ch = coinsHash(raw);

        boolean strikerMoved = Float.isNaN(cacheStrikerX) ||
            (sx-cacheStrikerX)*(sx-cacheStrikerX) +
            (sy-cacheStrikerY)*(sy-cacheStrikerY) >= CACHE_THRESH_PX * CACHE_THRESH_PX;
        boolean coinsChanged = (ch != cacheCoinsHash);

        if (!strikerMoved && !coinsChanged) return;

        cacheStrikerX  = sx;
        cacheStrikerY  = sy;
        cacheCoinsHash = ch;

        // Compute shots (engine still runs — AutoPlay uses them, no visual output)
        cachedShots = computeShots(s);

        if (!cachedShots.isEmpty()) {
            CarromAI.AiShot best = cachedShots.get(0);
            float dx = best.ghostPos.x - s.striker.pos.x;
            float dy = best.ghostPos.y - s.striker.pos.y;
            lastBestShot = new BestShot(
                s.striker.pos.x, s.striker.pos.y,
                s.striker.pos.x + dx * 1.20f,
                s.striker.pos.y + dy * 1.20f,
                best.powerFrac);
        } else {
            lastBestShot = null;
        }
    }

    private List<CarromAI.AiShot> computeShots(GameState s) {
        // Keep top-1 only — we just need the best shot for AutoPlay
        return CarromAI.findBestShots(s, 1);
    }

    private static int coinsHash(GameState s) {
        int h = s.coins.size() * 31;
        for (Coin c : s.coins) {
            h = h * 31 + Math.round(c.pos.x / 8f);
            h = h * 31 + Math.round(c.pos.y / 8f);
        }
        return h;
    }

    // ── EMA smoothing ─────────────────────────────────────────────────────────

    private void applySmoothing(GameState raw) {
        if (smoothed == null) { smoothed = raw; return; }
        GameState out = new GameState();
        out.board   = smoothRect(smoothed.board, raw.board);
        out.striker = (raw.striker != null && smoothed.striker != null)
            ? new Coin(ema(smoothed.striker.pos.x, raw.striker.pos.x),
                       ema(smoothed.striker.pos.y, raw.striker.pos.y),
                       ema(smoothed.striker.radius, raw.striker.radius),
                       Coin.COLOR_STRIKER, true)
            : raw.striker;
        out.coins   = raw.coins;   // no coin smoothing needed — not displayed
        out.pockets = raw.pockets.isEmpty() ? smoothed.pockets : raw.pockets;
        smoothed = out;
    }

    private RectF smoothRect(RectF p, RectF n) {
        if (p == null) return n; if (n == null) return p;
        return new RectF(ema(p.left,n.left), ema(p.top,n.top),
                         ema(p.right,n.right), ema(p.bottom,n.bottom));
    }
    private float ema(float p, float n) { return p + EMA_ALPHA * (n - p); }

    // ── DRAW — minimal HUD only, zero aim lines ───────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        GameState s = smoothed != null ? smoothed : detected;
        if (s == null) return;

        // Board border (thin dashed gold rectangle)
        if (s.board != null) {
            canvas.drawRect(s.board, boardPaint);
            canvas.drawText("created by abraham / Xhay",
                s.board.centerX(), s.board.centerY(), watermarkPaint);
        }

        // Pocket dots (small, semi-transparent)
        float pocketR = (s.board != null) ? s.board.width() * 0.022f : 8 * dp;
        for (PointF p : s.pockets)
            canvas.drawCircle(p.x, p.y, pocketR, pocketPaint);

        // Striker ring — shows where CV thinks the striker is
        if (s.striker != null) {
            canvas.drawCircle(
                s.striker.pos.x, s.striker.pos.y,
                s.striker.radius, strikerRingPaint);
        }

        // NO coins, NO lines, NO arrows drawn.
    }
}
