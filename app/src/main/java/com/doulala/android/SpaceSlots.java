package com.doulala.android;

/** Stable slot identities: never renumber existing profiles when expanding capacity. */
final class SpaceSlots {
    static final int COUNT=64;
    private SpaceSlots() {}
    static String suffix(int slot) {
        if(slot<0||slot>=COUNT)throw new IllegalArgumentException("Invalid space slot");
        return "space"+slot;
    }
    static String className(int slot) { suffix(slot);return "com.doulala.android.BrowserActivity$Space"+slot; }
    static boolean isSuffix(String value) {
        if(value==null||!value.matches("space(?:0|[1-9][0-9]?)"))return false;
        return Integer.parseInt(value.substring(5))<COUNT;
    }
}
