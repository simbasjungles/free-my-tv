package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Shader;
import android.os.Build;

/**
 * The frosted glass background: the aurora without detail (the same math as `aurora(uv, 0)` in Shaders) in a 48×27
 * image, computed on the CPU at every aurora step (~1,300 pixels, under 1 ms). The glass shader scales it up with
 * linear filtering, which by itself is the blur.
 */
final class Frost {
    static final int W = 48, H = 27;
    private final int[] pixels = new int[W * H];
    private final Bitmap bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);

    /** Recomputes and returns a new shader (a BitmapShader keeps a copy of the bitmap as it was when created). */
    BitmapShader render(float t, float speed, float dim, float[] palette, float aspect) {
        float tt = t * 0.035f * speed;
        for (int j = 0; j < H; j++) {
            float v = (j + 0.5f) / H;
            for (int i = 0; i < W; i++) {
                float u = (i + 0.5f) / W;
                pixels[j * W + i] = color(u, v, tt, dim, palette, aspect);
            }
        }
        bitmap.setPixels(pixels, 0, W, 0, 0, W, H);
        BitmapShader shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        return filter(shader, BitmapShader.FILTER_MODE_LINEAR);
    }

    /** BitmapShader.setFilterMode is Android 13+; older ones filter through the Paint. */
    static BitmapShader filter(BitmapShader s, int mode) {
        if (Build.VERSION.SDK_INT >= 33) s.setFilterMode(mode);
        return s;
    }

    private static int color(float u, float v, float tt, float dim, float[] c, float aspect) {
        float px = u * aspect * 1.7f, py = v * 1.7f;
        float qx = fbm2(px, py + tt);
        float qy = fbm2(px + 5.2f - tt, py + 1.3f - tt);
        float n = low(px + 2 * qx + 1.7f + tt, py + 2 * qy + 9.2f);
        float band = smoothstep(0.02f, 0.38f, v) * (1 - smoothstep(0.5f, 0.92f, v));
        float curtain = smoothstep(0.35f, 0.85f, n) * (0.35f + 0.65f * band);
        float m = smoothstep(0.15f, 0.85f, n), k = curtain * 0.85f;
        float vig = 1 - 0.38f * smoothstep(0.35f, 1, v) - 0.2f * smoothstep(0.45f, 0.95f, Math.abs(u - 0.5f) * 1.6f);
        float scrim = 1 - 0.22f * (1 - smoothstep(0, 0.42f, v));
        float gain = vig * scrim * (1 - dim);
        int out = 0xFF000000;
        for (int ch = 0; ch < 3; ch++) {
            float col = c[ch] + (c[3 + ch] - c[ch]) * m;
            col = col + (c[6 + ch] - col) * k;
            int b = Math.round(Math.max(0, Math.min(1, col * gain)) * 255);
            out |= b << (16 - 8 * ch);
        }
        return out;
    }

    private static float hash(float x, float y) {
        // The shader's scale (43/128, 27/128): exact at a lattice corner, as on the GPU.
        x = fract(x * 0.3359375f);
        y = fract(y * 0.2109375f);
        float d = x * (x + 45.32f) + y * (y + 45.32f);
        return fract((x + d) * (y + d));
    }

    private static float noise(float x, float y) {
        float ix = (float) Math.floor(x), iy = (float) Math.floor(y);
        float fx = x - ix, fy = y - iy;
        float ux = fx * fx * (3 - 2 * fx), uy = fy * fy * (3 - 2 * fy);
        float a = hash(ix, iy), b = hash(ix + 1, iy), c = hash(ix, iy + 1), d = hash(ix + 1, iy + 1);
        float top = a + (b - a) * ux, bottom = c + (d - c) * ux;
        return top + (bottom - top) * uy;
    }

    private static float fbm2(float x, float y) {
        return 0.6f * noise(x, y) + 0.3f * noise(x * 2.03f + 17, y * 2.03f + 17);
    }

    /** The first 2 octaves + the mean of the rest (the shader's fbm without detail). */
    private static float low(float x, float y) {
        return 0.5f * noise(x, y) + 0.25f * noise(x * 2.03f + 17, y * 2.03f + 17) + 0.09375f;
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }

    private static float fract(float v) {
        return v - (float) Math.floor(v);
    }
}
