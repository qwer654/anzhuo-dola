package com.doulala.android;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class CookieRulesTest {
    @Test public void keepsEqualsInsideValueAndEmptyValues(){
        assertEquals(Arrays.asList("session=abc==","empty="),CookieRules.parse("session=abc==; empty="));
    }
    @Test public void rejectsHeaderInjectionIncludingTrailingNewlines(){
        reject("a=1\r\nDomain=evil.test");reject("a=1\n");reject("\ta=1");reject("a=1\u0000");
    }
    @Test public void rejectsSetCookieAttributes(){
        for(String s:new String[]{"a=1; Domain=evil.test","a=1; Path=/","a=1; Secure","a=1; SameSite=None","a=1; max-age=9","$Path=/"})reject(s);
    }
    @Test public void rejectsAmbiguousOrUnsupportedValues(){
        for(String s:new String[]{"a=1; a=2","a=1;","a=b,c","a=b c","a=\"quoted\"","a=中","a=\\bad","missing","bad name=1"})reject(s);
    }
    @Test public void enforcesSizeLimits(){
        reject(null);reject("");reject("a="+"x".repeat(4095));reject("a="+"x".repeat(65536));
        StringJoiner j=new StringJoiner("; ");for(int i=0;i<101;i++)j.add("n"+i+"=1");reject(j.toString());
    }
    @Test public void requiresExactHttpsHostAndPort(){
        assertTrue(CookieRules.sameSite("https://EXAMPLE.com:443/a","https://example.com/b"));
        assertFalse(CookieRules.sameSite("https://example.com","https://sub.example.com"));
        assertFalse(CookieRules.sameSite("https://example.com","https://example.com.evil.test"));
        assertFalse(CookieRules.sameSite("https://example.com","https://example.com:444"));
        assertFalse(CookieRules.sameSite("https://example.com","http://example.com"));
        assertFalse(CookieRules.sameSite("https://user@example.com","https://example.com"));
        assertFalse(CookieRules.sameSite(null,"https://example.com"));
    }
    @Test public void restoresHostOnlySecureHttpOnlySessionCookies(){
        assertEquals("__Host-token=abc; Path=/; Secure; HttpOnly; SameSite=Lax",CookieRules.restoredCookie("__Host-token=abc"));
        try{CookieRules.restoredCookie("a=1; b=2");fail();}catch(IllegalArgumentException expected){}
    }
    private void reject(String value){try{CookieRules.parse(value);fail("invalid input accepted");}catch(IllegalArgumentException expected){}}
}
