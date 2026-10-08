package com.doulala.android;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.*;

final class Ui {
    static final int BG = Color.rgb(16,21,34), CARD = Color.rgb(27,35,52), INK = Color.rgb(240,242,252);
    static final int MUTED = Color.rgb(155,167,190), ACCENT = Color.rgb(155,139,255);
    static int dp(Context c, float n) { return Math.round(n * c.getResources().getDisplayMetrics().density); }
    static LinearLayout column(Context c) { LinearLayout v=new LinearLayout(c); v.setOrientation(LinearLayout.VERTICAL); return v; }
    static LinearLayout row(Context c) { LinearLayout v=new LinearLayout(c); v.setGravity(android.view.Gravity.CENTER_VERTICAL); return v; }
    static void pad(View v, int n) { int d=dp(v.getContext(),n); v.setPadding(d,d,d,d); }
    static TextView text(Context c,String s,int size,int color) { TextView v=new TextView(c); v.setText(s); v.setTextSize(size); v.setTextColor(color); v.setPadding(0,dp(c,6),0,dp(c,6)); return v; }
    static TextView title(Context c,String s,int size) { TextView v=text(c,s,size,INK); v.setTypeface(null,Typeface.BOLD); return v; }
    static Button button(Context c,String s,Runnable action) { Button b=new Button(c); b.setText(s); b.setTextColor(INK); b.setTextSize(13); b.setAllCaps(false); b.setMinHeight(dp(c,48)); b.setOnClickListener(v->action.run()); return b; }
    static Button button(Context c,String s,View.OnClickListener action) { Button b=button(c,s,()->{}); b.setOnClickListener(action); return b; }
    static EditText edit(Context c,String hint,String value) { EditText e=new EditText(c); e.setHint(hint); e.setText(value); e.setTextColor(INK); e.setHintTextColor(MUTED); e.setTextSize(15); e.setSingleLine(true); return e; }
    static GradientDrawable shape(Context c,int color,int radius) { GradientDrawable g=new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(c,radius)); return g; }
    static LinearLayout card(Context c) { LinearLayout l=column(c); pad(l,16); l.setBackground(shape(c,CARD,18)); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,dp(c,6),0,dp(c,8)); l.setLayoutParams(p); return l; }
    static void equal(LinearLayout row,View child) { row.addView(child,new LinearLayout.LayoutParams(0,-2,1)); }
    static ScrollView scroll(Context c,View child) { ScrollView s=new ScrollView(c); s.setFillViewport(true); s.addView(child); return s; }
    static void toast(Context c,String message) { Toast.makeText(c,message,Toast.LENGTH_LONG).show(); }
    static void setup(Activity a) { a.getWindow().setStatusBarColor(BG); a.getWindow().setNavigationBarColor(BG); }
}
