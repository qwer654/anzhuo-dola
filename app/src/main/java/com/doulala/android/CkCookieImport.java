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
        ArrayList<Entry> out=new ArrayList<>();
        if(accounts!=null) {
            if(accounts.length()<1 || accounts.length()>MAX_ACCOUNTS) throw new JSONException("invalid accounts");
            for(int i=0;i<accounts.length();i++) {
                JSONObject account=accounts.optJSONObject(i);
                if(account==null) throw new JSONException("invalid account entry");
                out.add(parseEntry(account,i+1));
            }
        } else if(root.has("cookie")) {
            // DolaFetchCk exports one account per JSON file at the document root.
            out.add(parseEntry(root,1));
        } else throw new JSONException("missing CK cookie data");
        return Collections.unmodifiableList(out);
    }

    private static Entry parseEntry(JSONObject account,int index) throws JSONException {
        Object rawCookie=account.opt("cookie");
        if(!(rawCookie instanceof String)) throw new JSONException("missing cookie");
        List<String> pairs;
        try { pairs=CookieRules.parse((String)rawCookie); }
        catch(IllegalArgumentException invalid) { throw new JSONException("invalid cookie header"); }
        return new Entry("账号 "+index,pairs);
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
