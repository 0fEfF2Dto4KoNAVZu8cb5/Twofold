package org.twofold.app;

import android.app.*;
import android.appwidget.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;
import org.json.JSONObject;

public final class PhotoWidget extends AppWidgetProvider {
    @Override public void onUpdate(Context c,AppWidgetManager manager,int[] ids){for(int id:ids)update(c,manager,id);SyncJob.schedule(c);}
    @Override public void onAppWidgetOptionsChanged(Context c,AppWidgetManager manager,int id,Bundle options){update(c,manager,id);}
    public static void updateAll(Context c){AppWidgetManager m=AppWidgetManager.getInstance(c);for(int id:m.getAppWidgetIds(new ComponentName(c,PhotoWidget.class)))update(c,m,id);}
    private static void update(Context c,AppWidgetManager manager,int id) {
        manager.updateAppWidget(id,views(c));
    }
    static RemoteViews views(Context c){
        RemoteViews views=new RemoteViews(c.getPackageName(),R.layout.photo_widget);
        views.setOnClickPendingIntent(R.id.widget_root,Notify.open(c));
        try {
            JSONObject partner=SyncEngine.state(c).optJSONObject("partner");
            if(partner==null)throw new IllegalStateException();
            JSONObject meta=SyncEngine.metadata(Vault.load(c),partner);
            Bitmap image=SyncEngine.picture(c,partner,400);
            views.setImageViewBitmap(R.id.widget_photo,image);views.setViewVisibility(R.id.widget_empty,View.GONE);
            if(partner.optBoolean("locked")){
                views.setViewVisibility(R.id.widget_empty,View.VISIBLE);views.setTextViewText(R.id.widget_empty,"Your turn ♡\nShare a moment to reveal");
                views.setTextViewText(R.id.widget_label,"Tap to take your photo");views.setOnClickPendingIntent(R.id.widget_root,Notify.open(c,true));
            }else views.setTextViewText(R.id.widget_label,meta.optString("author_name","Your partner")+" · "+MainActivity.when(meta.optString("captured_at")));
        } catch(Exception e) {
            views.setImageViewBitmap(R.id.widget_photo,null);views.setViewVisibility(R.id.widget_empty,View.VISIBLE);
            JSONObject partner=SyncEngine.state(c).optJSONObject("partner");boolean locked=partner!=null&&partner.optBoolean("locked");
            views.setTextViewText(R.id.widget_empty,locked?"Your turn ♡\nShare a moment to reveal":"Twofold\nYour next little moment");views.setTextViewText(R.id.widget_label,locked?"Tap to take your photo":"Tap to open Twofold");
            if(locked)views.setOnClickPendingIntent(R.id.widget_root,Notify.open(c,true));
        }
        // Always replace the full RemoteViews, avoiding retained bitmap allocations.
        return views;
    }
}
