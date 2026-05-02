package com.bitaim.carromaim.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.view.View;

import com.bitaim.carromaim.cv.CarromAI;
import com.bitaim.carromaim.cv.Coin;
import com.bitaim.carromaim.cv.GameState;
import com.bitaim.carromaim.cv.TrajectorySimulator;

import java.util.ArrayList;
import java.util.List;

/**
 * AimOverlayView — v8.4
 *
 * Visual changes vs v8.3:
 *  - Removed coin-circle drawing (no more transparent ghost discs cluttering board)
 *  - Removed full aim-lines from striker position
 *  - NEW: directional arrow drawn at the LAST POINT of each shot (ghost-ball endpoint)
 *    The arrow points in the direction of travel (striker → ghost).
 *  - Coin→pocket line kept (shows where the targeted coin will travel)
 *  - Best shot arrow: bright gold; lower ranks: progressively dimmer cyan/white
 */
public class AimOverlayView extends View {

    public static final String MODE_ALL    = "ALL";
    public static final String MODE_DIRECT = "DIRECT";
    public static final String MODE_AI     = "AI";
    public static final String MODE_GOLDEN = "GOLDEN";
    public static final String MODE_LUCKY  = "LUCKY";

    private static final int   MAX_LINES       = 5;
    private static final float EMA_ALPHA       = 0.25f;
    private static final float CACHE_THRESH_PX = 12f;

    // ── Arrow visual parameters per rank ──────────────────────────────────────

    /** Arrow head size multiplier relative to striker radius. */
    private static final float[] ARROW_SCALE = { 1.5f, 1.2f, 1.0f, 0.85f, 0.70f };
    /** Glow ring alpha per rank. */
    private static final int[]   GLOW_ALPHA  = { 80,  60,  45,  32, 22 };
    /** Arrow fill alpha per rank. */
    private static final int[]   FILL_ALPHA  = { 255, 210, 170, 130, 90 };
    /** Glow ring width per rank (dp). */
    private static final float[] GLOW_WIDTH  = { 18f, 14f, 11f, 9f, 7f };
    /** Stem line width per rank (dp). */
    private static final float[] STEM_WIDTH  = {  4f,  3f,  2.5f, 2f, 1.5f };

    // Best shot: gold; others: cyan→white gradient
    private static final int[] ARROW_COLORS = {
        0xFFFFD700,   // rank 0 — gold
        0xFF00E5FF,   // rank 1 — cyan
        0xFFFFFFFF,   // rank 2 — white
        0xFFBBBBFF,   // rank 3 — lavender
        0xFF888888,   // rank 4 — grey
    };

    // Coin→pocket line paints
    private static final int COIN_GLOW_COLOR = 0xFFCCDDFF;
    private static final int COIN_CORE_COLOR = 0xFF1A1A2E;
    private static final float[] COIN_GLOW_W = { 14f, 11f, 9f, 7f, 5f };
    private static final float[] COIN_CORE_W = {  4f,  3f,  2.5f, 2f, 1.5f };
    private static final int[]   COIN_GLOW_A = { 55,  42, 32, 24, 16 };
    private static final int[]   COIN_CORE_A = { 255, 220, 185, 150, 110 };

    // ── Per-rank paints ───────────────────────────────────────────────────────

    private final Paint[] arrowGlowPaints = new Paint[MAX_LINES];   // glow ring
    private final Paint[] arrowFillPaints = new Paint[MAX_LINES];   // filled arrowhead
    private final Paint[] stemPaints      = new Paint[MAX_LINES];   // tail stem
    private final Paint[] coinGlowPaints  = new Paint[MAX_LINES];
    private final Paint[] coinCorePaints  = new Paint[MAX_LINES];

    // ── Board / striker / misc paints ─────────────────────────────────────────

    private final Paint boardPaint, boardDemoPaint;
    private final Paint strikerRingPaint, strikerFillPaint;
    private final Paint pocketFill;
    private final Paint watermarkPaint;

    // Reused Path to avoid per-frame allocation
    private final Path arrowPath = new Path();

    private final TrajectorySimulator simulator = new TrajectorySimulator();
    private String    shotMode   = MODE_ALL;
    private GameState detected;
    private GameState smoothed;
    private boolean   hasLiveData = false;
    private final float dp;

    // ── Trajectory cache ──────────────────────────────────────────────────────
    private float cacheStrikerX  = Float.NaN;
    private float cacheStrikerY  = Float.NaN;
    private int   cacheCoinsHash = -1;
    private List<CarromAI.AiShot> cachedShots = new ArrayList<>();

    // ── BestShot ──────────────────────────────────────────────────────────────
    public static class BestShot {
        public final float strikerX, strikerY, targetX, targetY, powerFrac;
        public BestShot(float sx, float sy, float tx, float ty, float pw) {
            strikerX = sx; strikerY = sy; targetX = tx; targetY = ty; powerFrac = pw;
        }
    }
    private volatile BestShot lastBestShot;
    public BestShot getLastBestShot() { return hasLiveData ? lastBestShot : null; }

    public void setPhysicsBestShot(CarromAI.AiShot aiShot, GameState state) {
        if (aiShot == null || state == null || state.striker == null) return;
        float dx = aiShot.ghostPos.x - state.striker.pos.x;
        float dy = aiShot.ghostPos.y - state.striker.pos.y;
        lastBestShot = new BestShot(
            state.striker.pos.x, state.striker.pos.y,
            state.striker.pos.x + dx * 1.20f,
            state.striker.pos.y + dy * 1.20f,
            aiShot.powerFrac);
    }

    // ── AutoPlay swipe listener ────────────────────────────────────────────────
    public interface AutoplaySwipeListener {
        void onPerformSwipe(float fromX, float fromY,
                            float toX,   float toY,
                            int durationMs, float powerFrac);
    }
    private AutoplaySwipeListener autoplaySwipeListener;
    public void setAutoplaySwipeListener(AutoplaySwipeListener l) {
        autoplaySwipeListener = l;
    }

    // ── Constructor ───────────────────────────────────────────────────────────

    public AimOverlayView(Context context) {
        super(context);
        dp = context.getResources().getDisplayMetrics().density;

        // Per-rank arrow paints
        for (int i = 0; i < MAX_LINES; i++) {
            int col = ARROW_COLORS[i];

            // Glow ring (stroke)
            arrowGlowPaints[i] = makeStroke(col, GLOW_WIDTH[i] * dp, GLOW_ALPHA[i]);

            // Arrow fill (solid fill for arrowhead triangle)
            arrowFillPaints[i] = makeFill(col);
            arrowFillPaints[i].setAlpha(FILL_ALPHA[i]);

            // Stem line (stroke)
            stemPaints[i] = makeStroke(col, STEM_WIDTH[i] * dp, FILL_ALPHA[i]);

            // Coin→pocket
            coinGlowPaints[i] = makeStroke(COIN_GLOW_COLOR,
                    COIN_GLOW_W[i] * dp, COIN_GLOW_A[i]);
            coinCorePaints[i] = makeStroke(COIN_CORE_COLOR,
                    COIN_CORE_W[i] * dp, COIN_CORE_A[i]);
        }

        // Board outline
        boardPaint = makeStroke(0x88FFD700, 1.5f * dp, 255);
        boardPaint.setPathEffect(new DashPathEffect(new float[]{6*dp, 6*dp}, 0));
        boardDemoPaint = makeStroke(0x44FFD700, 1.0f * dp, 255);
        boardDemoPaint.setPathEffect(new DashPathEffect(new float[]{4*dp, 8*dp}, 0));

        // Striker
        strikerRingPaint = makeStroke(0xFFFFD700, 2.5f * dp, 255);
        strikerFillPaint = makeFill(0x99EEEEEE);

        // Pocket fill
        pocketFill = makeFill(0xAA2ECC71);

        // Watermark
        watermarkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        watermarkPaint.setColor(0x44FFFFFF);
        watermarkPaint.setTextSize(9 * dp);
        watermarkPaint.setTextAlign(Paint.Align.CENTER);
        watermarkPaint.setShadowLayer(dp, 0, 0, Color.BLACK);

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

    // ── Cache management ──────────────────────────────────────────────────────

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
            (sx - cacheStrikerX)*(sx - cacheStrikerX) +
            (sy - cacheStrikerY)*(sy - cacheStrikerY) >= CACHE_THRESH_PX * CACHE_THRESH_PX;
        boolean coinsChanged = (ch != cacheCoinsHash);

        if (!strikerMoved && !coinsChanged) return;

        cacheStrikerX  = sx;
        cacheStrikerY  = sy;
        cacheCoinsHash = ch;
        cachedShots    = computeFilteredShots(s);

        if (!cachedShots.isEmpty()) {
            CarromAI.AiShot best = cachedShots.get(0);
            float dx = best.ghostPos.x - s.striker.pos.x;
            float dy = best.ghostPos.y - s.striker.pos.y;
            lastBestShot = new BestShot(s.striker.pos.x, s.striker.pos.y,
                s.striker.pos.x + dx * 1.20f, s.striker.pos.y + dy * 1.20f, best.powerFrac);
        } else {
            lastBestShot = null;
        }
    }

    private static int coinsHash(GameState s) {
        int h = s.coins.size() * 31;
        for (com.bitaim.carromaim.cv.Coin c : s.coins) {
            h = h * 31 + Math.round(c.pos.x / 8f);
            h = h * 31 + Math.round(c.pos.y / 8f);
        }
        return h;
    }

    public void performBestSwipe() {
        if (!hasLiveData) return;
        BestShot bs = lastBestShot;
        if (bs == null || autoplaySwipeListener == null) return;
        autoplaySwipeListener.onPerformSwipe(
            bs.strikerX, bs.strikerY, bs.targetX, bs.targetY, 70, bs.powerFrac);
    }

    // ── Shot computation ──────────────────────────────────────────────────────

    private List<CarromAI.AiShot> computeFilteredShots(GameState s) {
        List<CarromAI.AiShot> all = CarromAI.findBestShots(s, MAX_LINES * 3);
        List<CarromAI.AiShot> out = new ArrayList<>();
        for (CarromAI.AiShot shot : all) {
            if (modeAllows(shot.wallsNeeded, shot.isBank)) {
                out.add(shot);
                if (out.size() >= MAX_LINES) break;
            }
        }
        return out;
    }

    private boolean modeAllows(int walls, boolean isBank) {
        switch (shotMode) {
            case MODE_DIRECT: return walls == 0 && !isBank;
            case MODE_AI:     return walls == 0;
            case MODE_GOLDEN: return walls <= 1;
            case MODE_LUCKY:  return walls <= 2;
            default:          return true;
        }
    }

    // ── EMA smoothing ─────────────────────────────────────────────────────────

    private void applySmoothing(GameState raw) {
        if (smoothed == null) { smoothed = raw; return; }
        GameState out = new GameState();
        out.board = smoothRect(smoothed.board, raw.board);
        if (raw.striker != null) {
            out.striker = (smoothed.striker != null)
                ? new Coin(ema(smoothed.striker.pos.x, raw.striker.pos.x),
                           ema(smoothed.striker.pos.y, raw.striker.pos.y),
                           ema(smoothed.striker.radius, raw.striker.radius),
                           Coin.COLOR_STRIKER, true)
                : raw.striker;
        }
        out.coins   = smoothCoins(smoothed.coins, raw.coins);
        out.pockets = raw.pockets.isEmpty() ? smoothed.pockets : raw.pockets;
        smoothed = out;
    }

    private List<Coin> smoothCoins(List<Coin> prev, List<Coin> next) {
        if (prev == null || prev.isEmpty()) return next;
        if (next == null || next.isEmpty()) return new ArrayList<>();
        List<Coin>  result  = new ArrayList<>(next.size());
        boolean[]   matched = new boolean[prev.size()];
        for (Coin n : next) {
            Coin bestPrev = null; float bestD = Float.MAX_VALUE; int bi = -1;
            for (int i = 0; i < prev.size(); i++) {
                if (matched[i]) continue;
                Coin p = prev.get(i);
                if (p.color != n.color) continue;
                float dx = p.pos.x-n.pos.x, dy = p.pos.y-n.pos.y;
                float d  = (float)Math.sqrt(dx*dx+dy*dy);
                if (d < bestD && d < (p.radius+n.radius)*2f) { bestD=d; bestPrev=p; bi=i; }
            }
            if (bestPrev != null) {
                matched[bi] = true;
                result.add(new Coin(ema(bestPrev.pos.x, n.pos.x),
                                    ema(bestPrev.pos.y, n.pos.y),
                                    ema(bestPrev.radius, n.radius),
                                    n.color, n.isStriker));
            } else { result.add(n); }
        }
        return result;
    }

    private RectF smoothRect(RectF p, RectF n) {
        if (p == null) return n; if (n == null) return p;
        return new RectF(ema(p.left,n.left), ema(p.top,n.top),
                         ema(p.right,n.right), ema(p.bottom,n.bottom));
    }
    private float ema(float p, float n) { return p + EMA_ALPHA*(n-p); }

    // ── DRAW ──────────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        GameState s = smoothed != null ? smoothed : detected;
        if (s == null || s.striker == null) return;

        RectF board = s.board;

        // Board outline
        if (board != null) {
            canvas.drawRect(board, hasLiveData ? boardPaint : boardDemoPaint);
            canvas.drawText("created by abraham / Xhay",
                    board.centerX(), board.centerY(), watermarkPaint);
        }

        // Pocket indicators
        float pocketR = (board != null) ? board.width() * 0.032f : 13 * dp;
        for (PointF p : s.pockets)
            canvas.drawCircle(p.x, p.y, pocketR, pocketFill);

        // NOTE: Coins are intentionally NOT drawn — they cluttered the board
        // with transparent ghost circles. Only the aim arrows are shown.

        List<CarromAI.AiShot> shots = cachedShots;

        // Expanded clip rect for arrows near board edge
        RectF boardExpanded = null;
        if (board != null) {
            float margin = s.striker.radius * 2f;
            boardExpanded = new RectF(board.left-margin, board.top-margin,
                                      board.right+margin, board.bottom+margin);
        }

        // Draw shots back-to-front (rank MAX-1 first, rank 0 last = on top)
        for (int rank = shots.size() - 1; rank >= 0; rank--) {
            CarromAI.AiShot shot = shots.get(rank);

            float sX = s.striker.pos.x, sY = s.striker.pos.y;
            float gX = shot.ghostPos.x,  gY = shot.ghostPos.y;

            // Direction: striker → ghost (normalised)
            float dx = gX - sX, dy = gY - sY;
            float len = (float) Math.sqrt(dx*dx + dy*dy);
            if (len < 1f) continue;
            float nx = dx/len, ny = dy/len;

            // Arrow size: proportional to striker radius
            float arrowSize = s.striker.radius * ARROW_SCALE[rank];

            // ── Arrow at ghost position (the "last point" of the striker path) ──
            if (isFinite(gX, gY)) {
                drawShotArrow(canvas, gX, gY, nx, ny, arrowSize, rank);
            }

            // ── Coin→pocket trajectory line ───────────────────────────────────
            if (shot.coin != null && shot.pocket != null) {
                float cX = shot.coin.pos.x, cY = shot.coin.pos.y;
                float pX = shot.pocket.x,   pY = shot.pocket.y;
                drawLineSafe(canvas, cX, cY, pX, pY, coinGlowPaints[rank], board);
                drawLineSafe(canvas, cX, cY, pX, pY, coinCorePaints[rank], board);
            }
        }

        // ── Striker marker (always on top) ────────────────────────────────────
        canvas.drawCircle(s.striker.pos.x, s.striker.pos.y, s.striker.radius, strikerFillPaint);
        canvas.drawCircle(s.striker.pos.x, s.striker.pos.y, s.striker.radius, strikerRingPaint);
    }

    /**
     * Draw a directional arrow at position (cx, cy) pointing in direction (nx, ny).
     *
     * The arrow consists of:
     *   1. A soft glow ring centred at (cx, cy)
     *   2. A filled triangle (arrowhead) with tip pointing in (nx, ny)
     *   3. A short stem line going backward (opposite to direction)
     *
     * This replaces the old "line from striker to ghost" — now only the endpoint
     * carries the visual indicator, keeping the board uncluttered.
     */
    private void drawShotArrow(Canvas canvas,
                                float cx, float cy,
                                float nx, float ny,
                                float size, int rank) {
        // Perpendicular unit vector
        float px = -ny, py = nx;

        // ── 1. Glow ring at arrow centre ──────────────────────────────────────
        canvas.drawCircle(cx, cy, size * 1.0f, arrowGlowPaints[rank]);

        // ── 2. Arrowhead triangle ─────────────────────────────────────────────
        //   Tip: forward in direction (nx,ny) from centre
        //   Base: behind centre, spread by halfWidth
        float tipX  = cx + nx * size * 1.1f;
        float tipY  = cy + ny * size * 1.1f;
        float halfW = size * 0.65f;
        float baseX = cx - nx * size * 0.4f;
        float baseY = cy - ny * size * 0.4f;
        float b1x   = baseX + px * halfW;
        float b1y   = baseY + py * halfW;
        float b2x   = baseX - px * halfW;
        float b2y   = baseY - py * halfW;

        arrowPath.reset();
        arrowPath.moveTo(tipX, tipY);
        arrowPath.lineTo(b1x, b1y);
        arrowPath.lineTo(b2x, b2y);
        arrowPath.close();
        canvas.drawPath(arrowPath, arrowFillPaints[rank]);

        // ── 3. Stem line going backward from arrow base ───────────────────────
        //   Makes it clear which direction the shot comes from
        float stemLen = size * 2.2f;
        float stemEndX = cx - nx * stemLen;
        float stemEndY = cy - ny * stemLen;
        canvas.drawLine(baseX, baseY, stemEndX, stemEndY, stemPaints[rank]);
    }

    // ── Draw helpers ──────────────────────────────────────────────────────────

    private void drawLineSafe(Canvas canvas,
                               float x0, float y0, float x1, float y1,
                               Paint p, RectF board) {
        if (!isFinite(x0, y0, x1, y1)) return;
        if (board != null) {
            float[] c = clipLineToRect(x0, y0, x1, y1, board);
            if (c != null) canvas.drawLine(c[0], c[1], c[2], c[3], p);
        } else {
            canvas.drawLine(x0, y0, x1, y1, p);
        }
    }

    private static float[] clipLineToRect(float x0, float y0, float x1, float y1, RectF r) {
        int code0 = outcode(x0, y0, r);
        int code1 = outcode(x1, y1, r);
        while (true) {
            if ((code0 | code1) == 0) return new float[]{x0, y0, x1, y1};
            if ((code0 & code1) != 0) return null;
            int out = (code0 != 0) ? code0 : code1;
            float x, y;
            if      ((out & 8) != 0) { x = x0 + (x1-x0)*(r.top-y0)/(y1-y0);    y = r.top;    }
            else if ((out & 4) != 0) { x = x0 + (x1-x0)*(r.bottom-y0)/(y1-y0); y = r.bottom; }
            else if ((out & 2) != 0) { y = y0 + (y1-y0)*(r.right-x0)/(x1-x0);  x = r.right;  }
            else                     { y = y0 + (y1-y0)*(r.left-x0)/(x1-x0);   x = r.left;   }
            if (out == code0) { x0 = x; y0 = y; code0 = outcode(x0, y0, r); }
            else              { x1 = x; y1 = y; code1 = outcode(x1, y1, r); }
        }
    }

    private static int outcode(float x, float y, RectF r) {
        int c = 0;
        if (x < r.left)   c |= 1;
        if (x > r.right)  c |= 2;
        if (y > r.bottom) c |= 4;
        if (y < r.top)    c |= 8;
        return c;
    }

    private static boolean isFinite(float... v) {
        for (float f : v) if (Float.isNaN(f) || Float.isInfinite(f)) return false;
        return true;
    }

    // ── Paint factory ─────────────────────────────────────────────────────────

    private static Paint makeStroke(int color, float widthPx, int alpha) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setAlpha(alpha);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(widthPx);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        return p;
    }

    private static Paint makeFill(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStyle(Paint.Style.FILL);
        return p;
    }
}
