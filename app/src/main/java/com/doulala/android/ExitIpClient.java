package com.doulala.android;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Explicit user-triggered IP lookup. No WebView cookies, account headers or page contents. */
final class ExitIpClient {
    private ExitIpClient() {}
    static String validate(String raw) throws java.io.IOException {
        String ip=raw==null?"":raw.trim();
        if(ip.length()>64||!ip.matches("[0-9a-fA-F:.]+")||(!ip.contains(":")&&!ip.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")))
            throw new java.io.IOException("Invalid IP response");
        // The literal-only check above prevents DNS lookups from untrusted response text.
        try {return InetAddress.getByName(ip).getHostAddress();}
        catch(Exception e){throw new java.io.IOException("Invalid IP response");}
    }
    static String lookup() throws java.io.IOException {
        HttpURLConnection connection=(HttpURLConnection)new URL("https://api64.ipify.org").openConnection();
        try {
            connection.setConnectTimeout(10000);connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);
            connection.setRequestMethod("GET");connection.setRequestProperty("Accept","text/plain");
            connection.setRequestProperty("User-Agent","Doulala-IP-Check/0.3");
            connection.setRequestProperty("Cookie","");connection.setRequestProperty("Authorization","");
            if(connection.getResponseCode()!=200)throw new java.io.IOException("IP lookup unavailable");
            try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] data=new byte[128];int n;
                while((n=in.read(data))!=-1){if(out.size()+n>128)throw new java.io.IOException("Response too large");out.write(data,0,n);}
                return validate(new String(out.toByteArray(),StandardCharsets.US_ASCII));
            }
        } finally {connection.disconnect();}
    }
}
