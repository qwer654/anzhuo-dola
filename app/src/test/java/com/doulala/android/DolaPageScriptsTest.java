package com.doulala.android;

import org.junit.Test;
import static org.junit.Assert.*;

public class DolaPageScriptsTest {
    @Test public void bundlePreservesSourcesAndOrdersThemWithoutExtensionApis() {
        String skill="/* exact skill source */\n(function(){})();\n";
        String upload="/* exact upload source */\n(function(){})();\n";
        String script=DolaPageScripts.bootstrap(true,true,skill,upload);
        assertTrue(script.contains(skill));assertTrue(script.contains(upload));
        assertTrue(script.indexOf(skill)<script.indexOf(upload));
        assertFalse(script.contains("chrome."));assertFalse(script.contains("content-panel"));
        assertFalse(script.contains("service-worker"));
        assertTrue(script.contains("chromeDebugger:false,extensionPanel:false,backendVerified:false"));
    }
    @Test public void offNeverIncludesOriginalSourcesAndDefaultDoesNotResetPriorState() {
        String off=DolaPageScripts.bootstrap(false,false,"SECRET_SOURCE_A","SECRET_SOURCE_B");
        assertFalse(off.contains("SECRET_SOURCE"));assertFalse(off.contains("localStorage.setItem"));
        String managedOff=DolaPageScripts.bootstrap(false,true,null,null);
        assertTrue(managedOff.contains("target_duration_v1','15'"));
        assertTrue(managedOff.contains("enable_30s_v1','0'"));
    }
    @Test public void perSpaceKeysAndDiagnosticsDoNotOverclaim() {
        assertEquals("browser_Space0",DolaPageScripts.preferenceName("Space0"));
        assertNotEquals(DolaPageScripts.preferenceName("Space0"),DolaPageScripts.preferenceName("Space1"));
        assertTrue(DolaPageScripts.summary(true,false,false,false).contains("未生效"));
        assertTrue(DolaPageScripts.summary(true,true,true,true).contains("不代表上传成功"));
        assertTrue(DolaPageScripts.summary(false,false,false,false).contains("不注入"));
    }
    @Test(expected=IllegalArgumentException.class) public void enabledNeedsBothPageSources() {
        DolaPageScripts.bootstrap(true,true,"skill",null);
    }
}
