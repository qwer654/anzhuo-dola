package com.doulala.android;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Per BrowserActivity/space resolver. Network access is deliberately cookie-free and fail-closed. */
public final class FallbackMediaResolver {
    public static final int CONNECT_READ_MS=8000, TOTAL_MS=8000, MAX_BODY=2*1024*1024, MAX_CACHE=200;
    private final LinkedHashMap<String, CacheEntry> cache = new LinkedHashMap<String, CacheEntry>(16,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,CacheEntry> e) { return size()>MAX_CACHE; }
    };
    private final ExecutorService dnsWorkers = new java.util.concurrent.ThreadPoolExecutor(2,2,0L,TimeUnit.MILLISECONDS,new java.util.concurrent.ArrayBlockingQueue<>(2),new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private final java.util.concurrent.ScheduledExecutorService deadlineWorker=Executors.newSingleThreadScheduledExecutor();
    private volatile boolean closed;
    private volatile HttpsURLConnection currentConnection;
    public static final class Result { public final boolean success,cached; public final String url,errorCode;
        private Result(boolean ok,String value,String error,boolean fromCache){success=ok;url=value;errorCode=error;cached=fromCache;}
        static Result ok(String u,boolean c){return new Result(true,u,null,c);} static Result fail(String e){return new Result(false,null,e,false);} }
    private static final class CacheEntry { final String url; final long at; CacheEntry(String u,long t){url=u;at=t;} }

    public synchronized Result resolve(String fallbackUrl, BooleanSupplier cancelled) {
        long deadline=System.nanoTime()+TOTAL_MS*1_000_000L;
        if (closed || cancelled != null && cancelled.getAsBoolean()) return Result.fail("cancelled");
        String request=buildRequest(fallbackUrl); if (request==null) return Result.fail("invalid_url");
        HttpsURLConnection c=null;java.util.concurrent.ScheduledFuture<?> timeout=null;
        try {
            URI uri=new URI(request); resolvePublicHost(uri.getHost(),deadline,cancelled);
            CacheEntry prior; synchronized(cache){prior=cache.get(request);} long now=System.currentTimeMillis();
            if(prior!=null && now-prior.at<=600_000L) return Result.ok(prior.url,true);
            checkDeadline(deadline,cancelled);
            c=(HttpsURLConnection)new URL(request).openConnection(); currentConnection=c;
            final HttpsURLConnection owned=c; timeout=deadlineWorker.schedule(owned::disconnect,remainingMs(deadline),TimeUnit.MILLISECONDS);
            c.setConnectTimeout(remainingMs(deadline)); c.setReadTimeout(remainingMs(deadline)); c.setInstanceFollowRedirects(false);
            c.setRequestMethod("GET"); c.setUseCaches(false); c.setRequestProperty("Accept","application/json,text/plain,*/*");
            c.setRequestProperty("Cookie",""); c.setRequestProperty("Authorization",""); c.setRequestProperty("Referer","");
            c.setSSLSocketFactory(new PublicAddressSocketFactory((SSLSocketFactory)SSLSocketFactory.getDefault(), uri.getHost(),this,deadline,cancelled));
            if(c.getResponseCode()!=200) return Result.fail("request_failed");
            checkDeadline(deadline,cancelled); String text=new String(readBounded(c,deadline,cancelled),java.nio.charset.StandardCharsets.UTF_8);
            String[] parsed=parsePayload(text); if(parsed==null) return Result.fail("no_media"); String token=parsed[0];
            String seed=parsed[1];
            if(seed==null) seed=uri.getQuery()==null?null:query(uri.getRawQuery(),"key_seed");
            String output=MediaTokenDecoder.decode(token,seed);
            if(!MediaTokenDecoder.isHttpsPublicShape(output)) return Result.fail("decode_failed");
            resolvePublicHost(new URI(output).getHost(),deadline,cancelled);
            checkDeadline(deadline,cancelled); if(closed)return Result.fail("cancelled"); synchronized(cache){if(!closed)cache.put(request,new CacheEntry(output,System.currentTimeMillis()));} return Result.ok(output,false);
        } catch (SecurityException e) { return Result.fail("blocked_host"); }
          catch (java.util.concurrent.CancellationException e) { return Result.fail("cancelled"); }
          catch (Exception e) { return Result.fail("request_failed"); }
          finally { if(timeout!=null)timeout.cancel(false);if(c!=null)c.disconnect(); currentConnection=null; }
    }
    /** Owner must call this from onDestroy; it clears per-space results and bounds outstanding DNS tasks. */
    public void close(){closed=true;dnsWorkers.shutdownNow();deadlineWorker.shutdownNow();HttpsURLConnection c=currentConnection;if(c!=null)c.disconnect();synchronized(cache){cache.clear();}}
    static String buildRequest(String raw) {
        if(!MediaTokenDecoder.isHttpsPublicShape(raw)) return null;
        try { URI u=new URI(raw); String q=u.getRawQuery(); StringBuilder kept=new StringBuilder();
            // Keep every signed field byte-for-byte, except the three original controls which v1.6.14 replaces.
            if(q!=null) for(String part:q.split("&")){String k=part.contains("=")?part.substring(0,part.indexOf('=')):part;k=java.net.URLDecoder.decode(k,"UTF-8");if(!"channel".equals(k)&&!"codec_type".equals(k)&&!"logo_type".equals(k)){if(kept.length()>0)kept.append('&');kept.append(part);}}
            if(kept.length()>0)kept.append('&'); kept.append("channel=no&codec_type=8&logo_type=unwatermarked"); q=kept.toString();
            String authority=u.getRawAuthority();
            String path=u.getRawPath()==null||u.getRawPath().isEmpty()?"/":u.getRawPath();
            return "https://"+authority+path+"?"+q;
        } catch(Exception e){return null;}
    }
    static boolean isPublicAddress(InetAddress a) {
        if(a==null || a.isAnyLocalAddress()||a.isLoopbackAddress()||a.isLinkLocalAddress()||a.isSiteLocalAddress()||a.isMulticastAddress()) return false;
        byte[] b=a.getAddress();
        if(a instanceof Inet4Address){int x=b[0]&255,y=b[1]&255,z=b[2]&255; return !(x==0||x==10||x==100&&y>=64&&y<=127||x==127||x==169&&y==254||x==172&&y>=16&&y<=31||x==192&&y==0&&z==0||x==192&&y==88&&z==99||x==192&&y==168||x==192&&y==0&&z==2||x==198&&(y==18||y==19)||x==198&&y==51&&z==100||x==203&&y==0&&z==113||x>=224);}
        if(a instanceof Inet6Address){
            if((b[0]&0xe0)!=0x20)return false;
            // Reject IETF special assignments 2001::/23, documentation and 6to4.
            if((b[0]&255)==0x20&&(b[1]&255)==0x01&&((b[2]&255)<2 || (b[2]&255)==0x0d&&(b[3]&255)==0xb8))return false;
            if((b[0]&255)==0x20&&(b[1]&255)==0x02)return false;
            if((b[0]&255)==0x3f&&(b[1]&255)==0xff&&(b[2]&255)<0x10)return false;
            return true;
        }
        return false;
    }
    private InetAddress[] resolvePublicHost(final String host,long deadline,BooleanSupplier cancelled) throws Exception { if(host==null||host.isEmpty())throw new SecurityException();checkDeadline(deadline,cancelled);if(closed)throw new java.util.concurrent.CancellationException();Future<InetAddress[]> f=dnsWorkers.submit(()->InetAddress.getAllByName(host));try{long remaining=Math.max(1,(deadline-System.nanoTime())/1_000_000L);InetAddress[] all=f.get(Math.min(CONNECT_READ_MS,remaining),TimeUnit.MILLISECONDS);checkDeadline(deadline,cancelled);if(all.length==0)throw new SecurityException();for(InetAddress a:all)if(!isPublicAddress(a))throw new SecurityException();return all;}finally{f.cancel(true);} }
    private static int remainingMs(long deadline){return (int)Math.max(1,Math.min(CONNECT_READ_MS,(deadline-System.nanoTime())/1_000_000L));}
    private static byte[] readBounded(HttpsURLConnection connection,long deadline,BooleanSupplier cancelled)throws Exception{connection.setReadTimeout(remainingMs(deadline));try(InputStream input=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n,total=0;while(true){checkDeadline(deadline,cancelled);connection.setReadTimeout(remainingMs(deadline));n=input.read(b);if(n==-1)break;checkDeadline(deadline,cancelled);if((total+=n)>MAX_BODY)throw new SecurityException();out.write(b,0,n);}return out.toByteArray();}}
    private static void checkDeadline(long d,BooleanSupplier c){if((c!=null&&c.getAsBoolean()))throw new java.util.concurrent.CancellationException();if(System.nanoTime()>d)throw new java.util.concurrent.CancellationException();}
    private static String query(String q,String wanted){if(q==null)return null;for(String p:q.split("&")){int i=p.indexOf('=');String k=i<0?p:p.substring(0,i);if(wanted.equals(k))try{return i<0?"":java.net.URLDecoder.decode(p.substring(i+1),"UTF-8");}catch(Exception ignored){return null;}}return null;}
    private static String mediaToken(JSONObject value){
        Object main=value.opt("main_url"), play=value.opt("play_url");
        return main instanceof String&&!((String)main).trim().isEmpty()?((String)main).trim():play instanceof String?((String)play).trim():"";
    }
    private static String pickToken(JSONObject root){
        JSONObject data=root.optJSONObject("video_info");
        if(data==null&&root.optJSONObject("data")!=null)data=root.optJSONObject("data").optJSONObject("video_info");
        if(data==null)data=root; if(data.optJSONObject("data")!=null)data=data.optJSONObject("data");
        Object list=data.opt("video_list");ArrayList<JSONObject> choices=new ArrayList<>();
        if(list instanceof JSONObject){java.util.Iterator<String> it=((JSONObject)list).keys();int count=0;while(it.hasNext()&&++count<=1000){JSONObject entry=((JSONObject)list).optJSONObject(it.next());if(entry!=null)choices.add(entry);}}
        else if(list instanceof JSONArray){JSONArray a=(JSONArray)list;for(int i=0;i<a.length()&&i<1000;i++){JSONObject entry=a.optJSONObject(i);if(entry!=null)choices.add(entry);}}
        if(choices.isEmpty())choices.add(data);String best=null;double score=Double.NEGATIVE_INFINITY;
        for(JSONObject entry:choices){String token=mediaToken(entry);if(token.isEmpty())continue;double next=entry.optDouble("bitrate",entry.optDouble("real_bitrate",0))+entry.optDouble("vwidth",entry.optDouble("width",0))*entry.optDouble("vheight",entry.optDouble("height",0));
            if(best==null||next>score){best=token;score=next;}}return best;
    }
    static boolean boundedJson(String text){
        if(text==null||text.length()>MAX_BODY)return false;
        int depth=0;boolean quoted=false,escaped=false;
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);
            if(quoted){if(escaped)escaped=false;else if(c==92)escaped=true;else if(c==34)quoted=false;}
            else if(c==34)quoted=true;else if(c==123||c==91){if(++depth>32)return false;}else if(c==125||c==93){if(--depth<0)return false;}
        }
        return depth==0&&!quoted;
    }
    static String[] parsePayload(String text){if(!boundedJson(text))return null;try{JSONObject root=new JSONObject(text);String token=pickToken(root);if(token==null||token.length()>131072)return null;return new String[]{token,findKeySeed(root,0,new int[]{0})};}catch(Exception e){return null;}}
    private static String findKeySeed(Object x,int depth,int[] nodes){if(x==null||depth>10||++nodes[0]>1000)return null;if(x instanceof JSONObject){JSONObject o=(JSONObject)x;String s=o.optString("key_seed","").trim();if(!s.isEmpty())return s;java.util.Iterator<String>it=o.keys();while(it.hasNext()){String r=findKeySeed(o.opt(it.next()),depth+1,nodes);if(r!=null)return r;}}else if(x instanceof JSONArray){JSONArray a=(JSONArray)x;for(int i=0;i<a.length();i++){String r=findKeySeed(a.opt(i),depth+1,nodes);if(r!=null)return r;}}else if(x instanceof String){String s=(String)x;if(boundedJson(s)&&s.trim().startsWith("{"))try{String nested=findKeySeed(new JSONObject(s),depth+1,nodes);if(nested!=null)return nested;}catch(Exception ignored){}int p=s.indexOf("key_seed=");if(p>=0){String q=s.substring(p+9);int e=q.indexOf('&');try{return java.net.URLDecoder.decode(e<0?q:q.substring(0,e),"UTF-8");}catch(Exception ignored){return null;}}}return null;}
    private static final class PublicAddressSocketFactory extends SSLSocketFactory { final SSLSocketFactory d;final String expected;final FallbackMediaResolver owner;final long deadline;final BooleanSupplier cancelled;PublicAddressSocketFactory(SSLSocketFactory x,String h,FallbackMediaResolver o,long dl,BooleanSupplier c){d=x;expected=h;owner=o;deadline=dl;cancelled=c;} private java.net.Socket safe(String h,int p)throws Exception{if(!expected.equalsIgnoreCase(h)||p!=443)throw new SecurityException();for(InetAddress a:owner.resolvePublicHost(h,deadline,cancelled)){owner.checkDeadline(deadline,cancelled);java.net.Socket s=new java.net.Socket();s.connect(new InetSocketAddress(a,p),remainingMs(deadline));s.setSoTimeout(remainingMs(deadline));if(isPublicAddress(s.getInetAddress()))return s;s.close();}throw new SecurityException();} private SSLSocket wrap(java.net.Socket s,String h,int p)throws Exception{if(!isPublicAddress(s.getInetAddress()))throw new SecurityException();return (SSLSocket)d.createSocket(s,h,p,true);} public String[] getDefaultCipherSuites(){return d.getDefaultCipherSuites();}public String[] getSupportedCipherSuites(){return d.getSupportedCipherSuites();}public java.net.Socket createSocket(String h,int p)throws java.io.IOException{try{return wrap(safe(h,p),h,p);}catch(Exception e){throw new java.io.IOException("blocked",e);}}public java.net.Socket createSocket(String h,int p,InetAddress l,int lp)throws java.io.IOException{return createSocket(h,p);}public java.net.Socket createSocket(InetAddress h,int p)throws java.io.IOException{return createSocket(h.getHostAddress(),p);}public java.net.Socket createSocket(InetAddress h,int p,InetAddress l,int lp)throws java.io.IOException{return createSocket(h,p);}public java.net.Socket createSocket(java.net.Socket s,String h,int p,boolean a)throws java.io.IOException{try{if(!s.isConnected()||!isPublicAddress(s.getInetAddress())||!expected.equalsIgnoreCase(h)||p!=443)throw new SecurityException();return wrap(s,h,p);}catch(Exception e){throw new java.io.IOException("blocked",e);}} }
}
