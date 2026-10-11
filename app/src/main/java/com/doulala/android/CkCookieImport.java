package com.doulala.android;

import org.json.*;
import java.net.URI;
import java.util.*;

/** Parser for the user's single-account CK export format. Never imports identity/proxy metadata. */
final class CkCookieImport {
    static final int MAX_ACCOUNTS = 16;
    static final class Entry {
        final String label;
        final List<String> cookies;
        Entry(String label,List<String> cookies) {
            this.label=label;
            this.cookies=Collections.unmodifiableList(new ArrayList<>(cookies));
        }
    }
    private CkCookieImport() {}

    static List<Entry> parse(String json) throws JSONException {
        if(json==null || json.isEmpty() || json.length()>128*1024) throw new JSONException("invalid CK file size");
        JSONObject root=new JSONObject(json);
        JSONArray accounts=root.optJSONArray("accounts");
        if(accounts==null || accounts.length()<1 || accounts.length()>MAX_ACCOUNTS) throw new JSONException("invalid accounts");
        ArrayList<Entry> out=new ArrayList<>();
        for(int i=0;i<accounts.length();i++) {
            JSONObject account=accounts.optJSONObject(i);
            if(account==null) throw new JSONException("invalid account entry");
            Object rawCookie=account.opt("cookie");
            if(!(rawCookie instanceof String)) throw new JSONException("missing cookie");
            List<String> pairs;
            try { pairs=CookieRules.parse((String)rawCookie); }
            catch(IllegalArgumentException invalid) { throw new JSONException("invalid cookie header"); }
            out.add(new Entry("账号 "+(i+1),pairs));
        }
        return Collections.unmodifiableList(out);
    }

    static boolean isDolaTarget(String target) {
        try {
            URI uri=new URI(target);
            String host=uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme()) && host!=null &&
                (uri.getPort()==-1 || uri.getPort()==443) &&
                (host.equalsIgnoreCase("dola.com") || host.toLowerCase(Locale.ROOT).endsWith(".dola.com")) &&
                uri.getRawUserInfo()==null;
        } catch(Exception ignored) { return false; }
    }

}
