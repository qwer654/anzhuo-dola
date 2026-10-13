package com.doulala.android;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.json.JSONObject;
import org.json.JSONArray;
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
    @Test public void deepPayloadFailsBeforeRecursiveJsonParser(){
        StringBuilder s=new StringBuilder();for(int i=0;i<100;i++)s.append("[");for(int i=0;i<100;i++)s.append("]");
        assertFalse(FallbackMediaResolver.boundedJson(s.toString()));
        assertNull(FallbackMediaResolver.parsePayload(s.toString()));
    }
    @Test public void arraysAndEmptyMainUrlMatchOriginalWorker(){
        JSONObject root=new JSONObject();JSONObject video=new JSONObject();JSONArray list=new JSONArray();
        try{JSONObject item=new JSONObject();item.put("main_url","");item.put("play_url","https://media.example.test/result.mp4");list.put(item);video.put("video_list",list);root.put("video_info",video);root.put("metadata",new JSONObject().put("key_seed","provided-seed").toString());}catch(Exception e){throw new AssertionError(e);}
        String[] parsed=FallbackMediaResolver.parsePayload(root.toString());assertNotNull(parsed);
        assertEquals("https://media.example.test/result.mp4",parsed[0]);assertEquals("provided-seed",parsed[1]);
    }
    @Test public void missingMediaIsRejectedWithoutNetwork() { assertNull(FallbackMediaResolver.parsePayload("{\"data\":{}}")); }
}
