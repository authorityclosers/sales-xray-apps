package com.authorityclosers.salesxray;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutorService;

/**
 * "Picture of this screen" for Report a problem (Redmi bug #13: the page's own
 * copy came out blank). Android copies the app's window as the seller sees it,
 * shrunk to the size the page asked for. The picture stays in memory and goes
 * to the page only: never the gallery or a file.
 */
final class Screenshot {

    interface Done {
        void picture(String base64, String type);

        void failed(String code);
    }

    private Screenshot() {}

    /** On the main thread; the encoding runs on {@code io}. */
    static void take(Activity activity, int maxEdge, String format, int quality, ExecutorService io, Done done) {
        Window window = activity.getWindow();
        View root = window == null ? null : window.getDecorView();
        if (root == null || root.getWidth() <= 0 || root.getHeight() <= 0) {
            done.failed("unavailable");
            return;
        }
        int[] size = ScreenshotRules.size(root.getWidth(), root.getHeight(), maxEdge);
        Bitmap bitmap;
        try {
            bitmap = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError | IllegalArgumentException error) {
            done.failed("unavailable");
            return;
        }
        boolean png = ScreenshotRules.png(format);
        int level = ScreenshotRules.quality(quality);
        if (Build.VERSION.SDK_INT >= 26) {
            // The window's own pixels, scaled into the bitmap (the WebView draws on the GPU, which
            // a software copy of the view would miss).
            try {
                PixelCopy.request(
                    window,
                    bitmap,
                    result -> {
                        if (result == PixelCopy.SUCCESS) encode(bitmap, png, level, io, done);
                        else {
                            bitmap.recycle();
                            done.failed("unavailable");
                        }
                    },
                    new Handler(Looper.getMainLooper())
                );
            } catch (IllegalArgumentException error) {
                bitmap.recycle();
                done.failed("unavailable");
            }
            return;
        }
        Canvas canvas = new Canvas(bitmap);
        canvas.scale((float) size[0] / root.getWidth(), (float) size[1] / root.getHeight());
        root.draw(canvas);
        encode(bitmap, png, level, io, done);
    }

    private static void encode(Bitmap bitmap, boolean png, int quality, ExecutorService io, Done done) {
        io.execute(() -> {
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                bitmap.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, quality, out);
                done.picture(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), png ? "image/png" : "image/jpeg");
            } catch (Exception | OutOfMemoryError error) {
                done.failed("unavailable");
            } finally {
                bitmap.recycle();
            }
        });
    }
}
