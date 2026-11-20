/*
 * Follows Project Nayuki's QR Code generator library (https://www.nayuki.io/page/qr-code-generator-library), cut down
 * to byte mode, level M and versions 1 to 10.
 *
 * Copyright (c) Project Nayuki. (MIT License)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit
 * persons to whom the Software is furnished to do so, subject to the following conditions:
 * - The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 *   the Software.
 * - The Software is provided "as is", without warranty of any kind, express or implied, including but not limited to
 *   the warranties of merchantability, fitness for a particular purpose and noninfringement. In no event shall the
 *   authors or copyright holders be liable for any claim, damages or other liability, whether in an action of
 *   contract, tort or otherwise, arising from, out of or in connection with the Software or the use or other dealings
 *   in the Software.
 */
package io.freemytv;

import java.nio.charset.StandardCharsets;

/**
 * A QR code (Model 2, byte mode, error correction level M) for the TMDB key page's address, without a library: just
 * the part of ISO/IEC 18004 a short text needs. Versions 1 to 10 (up to 213 bytes); the mask with the lowest penalty.
 * Plain Java, so it can be checked on a computer against a real decoder.
 */
final class Qr {
    private Qr() {}

    /** Level M, versions 1 to 10: error correction codewords per block, and blocks. */
    private static final int[] ECC_PER_BLOCK = {0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26};
    private static final int[] BLOCKS = {0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5};

    /** The modules, [y][x], true = dark, without the quiet zone (4 modules of light around it). */
    static boolean[][] encode(String text) {
        return encode(text.getBytes(StandardCharsets.UTF_8), -1);
    }

    /** mask: 0 to 7, or -1 for the one with the lowest penalty. */
    static boolean[][] encode(byte[] data, int mask) {
        int version = 1;
        while (data.length > capacity(version)) {
            if (++version > 10) throw new IllegalArgumentException("too long for a QR code: " + data.length + " bytes");
        }
        int size = version * 4 + 17;
        boolean[][] modules = new boolean[size][size], function = new boolean[size][size];
        drawFunctions(version, modules, function);
        drawCodewords(interleave(version, codewords(version, data)), modules, function);
        if (mask < 0) {
            int best = Integer.MAX_VALUE;
            for (int m = 0; m < 8; m++) {
                applyMask(m, modules, function);
                drawFormat(m, modules, function);
                int score = penalty(modules);
                if (score < best) {
                    best = score;
                    mask = m;
                }
                applyMask(m, modules, function);   // XOR again: undone
            }
        }
        applyMask(mask, modules, function);
        drawFormat(mask, modules, function);
        return modules;
    }

    /** Data bytes a version holds in byte mode (after the 4-bit mode and the character count). */
    private static int capacity(int version) {
        return dataCodewords(version) - (version < 10 ? 2 : 3);
    }

    private static int rawModules(int version) {
        int n = (16 * version + 128) * version + 64;
        if (version >= 2) {
            int align = version / 7 + 2;
            n -= (25 * align - 10) * align - 55;
            if (version >= 7) n -= 36;
        }
        return n;
    }

    private static int dataCodewords(int version) {
        return rawModules(version) / 8 - ECC_PER_BLOCK[version] * BLOCKS[version];
    }

    // MARK: - Function patterns

    private static void set(boolean[][] modules, boolean[][] function, int x, int y, boolean dark) {
        modules[y][x] = dark;
        function[y][x] = true;
    }

    private static void drawFunctions(int version, boolean[][] modules, boolean[][] function) {
        int size = modules.length;
        for (int i = 0; i < size; i++) {   // timing
            set(modules, function, 6, i, i % 2 == 0);
            set(modules, function, i, 6, i % 2 == 0);
        }
        drawFinder(modules, function, 3, 3);
        drawFinder(modules, function, size - 4, 3);
        drawFinder(modules, function, 3, size - 4);
        int[] align = alignmentPositions(version);
        int n = align.length;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue;   // the finders
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        set(modules, function, align[i] + dx, align[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
                    }
                }
            }
        }
        drawFormat(0, modules, function);   // reserves the format areas; drawn again with the real mask
        if (version >= 7) {
            int rem = version;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
            int bits = version << 12 | rem;
            for (int i = 0; i < 18; i++) {
                boolean dark = ((bits >>> i) & 1) != 0;
                int a = size - 11 + i % 3, b = i / 3;
                set(modules, function, a, b, dark);
                set(modules, function, b, a, dark);
            }
        }
    }

    /** A finder (7×7 with its light separator) centered on x, y. */
    private static void drawFinder(boolean[][] modules, boolean[][] function, int x, int y) {
        int size = modules.length;
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int xx = x + dx, yy = y + dy, d = Math.max(Math.abs(dx), Math.abs(dy));
                if (xx >= 0 && xx < size && yy >= 0 && yy < size) set(modules, function, xx, yy, d != 2 && d != 4);
            }
        }
    }

    private static int[] alignmentPositions(int version) {
        if (version == 1) return new int[0];
        int n = version / 7 + 2;
        int step = (version * 4 + n * 2 + 1) / (n * 2 - 2) * 2;
        int[] out = new int[n];
        out[0] = 6;
        for (int i = n - 1, pos = version * 4 + 10; i >= 1; i--, pos -= step) out[i] = pos;
        return out;
    }

    /** The 15 format bits (level M = 00, the mask), twice. */
    private static void drawFormat(int mask, boolean[][] modules, boolean[][] function) {
        int data = mask;   // M's two level bits are 00
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        int size = modules.length;
        for (int i = 0; i <= 5; i++) set(modules, function, 8, i, bit(bits, i));
        set(modules, function, 8, 7, bit(bits, 6));
        set(modules, function, 8, 8, bit(bits, 7));
        set(modules, function, 7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(modules, function, 14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(modules, function, size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(modules, function, 8, size - 15 + i, bit(bits, i));
        set(modules, function, 8, size - 8, true);   // the dark module
    }

    private static boolean bit(int value, int i) {
        return ((value >>> i) & 1) != 0;
    }

    // MARK: - Data and error correction

    /** Mode, count, the bytes, terminator and padding: the version's data codewords. */
    private static byte[] codewords(int version, byte[] data) {
        byte[] out = new byte[dataCodewords(version)];
        int[] at = {0};
        append(out, at, 0b0100, 4);   // byte mode
        append(out, at, data.length, version < 10 ? 8 : 16);
        for (byte b : data) append(out, at, b & 0xFF, 8);
        int capacityBits = out.length * 8;
        append(out, at, 0, Math.min(4, capacityBits - at[0]));   // terminator
        append(out, at, 0, (8 - at[0] % 8) % 8);
        for (int pad = 0xEC; at[0] < capacityBits; pad ^= 0xEC ^ 0x11) append(out, at, pad, 8);
        return out;
    }

    private static void append(byte[] out, int[] at, int value, int bits) {
        for (int i = bits - 1; i >= 0; i--, at[0]++) {
            if (((value >>> i) & 1) != 0) out[at[0] >>> 3] |= (byte) (0x80 >>> (at[0] & 7));
        }
    }

    /** Splits the data into blocks, adds each one's Reed-Solomon codewords, and interleaves them. */
    private static byte[] interleave(int version, byte[] data) {
        int blocks = BLOCKS[version], ecc = ECC_PER_BLOCK[version], raw = rawModules(version) / 8;
        int shortBlocks = blocks - raw % blocks, shortLen = raw / blocks;
        byte[] divisor = rsDivisor(ecc);
        byte[][] all = new byte[blocks][];
        for (int i = 0, k = 0; i < blocks; i++) {
            int len = shortLen - ecc + (i < shortBlocks ? 0 : 1);
            byte[] block = new byte[shortLen + 1];
            System.arraycopy(data, k, block, 0, len);
            byte[] rem = rsRemainder(data, k, len, divisor);
            System.arraycopy(rem, 0, block, block.length - ecc, ecc);
            k += len;
            all[i] = block;
        }
        byte[] out = new byte[raw];
        int n = 0;
        for (int i = 0; i < shortLen + 1; i++) {
            for (int j = 0; j < blocks; j++) {
                if (i != shortLen - ecc || j >= shortBlocks) out[n++] = all[j][i];   // short blocks have no byte there
            }
        }
        return out;
    }

    private static byte[] rsDivisor(int degree) {
        byte[] out = new byte[degree];
        out[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                out[j] = (byte) gfMultiply(out[j] & 0xFF, root);
                if (j + 1 < degree) out[j] ^= out[j + 1];
            }
            root = gfMultiply(root, 0x02);
        }
        return out;
    }

    private static byte[] rsRemainder(byte[] data, int from, int len, byte[] divisor) {
        byte[] out = new byte[divisor.length];
        for (int i = from; i < from + len; i++) {
            int factor = (data[i] ^ out[0]) & 0xFF;
            System.arraycopy(out, 1, out, 0, out.length - 1);
            out[out.length - 1] = 0;
            for (int j = 0; j < out.length; j++) out[j] ^= (byte) gfMultiply(divisor[j] & 0xFF, factor);
        }
        return out;
    }

    /** Multiplication in GF(2^8) modulo x^8 + x^4 + x^3 + x^2 + 1. */
    private static int gfMultiply(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    /** Two columns at a time from the right, up then down, skipping the vertical timing column. */
    private static void drawCodewords(byte[] data, boolean[][] modules, boolean[][] function) {
        int size = modules.length, i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!function[y][x] && i < data.length * 8) {
                        modules[y][x] = ((data[i >>> 3] >>> (7 - (i & 7))) & 1) != 0;
                        i++;
                    }
                }
            }
        }
    }

    // MARK: - Masks

    private static void applyMask(int mask, boolean[][] modules, boolean[][] function) {
        int size = modules.length;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean invert;
                switch (mask) {
                    case 0: invert = (x + y) % 2 == 0; break;
                    case 1: invert = y % 2 == 0; break;
                    case 2: invert = x % 3 == 0; break;
                    case 3: invert = (x + y) % 3 == 0; break;
                    case 4: invert = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: invert = x * y % 2 + x * y % 3 == 0; break;
                    case 6: invert = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: invert = ((x + y) % 2 + x * y % 3) % 2 == 0;
                }
                if (invert && !function[y][x]) modules[y][x] = !modules[y][x];
            }
        }
    }

    /** The standard's four penalties: runs of 5 or more, 2×2 blocks, finder-like patterns, and dark/light balance. */
    private static int penalty(boolean[][] m) {
        int size = m.length, score = 0, dark = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int a = 0; a < size; a++) {
                int run = 1;
                for (int b = 1; b <= size; b++) {
                    boolean same = b < size && at(m, pass, a, b) == at(m, pass, a, b - 1);
                    if (same) {
                        run++;
                    } else {
                        if (run >= 5) score += 3 + run - 5;
                        run = 1;
                    }
                }
                for (int b = 0; b + 11 <= size; b++) {
                    if (finderLike(m, pass, a, b, false) || finderLike(m, pass, a, b, true)) score += 40;
                }
            }
        }
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (m[y][x]) dark++;
                if (x + 1 < size && y + 1 < size && m[y][x] == m[y][x + 1] && m[y][x] == m[y + 1][x] && m[y][x] == m[y + 1][x + 1]) {
                    score += 3;
                }
            }
        }
        int total = size * size;
        score += ((Math.abs(dark * 20 - total * 10) + total - 1) / total - 1) * 10;
        return score;
    }

    /** Row a (pass 0) or column a (pass 1), module b. */
    private static boolean at(boolean[][] m, int pass, int a, int b) {
        return pass == 0 ? m[a][b] : m[b][a];
    }

    private static final boolean[] FINDER = {true, false, true, true, true, false, true, false, false, false, false};

    /** 1:1:3:1:1 dark-light-dark with four light modules after it (or before it, reversed). */
    private static boolean finderLike(boolean[][] m, int pass, int a, int b, boolean reversed) {
        for (int i = 0; i < 11; i++) {
            if (at(m, pass, a, b + i) != FINDER[reversed ? 10 - i : i]) return false;
        }
        return true;
    }
}
