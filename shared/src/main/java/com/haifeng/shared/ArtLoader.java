package com.haifeng.shared;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.util.LruCache;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public final class ArtLoader {
    private static final String TAG = "ArtLoader";

    // 内存缓存：最多缓存 30 张封面图，按字节大小管理 (约 15MB 内存上限)
    private static final int MAX_CACHE_SIZE_BYTES = 15 * 1024 * 1024;
    private static final LruCache<String, Bitmap> MEMORY_CACHE = new LruCache<String, Bitmap>(MAX_CACHE_SIZE_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount();
        }
    };

    private ArtLoader() {}

    /**
     * 同步检查内存缓存是否存在
     */
    @Nullable
    public static Bitmap getFromMemoryCache(String urlString) {
        if (urlString == null) return null;
        synchronized (MEMORY_CACHE) {
            return MEMORY_CACHE.get(urlString);
        }
    }

    /**
     * 将位图存入内存缓存
     */
    public static void putToMemoryCache(String urlString, Bitmap bmp) {
        if (urlString == null || bmp == null) return;
        synchronized (MEMORY_CACHE) {
            MEMORY_CACHE.put(urlString, bmp);
        }
    }

    /**
     * 加载封面图，优先从内存缓存读取（0ms 响应）；未命中时发起网络请求并自动写入缓存。
     */
    @Nullable
    public static Bitmap loadBitmapFromUrl(String urlString) {
        if (urlString == null || urlString.trim().isEmpty()) {
            return null;
        }

        // 1. 优先命中内存缓存
        Bitmap cached = getFromMemoryCache(urlString);
        if (cached != null) {
            Log.d(TAG, "⚡ Cache hit for artwork: " + urlString);
            return cached;
        }

        // 2. 缓存未命中，执行网络拉取
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(5000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)");
            conn.connect();

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP " + code + " for artwork URL: " + urlString);
                return null;
            }

            in = conn.getInputStream();
            Bitmap bmp = BitmapFactory.decodeStream(in);
            if (bmp != null) {
                putToMemoryCache(urlString, bmp);
                Log.i(TAG, "✅ Loaded remote artwork: " + bmp.getWidth() + "x" + bmp.getHeight() + " from " + urlString);
            }
            return bmp;
        } catch (Throwable t) {
            Log.w(TAG, "Failed to load artwork from url: " + urlString + " (" + t.getMessage() + ")");
            return null;
        } finally {
            if (in != null) {
                try { in.close(); } catch (Exception ignored) {}
            }
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Safely compress bitmap to PNG byte array for Media3 metadata.
     */
    @Nullable
    public static byte[] bitmapToByteArray(Bitmap bitmap) {
        if (bitmap == null) return null;
        try {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            return stream.toByteArray();
        } catch (Throwable t) {
            Log.w(TAG, "Failed to compress bitmap", t);
            return null;
        }
    }
}
