package com.doulala.android;

import android.app.Activity;
import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * Dialog regressions using only synthetic CK. Cancels before any Cookie write.
 * Uses a catalog Activity, so no real webpage or account is opened.
 */
@RunWith(AndroidJUnit4.class)
public class CkCookieImportUiRegressionTest {
    private Activity host;
    private CookieTransfer transfer;
    private UiDevice device;

    @Before public void setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        Intent launch = new Intent(
            InstrumentationRegistry.getInstrumentation().getTargetContext(), MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        host = InstrumentationRegistry.getInstrumentation().startActivitySync(launch);
        // CookieTransfer only needs an Activity and an origin supplier. Keeping
        // it independent of BrowserActivity avoids opening a real web page.
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
            transfer = new CookieTransfer(host, () -> "https://www.dola.com/",62));
    }

    @After public void tearDown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            if (transfer != null) transfer.close();
            if (host != null) host.finish();
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test public void singleSyntheticAccountShowsConfirmTitleAndCookieCount_thenCancel() throws Exception {
        List<CkCookieImport.Entry> entries = CkCookieImport.parse(
            "{\"cookie\":\"sid=fixture-single; csrf=fixture-csrf\"}" );
        invokeChoose(entries);

        assertTrue("single account must go directly to confirmation",
            device.wait(Until.hasObject(By.text("确认修改本空间登录态？")), 10_000));
        assertTrue("confirmation must show the synthetic cookie count",
            device.wait(Until.hasObject(By.textContains("数量：2")), 5_000));

        // Negative button only: no CookieManager.setCookie/flush path is reached.
        assertTrue(device.wait(Until.hasObject(By.text("取消")), 5_000));
        device.findObject(By.text("取消")).click();
    }

    @Test public void aggregateSyntheticAccountsShowRows_selectSecond_thenCancelConfirmation() throws Exception {
        List<CkCookieImport.Entry> entries = CkCookieImport.parse(
            "{\"accounts\":["
                +"{\"cookie\":\"sid=fixture-first\"},"
                +"{\"cookie\":\"sid=fixture-second; csrf=fixture-second-csrf\"}]}" );
        invokeChoose(entries);

        assertTrue("aggregate import must render a selectable account list",
            device.wait(Until.hasObject(By.text("选择一个 Dola CK 账号")), 10_000));
        assertTrue(device.wait(Until.hasObject(By.text("账号 1（1 项 Cookie）")), 5_000));
        assertTrue(device.wait(Until.hasObject(By.text("账号 2（2 项 Cookie）")), 5_000));

        // Select the second synthetic account, then cancel its confirmation.
        device.findObject(By.text("账号 2（2 项 Cookie）")).click();
        assertTrue(device.wait(Until.hasObject(By.text("确认修改本空间登录态？")), 10_000));
        assertTrue("second account's cookie count must be shown",
            device.wait(Until.hasObject(By.textContains("数量：2")), 5_000));
        assertTrue(device.wait(Until.hasObject(By.text("取消")), 5_000));
        device.findObject(By.text("取消")).click();
    }


    @Test public void compatibilityImportRequiresSecondConfirmationAndCanBeCancelled() throws Exception {
        invokeChoose(CkCookieImport.parse("{\"cookie\":\"sessionid=fixture; passport_csrf_token=fixture\"}"));
        assertTrue(device.wait(Until.hasObject(By.text("Dola兼容方式")),10_000));
        device.findObject(By.text("Dola兼容方式")).click();
        assertTrue(device.wait(Until.hasObject(By.text("确认使用 Dola CK 兼容方式？")),10_000));
        assertTrue(device.wait(Until.hasObject(By.textContains("不延长服务端有效期")),5_000));
        assertTrue(device.wait(Until.hasObject(By.text("确认兼容导入")),5_000));
        device.findObject(By.text("取消")).click();
        assertTrue("Cancelled import dialog must close",
            device.wait(Until.gone(By.text("确认使用 Dola CK 兼容方式？")),5_000));
    }

    @Test public void ordinarySnapshotCannotSelectDolaCompatibility() throws Exception {
        Method confirm=CookieTransfer.class.getDeclaredMethod("confirmImport",String.class,List.class,boolean.class);
        confirm.setAccessible(true);
        List<String> pairs=CookieRules.parse("sessionid=fixture");
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            try {confirm.invoke(transfer,"https://www.dola.com/",pairs,false);}
            catch(Exception e){throw new AssertionError(e);}
        });
        assertTrue(device.wait(Until.hasObject(By.text("确认修改本空间登录态？")),10_000));
        org.junit.Assert.assertFalse(device.hasObject(By.text("Dola兼容方式")));
        device.findObject(By.text("取消")).click();
    }

    @Test public void importShowsRiskWarningWithoutClaimingSessionRepair() throws Exception {
        invokeChoose(CkCookieImport.parse("{\"cookie\":\"sessionid=fixture-risk\"}"));
        assertTrue(device.wait(Until.hasObject(By.textContains("710022002")),10_000));
        assertTrue(device.wait(Until.hasObject(By.textContains("安卓根因")),5_000));
        device.findObject(By.text("取消")).click();
    }

    @Test public void identicalCkInAnotherSpaceIsRejectedBeforeCookieWrites() throws Exception {
        android.content.Context c=host.getApplicationContext();
        CkImportBindings guard=new CkImportBindings(c.getNoBackupFilesDir().toPath().resolve("ck-import-bindings"));
        List<String> pairs=CookieRules.parse("sessionid=fixture-conflict-only; csrf=fixture");
        guard.release(61);guard.release(62);
        try {
            assertTrue(guard.reserve(61,pairs).allowed);
            Method begin=CookieTransfer.class.getDeclaredMethod("beginConfirmedImport",String.class,List.class,boolean.class);
            begin.setAccessible(true);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
                try{begin.invoke(transfer,"https://www.dola.com/",pairs,false);}catch(Exception e){throw new AssertionError(e);}
            });
            assertTrue(device.wait(Until.hasObject(By.text("拒绝跨空间重复导入 CK")),10_000));
            assertTrue(device.wait(Until.hasObject(By.textContains("SPACE 62")),5_000));
            assertTrue(device.wait(Until.hasObject(By.textContains("未写入任何 Cookie")),5_000));
            device.findObject(By.text("知道了")).click();
        } finally {guard.release(61);guard.release(62);}
    }

    private void invokeChoose(List<CkCookieImport.Entry> entries) throws Exception {
        Method choose = CookieTransfer.class.getDeclaredMethod(
            "chooseCkAccount", String.class, List.class);
        choose.setAccessible(true);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                choose.invoke(transfer, "https://www.dola.com/", entries);
            } catch (Exception e) {
                throw new AssertionError("chooseCkAccount invocation failed", e);
            }
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
}
