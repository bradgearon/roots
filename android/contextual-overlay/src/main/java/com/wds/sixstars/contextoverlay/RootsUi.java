package com.wds.sixstars.contextoverlay;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared native UI primitives for the two Roots entry screens. */
public final class RootsUi {
    public static final int BG=0xFF08171D, CARD=0xFF10282D, STROKE=0xFF294348,
        TEXT=0xFFEDF7F8, MUTED=0xFFA7C5CA, ACCENT=0xFF28D9C5;
    public static int dp(Activity a,int n){return Math.round(n*a.getResources().getDisplayMetrics().density);}
    public static GradientDrawable rounded(Activity a,int fill,int radius,int stroke){
        GradientDrawable d=new GradientDrawable();
        d.setColor(fill);d.setCornerRadius(dp(a,radius));if(stroke!=0)d.setStroke(dp(a,1),stroke);return d;
    }
    public static LinearLayout column(Activity a){LinearLayout l=new LinearLayout(a);l.setOrientation(LinearLayout.VERTICAL);return l;}
    public static LinearLayout shell(Activity a){
        LinearLayout root=column(a);root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            if(android.os.Build.VERSION.SDK_INT>=30){
                android.graphics.Insets sys=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
                v.setPadding(sys.left,sys.top,sys.right,sys.bottom);
            }else v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        if(android.os.Build.VERSION.SDK_INT>=30)a.getWindow().setDecorFitsSystemWindows(false);
        else a.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        return root;
    }
    public static TextView text(Activity a,String value,int size,int color,boolean bold){
        TextView t=new TextView(a);t.setText(value);t.setTextSize(size);t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;
    }
    public static LinearLayout header(Activity a,String title,String sub){
        LinearLayout box=column(a);box.setPadding(dp(a,20),dp(a,16),dp(a,20),dp(a,12));
        box.addView(text(a,title,26,TEXT,true));box.addView(text(a,sub,13,MUTED,false));return box;
    }
    public static LinearLayout card(Activity a,LinearLayout parent){
        LinearLayout card=column(a);card.setBackground(rounded(a,CARD,14,STROKE));
        card.setPadding(dp(a,14),dp(a,12),dp(a,14),dp(a,12));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(a,10);parent.addView(card,p);return card;
    }
    static LinearLayout section(Activity a,LinearLayout parent,String title,String subtitle,boolean open){
        LinearLayout card=card(a,parent);
        TextView heading=text(a,title+(open?"   ▴":"   ▾"),17,TEXT,true);
        heading.setMinHeight(dp(a,48));heading.setGravity(Gravity.CENTER_VERTICAL);card.addView(heading);
        card.addView(text(a,subtitle,12,MUTED,false));
        LinearLayout body=column(a);body.setPadding(0,dp(a,8),0,0);card.addView(body);
        body.setVisibility(open?View.VISIBLE:View.GONE);
        heading.setOnClickListener(v->{boolean show=body.getVisibility()!=View.VISIBLE;body.setVisibility(show?View.VISIBLE:View.GONE);heading.setText(title+(show?"   ▴":"   ▾"));});
        return body;
    }
    public static TextView action(Activity a,String label,Runnable run){
        TextView b=text(a,label,14,ACCENT,true);b.setGravity(Gravity.CENTER);b.setMinHeight(dp(a,48));
        b.setPadding(dp(a,12),dp(a,8),dp(a,12),dp(a,8));b.setBackground(rounded(a,0xFF143A3E,10,STROKE));
        b.setOnClickListener(v->run.run());return b;
    }
    static LinearLayout navigation(Activity a,boolean settings){
        LinearLayout row=new LinearLayout(a);row.setPadding(dp(a,12),dp(a,8),dp(a,12),dp(a,8));
        row.setBackgroundColor(0xFF0C2025);
        for(int j=0;j<2;j++){
            final boolean isSettings=j==1;
            boolean selected=isSettings==settings;
            TextView item=text(a,isSettings?"⚙  Settings":"⌂  Stream",15,selected?ACCENT:MUTED,true);
            item.setGravity(Gravity.CENTER);item.setMinHeight(dp(a,52));
            item.setBackground(rounded(a,selected?0xFF123C40:0xFF0C2025,12,0));
            row.addView(item,new LinearLayout.LayoutParams(0,-2,1));
            item.setOnClickListener(v->{
                if(selected)return;
                if(isSettings)a.startActivity(new Intent(a,OverlayTranslationSettingsActivity.class));
                else {Intent intent=new Intent(a,RootsStreamActivity.class);intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);a.startActivity(intent);a.finish();}
            });
        }
        return row;
    }
}
