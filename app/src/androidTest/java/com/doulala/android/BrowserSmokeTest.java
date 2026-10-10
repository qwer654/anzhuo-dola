package com.doulala.android;

import android.content.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import java.io.File;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** UI-only smoke test across isolated processes; uses only disposable CI fixture data. */
@RunWith(AndroidJUnit4.class)
public class BrowserSmokeTest {
    private final UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private UiObject2 waitFor(BySelector selector){
        UiObject2 obj=device.wait(Until.findObject(selector),20000);assertNotNull("Missing UI: "+selector,obj);return obj;
    }
    private void tap(String text){waitFor(By.text(text)).click();}
    private void menu(String item){waitFor(By.desc("浏览器菜单")).click();tap(item);}
    private void launchHome() {
        // Each smoke method starts from a clean foreground task. Prior isolated
        // BrowserActivity tasks can otherwise be restored instead of the catalog.
        device.pressHome();
        context.startActivity(new Intent(context,MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }
    private void screenshot(String name) throws Exception {
        File dir=new File(context.getExternalFilesDir(null),"smoke");
        assertTrue(dir.isDirectory() || dir.mkdirs());
        assertTrue(device.takeScreenshot(new File(dir,name+".png")));
        device.dumpWindowHierarchy(new File(dir,name+".xml"));
    }
    @After public void captureFinalState() throws Exception {screenshot("final");}
    private void openSpace(String title) throws Exception {
        waitFor(By.text("账号空间"));
        UiScrollable list=new UiScrollable(new UiSelector().scrollable(true));
        String entryDescription="进入空间："+title;
        UiSelector entrySelector=new UiSelector().description(entryDescription);
        assertTrue("Space entry button is not reachable: "+title,list.scrollIntoView(entrySelector));
        UiObject entry=device.findObject(entrySelector);
        assertTrue("Space entry button is not reachable: "+title,entry.exists());entry.click();
        waitFor(By.text(title));waitFor(By.desc("浏览器菜单"));
    }
    @Test public void tabsBookmarksDownloadsAndSpaceIsolation() throws Exception {
        // Follow the real entry/return path. Starting a finishing singleTask activity
        // directly from the test races onNewIntent and does not represent user re-entry.
        assertTrue(context.getSharedPreferences("catalog",Context.MODE_PRIVATE).edit().clear()
            .putBoolean("enabled0",false).putBoolean("enabled6",true).putBoolean("enabled7",true)
            .putString("name6","测试空间七").putString("name7","测试空间八")
            .putString("url6","https://example.com/").putString("url7","https://example.com/").commit());
        launchHome();
        waitFor(By.text("账号空间"));screenshot("home");openSpace("测试空间七");
        waitFor(By.text("标签 1"));
        menu("新建标签");waitFor(By.text("标签 2"));screenshot("tabs");
        menu("添加当前页书签");
        waitFor(By.desc("书签名称")).setText("Smoke bookmark");tap("保存");
        device.waitForIdle();
        menu("打开书签");waitFor(By.text("Smoke bookmark"));screenshot("bookmarks");tap("关闭");
        menu("下载中心");waitFor(By.text("暂无下载记录"));screenshot("downloads");tap("关闭");
        menu("Cookie 手动迁移");waitFor(By.text("Cookie 手动迁移（有限快照）"));screenshot("cookies");tap("取消");
        menu("回到空间列表");openSpace("测试空间七");
        waitFor(By.text("标签 2"));menu("打开书签");waitFor(By.text("Smoke bookmark"));tap("关闭");
        menu("回到空间列表");openSpace("测试空间八");
        waitFor(By.text("标签 1"));menu("打开书签");
        assertFalse("A bookmark from space seven leaked into space eight",device.hasObject(By.text("Smoke bookmark")));
        assertNotNull("Browser menu is missing after switching to space eight",device.findObject(By.desc("浏览器菜单")));
    }

    @Test public void privacyCleanModeRequiresConsentAndCanBeReverted() throws Exception {
        assertTrue(context.getSharedPreferences("catalog",Context.MODE_PRIVATE).edit().clear()
            .putBoolean("enabled0",false).putBoolean("enabled7",true)
            .putString("name7","隐私洁净验收").putString("url7","https://example.com/").commit());
        launchHome();
        waitFor(By.text("账号空间"));openSpace("隐私洁净验收");
        waitFor(By.text("标签 1"));
        menu("隐私洁净模式：关闭");
        waitFor(By.text("开启隐私洁净模式？"));
        tap("取消");
        menu("隐私洁净模式：关闭");
        waitFor(By.text("开启隐私洁净模式？"));
        tap("开启并重载");
        waitFor(By.desc("浏览器菜单"));
        menu("隐私洁净模式：已启用");
        waitFor(By.text("关闭隐私洁净模式？"));
        tap("关闭并重载");
        waitFor(By.desc("浏览器菜单"));
        menu("隐私洁净模式：关闭");
        waitFor(By.text("开启隐私洁净模式？"));
        tap("取消");
        menu("回到空间列表");
        waitFor(By.text("账号空间"));
    }
}

