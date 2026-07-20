package com.cakesportal.piggyroadtrip;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Random;

public class MainActivity extends Activity {
    private RoadTripView gameView;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            gameView = new RoadTripView(this);
            setContentView(gameView);
        } catch (Throwable error) {
            showStartupError(error);
        }
    }

    private void showStartupError(Throwable error) {
        TextView message = new TextView(this);
        message.setText("The game could not start.\n\n" + error.getClass().getSimpleName()
                + ": " + String.valueOf(error.getMessage()));
        message.setTextColor(Color.WHITE);
        message.setTextSize(18);
        message.setPadding(32, 32, 32, 32);
        message.setBackgroundColor(Color.rgb(30, 67, 85));
        setContentView(message);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (gameView != null) gameView.pauseForLifecycle();
    }

    private static class RoadTripView extends View {
        private static final int MENU = 0;
        private static final int PLAYING = 1;
        private static final int PAUSED = 2;
        private static final int GAME_OVER = 3;

        private static final int STAR = 0;
        private static final int APPLE = 1;
        private static final int PUDDLE = 2;
        private static final int CONE = 3;
        private static final int ROCK = 4;

        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final Random random = new Random();
        private final ArrayList<RoadItem> items = new ArrayList<>();
        private final SharedPreferences prefs;
        private final Vibrator vibrator;

        private int state = MENU;
        private int selectedDriver = 0;
        private int score;
        private int stars;
        private int hearts;
        private int best;
        private float playerX;
        private float targetX;
        private float speed;
        private float distance;
        private float spawnTimer;
        private float stripeOffset;
        private float invincible;
        private float menuBob;
        private long lastFrame;
        private boolean dragging;

        private final int[] driverColors = {
                Color.rgb(247, 147, 177),
                Color.rgb(243, 219, 119),
                Color.rgb(164, 213, 229)
        };
        private final String[] driverNames = {"PIP", "BENNY", "ELLIE"};

        RoadTripView(Context context) {
            super(context);
            p.setTypeface(android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL));
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            prefs = context.getSharedPreferences("road_trip_scores", Context.MODE_PRIVATE);
            best = prefs.getInt("best", 0);
            vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            setFocusable(true);
        }

        void pauseForLifecycle() {
            if (state == PLAYING) state = PAUSED;
            lastFrame = 0;
            invalidate();
        }

        private void startGame() {
            state = PLAYING;
            score = 0;
            stars = 0;
            hearts = 3;
            playerX = 0f;
            targetX = 0f;
            speed = .27f;
            distance = 0f;
            spawnTimer = .55f;
            stripeOffset = 0f;
            invincible = 0f;
            items.clear();
            lastFrame = System.nanoTime();
            invalidate();
        }

        private void endGame() {
            state = GAME_OVER;
            if (score > best) {
                best = score;
                prefs.edit().putInt("best", best).apply();
            }
            vibrate(120);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            try {
                super.onDraw(canvas);
                long now = System.nanoTime();
                float dt = lastFrame == 0 ? 0f : Math.min((now - lastFrame) / 1_000_000_000f, .04f);
                lastFrame = now;

                if (state == PLAYING) update(dt);
                else menuBob += dt * 2.5f;

                drawWorld(canvas);
                if (state == MENU) drawMenu(canvas);
                else {
                    drawHud(canvas);
                    if (state == PAUSED) drawPaused(canvas);
                    if (state == GAME_OVER) drawGameOver(canvas);
                }

                if (state == PLAYING || state == MENU) postInvalidateOnAnimation();
            } catch (Throwable error) {
                drawRenderError(canvas, error);
            }
        }

        private void drawRenderError(Canvas canvas, Throwable error) {
            canvas.drawColor(Color.rgb(30, 67, 85));
            p.reset();
            p.setAntiAlias(true);
            p.setColor(Color.WHITE);
            p.setTextSize(Math.max(22f, getHeight() * .045f));
            canvas.drawText("The game hit a drawing error:", 32, getHeight() * .35f, p);
            p.setTextSize(Math.max(17f, getHeight() * .033f));
            canvas.drawText(error.getClass().getSimpleName() + ": "
                    + String.valueOf(error.getMessage()), 32, getHeight() * .48f, p);
        }

        private void update(float dt) {
            if (dt <= 0f) return;
            distance += speed * dt;
            score += Math.max(1, (int) (dt * speed * 180f));
            speed = Math.min(.53f, .27f + distance * .012f);
            stripeOffset = (stripeOffset + speed * dt * 1.7f) % .25f;
            invincible = Math.max(0f, invincible - dt);
            playerX += (targetX - playerX) * Math.min(1f, dt * 9f);

            spawnTimer -= dt;
            if (spawnTimer <= 0f) {
                spawnItem();
                spawnTimer = Math.max(.48f, 1.02f - speed * .72f) + random.nextFloat() * .24f;
            }

            Iterator<RoadItem> iterator = items.iterator();
            while (iterator.hasNext()) {
                RoadItem item = iterator.next();
                item.y += speed * dt;
                if (!item.hit && item.y > .73f && item.y < .93f &&
                        Math.abs(item.x - playerX) < collisionWidth(item.type)) {
                    item.hit = true;
                    if (item.type == STAR) {
                        stars++;
                        score += 120;
                        vibrate(25);
                        iterator.remove();
                        continue;
                    } else if (item.type == APPLE) {
                        score += 70;
                        iterator.remove();
                        continue;
                    } else if (invincible <= 0f) {
                        hearts--;
                        invincible = 1.35f;
                        vibrate(80);
                        iterator.remove();
                        if (hearts <= 0) endGame();
                        continue;
                    }
                }
                if (item.y > 1.12f) iterator.remove();
            }
        }

        private float collisionWidth(int type) {
            return (type == PUDDLE || type == ROCK) ? .27f : .22f;
        }

        private void spawnItem() {
            float[] lanes = {-0.62f, 0f, .62f};
            float x = lanes[random.nextInt(lanes.length)];
            int roll = random.nextInt(100);
            int type;
            if (roll < 23) type = STAR;
            else if (roll < 38) type = APPLE;
            else if (roll < 62) type = PUDDLE;
            else if (roll < 83) type = CONE;
            else type = ROCK;
            items.add(new RoadItem(x, .08f, type));

            if (distance > 7f && random.nextFloat() < .22f) {
                int otherLane = random.nextInt(lanes.length);
                if (lanes[otherLane] != x) {
                    int secondType = random.nextBoolean() ? STAR : CONE;
                    items.add(new RoadItem(lanes[otherLane], .02f, secondType));
                }
            }
        }

        private void drawWorld(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;

            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.rgb(141, 215, 246));
            c.drawRect(0, 0, w, h, p);

            drawSun(c, w * .86f, h * .14f, h * .075f);
            drawCloud(c, w * .14f, h * .13f, h * .042f);
            drawCloud(c, w * .62f, h * .08f, h * .032f);

            p.setColor(Color.rgb(90, 188, 110));
            path.reset();
            path.moveTo(0, h * .43f);
            path.quadTo(w * .13f, h * .25f, w * .28f, h * .43f);
            path.quadTo(w * .45f, h * .22f, w * .63f, h * .43f);
            path.quadTo(w * .81f, h * .26f, w, h * .43f);
            path.lineTo(w, h);
            path.lineTo(0, h);
            path.close();
            c.drawPath(path, p);

            p.setColor(Color.rgb(73, 166, 91));
            path.reset();
            path.moveTo(0, h * .50f);
            path.quadTo(w * .18f, h * .34f, w * .39f, h * .50f);
            path.quadTo(w * .67f, h * .32f, w, h * .50f);
            path.lineTo(w, h);
            path.lineTo(0, h);
            path.close();
            c.drawPath(path, p);

            drawScenery(c);
            drawRoad(c);

            for (RoadItem item : items) drawRoadItem(c, item);

            if (state != MENU) drawCar(c, playerX, .83f, selectedDriver,
                    invincible > 0f && ((int) (invincible * 12) % 2 == 0));
        }

        private void drawRoad(Canvas c) {
            float w = getWidth(), h = getHeight();
            float horizonY = h * .40f;
            float roadTop = w * .16f;
            float roadBottom = w * .47f;
            float cx = w / 2f;

            p.setColor(Color.rgb(231, 205, 147));
            path.reset();
            path.moveTo(cx - roadTop - w * .018f, horizonY);
            path.lineTo(cx + roadTop + w * .018f, horizonY);
            path.lineTo(cx + roadBottom + w * .028f, h);
            path.lineTo(cx - roadBottom - w * .028f, h);
            path.close();
            c.drawPath(path, p);

            p.setColor(Color.rgb(84, 88, 92));
            path.reset();
            path.moveTo(cx - roadTop, horizonY);
            path.lineTo(cx + roadTop, horizonY);
            path.lineTo(cx + roadBottom, h);
            path.lineTo(cx - roadBottom, h);
            path.close();
            c.drawPath(path, p);

            p.setColor(Color.rgb(245, 239, 199));
            for (int lane = -1; lane <= 1; lane += 2) {
                for (int i = -1; i < 6; i++) {
                    float y1 = i * .25f + stripeOffset;
                    float y2 = y1 + .115f;
                    if (y2 < 0 || y1 > 1) continue;
                    y1 = Math.max(0, y1);
                    y2 = Math.min(1, y2);
                    float sy1 = horizonY + y1 * (h - horizonY);
                    float sy2 = horizonY + y2 * (h - horizonY);
                    float half1 = roadTop + (roadBottom - roadTop) * y1;
                    float half2 = roadTop + (roadBottom - roadTop) * y2;
                    float x1 = cx + lane * half1 / 3f;
                    float x2 = cx + lane * half2 / 3f;
                    float thick1 = 2f + y1 * w * .005f;
                    float thick2 = 2f + y2 * w * .005f;
                    path.reset();
                    path.moveTo(x1 - thick1, sy1);
                    path.lineTo(x1 + thick1, sy1);
                    path.lineTo(x2 + thick2, sy2);
                    path.lineTo(x2 - thick2, sy2);
                    path.close();
                    c.drawPath(path, p);
                }
            }
        }

        private void drawScenery(Canvas c) {
            float w = getWidth(), h = getHeight();
            for (int i = 0; i < 7; i++) {
                float x = ((i * .173f + distance * .035f) % 1.15f) * w - w * .07f;
                if (x > w * .31f && x < w * .69f) continue;
                float base = h * (.47f + (i % 3) * .10f);
                float s = h * (.04f + (i % 2) * .018f);
                drawTree(c, x, base, s);
            }
            for (int i = 0; i < 12; i++) {
                float x = (i * .091f + .02f) * w;
                if (x > w * .29f && x < w * .71f) continue;
                float y = h * (.59f + (i % 4) * .09f);
                drawFlower(c, x, y, h * .009f, i % 3);
            }
        }

        private void drawRoadItem(Canvas c, RoadItem item) {
            float w = getWidth(), h = getHeight();
            float horizonY = h * .40f;
            float roadTop = w * .16f;
            float roadBottom = w * .47f;
            float roadHalf = roadTop + (roadBottom - roadTop) * item.y;
            float x = w / 2f + item.x * roadHalf * .72f;
            float y = horizonY + item.y * (h - horizonY);
            float s = h * (.035f + item.y * .10f);

            if (item.type == STAR) drawStar(c, x, y, s, Color.rgb(255, 219, 70));
            else if (item.type == APPLE) drawApple(c, x, y, s);
            else if (item.type == PUDDLE) drawPuddle(c, x, y, s);
            else if (item.type == CONE) drawCone(c, x, y, s);
            else drawRock(c, x, y, s);
        }

        private void drawCar(Canvas c, float logicalX, float logicalY, int driver, boolean hidden) {
            if (hidden) return;
            float w = getWidth(), h = getHeight();
            float roadHalf = w * (.16f + (.47f - .16f) * logicalY);
            float x = w / 2f + logicalX * roadHalf * .72f;
            float y = h * (.40f + logicalY * .60f);
            float s = h * .19f;

            p.setColor(0x33000000);
            c.drawOval(new RectF(x - s * .66f, y + s * .25f, x + s * .66f, y + s * .52f), p);

            p.setColor(Color.rgb(69, 79, 91));
            c.drawRoundRect(new RectF(x - s * .58f, y + s * .03f, x - s * .38f, y + s * .48f),
                    s * .08f, s * .08f, p);
            c.drawRoundRect(new RectF(x + s * .38f, y + s * .03f, x + s * .58f, y + s * .48f),
                    s * .08f, s * .08f, p);

            p.setColor(Color.rgb(226, 72, 80));
            c.drawRoundRect(new RectF(x - s * .58f, y - s * .06f, x + s * .58f, y + s * .38f),
                    s * .18f, s * .18f, p);
            path.reset();
            path.moveTo(x - s * .40f, y - s * .05f);
            path.lineTo(x - s * .27f, y - s * .43f);
            path.quadTo(x, y - s * .59f, x + s * .27f, y - s * .43f);
            path.lineTo(x + s * .40f, y - s * .05f);
            path.close();
            c.drawPath(path, p);

            p.setColor(Color.rgb(184, 230, 245));
            path.reset();
            path.moveTo(x - s * .31f, y - s * .08f);
            path.lineTo(x - s * .20f, y - s * .36f);
            path.quadTo(x, y - s * .45f, x + s * .20f, y - s * .36f);
            path.lineTo(x + s * .31f, y - s * .08f);
            path.close();
            c.drawPath(path, p);

            drawDriver(c, x, y - s * .30f, s * .30f, driver);

            p.setColor(Color.rgb(255, 224, 94));
            c.drawCircle(x - s * .41f, y + s * .18f, s * .10f, p);
            c.drawCircle(x + s * .41f, y + s * .18f, s * .10f, p);
            p.setColor(Color.WHITE);
            c.drawRect(x - s * .16f, y + s * .21f, x + s * .16f, y + s * .27f, p);
        }

        private void drawDriver(Canvas c, float x, float y, float s, int driver) {
            if (driver == 0) drawPigFace(c, x, y, s, driverColors[0]);
            else if (driver == 1) drawBunnyFace(c, x, y, s, driverColors[1]);
            else drawElephantFace(c, x, y, s, driverColors[2]);
        }

        private void drawPigFace(Canvas c, float x, float y, float s, int color) {
            p.setColor(color);
            c.drawCircle(x - s * .38f, y - s * .35f, s * .23f, p);
            c.drawCircle(x + s * .38f, y - s * .35f, s * .23f, p);
            c.drawOval(new RectF(x - s * .60f, y - s * .55f, x + s * .60f, y + s * .60f), p);
            p.setColor(Color.rgb(255, 181, 201));
            c.drawOval(new RectF(x - s * .28f, y + s * .06f, x + s * .38f, y + s * .43f), p);
            p.setColor(Color.rgb(91, 55, 70));
            c.drawCircle(x - s * .05f, y + s * .24f, s * .055f, p);
            c.drawCircle(x + s * .20f, y + s * .24f, s * .055f, p);
            drawEyesAndSmile(c, x, y, s);
        }

        private void drawBunnyFace(Canvas c, float x, float y, float s, int color) {
            p.setColor(color);
            c.drawOval(new RectF(x - s * .47f, y - s * 1.05f, x - s * .12f, y - s * .25f), p);
            c.drawOval(new RectF(x + s * .12f, y - s * 1.05f, x + s * .47f, y - s * .25f), p);
            c.drawOval(new RectF(x - s * .58f, y - s * .54f, x + s * .58f, y + s * .60f), p);
            p.setColor(Color.rgb(246, 154, 165));
            path.reset();
            path.moveTo(x, y + s * .08f);
            path.lineTo(x - s * .10f, y + s * .20f);
            path.lineTo(x + s * .10f, y + s * .20f);
            path.close();
            c.drawPath(path, p);
            drawEyesAndSmile(c, x, y, s);
        }

        private void drawElephantFace(Canvas c, float x, float y, float s, int color) {
            p.setColor(color);
            c.drawCircle(x - s * .52f, y - s * .02f, s * .35f, p);
            c.drawCircle(x + s * .52f, y - s * .02f, s * .35f, p);
            c.drawOval(new RectF(x - s * .55f, y - s * .55f, x + s * .55f, y + s * .56f), p);
            path.reset();
            path.moveTo(x - s * .13f, y + s * .25f);
            path.quadTo(x - s * .10f, y + s * .90f, x + s * .20f, y + s * .82f);
            path.quadTo(x + s * .35f, y + s * .76f, x + s * .22f, y + s * .63f);
            path.quadTo(x + s * .08f, y + s * .68f, x + s * .08f, y + s * .25f);
            path.close();
            c.drawPath(path, p);
            drawEyesAndSmile(c, x, y, s);
        }

        private void drawEyesAndSmile(Canvas c, float x, float y, float s) {
            p.setColor(Color.WHITE);
            c.drawCircle(x - s * .22f, y - s * .12f, s * .12f, p);
            c.drawCircle(x + s * .22f, y - s * .12f, s * .12f, p);
            p.setColor(Color.rgb(55, 60, 68));
            c.drawCircle(x - s * .20f, y - s * .10f, s * .052f, p);
            c.drawCircle(x + s * .24f, y - s * .10f, s * .052f, p);
            stroke.setColor(Color.rgb(104, 52, 66));
            stroke.setStrokeWidth(Math.max(2f, s * .045f));
            RectF smile = new RectF(x - s * .20f, y + s * .15f, x + s * .20f, y + s * .44f);
            c.drawArc(smile, 12, 156, false, stroke);
        }

        private void drawHud(Canvas c) {
            float w = getWidth(), h = getHeight();
            float pad = h * .035f;
            float boxH = h * .105f;
            p.setColor(0xCCFFFFFF);
            c.drawRoundRect(new RectF(pad, pad, w * .27f, pad + boxH), boxH * .36f, boxH * .36f, p);
            drawText(c, "SCORE  " + score, pad * 1.8f, pad + boxH * .68f,
                    h * .043f, Color.rgb(44, 60, 72), true, Paint.Align.LEFT);

            p.setColor(0xCCFFFFFF);
            c.drawRoundRect(new RectF(w * .31f, pad, w * .49f, pad + boxH), boxH * .36f, boxH * .36f, p);
            drawStar(c, w * .34f, pad + boxH * .50f, boxH * .25f, Color.rgb(255, 207, 52));
            drawText(c, String.valueOf(stars), w * .38f, pad + boxH * .68f,
                    h * .043f, Color.rgb(44, 60, 72), true, Paint.Align.LEFT);

            for (int i = 0; i < 3; i++) {
                drawHeart(c, w * (.56f + i * .055f), pad + boxH * .50f, boxH * .27f,
                        i < hearts ? Color.rgb(238, 72, 93) : Color.rgb(190, 199, 202));
            }

            p.setColor(0xCCFFFFFF);
            c.drawCircle(w - pad - boxH * .48f, pad + boxH * .50f, boxH * .50f, p);
            p.setColor(Color.rgb(53, 75, 88));
            c.drawRoundRect(new RectF(w - pad - boxH * .67f, pad + boxH * .27f,
                    w - pad - boxH * .55f, pad + boxH * .73f), 3, 3, p);
            c.drawRoundRect(new RectF(w - pad - boxH * .40f, pad + boxH * .27f,
                    w - pad - boxH * .28f, pad + boxH * .73f), 3, 3, p);

            if (!dragging && distance < 2.8f && state == PLAYING) {
                drawText(c, "DRAG LEFT OR RIGHT TO STEER", w / 2f, h * .94f,
                        h * .035f, Color.WHITE, true, Paint.Align.CENTER);
            }
        }

        private void drawMenu(Canvas c) {
            float w = getWidth(), h = getHeight();
            p.setColor(0xB91E4355);
            c.drawRect(0, 0, w, h, p);

            drawText(c, "PIP & PALS", w * .5f, h * .15f, h * .085f,
                    Color.WHITE, true, Paint.Align.CENTER);
            drawText(c, "ROAD TRIP", w * .5f, h * .245f, h * .062f,
                    Color.rgb(255, 224, 91), true, Paint.Align.CENTER);

            drawText(c, "CHOOSE YOUR DRIVER", w * .5f, h * .34f, h * .034f,
                    Color.WHITE, true, Paint.Align.CENTER);

            float y = h * .51f;
            float gap = w * .15f;
            for (int i = 0; i < 3; i++) {
                float x = w * .5f + (i - 1) * gap;
                float r = h * (i == selectedDriver ? .105f : .087f);
                p.setColor(i == selectedDriver ? Color.rgb(255, 222, 76) : 0xCCFFFFFF);
                c.drawCircle(x, y, r, p);
                p.setColor(Color.rgb(34, 100, 116));
                c.drawCircle(x, y, r * .83f, p);
                drawDriver(c, x, y + r * .06f + (float) Math.sin(menuBob + i) * 2f,
                        r * .62f, i);
                drawText(c, driverNames[i], x, y + r * 1.35f, h * .029f,
                        Color.WHITE, true, Paint.Align.CENTER);
            }

            float left = w * .36f, right = w * .64f, top = h * .75f, bottom = h * .88f;
            p.setColor(Color.rgb(243, 79, 91));
            c.drawRoundRect(new RectF(left, top, right, bottom), h * .05f, h * .05f, p);
            drawText(c, "DRIVE!", w / 2f, h * .835f, h * .052f,
                    Color.WHITE, true, Paint.Align.CENTER);
            drawText(c, "Collect stars • Avoid obstacles", w / 2f, h * .95f, h * .028f,
                    Color.WHITE, false, Paint.Align.CENTER);
        }

        private void drawPaused(Canvas c) {
            float w = getWidth(), h = getHeight();
            p.setColor(0xB8002835);
            c.drawRect(0, 0, w, h, p);
            drawText(c, "PAUSED", w / 2f, h * .37f, h * .09f,
                    Color.WHITE, true, Paint.Align.CENTER);
            drawButton(c, "CONTINUE", w * .37f, h * .50f, w * .63f, h * .64f,
                    Color.rgb(66, 183, 118));
            drawButton(c, "HOME", w * .41f, h * .70f, w * .59f, h * .81f,
                    Color.rgb(72, 121, 145));
        }

        private void drawGameOver(Canvas c) {
            float w = getWidth(), h = getHeight();
            p.setColor(0xC8002835);
            c.drawRect(0, 0, w, h, p);
            drawText(c, "ROAD TRIP OVER", w / 2f, h * .25f, h * .075f,
                    Color.WHITE, true, Paint.Align.CENTER);
            drawText(c, "SCORE  " + score, w / 2f, h * .40f, h * .062f,
                    Color.rgb(255, 224, 91), true, Paint.Align.CENTER);
            drawText(c, "BEST  " + best + "     STARS  " + stars, w / 2f, h * .50f,
                    h * .034f, Color.WHITE, true, Paint.Align.CENTER);
            drawButton(c, "DRIVE AGAIN", w * .35f, h * .59f, w * .65f, h * .73f,
                    Color.rgb(243, 79, 91));
            drawButton(c, "CHANGE DRIVER", w * .38f, h * .79f, w * .62f, h * .90f,
                    Color.rgb(72, 121, 145));
        }

        private void drawButton(Canvas c, String label, float l, float t, float r, float b, int color) {
            p.setColor(color);
            c.drawRoundRect(new RectF(l, t, r, b), (b - t) * .35f, (b - t) * .35f, p);
            drawText(c, label, (l + r) / 2f, t + (b - t) * .67f,
                    (b - t) * .36f, Color.WHITE, true, Paint.Align.CENTER);
        }

        private void drawText(Canvas c, String text, float x, float y, float size,
                              int color, boolean bold, Paint.Align align) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            p.setTextSize(size);
            p.setTextAlign(align);
            p.setTypeface(android.graphics.Typeface.create("sans",
                    bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
            c.drawText(text, x, y, p);
        }

        private void drawSun(Canvas c, float x, float y, float r) {
            stroke.setColor(Color.rgb(255, 218, 76));
            stroke.setStrokeWidth(r * .12f);
            for (int i = 0; i < 8; i++) {
                double a = Math.PI * i / 4;
                c.drawLine(x + (float) Math.cos(a) * r * 1.25f,
                        y + (float) Math.sin(a) * r * 1.25f,
                        x + (float) Math.cos(a) * r * 1.65f,
                        y + (float) Math.sin(a) * r * 1.65f, stroke);
            }
            p.setColor(Color.rgb(255, 218, 76));
            c.drawCircle(x, y, r, p);
        }

        private void drawCloud(Canvas c, float x, float y, float s) {
            p.setColor(0xE8FFFFFF);
            c.drawCircle(x - s, y + s * .15f, s * .72f, p);
            c.drawCircle(x, y - s * .18f, s, p);
            c.drawCircle(x + s, y + s * .10f, s * .75f, p);
            c.drawRoundRect(new RectF(x - s * 1.5f, y, x + s * 1.55f, y + s * .72f),
                    s * .35f, s * .35f, p);
        }

        private void drawTree(Canvas c, float x, float y, float s) {
            p.setColor(Color.rgb(126, 78, 50));
            c.drawRoundRect(new RectF(x - s * .13f, y - s * .6f, x + s * .13f, y + s * .55f),
                    s * .08f, s * .08f, p);
            p.setColor(Color.rgb(45, 139, 69));
            c.drawCircle(x, y - s * .75f, s * .65f, p);
            c.drawCircle(x - s * .42f, y - s * .52f, s * .44f, p);
            c.drawCircle(x + s * .42f, y - s * .52f, s * .44f, p);
        }

        private void drawFlower(Canvas c, float x, float y, float s, int kind) {
            int[] colors = {Color.WHITE, Color.rgb(255, 145, 183), Color.rgb(168, 133, 242)};
            p.setColor(colors[kind]);
            c.drawCircle(x - s, y, s, p);
            c.drawCircle(x + s, y, s, p);
            c.drawCircle(x, y - s, s, p);
            c.drawCircle(x, y + s, s, p);
            p.setColor(Color.rgb(255, 214, 67));
            c.drawCircle(x, y, s * .65f, p);
        }

        private void drawStar(Canvas c, float x, float y, float s, int color) {
            p.setColor(color);
            path.reset();
            for (int i = 0; i < 10; i++) {
                double a = -Math.PI / 2 + i * Math.PI / 5;
                float r = i % 2 == 0 ? s : s * .45f;
                float px = x + (float) Math.cos(a) * r;
                float py = y + (float) Math.sin(a) * r;
                if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
            }
            path.close();
            c.drawPath(path, p);
            stroke.setColor(Color.rgb(220, 160, 35));
            stroke.setStrokeWidth(Math.max(2f, s * .10f));
            c.drawPath(path, stroke);
        }

        private void drawApple(Canvas c, float x, float y, float s) {
            p.setColor(Color.rgb(231, 66, 73));
            c.drawCircle(x - s * .28f, y, s * .52f, p);
            c.drawCircle(x + s * .28f, y, s * .52f, p);
            c.drawOval(new RectF(x - s * .62f, y - s * .25f, x + s * .62f, y + s * .72f), p);
            stroke.setColor(Color.rgb(98, 60, 35));
            stroke.setStrokeWidth(s * .13f);
            c.drawLine(x, y - s * .42f, x + s * .08f, y - s * .82f, stroke);
            p.setColor(Color.rgb(67, 153, 71));
            c.drawOval(new RectF(x + s * .03f, y - s * .77f, x + s * .55f, y - s * .48f), p);
        }

        private void drawPuddle(Canvas c, float x, float y, float s) {
            p.setColor(Color.rgb(78, 168, 203));
            c.drawOval(new RectF(x - s, y - s * .28f, x + s, y + s * .35f), p);
            p.setColor(0x55FFFFFF);
            c.drawOval(new RectF(x - s * .55f, y - s * .16f, x + s * .18f, y), p);
        }

        private void drawCone(Canvas c, float x, float y, float s) {
            p.setColor(Color.rgb(245, 118, 52));
            path.reset();
            path.moveTo(x, y - s);
            path.lineTo(x - s * .52f, y + s * .62f);
            path.lineTo(x + s * .52f, y + s * .62f);
            path.close();
            c.drawPath(path, p);
            p.setColor(Color.WHITE);
            path.reset();
            path.moveTo(x - s * .25f, y - s * .10f);
            path.lineTo(x + s * .25f, y - s * .10f);
            path.lineTo(x + s * .36f, y + s * .22f);
            path.lineTo(x - s * .36f, y + s * .22f);
            path.close();
            c.drawPath(path, p);
            p.setColor(Color.rgb(226, 91, 40));
            c.drawRoundRect(new RectF(x - s * .72f, y + s * .55f, x + s * .72f, y + s * .78f),
                    s * .10f, s * .10f, p);
        }

        private void drawRock(Canvas c, float x, float y, float s) {
            p.setColor(Color.rgb(111, 106, 104));
            path.reset();
            path.moveTo(x - s * .85f, y + s * .55f);
            path.lineTo(x - s * .60f, y - s * .25f);
            path.lineTo(x - s * .12f, y - s * .68f);
            path.lineTo(x + s * .58f, y - s * .35f);
            path.lineTo(x + s * .86f, y + s * .55f);
            path.close();
            c.drawPath(path, p);
            p.setColor(0x44FFFFFF);
            path.reset();
            path.moveTo(x - s * .43f, y - s * .23f);
            path.lineTo(x - s * .12f, y - s * .48f);
            path.lineTo(x + s * .25f, y - s * .31f);
            path.close();
            c.drawPath(path, p);
        }

        private void drawHeart(Canvas c, float x, float y, float s, int color) {
            p.setColor(color);
            path.reset();
            path.moveTo(x, y + s * .75f);
            path.cubicTo(x - s * 1.2f, y, x - s * .75f, y - s * .75f, x, y - s * .18f);
            path.cubicTo(x + s * .75f, y - s * .75f, x + s * 1.2f, y, x, y + s * .75f);
            path.close();
            c.drawPath(path, p);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            float x = event.getX(), y = event.getY();
            float w = getWidth(), h = getHeight();

            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (state == MENU) {
                    if (y > h * .39f && y < h * .68f) {
                        float gap = w * .15f;
                        for (int i = 0; i < 3; i++) {
                            float cx = w * .5f + (i - 1) * gap;
                            if (Math.abs(x - cx) < h * .12f) {
                                selectedDriver = i;
                                invalidate();
                                return true;
                            }
                        }
                    }
                    if (x > w * .33f && x < w * .67f && y > h * .72f && y < h * .91f) {
                        startGame();
                    }
                    return true;
                }

                if (state == PLAYING) {
                    float pauseCx = w - h * .035f - h * .105f * .48f;
                    float pauseCy = h * .035f + h * .105f * .50f;
                    if (distance(x, y, pauseCx, pauseCy) < h * .08f) {
                        state = PAUSED;
                        invalidate();
                        return true;
                    }
                    dragging = true;
                    setSteeringTarget(x);
                    return true;
                }

                if (state == PAUSED) {
                    if (x > w * .34f && x < w * .66f && y > h * .47f && y < h * .67f) {
                        state = PLAYING;
                        lastFrame = System.nanoTime();
                        invalidate();
                    } else if (x > w * .38f && x < w * .62f && y > h * .67f && y < h * .84f) {
                        state = MENU;
                        items.clear();
                        invalidate();
                    }
                    return true;
                }

                if (state == GAME_OVER) {
                    if (x > w * .32f && x < w * .68f && y > h * .56f && y < h * .76f) {
                        startGame();
                    } else if (x > w * .35f && x < w * .65f && y > h * .76f && y < h * .94f) {
                        state = MENU;
                        items.clear();
                        invalidate();
                    }
                    return true;
                }
            }

            if (event.getAction() == MotionEvent.ACTION_MOVE && state == PLAYING && dragging) {
                setSteeringTarget(x);
                return true;
            }

            if (event.getAction() == MotionEvent.ACTION_UP ||
                    event.getAction() == MotionEvent.ACTION_CANCEL) {
                dragging = false;
                return true;
            }
            return true;
        }

        private void setSteeringTarget(float touchX) {
            float normalized = (touchX - getWidth() / 2f) / (getWidth() * .34f);
            targetX = Math.max(-.92f, Math.min(.92f, normalized));
        }

        private float distance(float x1, float y1, float x2, float y2) {
            float dx = x1 - x2, dy = y1 - y2;
            return (float) Math.sqrt(dx * dx + dy * dy);
        }

        private void vibrate(long milliseconds) {
            if (vibrator == null || !vibrator.hasVibrator()) return;
            try {
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    vibrator.vibrate(VibrationEffect.createOneShot(milliseconds,
                            VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(milliseconds);
                }
            } catch (SecurityException ignored) {
            }
        }

        private static class RoadItem {
            final float x;
            float y;
            final int type;
            boolean hit;

            RoadItem(float x, float y, int type) {
                this.x = x;
                this.y = y;
                this.type = type;
            }
        }
    }
}
