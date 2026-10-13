package com.doulala.android;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.junit.Assert.*;

/** Payload-only checks: deliberately no sockets, DNS, or real service calls. */
@RunWith(AndroidJUnit4.class)
public class FallbackResolverInstrumentationTest {
    @Test public void nestedPayloadPicksHighestBitrateAndFindsSeed() {
        String low=Base64.getEncoder().encodeToString("https://low.example.test/a".getBytes(StandardCharsets.UTF_8));
        String high=Base64.getEncoder().encodeToString("https://high.example.test/b".getBytes(StandardCharsets.UTF_8));
        String json="{\"data\":{\"video_info\":{\"data\":{\"video_list\":{\"a\":{\"main_url\":\""+low+"\",\"bitrate\":1},\"b\":{\"play_url\":\""+high+"\",\"bitrate\":2}}}}},\"key_seed\":\"seed-value\"}";
        String[] parsed=FallbackMediaResolver.parsePayload(json);
        assertNotNull(parsed); assertEquals(high,parsed[0]); assertEquals("seed-value",parsed[1]);
    }
    @Test public void missingMediaIsRejectedWithoutNetwork() { assertNull(FallbackMediaResolver.parsePayload("{\"data\":{}}")); }
}
