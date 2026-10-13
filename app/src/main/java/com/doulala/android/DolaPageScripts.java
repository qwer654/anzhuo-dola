package com.doulala.android;
/** Original page scripts plus a separate native media adaptation, never a Chrome API shim. */
final class DolaPageScripts {
    static final String PREF_KEY="dola30NewPageScripts";
    static final String EXTRA_KEY="dola30AdditionalPageConsent1614";
    static final String SKILL_ASSET="dola30-source-v1.6.14/skill-pack-main.js";
    static final String UPLOAD_ASSET="dola30-source-v1.6.14/media-upload-main.js";
    static final String DURATION_ASSET="dola30-source-v1.6.14/duration-main.js";
    static final String MEDIA_ASSET="dola30-webview-media-v1.6.14.js";
    private DolaPageScripts(){}
    static String preferenceName(String space){return "browser_"+space;}
    static String bootstrap(boolean enabled,boolean managed,String skill,String upload){return bootstrap(enabled,managed,false,skill,upload,null,null);}
    static String bootstrap(boolean enabled,boolean managed,boolean additional,String skill,String upload,String duration,String media){
        if(enabled&&(skill==null||upload==null||skill.isEmpty()||upload.isEmpty()))throw new IllegalArgumentException("Both original page scripts are required");
        if(enabled&&additional&&(duration==null||media==null||duration.isEmpty()||media.isEmpty()))throw new IllegalArgumentException("Additional page sources are required");
        String prefix="(function(){'use strict';if(window.top!==window)return;var h=String(location.hostname||'').toLowerCase();var dola=h==='dola.com'||h.endsWith('.dola.com');var doubao=h==='doubao.com'||h.endsWith('.doubao.com');"
            +"if(location.protocol!=='https:'||(location.port&&location.port!=='443')||!(dola||("+additional+"&&doubao)))return;"
            +"if(window.__dolaWebViewPageScripts)return;var state={enabled:"+enabled+",skillPack:false,mediaUpload:false,storageSeeded:false,"
            +"chromeDebugger:false,extensionPanel:false,backendVerified:false,additionalConsent:"+additional+",duration:false,mediaObserver:false};window.__dolaWebViewPageScripts=state;";
        if(managed)prefix+="try{localStorage.setItem('intl_doubao_enable_30s_v1','"+(enabled?"1":"0")+"');localStorage.setItem('intl_doubao_target_duration_v1','"+(enabled?"30":"15")+"');state.storageSeeded=true;}catch(e){state.storageSeeded=false;}";
        if(!enabled)return prefix+"})();";
        prefix+="if(dola){try{\n"+skill+"\nstate.skillPack=!!window.__dolaNative30SkillPackInstalledV162;}catch(e){state.skillPack=false;}try{\n"+upload+"\nstate.mediaUpload=!!window.__DOLA_TOOLKIT_MEDIA_UPLOAD_BRIDGE_V3__;}catch(e){state.mediaUpload=false;}}";
        if(additional){
            prefix+="try{\n"+media+"\nstate.mediaObserver=!!(window.__dolaNewMediaState&&window.__dolaNewMediaState.installed);}catch(e){state.mediaObserver=false;}";
            prefix+="var idle=function(){try{\n"+duration+"\nstate.duration=!!window.__watermarkFreeDuration30Installed;}catch(e){state.duration=false;}};if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',function(){setTimeout(idle,0);},{once:true});else setTimeout(idle,0);";
        }
        return prefix+"})();";
    }
    static String summary(boolean enabled,boolean registered,boolean skill,boolean upload){
        if(!enabled)return "本空间 30s：关闭（页面脚本不注入）";
        if(!registered)return "本空间 30s：已保存开启，但文档开始注入不可用/注册失败，未生效";
        return "本空间 30s：开启；skill-pack："+(skill?"已执行":"未执行/非 Dola 页面/执行失败")+"；media-upload："+(upload?"已执行":"未执行/非 Dola 页面/执行失败")+"。仅表示页面脚本已加载，不代表上传成功、平台接受 30 秒或生成成功。";
    }
    static String additionalSummary(boolean consent,boolean duration,boolean media){return !consent?"新增页面适配：尚未确认，不执行。":"原始 duration-main（页面空闲）："+(duration?"已执行":"未执行/失败")+"；WebView 媒体观察适配："+(media?"已安装（只读）":"未安装/失败")+"。Chrome 后台、调试器及原扩展面板仍不可用。";}
}
