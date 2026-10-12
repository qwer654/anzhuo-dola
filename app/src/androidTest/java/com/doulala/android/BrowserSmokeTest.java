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
    private void menu(String item){
        waitFor(By.desc("浏览器菜单")).click();
        for(int i=0;i<3&&!device.hasObject(By.text(item));i++){
            UiObject2 list=device.wait(Until.findObject(By.clazz("android.widget.ListView")),2000);
            if(list==null||!list.scroll(Direction.DOWN,0.65f))break;
        }
        tap(item);
    }
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
        if(!device.hasObject(By.desc(entryDescription)))assertTrue("Space entry button is not reachable: "+title,list.scrollIntoView(entrySelector));
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
        context.getSharedPreferences("browser_Space6",Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("browser_Space7",Context.MODE_PRIVATE).edit().clear().commit();
        launchHome();
        waitFor(By.text("账号空间"));screenshot("home");openSpace("测试空间七");
        waitFor(By.text("标签 1"));
        menu("新建标签");waitFor(By.text("标签 2"));screenshot("tabs");
        menu("添加当前页书签");
        waitFor(By.desc("书签名称")).setText("Smoke bookmark");tap("保存");
        device.waitForIdle();
        menu("打开书签");waitFor(By.text("Smoke bookmark"));screenshot("bookmarks");tap("关闭");
        menu("下载中心");waitFor(By.text("暂无下载记录"));screenshot("downloads");tap("关闭");
        menu("Cookie / CK 导入导出");waitFor(By.text("Cookie 手动迁移（有限快照）"));screenshot("cookies");tap("取消");
        menu("回到空间列表");openSpace("测试空间七");
        waitFor(By.text("标签 2"));menu("打开书签");waitFor(By.text("Smoke bookmark"));tap("关闭");
        menu("回到空间列表");openSpace("测试空间八");
        waitFor(By.text("标签 1"));menu("打开书签");
        assertFalse("A bookmark from space seven leaked into space eight",device.hasObject(By.text("Smoke bookmark")));
        assertNotNull("Browser menu is missing after switching to space eight",device.findObject(By.desc("浏览器菜单")));
    }

    @Test public void archivedAccountSpaceCanBeRestoredInItsOriginalSlot() throws Exception {
        assertTrue(context.getSharedPreferences("catalog",Context.MODE_PRIVATE).edit().clear()
            .putBoolean("enabled0",true).putBoolean("enabled1",true)
            .putString("name1","可恢复测试空间").putString("url1","https://example.com/").commit());
        launchHome();
        waitFor(By.text("账号空间"));
        UiScrollable catalog=new UiScrollable(new UiSelector().scrollable(true));
        assertTrue("Active test space is not reachable",catalog.scrollIntoView(new UiSelector().text("可恢复测试空间")));
        waitFor(By.text("可恢复测试空间"));
        assertTrue("Archive action is not reachable",catalog.scrollIntoView(new UiSelector().text("归档")));
        tap("归档");
        waitFor(By.text("归档账号空间？"));
        tap("归档空间");
        waitFor(By.text("已归档空间"));
        assertTrue("Archived test space is not reachable",catalog.scrollIntoView(new UiSelector().text("可恢复测试空间")));
        waitFor(By.text("可恢复测试空间"));
        assertFalse("Archived space must not remain in the active card list",
            device.findObjects(By.desc("进入空间：可恢复测试空间")).size()>0);
        assertTrue("Restore action is not reachable",catalog.scrollIntoView(new UiSelector().text("恢复此空间")));
        tap("恢复此空间");
        waitFor(By.text("恢复账号空间？"));
        tap("恢复");
        assertTrue("Restored test space is not reachable",catalog.scrollIntoView(new UiSelector().description("进入空间：可恢复测试空间")));
        waitFor(By.desc("进入空间：可恢复测试空间"));
        assertTrue(context.getSharedPreferences("catalog",Context.MODE_PRIVATE).getBoolean("enabled1",false));
        // Restoring removes the archive keys; absent must mean not archived.
        android.content.SharedPreferences restored=context.getSharedPreferences("catalog",Context.MODE_PRIVATE);
        assertFalse("Restored space must not be archived",restored.getBoolean("archived1",false));
        assertFalse("Archive marker must be cleared",restored.contains("archived1"));
        assertFalse("Archived name must be cleared",restored.contains("archivedName1"));
        assertFalse("Archived URL must be cleared",restored.contains("archivedUrl1"));
    }

    @Test public void newAttachment30sNativeSwitchRequiresConsentAndIsIndependentPerSpace() throws Exception {
        // Uses fresh disposable emulator slots, not a real user's account or Dola endpoint.
        assertTrue(context.getSharedPreferences("catalog",Context.MODE_PRIVATE).edit().clear()
            .putBoolean("enabled0",false).putBoolean("enabled3",true).putBoolean("enabled4",true)
            .putString("name3","新脚本A").putString("name4","新脚本B")
            .putString("url3","https://example.com/").putString("url4","https://example.com/").commit());
        assertTrue(context.getSharedPreferences("browser_Space3",Context.MODE_PRIVATE).edit()
            .remove(DolaPageScripts.PREF_KEY).putBoolean("originalDola30",true).putBoolean("dola30PageScripts",true).commit());
        launchHome();openSpace("新脚本A");
        waitFor(By.desc("本空间新脚本30s开关")).click();waitFor(By.text("开启本空间 30s？"));tap("取消");
        waitFor(By.desc("本空间新脚本30s开关")).click();waitFor(By.text("开启本空间 30s？"));tap("开启并重载");
        waitFor(By.desc("浏览器菜单"));
        menu("回到空间列表");openSpace("新脚本B");
        waitFor(By.desc("本空间新脚本30s开关")).click();waitFor(By.text("开启本空间 30s？"));tap("取消");
        menu("回到空间列表");openSpace("新脚本A");
        waitFor(By.desc("本空间新脚本30s开关")).click();waitFor(By.text("关闭本空间 30s？"));tap("关闭并重载");
        waitFor(By.desc("浏览器菜单"));
        waitFor(By.desc("本空间新脚本30s开关")).click();waitFor(By.text("开启本空间 30s？"));tap("取消");
        menu("回到空间列表");waitFor(By.text("账号空间"));
    }
    @Test public void oldControlsAreAbsentAndDirectActionsAreVisible() throws Exception {
        assertTrue(context.getSharedPreferences("catalog",Context.MODE_PRIVATE).edit().clear()
            .putBoolean("enabled0",false).putBoolean("enabled7",true)
            .putString("name7","控件验收").putString("url7","https://example.com/").commit());
        launchHome();
        waitFor(By.text("账号空间"));openSpace("控件验收");
        waitFor(By.text("标签 1"));
        assertTrue(device.hasObject(By.desc("回到空间列表")));
        assertTrue(device.hasObject(By.desc("当前IP")));
        assertTrue(device.hasObject(By.desc("导入CK")));
        assertTrue(device.hasObject(By.desc("清理空间")));
        assertTrue(device.hasObject(By.desc("浏览器菜单")));
        assertFalse(device.hasObject(By.textContains("隐私洁净")));
        assertFalse(device.hasObject(By.textContains("旧 Dola30")));
        menu("回到空间列表");
        waitFor(By.text("账号空间"));
    }

    @Test public void directCkImportButtonCanBeCancelledWithoutSelectingCredentials() throws Exception {
        assertTrue(context.getSharedPreferences("catalog",Context.MODE_PRIVATE).edit().clear()
            .putBoolean("enabled0",false).putBoolean("enabled8",true)
            .putString("name8","CK按钮验收").putString("url8","https://example.com/").commit());
        launchHome();openSpace("CK按钮验收");
        waitFor(By.desc("导入CK")).click();
        // Native document picker is intentionally opened then cancelled; no file or real credential is selected.
        boolean picker = device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")),5000)
            || device.wait(Until.hasObject(By.pkg("com.android.documentsui")),5000);
        assertTrue("CK import must open the native document picker",picker);
        device.pressBack();
        waitFor(By.desc("浏览器菜单"));
    }
}

