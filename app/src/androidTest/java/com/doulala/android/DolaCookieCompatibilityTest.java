package com.doulala.android;

import android.content.Context;
import android.webkit.CookieManager;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Regression coverage for Dola's explicit compatibility-cookie import policy. */
@RunWith(AndroidJUnit4.class)
public final class DolaCookieCompatibilityTest {
    private static final long TIMEOUT_SECONDS = 20L;
    private static final String DOLA = "https://www.dola.com/";
    private static final String PARENT = "https://dola.com/";
    private static final String SUBDOMAIN = "https://auth.dola.com/";

    @Test public void compatibilityRulesAreExplicitAndCaseSensitive() {
        assertEquals("__Host-token=abc; Path=/; Secure; HttpOnly; SameSite=Lax",
                CookieRules.restoredCookie("__Host-token=abc"));
        assertEquals("passport_csrf_token=csrf; Domain=dola.com; Path=/; Secure; SameSite=Lax",
                CookieRules.restoredDolaCookie("passport_csrf_token=csrf", DOLA));
        assertEquals("passport_csrf_token_default=csrf2; Domain=dola.com; Path=/; Secure; SameSite=Lax",
                CookieRules.restoredDolaCookie("passport_csrf_token_default=csrf2", DOLA));
        assertEquals("auth=token; Domain=dola.com; Path=/; Secure; HttpOnly; SameSite=Lax",
                CookieRules.restoredDolaCookie("auth=token", DOLA));
        assertEquals("Passport_csrf_token=wrongcase; Domain=dola.com; Path=/; Secure; HttpOnly; SameSite=Lax",
                CookieRules.restoredDolaCookie("Passport_csrf_token=wrongcase", DOLA));
        assertEquals("unknown=value; Domain=dola.com; Path=/; Secure; HttpOnly; SameSite=Lax",
                CookieRules.restoredDolaCookie("unknown=value", DOLA));
        assertEquals("__Host-token=abc; Path=/; Secure; HttpOnly; SameSite=Lax",
                CookieRules.restoredDolaCookie("__Host-token=abc", DOLA));

        for (String target : new String[] {
                "http://dola.com/", "https://dola.com:444/", "https://dola.com.evil.test/",
                "https://evil-dola.com/", "https://user@dola.com/", "https://user:pass@dola.com/",
                "https://dola.com.evil/"}) {
            try {
                CookieRules.restoredDolaCookie("auth=token", target);
                fail("target accepted: " + target);
            } catch (IllegalArgumentException expected) {
                // Expected: compatibility import is restricted to HTTPS dola.com and subdomains.
            }
        }
        assertEquals("auth=token; Domain=dola.com; Path=/; Secure; HttpOnly; SameSite=Lax",
                CookieRules.restoredDolaCookie("auth=token", "https://sub.dola.com:443/path"));
    }

    @Test public void webViewSeesOnlyExplicitCompatibilityCsrfCookies() throws Exception {
        final String strictName = "passport_csrf_token";
        final String compatName = "passport_csrf_token_default";
        final String authName = "dola_synthetic_auth_compat_test";
        final String unknownName = "dola_synthetic_unknown_compat_test";
        final String strictCookie = CookieRules.restoredCookie(strictName + "=synthetic-strict");
        final String compatCookie = CookieRules.restoredDolaCookie(compatName + "=synthetic-compat", DOLA);
        final String authCookie = CookieRules.restoredDolaCookie(authName + "=synthetic-auth", DOLA);
        final String unknownCookie = CookieRules.restoredDolaCookie(unknownName + "=synthetic-unknown", DOLA);
        final CookieManager cookies = CookieManager.getInstance();
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final AtomicReference<WebView> viewRef = new AtomicReference<>();
        final AtomicReference<String> documentCookie = new AtomicReference<>();
        final CountDownLatch loaded = new CountDownLatch(1);
        try {
            setCookie(cookies, DOLA, strictCookie);


            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                WebView view = new WebView(context);
                view.getSettings().setJavaScriptEnabled(true);
                view.setNetworkAvailable(false);
                view.getSettings().setBlockNetworkLoads(true);
                view.setWebViewClient(new android.webkit.WebViewClient() {
                    @Override public void onPageFinished(WebView webView, String url) {
                        webView.evaluateJavascript("document.cookie", value -> {
                            documentCookie.set(value);
                            loaded.countDown();
                        });
                    }
                });
                viewRef.set(view);
                view.loadDataWithBaseURL(DOLA,
                        "<!doctype html><html><body>cookie fixture</body></html>",
                        "text/html", "UTF-8", null);
            });
            assertTrue("trusted HTTPS fixture did not finish", loaded.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertFalse("Strict legacy CSRF must be hidden from JavaScript",
                    documentCookie.get().contains(strictName + "="));
            // Exercise the real migration collision: remove the host-only copy before
            // installing the compatibility-domain copy with the same cookie name.
            setCookie(cookies, DOLA, CookieRules.expiredHostCookie(
                    strictName + "=synthetic-strict"));
            setCookie(cookies, DOLA,
                    CookieRules.restoredDolaCookie(strictName + "=synthetic-compat-primary", DOLA));
            setCookie(cookies, DOLA, compatCookie);
            setCookie(cookies, DOLA, authCookie);
            setCookie(cookies, DOLA, unknownCookie);
            CountDownLatch migrated = new CountDownLatch(1);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                viewRef.get().evaluateJavascript("document.cookie", value -> {
                    documentCookie.set(value);migrated.countDown();
                }));
            assertTrue("Migrated fixture did not finish",migrated.await(TIMEOUT_SECONDS,TimeUnit.SECONDS));
            String jsCookie = documentCookie.get();
            assertNotNull(jsCookie);
            assertTrue(jsCookie.contains(strictName + "="));
            assertTrue(jsCookie.contains(compatName + "="));
            assertFalse(jsCookie.contains(authName + "="));
            assertFalse(jsCookie.contains(unknownName + "="));

            // Domain=dola.com must be visible from both the parent and a Dola subdomain.
            assertEquals(1, cookieCount(cookies.getCookie(DOLA), strictName));
            assertEquals(1, cookieCount(cookies.getCookie(PARENT), strictName));
            assertEquals(1, cookieCount(cookies.getCookie(SUBDOMAIN), strictName));
            assertTrue(cookieContains(cookies.getCookie(PARENT), compatName));
            assertTrue(cookieContains(cookies.getCookie(SUBDOMAIN), compatName));
            assertTrue(cookieContains(cookies.getCookie(DOLA), compatName));
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                WebView view = viewRef.get();
                if (view != null) view.destroy();
            });
            deleteCookie(cookies, DOLA, strictName, false);
            deleteCookie(cookies, DOLA, strictName, true);
            deleteCookie(cookies, DOLA, compatName, true);
            deleteCookie(cookies, DOLA, authName, true);
            deleteCookie(cookies, DOLA, unknownName, true);
        }
    }

    private static boolean cookieContains(String header, String name) {
        if (header == null) return false;
        for (String part : header.split(";")) {
            if (part.trim().startsWith(name + "=")) return true;
        }
        return false;
    }

    private static int cookieCount(String header, String name) {
        if (header == null) return 0;
        int count = 0;
        for (String part : header.split(";")) {
            if (part.trim().startsWith(name + "=")) count++;
        }
        return count;
    }

    private static void setCookie(CookieManager manager, String url, String cookie) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Boolean> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> manager.setCookie(url, cookie, value -> { result.set(value); done.countDown(); }));
        assertTrue("CookieManager.setCookie timed out", done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue("CookieManager rejected synthetic cookie", Boolean.TRUE.equals(result.get()));
        manager.flush();
    }

    private static void deleteCookie(CookieManager manager, String url, String name, boolean domainCookie)
            throws Exception {
        String attrs = "; Max-Age=0; Path=/" + (domainCookie ? "; Domain=dola.com" : "");
        CountDownLatch done = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> manager.setCookie(url, name + "=" + attrs + "; Secure; HttpOnly",
                        value -> done.countDown()));
        assertTrue("CookieManager cleanup timed out", done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        manager.flush();
    }
}
