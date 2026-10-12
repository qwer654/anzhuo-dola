package com.doulala.android;

/** Assembles the supplied page-world sources; never loads an extension background or panel. */
final class DolaPageScripts {
    static final String PREF_KEY = "dola30NewPageScripts";
    static final String SKILL_ASSET = "dola30-source-v1.6.14/skill-pack-main.js";
    static final String UPLOAD_ASSET = "dola30-source-v1.6.14/media-upload-main.js";
    private DolaPageScripts() {}

    static String preferenceName(String space) { return "browser_" + space; }

    // One document-start registration guarantees skill-pack -> media-upload order.
    // Existing installations are NOT silently opted in via the older duration switch.
    static String bootstrap(boolean enabled, boolean managed, String skill, String upload) {
        if (enabled && (skill == null || upload == null || skill.isEmpty() || upload.isEmpty()))
            throw new IllegalArgumentException("Both original page scripts are required");
        String prefix = "(function(){'use strict';var h=String(location.hostname||'').toLowerCase();"
            + "if(location.protocol!=='https:'||(location.port&&location.port!=='443')||!(h==='dola.com'||h.endsWith('.dola.com')))return;"
            + "if(window.__dolaWebViewPageScripts)return;"
            + "var state={enabled:" + enabled + ",skillPack:false,mediaUpload:false,storageSeeded:false,"
            + "chromeDebugger:false,extensionPanel:false,backendVerified:false};"
            + "window.__dolaWebViewPageScripts=state;";
        // Only a user-managed setting seeds page state. Default OFF must not erase old local options.
        if (managed) prefix += "try{localStorage.setItem('intl_doubao_enable_30s_v1','" + (enabled ? "1" : "0")
            + "');localStorage.setItem('intl_doubao_target_duration_v1','" + (enabled ? "30" : "15")
            + "');state.storageSeeded=true;}catch(e){state.storageSeeded=false;}";
        if (!enabled) return prefix + "})();";
        return prefix + "try{\n" + skill
            + "\nstate.skillPack=!!window.__dolaNative30SkillPackInstalledV162;}catch(e){state.skillPack=false;}"
            + "try{\n" + upload
            + "\nstate.mediaUpload=!!window.__DOLA_TOOLKIT_MEDIA_UPLOAD_BRIDGE_V3__;}catch(e){state.mediaUpload=false;}"
            + "})();";
    }

    static String summary(boolean enabled, boolean registered, boolean skill, boolean upload) {
        if (!enabled) return "本空间 30s：关闭（两个页面主脚本不注入）";
        if (!registered) return "本空间 30s：已保存开启，但文档开始注入不可用/注册失败，未生效";
        return "本空间 30s：开启；skill-pack：" + (skill ? "已执行" : "未执行/非 Dola 页面/执行失败")
            + "；media-upload：" + (upload ? "已执行" : "未执行/非 Dola 页面/执行失败")
            + "。仅表示页面脚本已加载，不代表上传成功、平台接受 30 秒或生成成功。";
    }
}
