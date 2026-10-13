package com.doulala.android;
import org.junit.Test;
import java.net.InetAddress;
import static org.junit.Assert.*;
public class ResolverNetworkRulesTest {
 @Test public void blocksNonPublicAddressRanges(){
   for(String s:new String[]{"127.0.0.1","10.1.2.3","172.16.0.1","192.168.1.1","169.254.1.1","100.64.0.1","192.0.2.1","198.51.100.1","203.0.113.1","0.0.0.0","224.0.0.1","::1","fc00::1","fe80::1","2001:db8::1","::ffff:127.0.0.1","2002:a00:1::1","2001::1","3fff::1"})try{assertFalse(s,FallbackMediaResolver.isPublicAddress(InetAddress.getByName(s)));}catch(Exception e){throw new AssertionError(e);}
 }
 @Test public void cancellationAndClosedOwnerDoNotAccessNetwork(){
   FallbackMediaResolver r=new FallbackMediaResolver();
   assertEquals("cancelled",r.resolve("https://invalid.example.test/",()->true).errorCode);
   r.close();assertEquals("cancelled",r.resolve("https://invalid.example.test/",()->false).errorCode);
 }
 @Test public void acceptsOrdinaryGlobalUnicastOnly(){try{assertTrue(FallbackMediaResolver.isPublicAddress(InetAddress.getByName("8.8.8.8")));assertTrue(FallbackMediaResolver.isPublicAddress(InetAddress.getByName("2606:4700:4700::1111")));}catch(Exception e){throw new AssertionError(e);}}
 @Test public void requestIsHttpsPort443NoFragmentsOrCredentialsAndAddsOnlyAbsentControls(){
   assertNull(FallbackMediaResolver.buildRequest("https://u:p@api.example.test/a"));assertNull(FallbackMediaResolver.buildRequest("https://api.example.test/a#x"));assertNull(FallbackMediaResolver.buildRequest("http://api.example.test/a"));
   String r=FallbackMediaResolver.buildRequest("https://api.example.test/a%2Fb?sig=a%2Bb&channel=original");assertNotNull(r);assertTrue(r.contains("/a%2Fb?sig=a%2Bb"));assertTrue(r.contains("channel=no"));assertTrue(r.contains("codec_type=8"));assertTrue(r.contains("logo_type=unwatermarked"));assertFalse(r.contains("channel=original"));
 }
}
