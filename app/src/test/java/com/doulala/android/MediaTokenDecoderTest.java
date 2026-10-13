package com.doulala.android;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.Assert.*;

public class MediaTokenDecoderTest {
 @Test public void decodesPlainAndUrlSafeBase64HttpsOnly(){
   String url="https://cdn.example.test/video.mp4?sig=a%2Bb";
   assertEquals(url,MediaTokenDecoder.decode(Base64.getUrlEncoder().withoutPadding().encodeToString(url.getBytes(StandardCharsets.UTF_8)),null));
   assertNull(MediaTokenDecoder.decode(Base64.getEncoder().encodeToString("http://bad.test/a".getBytes(StandardCharsets.UTF_8)),null));
 }
 @Test public void originalWorkerVerifiedHardcodedVector(){
   assertEquals("https://media.example.test/original-worker.mp4?sig=a%2Bb",
     MediaTokenDecoder.decode("qAABAOZE1g2IEVTwuKXKA3CBVNhfE8EcxZiU4HhlDYYKflBxG06bkDkaILZ8JDE7Tkrza8izKAo2jmf9Ji4qTgx7dVo=",
       "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDEyMzQ1"));
 }
 @Test public void qaabKnownAnswerUsesOnlyProvidedSeed(){ throwsNothing(); }
 private static void throwsNothing(){
   try {
    String url="https://media.example.test/a.mp4?x=1", seed=Base64.getEncoder().encodeToString("012345678901234567890123456789012345".getBytes(StandardCharsets.UTF_8));
    byte[] seedBytes=Base64.getDecoder().decode(seed), first=MessageDigest.getInstance("SHA-512").digest(java.util.Arrays.copyOf(seedBytes,32));
    // Independent full service-worker salt, deliberately not obtained from the decoder.
    byte[] salt=hex("4dd4c2e6b83162090e52b3c7a6733ba4"+"1cb2462b829ab58a196b39db57177524"+"f49baf7f08e8d68d26a72e37c1a95a2f"+"1f05a51892aef2949732b62a38aadd58"), input=new byte[first.length+salt.length];System.arraycopy(first,0,input,0,first.length);System.arraycopy(salt,0,input,first.length,salt.length);
    byte[] d=MessageDigest.getInstance("SHA-512").digest(input), plain=pkcs7(url.getBytes(StandardCharsets.UTF_8)); Cipher c=Cipher.getInstance("AES/CBC/NoPadding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(java.util.Arrays.copyOfRange(d,0,16),"AES"),new IvParameterSpec(java.util.Arrays.copyOfRange(d,16,32)));byte[] cipher=c.doFinal(plain), qaab=new byte[cipher.length+4];qaab[0]=(byte)0xa8;qaab[2]=1;System.arraycopy(cipher,0,qaab,4,cipher.length);
    assertEquals(url,MediaTokenDecoder.decode(Base64.getEncoder().encodeToString(qaab),seed)); assertNull(MediaTokenDecoder.decode(Base64.getEncoder().encodeToString(qaab),"not a seed"));
   } catch(Exception e){throw new AssertionError(e);}
 }
 @Test public void rejectsCredentialsFragmentsAndControls(){assertFalse(MediaTokenDecoder.isHttpsPublicShape("https://u:p@example.test/a"));assertFalse(MediaTokenDecoder.isHttpsPublicShape("https://example.test/a#x"));assertFalse(MediaTokenDecoder.isHttpsPublicShape("https://example.test/a\n"));assertFalse(MediaTokenDecoder.isHttpsPublicShape("https://example.test:444/a"));}
 private static byte[] pkcs7(byte[] x){int p=16-x.length%16;byte[] r=java.util.Arrays.copyOf(x,x.length+p);java.util.Arrays.fill(r,x.length,r.length,(byte)p);return r;}private static byte[] hex(String x){byte[]r=new byte[x.length()/2];for(int i=0;i<r.length;i++)r[i]=(byte)Integer.parseInt(x.substring(2*i,2*i+2),16);return r;}
}
