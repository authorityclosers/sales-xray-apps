package com.authorityclosers.salesxray;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Ed25519 signature checks for live web updates (LiveWeb). Android has its own
 * Ed25519 only from Android 13, and this app runs from Android 7, so this is
 * TweetNaCl's verify (public domain, Bernstein et al.) in Java, plus RFC 8032's
 * rule that S is below the group order. Verify only: the app never signs.
 * Tested against RFC 8032's vectors and the JDK's own Ed25519 (Ed25519Test).
 */
final class Ed25519 {

    private Ed25519() {}

    private static final long[] GF0 = new long[16];
    private static final long[] GF1 = gf(1);
    private static final long[] D = gf(0x78a3, 0x1359, 0x4dca, 0x75eb, 0xd8ab, 0x4141, 0x0a4d, 0x0070, 0xe898, 0x7779, 0x4079, 0x8cc7, 0xfe73, 0x2b6f, 0x6cee, 0x5203);
    private static final long[] D2 = gf(0xf159, 0x26b2, 0x9b94, 0xebd6, 0xb156, 0x8283, 0x149a, 0x00e0, 0xd130, 0xeef3, 0x80f2, 0x198e, 0xfce7, 0x56df, 0xd9dc, 0x2406);
    private static final long[] X = gf(0xd51a, 0x8f25, 0x2d60, 0xc956, 0xa7b2, 0x9525, 0xc760, 0x692c, 0xdc5c, 0xfdd6, 0xe231, 0xc0a4, 0x53fe, 0xcd6e, 0x36d3, 0x2169);
    private static final long[] Y = gf(0x6658, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666);
    private static final long[] I = gf(0xa0b0, 0x4a0e, 0x1b27, 0xc4ee, 0xe478, 0xad2f, 0x1806, 0x2f43, 0xd7a7, 0x3dfb, 0x0099, 0x2b4d, 0xdf0b, 0x4fc1, 0x2480, 0x2b83);
    /** The group order L, little-endian. */
    private static final long[] L = {
        0xed, 0xd3, 0xf5, 0x5c, 0x1a, 0x63, 0x12, 0x58, 0xd6, 0x9c, 0xf7, 0xa2, 0xde, 0xf9, 0xde, 0x14,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x10,
    };

    /** True when {@code signature} (64 bytes) is {@code publicKey}'s (32 bytes) signature of {@code message}. */
    static boolean verify(byte[] publicKey, byte[] message, byte[] signature) {
        if (publicKey == null || publicKey.length != 32 || signature == null || signature.length != 64 || message == null) return false;
        if (!canonicalScalar(signature)) return false;
        long[][] q = point();
        if (unpackneg(q, publicKey) != 0) return false;
        byte[] h;
        try {
            MessageDigest sha512 = MessageDigest.getInstance("SHA-512");
            sha512.update(signature, 0, 32);
            sha512.update(publicKey);
            sha512.update(message);
            h = sha512.digest();
        } catch (NoSuchAlgorithmException error) {
            return false;
        }
        reduce(h);
        long[][] p = point();
        scalarmult(p, q, h);
        byte[] s = new byte[32];
        System.arraycopy(signature, 32, s, 0, 32);
        scalarbase(q, s);
        add(p, q);
        byte[] t = new byte[32];
        pack(t, p);
        byte[] r = new byte[32];
        System.arraycopy(signature, 0, r, 0, 32);
        return MessageDigest.isEqual(r, t);
    }

    /** RFC 8032: S (the signature's second half) must be below L. */
    private static boolean canonicalScalar(byte[] signature) {
        for (int i = 31; i >= 0; i--) {
            long s = signature[32 + i] & 0xff;
            if (s < L[i]) return true;
            if (s > L[i]) return false;
        }
        return false;
    }

    private static long[] gf(long... values) {
        long[] o = new long[16];
        System.arraycopy(values, 0, o, 0, values.length);
        return o;
    }

    private static long[][] point() {
        return new long[][] { new long[16], new long[16], new long[16], new long[16] };
    }

    private static void set25519(long[] r, long[] a) {
        System.arraycopy(a, 0, r, 0, 16);
    }

    private static void car25519(long[] o) {
        for (int i = 0; i < 16; i++) {
            o[i] += 1L << 16;
            long c = o[i] >> 16;
            o[i < 15 ? i + 1 : 0] += c - 1 + (i == 15 ? 37 * (c - 1) : 0);
            o[i] -= c << 16;
        }
    }

    private static void sel25519(long[] p, long[] q, int b) {
        long c = ~(b - 1L);
        for (int i = 0; i < 16; i++) {
            long t = c & (p[i] ^ q[i]);
            p[i] ^= t;
            q[i] ^= t;
        }
    }

    private static void pack25519(byte[] o, long[] n) {
        long[] m = new long[16];
        long[] t = new long[16];
        System.arraycopy(n, 0, t, 0, 16);
        car25519(t);
        car25519(t);
        car25519(t);
        for (int j = 0; j < 2; j++) {
            m[0] = t[0] - 0xffed;
            for (int i = 1; i < 15; i++) {
                m[i] = t[i] - 0xffff - ((m[i - 1] >> 16) & 1);
                m[i - 1] &= 0xffff;
            }
            m[15] = t[15] - 0x7fff - ((m[14] >> 16) & 1);
            int b = (int) ((m[15] >> 16) & 1);
            m[14] &= 0xffff;
            sel25519(t, m, 1 - b);
        }
        for (int i = 0; i < 16; i++) {
            o[2 * i] = (byte) (t[i] & 0xff);
            o[2 * i + 1] = (byte) ((t[i] >> 8) & 0xff);
        }
    }

    private static boolean neq25519(long[] a, long[] b) {
        byte[] c = new byte[32];
        byte[] d = new byte[32];
        pack25519(c, a);
        pack25519(d, b);
        return !MessageDigest.isEqual(c, d);
    }

    private static int par25519(long[] a) {
        byte[] d = new byte[32];
        pack25519(d, a);
        return d[0] & 1;
    }

    private static void unpack25519(long[] o, byte[] n) {
        for (int i = 0; i < 16; i++) o[i] = (n[2 * i] & 0xff) + ((long) (n[2 * i + 1] & 0xff) << 8);
        o[15] &= 0x7fff;
    }

    private static void add(long[] o, long[] a, long[] b) {
        for (int i = 0; i < 16; i++) o[i] = a[i] + b[i];
    }

    private static void sub(long[] o, long[] a, long[] b) {
        for (int i = 0; i < 16; i++) o[i] = a[i] - b[i];
    }

    private static void mul(long[] o, long[] a, long[] b) {
        long[] t = new long[31];
        for (int i = 0; i < 16; i++) {
            for (int j = 0; j < 16; j++) t[i + j] += a[i] * b[j];
        }
        for (int i = 0; i < 15; i++) t[i] += 38 * t[i + 16];
        System.arraycopy(t, 0, o, 0, 16);
        car25519(o);
        car25519(o);
    }

    private static void square(long[] o, long[] a) {
        mul(o, a, a);
    }

    private static void pow2523(long[] o, long[] i) {
        long[] c = new long[16];
        System.arraycopy(i, 0, c, 0, 16);
        for (int a = 250; a >= 0; a--) {
            square(c, c);
            if (a != 1) mul(c, c, i);
        }
        System.arraycopy(c, 0, o, 0, 16);
    }

    private static void inv25519(long[] o, long[] i) {
        long[] c = new long[16];
        System.arraycopy(i, 0, c, 0, 16);
        for (int a = 253; a >= 0; a--) {
            square(c, c);
            if (a != 2 && a != 4) mul(c, c, i);
        }
        System.arraycopy(c, 0, o, 0, 16);
    }

    private static void add(long[][] p, long[][] q) {
        long[] a = new long[16], b = new long[16], c = new long[16], d = new long[16];
        long[] t = new long[16], e = new long[16], f = new long[16], g = new long[16], h = new long[16];
        sub(a, p[1], p[0]);
        sub(t, q[1], q[0]);
        mul(a, a, t);
        add(b, p[0], p[1]);
        add(t, q[0], q[1]);
        mul(b, b, t);
        mul(c, p[3], q[3]);
        mul(c, c, D2);
        mul(d, p[2], q[2]);
        add(d, d, d);
        sub(e, b, a);
        sub(f, d, c);
        add(g, d, c);
        add(h, b, a);
        mul(p[0], e, f);
        mul(p[1], h, g);
        mul(p[2], g, f);
        mul(p[3], e, h);
    }

    private static void cswap(long[][] p, long[][] q, int b) {
        for (int i = 0; i < 4; i++) sel25519(p[i], q[i], b);
    }

    private static void pack(byte[] r, long[][] p) {
        long[] tx = new long[16], ty = new long[16], zi = new long[16];
        inv25519(zi, p[2]);
        mul(tx, p[0], zi);
        mul(ty, p[1], zi);
        pack25519(r, ty);
        r[31] ^= (byte) (par25519(tx) << 7);
    }

    private static void scalarmult(long[][] p, long[][] q, byte[] s) {
        set25519(p[0], GF0);
        set25519(p[1], GF1);
        set25519(p[2], GF1);
        set25519(p[3], GF0);
        for (int i = 255; i >= 0; --i) {
            int b = ((s[i >> 3] & 0xff) >> (i & 7)) & 1;
            cswap(p, q, b);
            add(q, p);
            add(p, p);
            cswap(p, q, b);
        }
    }

    private static void scalarbase(long[][] p, byte[] s) {
        long[][] q = point();
        set25519(q[0], X);
        set25519(q[1], Y);
        set25519(q[2], GF1);
        mul(q[3], X, Y);
        scalarmult(p, q, s);
    }

    private static void modL(byte[] r, long[] x) {
        long carry;
        for (int i = 63; i >= 32; --i) {
            carry = 0;
            int j;
            for (j = i - 32; j < i - 12; ++j) {
                x[j] += carry - 16 * x[i] * L[j - (i - 32)];
                carry = (x[j] + 128) >> 8;
                x[j] -= carry << 8;
            }
            x[j] += carry;
            x[i] = 0;
        }
        carry = 0;
        for (int j = 0; j < 32; j++) {
            x[j] += carry - (x[31] >> 4) * L[j];
            carry = x[j] >> 8;
            x[j] &= 255;
        }
        for (int j = 0; j < 32; j++) x[j] -= carry * L[j];
        for (int i = 0; i < 32; i++) {
            x[i + 1] += x[i] >> 8;
            r[i] = (byte) (x[i] & 255);
        }
    }

    /** {@code r} (64 bytes) mod L, into its first 32 bytes. */
    private static void reduce(byte[] r) {
        long[] x = new long[64];
        for (int i = 0; i < 64; i++) x[i] = r[i] & 0xff;
        for (int i = 0; i < 64; i++) r[i] = 0;
        modL(r, x);
    }

    private static int unpackneg(long[][] r, byte[] p) {
        long[] t = new long[16], chk = new long[16], num = new long[16], den = new long[16];
        long[] den2 = new long[16], den4 = new long[16], den6 = new long[16];
        set25519(r[2], GF1);
        unpack25519(r[1], p);
        square(num, r[1]);
        mul(den, num, D);
        sub(num, num, r[2]);
        add(den, r[2], den);

        square(den2, den);
        square(den4, den2);
        mul(den6, den4, den2);
        mul(t, den6, num);
        mul(t, t, den);

        pow2523(t, t);
        mul(t, t, num);
        mul(t, t, den);
        mul(t, t, den);
        mul(r[0], t, den);

        square(chk, r[0]);
        mul(chk, chk, den);
        if (neq25519(chk, num)) mul(r[0], r[0], I);

        square(chk, r[0]);
        mul(chk, chk, den);
        if (neq25519(chk, num)) return -1;

        if (par25519(r[0]) == ((p[31] & 0xff) >> 7)) sub(r[0], GF0, r[0]);

        mul(r[3], r[0], r[1]);
        return 0;
    }
}
