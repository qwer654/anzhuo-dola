package com.doulala.android;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Stateless decoder for the documented fallback response formats.  It never guesses keys. */
public final class MediaTokenDecoder {
    private static final int MAX_TOKEN_CHARS = 131072, MAX_SEED_CHARS = 16384;
    private static final byte[] SALT = hex("4dd4c2e6b83162090e52b3c7a6733ba4"
            + "1cb2462b829ab58a196b39db57177524"
            + "f49baf7f08e8d68d26a72e37c1a95a2f"
            + "1f05a51892aef2949732b62a38aadd58");
    private MediaTokenDecoder() { }

    public static String decode(String token, String keySeed) {
        if (token == null) return null;
        if (token.length() > MAX_TOKEN_CHARS || (keySeed != null && keySeed.length() > MAX_SEED_CHARS)) return null;
        String value = token.trim();
        if (isHttpsPublicShape(value)) return value;
        String plain = bytesToUrl(decodeLoose(value));
        if (plain != null && isHttpsPublicShape(plain)) return plain;
        if (!value.startsWith("qAAB") || keySeed == null || keySeed.trim().isEmpty()) return null;
        return decodeQaab(value, keySeed.trim());
    }

    static String decodeQaab(String token, String keySeed) {
        try {
            byte[] data = decodeLoose(token), seed = decodeLoose(keySeed);
            if (data == null || seed == null) return null;
            byte[] first = MessageDigest.getInstance("SHA-512").digest(slice(seed, 0, Math.min(32, seed.length)));
            byte[] secondInput = new byte[first.length + SALT.length];
            System.arraycopy(first, 0, secondInput, 0, first.length);
            System.arraycopy(SALT, 0, secondInput, first.length, SALT.length);
            byte[] second = MessageDigest.getInstance("SHA-512").digest(secondInput);
            byte[] key = slice(second, 0, 16), iv = slice(second, 16, 32);
            if (header(data)) {
                String out = decrypt(slice(data,4,data.length), key, iv); if (out != null) return out;
                out = decrypt(slice(data,4,data.length), iv, key); if (out != null) return out;
                if (data.length > 36) {
                    out = decrypt(slice(data,36,data.length), key, slice(data,20,36)); if (out != null) return out;
                    out = decrypt(slice(data,36,data.length), key, iv); if (out != null) return out;
                }
                return null;
            }
            return decrypt(data, key, iv);
        } catch (Exception ignored) { return null; }
    }

    static byte[] decodeLoose(String input) {
        if (input == null) return null;
        String raw = input.trim();
        String[] values = {raw, raw.replace('$','_').replace('@','/').replace('#','.'), raw.replace('$','+').replace('@','/').replace('#','=')};
        for (String value : values) try {
            if (value.isEmpty()) continue;
            int padding = (4 - value.length() % 4) % 4;
            String normalized = value.replace('-','+').replace('_','/');
            for (int i=0;i<padding;i++) normalized += "=";
            return Base64.getDecoder().decode(normalized);
        } catch (IllegalArgumentException ignored) { }
        return null;
    }

    static boolean isHttpsPublicShape(String text) {
        if (text == null || text.length() > 16384 || text.chars().anyMatch(c -> c < 32 || c == 127)) return false;
        try {
            URI uri = new URI(text);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getFragment() == null && (uri.getPort() == -1 || uri.getPort() == 443);
        } catch (Exception ignored) { return false; }
    }

    private static String decrypt(byte[] payload, byte[] key, byte[] iv) {
        if (payload == null || payload.length == 0 || payload.length % 16 != 0) return null;
        try {
            Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key,"AES"), new IvParameterSpec(iv));
            byte[] plain = c.doFinal(payload);
            String direct = bytesToUrl(plain); if (direct != null && isHttpsPublicShape(direct)) return direct;
            int pad = plain[plain.length - 1] & 255;
            if (pad > 0 && pad <= 16 && pad <= plain.length) {
                for (int i=plain.length-pad;i<plain.length;i++) if ((plain[i]&255) != pad) return null;
                String stripped = bytesToUrl(slice(plain,0,plain.length-pad));
                if (stripped != null && isHttpsPublicShape(stripped)) return stripped;
            }
        } catch (Exception ignored) { }
        return null;
    }
    private static boolean header(byte[] x) { return x.length >= 4 && (x[0]&255)==0xa8 && x[1]==0 && x[2]==1 && x[3]==0; }
    private static String bytesToUrl(byte[] b) {
        if (b == null || b.length == 0) return null;
        for (byte x:b) { int n=x&255; if (n != 9 && n != 10 && n != 13 && (n < 32 || n > 126)) return null; }
        return new String(b, StandardCharsets.UTF_8);
    }
    private static byte[] slice(byte[] x, int from, int to) { byte[] r=new byte[to-from]; System.arraycopy(x,from,r,0,r.length); return r; }
    private static byte[] hex(String s) { byte[] out=new byte[s.length()/2]; for(int i=0;i<out.length;i++) out[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16); return out; }
}
