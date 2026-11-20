package io.freemytv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** A small remote-friendly panel: no images, network requests, blur or continuously running animations. */
public final class QuickSettingsActivity extends Activity {
    private static final int INK = 0xffedf3fc, PANEL = 0xff101a29;
    private static final int FOCUS = 0xffdceaff, FOCUS_INK = 0xff122540;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setDimAmount(0.15f);
        w.setGravity(Gravity.RIGHT | Gravity.TOP);
        w.setBackgroundDrawable(shape(PANEL, 16));
        setFinishOnTouchOutside(true);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(20), dp(16), dp(16));
        TextView title = text(Ui.tr("Quick Settings"), 18, INK);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setMaxLines(2);
        LinearLayout.LayoutParams heading = new LinearLayout.LayoutParams(-1, -2);
        heading.leftMargin = dp(12);
        heading.bottomMargin = dp(12);
        panel.addView(title, heading);

        String[] labels = {Ui.tr("Picture"), Ui.tr("Sound"), Ui.tr("Inputs"), Ui.tr("Wi-Fi"),
                Ui.tr("Bluetooth"), Ui.tr("TV Settings")};
        Intent[] intents = {
                tv(Settings.ACTION_DISPLAY_SETTINGS),
                new Intent(Settings.ACTION_SOUND_SETTINGS).setClassName("com.android.tv.settings",
                        "com.android.tv.settings.device.sound.SoundActivity"),
                new Intent(Intent.ACTION_MAIN).setClassName("com.smartdevice.dailyshortcut",
                        "com.smartdevice.dailyshortcut.ui.MainActivity"),
                tv(Settings.ACTION_WIFI_SETTINGS),
                new Intent("com.google.android.intent.action.CONNECT_INPUT").setPackage("com.android.tv.settings"),
                tv(Settings.ACTION_SETTINGS)
        };
        Tile[] tiles = new Tile[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            Tile tile = new Tile(labels[i], i);
            tiles[i] = tile;
            tile.setId(View.generateViewId());
            tile.setOnClickListener(v -> open(intents[index]));
            LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(-1, dp(48));
            if (i > 0) row.topMargin = dp(3);
            panel.addView(tile, row);
        }
        // Keep navigation inside this panel, including at the edges; Back and the gear close it.
        for (int i = 0; i < tiles.length; i++) {
            tiles[i].setNextFocusLeftId(tiles[i].getId());
            tiles[i].setNextFocusRightId(tiles[i].getId());
            tiles[i].setNextFocusUpId(tiles[Math.max(0, i - 1)].getId());
            tiles[i].setNextFocusDownId(tiles[Math.min(tiles.length - 1, i + 1)].getId());
        }
        setContentView(panel);
        w.setLayout(dp(280), WindowManager.LayoutParams.WRAP_CONTENT);
        WindowManager.LayoutParams position = w.getAttributes();
        position.x = dp(20);
        position.y = dp(20);
        w.setAttributes(position);
        tiles[0].requestFocus();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finish(); // Pressing Quick Settings again toggles the panel closed.
    }

    private Intent tv(String action) { return new Intent(action).setPackage("com.android.tv.settings"); }

    private void open(Intent intent) {
        try {
            startActivity(intent);
            finish();
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, Ui.tr("Unavailable"), Toast.LENGTH_SHORT).show();
            AppLog.w("quick settings target unavailable: " + intent.getAction());
        }
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setFontFeatureSettings("kern");
        return t;
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private final class Tile extends LinearLayout {
        Tile(String label, int icon) {
            super(QuickSettingsActivity.this);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(12), 0, dp(12), 0);
            setFocusable(true);
            setClickable(true);
            setContentDescription(label);
            GradientDrawable normal = shape(0, 10), focused = shape(FOCUS, 10);
            setBackground(normal);
            Glyph glyph = new Glyph(getContext(), icon);
            addView(glyph, new LinearLayout.LayoutParams(dp(22), dp(22)));
            TextView title = text(label, 16, INK);
            title.setGravity(Gravity.CENTER_VERTICAL);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams caption = new LinearLayout.LayoutParams(0, -2, 1);
            caption.leftMargin = dp(14);
            addView(title, caption);
            setOnFocusChangeListener((v, hasFocus) -> {
                setBackground(hasFocus ? focused : normal);
                title.setTextColor(hasFocus ? FOCUS_INK : INK);
                glyph.color = hasFocus ? FOCUS_INK : INK;
                glyph.invalidate();
            });
        }
    }

    /** Six line icons drawn at 24 units, without bitmap caches or allocations during drawing. */
    private static final class Glyph extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int kind;
        int color = INK;

        Glyph(Context context, int kind) {
            super(context);
            this.kind = kind;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.6f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            if (kind == 0) {
                path.moveTo(3, 18); path.lineTo(9, 11); path.lineTo(13, 15); path.lineTo(17, 10); path.lineTo(21, 16);
            } else if (kind == 1) {
                path.moveTo(3, 9); path.lineTo(7, 9); path.lineTo(12, 5); path.lineTo(12, 19);
                path.lineTo(7, 15); path.lineTo(3, 15); path.close();
            } else if (kind == 4) {
                path.moveTo(7, 6); path.lineTo(17, 16); path.lineTo(12, 21); path.lineTo(12, 3);
                path.lineTo(17, 8); path.lineTo(7, 18);
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save();
            canvas.scale(getWidth() / 24f, getHeight() / 24f);
            paint.setColor(color);
            switch (kind) {
                case 0:
                    canvas.drawRoundRect(2, 4, 22, 20, 2, 2, paint);
                    canvas.drawCircle(7, 8, 1, paint); canvas.drawPath(path, paint); break;
                case 1:
                    canvas.drawPath(path, paint);
                    canvas.drawArc(9, 4, 22, 20, -55, 110, false, paint);
                    canvas.drawArc(11, 8, 18, 16, -55, 110, false, paint); break;
                case 2:
                    canvas.drawRoundRect(3, 5, 21, 17, 3, 3, paint);
                    canvas.drawLine(8, 17, 8, 21, paint); canvas.drawLine(16, 17, 16, 21, paint);
                    for (int x = 7; x <= 17; x += 5) canvas.drawLine(x, 9, x, 13, paint); break;
                case 3:
                    canvas.drawArc(-1, 4, 25, 30, -135, 90, false, paint);
                    canvas.drawArc(4, 9, 20, 25, -135, 90, false, paint);
                    canvas.drawCircle(12, 19, 1, paint); break;
                case 4: canvas.drawPath(path, paint); break;
                default:
                    for (int y = 6; y <= 18; y += 6) {
                        int knob = y == 12 ? 8 : 16;
                        canvas.drawLine(3, y, knob - 2, y, paint);
                        canvas.drawLine(knob + 2, y, 21, y, paint);
                        canvas.drawCircle(knob, y, 2, paint);
                    }
            }
            canvas.restore();
        }
    }
}
