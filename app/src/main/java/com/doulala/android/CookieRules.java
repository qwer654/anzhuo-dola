package com.doulala.android;

import java.util.*;

/** Strict request-cookie parsing. Never accepts Set-Cookie attributes or multiple headers. */
public final class CookieRules {
    public static final int MAX_BYTES = 65536, MAX_COOKIES = 100;
    private static final Set<String> RESERVED = new HashSet<>(Arrays.asList(
            "domain", "path", "expires", "max-age", "secure", "httponly", "samesite", "partitioned"));
    private CookieRules() {}

    public static List<String> parse(String header) {
        if (header == null || header.isEmpty() || header.length() > MAX_BYTES)
            throw new IllegalArgumentException("Cookie 为空或超过 64 KiB");
        // Check BEFORE trimming, so CR/LF cannot disappear at the edges.
        for (int i=0; i<header.length(); i++) {
            char c=header.charAt(i);
            if (c<32 || c>126) throw new IllegalArgumentException("Cookie 含非法控制字符或非 ASCII 字符");
        }
        String[] parts=header.split(";", -1);
        if (parts.length>MAX_COOKIES) throw new IllegalArgumentException("最多导入 100 个 Cookie");
        List<String> result=new ArrayList<>(); Set<String> names=new HashSet<>();
        for (String part:parts) {
            String p=part.trim(); int eq=p.indexOf('=');
            if (eq<1 || p.length()>4096) throw new IllegalArgumentException("Cookie 格式无效或单项过大");
            String name=p.substring(0,eq), value=p.substring(eq+1);
            if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || name.startsWith("$") ||
                    RESERVED.contains(name.toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("仅支持 Cookie 请求头，不接受 Set-Cookie 属性");
            for (int i=0;i<value.length();i++) {
                char c=value.charAt(i);
                if (c<=32 || c=='"' || c==',' || c=='\\' || c==';' || c>=127)
                    throw new IllegalArgumentException("Cookie 值含不支持的字符");
            }
            if (!names.add(name)) throw new IllegalArgumentException("同名不同路径 Cookie 无法用快照无损迁移");
            result.add(name+"="+value);
        }
        return Collections.unmodifiableList(result);
    }

    public static String target(String url) {
        String origin=UrlRules.origin(url);
        if (origin==null || !origin.startsWith("https://"))
            throw new IllegalArgumentException("请先打开 HTTPS 网页");
        return origin+"/";
    }

    public static boolean sameSite(String source, String destination) {
        try { return target(source).equals(target(destination)); }
        catch (IllegalArgumentException e) { return false; }
    }

    public static String restoredCookie(String pair) {
        List<String> parsed=parse(pair);
        if(parsed.size()!=1) throw new IllegalArgumentException("每次只能写入一个 Cookie");
        // Snapshot API has no attribute metadata. Do NOT silently weaken HttpOnly.
        return parsed.get(0)+"; Path=/; Secure; HttpOnly; SameSite=Lax";
    }

    /**
     * Explicit legacy Dola compatibility, not an attribute-preserving restore.
     * Only the two exact CSRF names may be script-readable; auth/unknown stay HttpOnly.
     * Parent-domain scope is user-confirmed, never used for generic snapshots.
     */
    public static String restoredDolaCookie(String pair,String url) {
        String origin=target(url);
        String host=java.net.URI.create(origin).getHost().toLowerCase(Locale.ROOT);
        if(!host.equals("dola.com") && !host.endsWith(".dola.com"))
            throw new IllegalArgumentException("Dola CK compatibility requires a Dola HTTPS origin");
        // A non-default HTTPS port is outside the explicitly supported Dola origins.
        if(java.net.URI.create(origin).getPort()!=-1)
            throw new IllegalArgumentException("Dola CK compatibility requires default HTTPS port");
        List<String> parsed=parse(pair);
        if(parsed.size()!=1) throw new IllegalArgumentException("one Cookie per write");
        String cookie=parsed.get(0),name=cookie.substring(0,cookie.indexOf('='));
        boolean readable=name.equals("passport_csrf_token") || name.equals("passport_csrf_token_default");
        boolean hostOnly=name.startsWith("__Host-");
        return cookie+(hostOnly?"":"; Domain=dola.com")+"; Path=/; Secure"
            +(readable?"":"; HttpOnly")+"; SameSite=Lax";
    }

    /** Clears only this named, root-path host cookie before a confirmed domain write. */
    public static String expiredHostCookie(String pair) {
        List<String> parsed=parse(pair);
        if(parsed.size()!=1) throw new IllegalArgumentException("one Cookie per write");
        String cookie=parsed.get(0),name=cookie.substring(0,cookie.indexOf('='));
        return name+"=; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=0";
    }
}
