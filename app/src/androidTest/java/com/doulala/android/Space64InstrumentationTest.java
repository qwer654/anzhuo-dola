package com.doulala.android;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import java.io.File;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Coverage for the 64th slot without starting all slot processes. */
@RunWith(AndroidJUnit4.class)
public class Space64InstrumentationTest {
    private final UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private UiObject2 waitFor(BySelector s){UiObject2 o=device.wait(Until.findObject(s),20000);assertNotNull("Missing UI: "+s,o);return o;}

    @Test public void manifestDeclaresSpace63AsPrivateProcess() throws Exception {
        PackageManager pm=context.getPackageManager();
        for(int slot=0;slot<SpaceSlots.COUNT;slot++){
            ComponentName c=new ComponentName(context,SpaceSlots.className(slot));
            ActivityInfo info=pm.getActivityInfo(c,0);
            assertFalse("Space activity must not be exported: "+slot,info.exported);
            assertEquals(":space"+slot,info.processName.substring(info.processName.lastIndexOf(':')));
        }
    }

    @Test public void lastSpaceIsReachedThroughCatalogAndLoadsDocumentProcess() throws Exception {
        assertTrue(context.getSharedPreferences("catalog",Context.MODE_PRIVATE).edit().clear()
            .putBoolean("enabled0",true).putBoolean("enabled63",true)
            .putString("name0","第1空间").putString("url0","https://example.com/")
            .putString("name63","第64空间").putString("url63","https://example.com/").commit());
        device.pressHome();
        context.startActivity(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitFor(By.text("账号空间"));
        UiScrollable list=new UiScrollable(new UiSelector().scrollable(true));
        assertTrue(list.scrollIntoView(new UiSelector().description("进入空间：第1空间")));
        device.findObject(By.desc("进入空间：第1空间")).click();
        waitFor(By.text("第1空间"));
        waitFor(By.desc("浏览器菜单"));
        String pid0=device.executeShellCommand("pidof "+context.getPackageName()+":space0").trim();
        assertFalse("space0 process did not start",pid0.isEmpty());
        assertTrue("space0 WebView directory missing",new File(context.getDataDir(),"app_webview_space0").exists());
        device.pressBack();
        device.pressHome();
        context.startActivity(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitFor(By.text("账号空间"));
        list=new UiScrollable(new UiSelector().scrollable(true));
        assertTrue(list.scrollIntoView(new UiSelector().description("进入空间：第64空间")));
        device.findObject(By.desc("进入空间：第64空间")).click();
        waitFor(By.text("第64空间"));
        waitFor(By.desc("浏览器菜单"));
        String pid63=device.executeShellCommand("pidof "+context.getPackageName()+":space63").trim();
        assertFalse("space63 process did not start",pid63.isEmpty());
        assertNotEquals("slot 0 and slot 63 must be distinct processes",pid0,pid63);
        assertTrue("space63 WebView directory missing",new File(context.getDataDir(),"app_webview_space63").exists());
        assertNotEquals("slot WebView directories must be isolated",
            new File(context.getDataDir(),"app_webview_space0").getCanonicalPath(),
            new File(context.getDataDir(),"app_webview_space63").getCanonicalPath());
    }
}
