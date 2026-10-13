package com.doulala.android;

import android.os.Bundle;
import android.os.Parcel;
import android.util.Base64;

/** Bounded private-preference encoding for best-effort WebView history restoration. */
final class TabStateCodec {
    static final int PER_TAB_LIMIT = 36 * 1024;
    static final int TOTAL_LIMIT = 96 * 1024;

    private TabStateCodec() {}

    static String encode(Bundle state, int remainingBudget) {
        if (state == null || remainingBudget <= 0) return null;
        Parcel parcel = Parcel.obtain();
        try {
            state.writeToParcel(parcel, 0);
            byte[] bytes = parcel.marshall();
            if (bytes.length == 0 || bytes.length > PER_TAB_LIMIT) return null;
            String encoded = Base64.encodeToString(bytes, Base64.NO_WRAP);
            return encoded.length() <= remainingBudget ? encoded : null;
        } catch (RuntimeException ignored) {
            return null;
        } finally {
            parcel.recycle();
        }
    }

    static String validEncoded(String value) {
        if (value == null || value.isEmpty() || value.length() > encodedLimit(PER_TAB_LIMIT)) return null;
        return value.matches("[A-Za-z0-9+/]*={0,2}") ? value : null;
    }

    static Bundle decode(String value) {
        String valid = validEncoded(value);
        if (valid == null) return null;
        byte[] bytes;
        try {
            bytes = Base64.decode(valid, Base64.DEFAULT);
            if (bytes.length == 0 || bytes.length > PER_TAB_LIMIT) return null;
            Parcel parcel = Parcel.obtain();
            try {
                parcel.unmarshall(bytes, 0, bytes.length);
                parcel.setDataPosition(0);
                Bundle state = Bundle.CREATOR.createFromParcel(parcel);
                state.setClassLoader(TabStateCodec.class.getClassLoader());
                return state;
            } finally {
                parcel.recycle();
            }
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static int encodedLimit(int bytes) { return ((bytes + 2) / 3) * 4; }
}

