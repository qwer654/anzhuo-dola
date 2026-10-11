package com.doulala.android;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.widget.*;

public final class MainActivity extends Activity {
    private SharedPreferences settings;
    public static final String[] SITES={"Dola","豆包","即梦","千问","云雀"};
    public static final String[] URLS={"https://www.dola.com/","https://www.doubao.com/chat/","https://jimeng.jianying.com/","https://www.qianwen.com/","https://yunq.jianying.com/"};
    private static final Class<?>[] SPACES={BrowserActivity.Space0.class,BrowserActivity.Space1.class,BrowserActivity.Space2.class,BrowserActivity.Space3.class,BrowserActivity.Space4.class,BrowserActivity.Space5.class,BrowserActivity.Space6.class,BrowserActivity.Space7.class};
    @Override public void onCreate(Bundle state) { super.onCreate(state); Ui.setup(this); settings=getSharedPreferences("catalog",MODE_PRIVATE); render(); }
    private void render() {
        LinearLayout root=Ui.column(this); root.setBackgroundColor(Ui.BG); Ui.pad(root,20);
        root.addView(Ui.text(this,"DOULALA  /  ANDROID",12,Ui.ACCENT));
        root.addView(Ui.title(this,"把灵感，带在身边。",27));
        root.addView(Ui.text(this,"豆啦啦 · 安卓移植预览版 0.2.1\n独立账号空间与移动创作工作台",14,Ui.MUTED));
        LinearLayout hero=Ui.card(this);
        hero.addView(Ui.title(this,"创作工作台",20));
        hero.addView(Ui.text(this,"管理快捷文本、导入剧本、拆分分镜。选择一条，即可填入网页继续创作。",14,Ui.MUTED));
        hero.addView(Ui.button(this,"打开工作台  →",()->startActivity(new Intent(this,StudioActivity.class)))); root.addView(hero);
        root.addView(Ui.title(this,"账号空间",21));
        root.addView(Ui.text(this,"每个空间独立保存 Cookie 和网页存储，最多 8 个空间。每个空间支持 6 个标签，空间内共享登录。",13,Ui.MUTED));
        int used=0;
        for(int i=0;i<8;i++) {
            final int slot=i;
            if(!settings.getBoolean("enabled"+i,i==0)) continue;
            used++;
            LinearLayout card=Ui.card(this);
            card.addView(Ui.text(this,String.format(java.util.Locale.ROOT,"SPACE %02d",i+1),11,Ui.ACCENT));
            card.addView(Ui.title(this,settings.getString("name"+i,"创作空间 "+(i+1)),19));
            card.addView(Ui.text(this,settings.getString("url"+i,URLS[0]),12,Ui.MUTED));
            LinearLayout buttons=Ui.row(this);
            Button enterSpace=Ui.button(this,"进入空间",()->open(slot));
            enterSpace.setContentDescription("进入空间："+settings.getString("name"+i,"创作空间 "+(i+1)));
            Ui.equal(buttons,enterSpace);
            Ui.equal(buttons,Ui.button(this,"设置",()->edit(slot)));
            if(slot!=0) Ui.equal(buttons,Ui.button(this,"删除",()->confirmDeleteSpace(slot)));
            card.addView(buttons); root.addView(card);
        }
        int reusable=0; for(int i=1;i<8;i++) if(!settings.getBoolean("enabled"+i,false)&&!settings.getBoolean("retired"+i,false)) reusable++;
        if(reusable>0) root.addView(Ui.button(this,"＋ 新建独立空间",()->{ for(int i=1;i<8;i++) if(!settings.getBoolean("enabled"+i,false)&&!settings.getBoolean("retired"+i,false)) {edit(i); break;} }));
        root.addView(Ui.button(this,"移植范围与使用说明",this::about));
        root.addView(Ui.text(this,"数据保存在本机 · 不自动同步桌面登录态\n空间由 Android 管理，后台进程可能被系统回收。",12,Ui.MUTED));
        setContentView(Ui.scroll(this,root));
    }
    private void open(int slot) {
        Intent i=new Intent(this,SPACES[slot]);
        i.putExtra("title",settings.getString("name"+slot,"创作空间 "+(slot+1)));
        i.putExtra("home",settings.getString("url"+slot,URLS[0]));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }
    private void confirmDeleteSpace(int slot) {
        String name=settings.getString("name"+slot,"创作空间 "+(slot+1));
        new AlertDialog.Builder(this).setTitle("从列表移除账号空间？")
            .setMessage("将移除“"+name+"”的空间入口和配置。重要：此版本不能安全地单独擦除该 WebView 进程的 Cookie/网页存储，因此本机网页登录态会保留且无法从列表访问；该槽位不会再分配给新空间，避免把旧登录态带给新账号。需要彻底清除本机所有空间数据时，请使用 Android 系统的应用存储清除功能。")
            .setNegativeButton("取消",null).setPositiveButton("移除",(d,w)->{
                boolean removed=settings.edit().remove("enabled"+slot).remove("name"+slot).remove("url"+slot).putBoolean("retired"+slot,true).commit();
                if(removed){Ui.toast(this,"空间入口已移除；本机网页数据保留");render();}
                else Ui.toast(this,"移除失败；设置未更改");
            }).show();
    }
    private void edit(int slot) {
        LinearLayout form=Ui.column(this); Ui.pad(form,20);
        EditText name=Ui.edit(this,"空间名称",settings.getString("name"+slot,"创作空间 "+(slot+1)));
        EditText url=Ui.edit(this,"HTTPS 首页",settings.getString("url"+slot,URLS[0])); url.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        form.addView(name); form.addView(url);
        form.addView(Ui.button(this,"选择平台",()->new AlertDialog.Builder(this).setTitle("默认首页").setItems(SITES,(d,n)->url.setText(URLS[n])).show()));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("空间 "+(slot+1)+" 设置").setView(form).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{
            String normalized=UrlRules.normalize(url.getText().toString());
            if(normalized==null || !normalized.startsWith("https://")) { url.setError("请输入有效 HTTPS 地址"); return; }
            String n=name.getText().toString().trim(); if(n.isEmpty()||n.length()>60) { name.setError("名称需为 1–60 字"); return; }
            settings.edit().putBoolean("enabled"+slot,true).putString("name"+slot,n).putString("url"+slot,normalized).apply(); dialog.dismiss(); render();
        })); dialog.show();
    }
    private void about() {
        new AlertDialog.Builder(this).setTitle("不是桌面版的完整替代品")
        .setMessage("已编写：8 个进程隔离账号空间、多标签与书签、上传文件、网页媒体观察、下载中心（打开/分享/另存）、同站点 Cookie 手动快照、快捷文本、分镜工作台。需真机验收。\n\n限制：Android WebView 不能运行 Windows DLL 或 Chrome 扩展。资源观察不等于桌面 CDP 深度嗅探，不能保证原图或无水印版本。30 秒生成取决于平台支持，本版不伪造授权或生成结果。\n\n待迁移：完整 Cookie 属性迁移、认证/链式代理、完整指纹、批量自动生成、遮罩与人脸处理、API 创作中心、本地自动化服务。\n\n网页登录兼容性需真机验证，若第三方不允许 WebView 登录，请通过官方客户端使用。无需 Root。")
        .setPositiveButton("知道了",null).show();
    }
}
