package com.doulala.android;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;

/** Explicit, bounded preview for public image URLs observed in the page. */
final class MediaPreview {
    static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_DIMENSION = 512;
    private MediaPreview() {}

    static URL validateImageUrl(String raw, String type) {
        if (raw == null || !raw.equals(raw.trim()) || raw.length() > 8192 || !"image".equals(type)) return null;
        try {
            URL url = new URL(raw);
            if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getHost().isEmpty()
                    || url.getUserInfo() != null || (url.getPort() != -1 && url.getPort() != 443)) return null;
            return url;
        } catch (Exception ignored) { return null; }
    }

    static Bitmap fetch(URL url) throws Exception {
        if (url == null) throw new IllegalArgumentException("Invalid preview URL");
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(7000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "image/jpeg,image/png,image/gif,image/webp");
        try {
            if (connection.getResponseCode() != 200) throw new java.io.IOException("HTTP " + connection.getResponseCode());
            String contentType = connection.getContentType();
            if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("image/"))
                throw new java.io.IOException("Not an image response");
            int length = connection.getContentLength();
            if (length > MAX_BYTES) throw new java.io.IOException("Preview exceeds size limit");
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, length > 0 ? length : 4096));
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[8192]; int count, total = 0;
                while ((count = in.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_BYTES) throw new java.io.IOException("Preview exceeds size limit");
                    out.write(buffer, 0, count);
                }
            }
            byte[] bytes = out.toByteArray();
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new java.io.IOException("Unsupported image");
            int sample = 1;
            while (bounds.outWidth / sample > MAX_DIMENSION || bounds.outHeight / sample > MAX_DIMENSION) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = sample;
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (bitmap == null) throw new java.io.IOException("Image decode failed");
            return bitmap;
        } finally { connection.disconnect(); }
    }
}
