package com.doulala.android;

import android.content.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** UI-only smoke test across isolated processes; no accounts, credentials or successful network required. */
@RunWith(AndroidJUnit4.class)
public class BrowserSmokeTest {
    private final UiDevice device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    private UiObject2 waitFor(BySelector selector){
        UiObject2 obj=device.wait(Until.findObject(selector),20000);assertNotNull("Missing UI: "+selector,obj);return obj;
    }
    private void tap(String text){waitFor(By.text(text)).click();}
    private void menu(String item){waitFor(By.desc("浏览器菜单")).click();tap(item);}
    private void launch(Class<?> type,String title) {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.startActivity(new Intent(context,type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("home","https://example.com/").putExtra("title",title));
    }
    @Test public void tabsBookmarksDownloadsAndSpaceIsolation() throws Exception {
        launch(BrowserActivity.Space6.class,"测试空间七");
        waitFor(By.text("标签 1"));
        menu("新建标签");waitFor(By.text("标签 2"));
        menu("添加当前页书签");
        waitFor(By.desc("书签名称")).setText("Smoke bookmark");tap("保存");
        device.waitForIdle();
        menu("打开书签");waitFor(By.text("Smoke bookmark"));tap("关闭");
        menu("下载中心");waitFor(By.text("暂无下载记录"));tap("关闭");
        menu("Cookie 手动迁移");waitFor(By.text("Cookie 手动迁移（有限快照）"));tap("取消");
        // Leave and re-enter the profile: only saved URLs/selection are restored.
        menu("回到空间列表");launch(BrowserActivity.Space6.class,"测试空间七");
        waitFor(By.text("标签 2"));menu("打开书签");waitFor(By.text("Smoke bookmark"));tap("关闭");
        // Another Space gets independent preferences and starts with one tab.
        launch(BrowserActivity.Space7.class,"测试空间八");waitFor(By.text("测试空间八"));
        waitFor(By.text("标签 1"));menu("打开书签");
        assertFalse(device.hasObject(By.text("Smoke bookmark")));
        assertNotNull(device.findObject(By.desc("浏览器菜单")));
    }
}
