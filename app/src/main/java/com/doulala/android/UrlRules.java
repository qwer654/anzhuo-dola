package com.doulala.android;

import java.net.URI;
import java.util.Locale;

/** Pure Java URL validation, also tested outside the Android runtime. */
public final class UrlRules {
    private UrlRules() {}
    public static String normalize(String text) {
        String s = text == null ? "" : text.trim();
        if (s.isEmpty() || s.length()>16384 || s.chars().anyMatch(c -> c<32 || c==127)) return null;
        if (!s.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) s="https://"+s;
        try {
            URI u=new URI(s);
            if (!("https".equalsIgnoreCase(u.getScheme())||"http".equalsIgnoreCase(u.getScheme())) || u.getHost()==null || u.getUserInfo()!=null) return null;
            if (u.getPort()<-1 || u.getPort()>65535) return null;
            return u.toASCIIString();
        } catch (Exception e) { return null; }
    }
    public static boolean trusted(String url) {
        try {
            URI u=new URI(url);
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getUserInfo()!=null || (u.getPort()!=-1 && u.getPort()!=443)) return false;
            String h=u.getHost().toLowerCase(Locale.ROOT);
            for(String root:new String[]{"doubao.com","dola.com","qianwen.com","jianying.com"}) if(h.equals(root)||h.endsWith("."+root)) return true;
        } catch(Exception ignored) {}
        return false;
    }
    public static String origin(String url) {
        String normalized=normalize(url);
        if(normalized==null) return null;
        try { URI u=new URI(normalized); return u.getScheme().toLowerCase(Locale.ROOT)+"://"+u.getRawAuthority().toLowerCase(Locale.ROOT); }
        catch(Exception e) { return null; }
    }
    public static String safeFilename(String name, String fallback) {
        String n=(name==null?"":name).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_").trim();
        n=n.replaceAll("^\\.+", "");
        if(n.isEmpty()) n=fallback;
        if(n.length()>120) {
            int dot=n.lastIndexOf('.');
            String ext=dot>0 && n.length()-dot<10 ? n.substring(dot):"";
            n=n.substring(0,120-ext.length())+ext;
        }
        return n;
    }
}
