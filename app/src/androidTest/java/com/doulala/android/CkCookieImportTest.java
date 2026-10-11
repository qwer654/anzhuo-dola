package com.doulala.android;

import org.json.JSONException;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class CkCookieImportTest {
    @Test public void parsesOnlyCookiesAndUsesGenericAccountLabels() throws Exception {
        String json="{\"accounts\":["
            +"{\"email\":\"private@example.test\",\"uid\":\"fixture-uid\",\"cookie\":\"sid=fixture123; csrf=fixture456\",\"user_agent\":\"spoofed-UA\",\"fingerprint\":\"fixture-fp\",\"proxy\":\"127.0.0.1\"},"
            +"{\"email\":\"another@example.test\",\"cookie\":\"sid=fixture789\"}]}";
        List<CkCookieImport.Entry> entries=CkCookieImport.parse(json);
        assertEquals(2,entries.size());
        assertEquals("账号 1",entries.get(0).label);
        assertEquals("账号 2",entries.get(1).label);
        assertFalse(entries.get(0).label.contains("private"));
        assertEquals("sid=fixture123",entries.get(0).cookies.get(0));
        assertEquals("csrf=fixture456",entries.get(0).cookies.get(1));
        assertEquals(1,entries.get(1).cookies.size());
    }

    @Test public void parsesSingleAccountDolaFetchCkExportAndIgnoresMetadata() throws Exception {
        String json="{\"email\":\"private@example.test\",\"uid\":\"fixture-uid\","
            +"\"cookie\":\"sid=fixture123; csrf=fixture456\",\"registered_at\":\"fixture-date\","
            +"\"fingerprint\":\"fixture-fp\",\"user_agent\":\"fixture-UA\",\"proxy\":\"fixture-proxy\"}";
        List<CkCookieImport.Entry> entries=CkCookieImport.parse(json);
        assertEquals(1,entries.size());
        assertEquals("账号 1",entries.get(0).label);
        assertEquals(2,entries.get(0).cookies.size());
        assertEquals("sid=fixture123",entries.get(0).cookies.get(0));
        assertEquals("csrf=fixture456",entries.get(0).cookies.get(1));
    }

    @Test public void rejectsMalformedCookieAndExcessiveAccountCount() throws Exception {
        try {
            CkCookieImport.parse("{\"accounts\":[{\"cookie\":\"sid=ok; Domain=evil.test\"}]}");
            fail("Set-Cookie attributes must be rejected");
        } catch(JSONException expected) {}
        StringBuilder many=new StringBuilder("{\"accounts\":[");
        for(int i=0;i<CkCookieImport.MAX_ACCOUNTS+1;i++) {
            if(i>0)many.append(',');
            many.append("{\"cookie\":\"sid=fixture\"}");
        }
        many.append("]}");
        try { CkCookieImport.parse(many.toString()); fail("excessive account count accepted"); }
        catch(JSONException expected) {}
    }

    @Test public void onlyAcceptsHttpsDolaHostsForAggregateFormat() {
        assertTrue(CkCookieImport.isDolaTarget("https://www.dola.com/"));
        assertTrue(CkCookieImport.isDolaTarget("https://dola.com/"));
        assertFalse(CkCookieImport.isDolaTarget("http://www.dola.com/"));
        assertFalse(CkCookieImport.isDolaTarget("https://dola.com.evil.test/"));
        assertFalse(CkCookieImport.isDolaTarget("https://www.dola.com:444/"));
        assertFalse(CkCookieImport.isDolaTarget("https://user@www.dola.com/"));
    }
}
