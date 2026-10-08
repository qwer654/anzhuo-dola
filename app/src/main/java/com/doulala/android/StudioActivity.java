package com.doulala.android;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Local-only writing workspace. It never submits prompts or reads browser cookies. */
public final class StudioActivity extends Activity {
    private static final int PICK=41, SAVE=42, MAX=2*1024*1024;
    private SharedPreferences prefs; private ArrayList<Entry> quick=new ArrayList<>(), shots=new ArrayList<>();
    private LinearLayout body; private int tab;
    private boolean invalidStored;
    private String lastSaved="{\"schemaVersion\":1,\"quickTexts\":[],\"storyboard\":[]}";
    private final java.util.concurrent.ExecutorService io=java.util.concurrent.Executors.newSingleThreadExecutor();
    @Override public void onCreate(Bundle b){super.onCreate(b);Ui.setup(this);prefs=getSharedPreferences("studio",MODE_PRIVATE);load();render();}
    private void load(){try{String value=prefs.getString("state",lastSaved);decode(value);lastSaved=encode();}catch(Exception e){invalidStored=true;Ui.toast(this,"保存数据无法读取；原文件未被覆盖，请导入有效备份恢复。");}}
    private void render(){ScrollView s=new ScrollView(this);s.setBackgroundColor(Ui.BG);body=Ui.column(this);body.setPadding(Ui.dp(this,18),Ui.dp(this,12),Ui.dp(this,18),Ui.dp(this,32));s.addView(body);setContentView(s);
        LinearLayout h=Ui.row(this);h.addView(Ui.button(this,"‹ 返回",v->finish()));TextView t=Ui.title(this,"创作工作台",23);h.addView(t,new LinearLayout.LayoutParams(0,-2,1));body.addView(h);
        body.addView(Ui.text(this,getIntent().getBooleanExtra("pickPrompt",false)?"选择一条填入浏览器；不会自动发送。":"本地草稿、分镜与快捷文本。",13,Ui.MUTED));
        LinearLayout tabs=Ui.row(this);String[] ls={"快捷文本","分镜","备份 / 工具"};for(int i=0;i<3;i++){final int x=i;Button b=Ui.button(this,ls[i],v->{tab=x;render();});b.setTextColor(tab==i?Ui.BG:Ui.INK);b.setBackground(Ui.shape(this,tab==i?Ui.ACCENT:Ui.CARD,10));tabs.addView(b,new LinearLayout.LayoutParams(0,-2,1));}body.addView(tabs);
        if(tab==0)quickTab();else if(tab==1)shotTab();else toolsTab();}
    private void quickTab(){body.addView(Ui.button(this,"＋ 添加快捷文本",v->edit(false,-1)));body.addView(Ui.text(this,quick.size()+" 条 · 数据仅保存在本机",12,Ui.MUTED));for(int i=0;i<quick.size();i++)card(false,i);if(quick.isEmpty())empty("保存常用提示词，下次直接复制或填入网页。");}
    private void shotTab(){LinearLayout r=Ui.row(this);r.addView(Ui.button(this,"＋ 添加分镜",v->edit(true,-1)),weight());r.addView(Ui.button(this,"导入文本",v->pick()),weight());body.addView(r);body.addView(Ui.text(this,"按空行拆分段落；状态由你手动更新，不代表平台生成进度。",12,Ui.MUTED));for(int i=0;i<shots.size();i++)card(true,i);if(shots.isEmpty())empty("导入 UTF-8 文本或逐条添加分镜。");}
    private void card(boolean shot,int i){ArrayList<Entry> list=shot?shots:quick;Entry e=list.get(i);LinearLayout c=Ui.card(this);c.addView(Ui.title(this,(shot?(i+1)+". ":"")+e.title,17));TextView p=Ui.text(this,e.text,14,Ui.MUTED);p.setMaxLines(4);p.setEllipsize(android.text.TextUtils.TruncateAt.END);c.addView(p);if(shot)c.addView(Ui.button(this,"状态："+new String[]{"待办","进行中","完成"}[e.status]+"（点击切换）",v->{e.status=e.status==2?0:e.status+1;save();render();}));LinearLayout r=Ui.row(this);r.addView(Ui.button(this,getIntent().getBooleanExtra("pickPrompt",false)?"填入":"复制",v->use(e)),weight());r.addView(Ui.button(this,"编辑",v->edit(shot,i)),weight());r.addView(Ui.button(this,"删除",v->new AlertDialog.Builder(this).setTitle("删除这条内容？").setMessage("此操作不能撤销，建议先导出备份。").setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{list.remove(i);save();render();}).show()),weight());c.addView(r);body.addView(c);}
    private void empty(String s){LinearLayout c=Ui.card(this);c.addView(Ui.text(this,s,14,Ui.MUTED));body.addView(c);}
    private void edit(boolean shot,int index){ArrayList<Entry> list=shot?shots:quick;Entry old=index<0?null:list.get(index);LinearLayout f=Ui.column(this);EditText title=Ui.edit(this,"标题",old==null?"":old.title);EditText text=Ui.edit(this,"内容",old==null?"":old.text);text.setSingleLine(false);text.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);f.addView(title);f.addView(text,new LinearLayout.LayoutParams(-1,Ui.dp(this,220)));AlertDialog d=new AlertDialog.Builder(this).setTitle((old==null?"添加":"编辑")+(shot?"分镜":"快捷文本")).setView(f).setNegativeButton("取消",null).setPositiveButton("保存",null).create();d.setOnShowListener(x->d.getButton(-1).setOnClickListener(v->{String a=title.getText().toString().trim(),b=text.getText().toString().trim();if(a.isEmpty()||b.isEmpty()){Ui.toast(this,"标题和内容不能为空");return;}if(a.length()>120||b.length()>30000){Ui.toast(this,"内容超出限制");return;}if(old==null)list.add(new Entry(a,b));else{old.title=a;old.text=b;}if(save()){d.dismiss();render();}}));d.show();}
    private void use(Entry e){if(getIntent().getBooleanExtra("pickPrompt",false)){setResult(RESULT_OK,new Intent().putExtra("prompt",e.text));finish();}else{android.content.ClipboardManager c=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);c.setPrimaryClip(ClipData.newPlainText(e.title,e.text));Ui.toast(this,"已复制；未发送到任何平台");}}
    private void toolsTab(){LinearLayout c=Ui.card(this);c.addView(Ui.title(this,"工作台备份",18));c.addView(Ui.text(this,"JSON schemaVersion=1，最大 2 MB；不包含账号、Cookie 或浏览历史。",13,Ui.MUTED));c.addView(Ui.button(this,"导出 JSON",v->{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").putExtra(Intent.EXTRA_TITLE,"doulala-studio.json");startActivityForResult(i,SAVE);}));c.addView(Ui.button(this,"导入 JSON",v->pickJson()));body.addView(c);LinearLayout q=Ui.card(this);q.addView(Ui.title(this,"秒数换算",18));EditText in=Ui.edit(this,"输入整数秒数","");TextView out=Ui.text(this,"",17,Ui.ACCENT);q.addView(in);q.addView(Ui.button(this,"换算",v->{try{long n=Long.parseLong(in.getText().toString().trim());if(n<0)throw new Exception();out.setText((n/3600)+" 时 "+((n%3600)/60)+" 分 "+(n%60)+" 秒");}catch(Exception e){in.setError("请输入非负整数");}}));q.addView(out);body.addView(q);empty("尚未迁移：桌面 CDP 深度嗅探、批量自动生成、Windows 代理/指纹与遮罩处理。不会将手动状态伪装成平台任务。");}
    private void pick(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/*");startActivityForResult(i,PICK);}
    private void pickJson(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json");startActivityForResult(i,43);}
    @Override protected void onActivityResult(int r,int c,Intent d){
        super.onActivityResult(r,c,d);
        if(c!=RESULT_OK||d==null||d.getData()==null||(r!=PICK&&r!=43&&r!=SAVE))return;
        Uri uri=d.getData(); if(!"content".equalsIgnoreCase(uri.getScheme())) {Ui.toast(this,"仅接受系统文档选择器的文件");return;}
        if(r==SAVE){
            if(invalidStored){Ui.toast(this,"请先恢复损坏的数据");return;}
            try{final byte[] bytes=encode().getBytes(StandardCharsets.UTF_8);
                if(bytes.length>MAX)throw new IOException("备份超过 2 MB");
                io.execute(()->{try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){
                    if(out==null)throw new IOException();out.write(bytes);out.flush();message("备份已导出");
                }catch(Exception ex){message("导出失败，请检查文档权限与空间");}});
            }catch(Exception ex){Ui.toast(this,"无法导出当前数据");}
            return;
        }
        io.execute(()->{try{String text=read(uri);runOnUiThread(()->{if(!isFinishing()&&!isDestroyed()) importText(text,r==PICK);});}
            catch(Exception ex){message("导入失败："+ex.getMessage());}});
    }
    private void message(String text){runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())Ui.toast(this,text);});}
    private void importText(String text,boolean script){
        ArrayList<Entry> oldQuick=quick,oldShots=shots;
        try{
            if(script){
                if(invalidStored)throw new JSONException("请先导入 JSON 备份恢复数据");
                shots=new ArrayList<>(shots);
                String normalized=text.replace("\r\n","\n").replace('\r','\n').trim();
                if(normalized.isEmpty())throw new JSONException("文本为空");
                for(String p:normalized.split("\\n[ \\t]*\\n+"))if(!p.trim().isEmpty()){
                    String z=p.trim(),title=z.split("\n",2)[0];
                    shots.add(new Entry(title.substring(0,Math.min(120,title.length())),z));
                }
            }else decode(text);
            String candidate=encode();decode(candidate);
            quick=oldQuick;shots=oldShots;
            new AlertDialog.Builder(this).setTitle(script?"追加导入的分镜？":"覆盖当前工作台？")
                .setMessage(script?"将按空行拆分并追加，状态均为待办。":"这会替换快捷文本和分镜；建议先取消并导出旧数据。")
                .setNegativeButton("取消",null).setPositiveButton("确认",(d,w)->{
                    boolean wasInvalid=invalidStored;
                    try{decode(candidate);invalidStored=false;if(save()){tab=script?1:0;render();}else invalidStored=wasInvalid;}
                    catch(Exception ex){invalidStored=wasInvalid;Ui.toast(this,"导入失败，未覆盖保存数据");}
                }).show();
        }catch(Exception ex){quick=oldQuick;shots=oldShots;Ui.toast(this,"导入失败："+ex.getMessage());}
    }
    private String read(Uri u)throws Exception{try(InputStream i=getContentResolver().openInputStream(u);ByteArrayOutputStream o=new ByteArrayOutputStream()){if(i==null)throw new IOException("无法读取文件");byte[] b=new byte[8192];int n;while((n=i.read(b))!=-1){if(o.size()+n>MAX)throw new IOException("文件超过 2 MB");o.write(b,0,n);}String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(o.toByteArray())).toString();return text.startsWith("\uFEFF")?text.substring(1):text;}}
    private String encode()throws JSONException{JSONObject r=new JSONObject().put("schemaVersion",1);JSONArray q=new JSONArray(),s=new JSONArray();for(Entry e:quick)q.put(new JSONObject().put("title",e.title).put("text",e.text));for(Entry e:shots)s.put(new JSONObject().put("title",e.title).put("text",e.text).put("status",e.status));return r.put("quickTexts",q).put("storyboard",s).toString(2);}
    private void decode(String value)throws JSONException{
        if(value.getBytes(StandardCharsets.UTF_8).length>MAX)throw new JSONException("内容超过 2 MB");
        JSONTokener tokener=new JSONTokener(value);Object parsed=tokener.nextValue();
        if(!(parsed instanceof JSONObject)||tokener.nextClean()!=0)throw new JSONException("需要单个完整 JSON 对象");
        JSONObject r=(JSONObject)parsed;Object version=r.get("schemaVersion");
        if(!(version instanceof Integer)||((Integer)version)!=1)throw new JSONException("不支持的 schemaVersion");
        ArrayList<Entry> nq=new ArrayList<>(),ns=new ArrayList<>();JSONArray q=r.getJSONArray("quickTexts"),s=r.getJSONArray("storyboard");
        if(q.length()>200||s.length()>500)throw new JSONException("最多 200 条快捷文本和 500 条分镜");
        for(int i=0;i<q.length();i++)nq.add(entry(q.getJSONObject(i),false));
        for(int i=0;i<s.length();i++)ns.add(entry(s.getJSONObject(i),true));quick=nq;shots=ns;
    }
    private Entry entry(JSONObject o,boolean shot)throws JSONException{Object title=o.get("title"),text=o.get("text"),status=shot?o.get("status"):0;if(!(title instanceof String)||!(text instanceof String)||!(status instanceof Integer))throw new JSONException("条目类型无效");String a=((String)title).trim(),b=((String)text).trim();int st=(Integer)status;if(a.isEmpty()||b.isEmpty()||a.length()>120||b.length()>30000||a.indexOf('\0')>=0||b.indexOf('\0')>=0||st<0||st>2)throw new JSONException("条目无效");return new Entry(a,b,st);}
    private boolean save(){try{if(invalidStored)throw new IOException("请先恢复数据");String j=encode();decode(j);if(!prefs.edit().putString("state",j).commit())throw new IOException("存储失败");lastSaved=j;return true;}catch(Exception e){try{decode(lastSaved);}catch(Exception ignored){}Ui.toast(this,"保存失败，未覆盖旧数据："+e.getMessage());return false;}}
    @Override protected void onDestroy(){io.shutdown();super.onDestroy();}
    private LinearLayout.LayoutParams weight(){return new LinearLayout.LayoutParams(0,-2,1);}
    private static final class Entry{String title,text;int status;Entry(String a,String b){this(a,b,0);}Entry(String a,String b,int c){title=a;text=b;status=c;}}
}
