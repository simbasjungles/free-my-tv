package io.freemytv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Handler;
import android.view.PixelCopy;

/**
 * The home screen as a frosted backdrop: copied into a tiny bitmap (PixelCopy, Android 8+), blurred with a few box
 * passes and given more color, then stretched over the screen by whoever draws it. Where the TV can't copy its screen,
 * `done` gets null and the caller draws a dark veil instead.
 */
final class ScreenBlur {
    private static final int W = 96, H = 54;

    private ScreenBlur() {}

    interface Done {
        void run(Bitmap blurred);
    }

    static void capture(Activity a, Handler on, Done done) {
        final Bitmap small = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(a.getWindow(), small, result -> {
                if (result != PixelCopy.SUCCESS) {
                    done.run(null);
                    return;
                }
                int[] px = new int[W * H];
                small.getPixels(px, 0, W, 0, 0, W, H);
                blur(px, W, H, 3, 3);
                small.setPixels(px, 0, W, 0, 0, W, H);
                done.run(small);
            }, on);
        } catch (RuntimeException e) {
            done.run(null);
        }
    }

    /** Box blur of `px` (w × h), then saturation 1.3, baked in so no color filter runs while it is drawn. */
    static void blur(int[] px, int w, int h, int radius, int passes) {
        int[] tmp = new int[px.length];
        for (int pass = 0; pass < passes; pass++) {
            box(px, tmp, w, h, radius, true);
            box(tmp, px, w, h, radius, false);
        }
        for (int i = 0; i < px.length; i++) {
            int p = px[i], r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            float gray = 0.299f * r + 0.587f * g + 0.114f * b;
            px[i] = 0xFF000000 | clamp(gray + 1.3f * (r - gray)) << 16 | clamp(gray + 1.3f * (g - gray)) << 8 | clamp(gray + 1.3f * (b - gray));
        }
    }

    private static int clamp(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }

    private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int n = horizontal ? w : h, lines = horizontal ? h : w, step = horizontal ? 1 : w, lineStep = horizontal ? w : 1;
        float norm = 1f / (2 * r + 1);
        for (int line = 0; line < lines; line++) {
            int base = line * lineStep;
            float sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[base + Math.max(0, Math.min(n - 1, i)) * step];
                sr += (p >> 16) & 255;
                sg += (p >> 8) & 255;
                sb += p & 255;
            }
            for (int i = 0; i < n; i++) {
                out[base + i * step] = 0xFF000000 | (Math.round(sr * norm) << 16) | (Math.round(sg * norm) << 8) | Math.round(sb * norm);
                int add = in[base + Math.min(n - 1, i + r + 1) * step], sub = in[base + Math.max(0, i - r) * step];
                sr += ((add >> 16) & 255) - ((sub >> 16) & 255);
                sg += ((add >> 8) & 255) - ((sub >> 8) & 255);
                sb += (add & 255) - (sub & 255);
            }
        }
    }
}
