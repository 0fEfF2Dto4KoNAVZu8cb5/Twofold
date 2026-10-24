package org.twofold.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import org.json.JSONObject;

public final class Notify {
    public static final int CONNECTION_ID=10, MOMENT_ID=20, REMINDER_ID=21;
    static void channels(Context c){
        NotificationManager manager=c.getSystemService(NotificationManager.class);
        NotificationChannel connection=new NotificationChannel("connection","Direct connection",NotificationManager.IMPORTANCE_LOW);
        connection.setDescription("Quiet status while direct delivery is enabled.");connection.setSound(null,null);connection.enableVibration(false);connection.setShowBadge(false);manager.createNotificationChannel(connection);
        NotificationChannel moments=new NotificationChannel("moments","New moments",NotificationManager.IMPORTANCE_DEFAULT);moments.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);manager.createNotificationChannel(moments);
        NotificationChannel turns=new NotificationChannel("turns","Your turn",NotificationManager.IMPORTANCE_DEFAULT);turns.setDescription("A partner invitation and one gentle follow-up.");turns.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);manager.createNotificationChannel(turns);
    }
    static PendingIntent open(Context c){return open(c,false);}
    static PendingIntent open(Context c,boolean camera){
        Intent intent=new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("open_camera",camera).putExtra("open_moments",!camera);
        return PendingIntent.getActivity(c,camera?2:0,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    public static Notification connection(Context c,String status){
        channels(c);PendingIntent stop=PendingIntent.getService(c,1,new Intent(c,DirectService.class).setAction("pause"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(c,"connection").setSmallIcon(R.drawable.ic_notification).setContentTitle("Twofold").setContentText(status).setContentIntent(open(c)).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_SECRET).addAction(new Notification.Action.Builder(null,"Pause direct updates",stop).build()).build();
    }
    static boolean allowed(Context c){return (Build.VERSION.SDK_INT<33||c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)&&c.getSystemService(NotificationManager.class).areNotificationsEnabled();}
    static boolean turnsAllowed(Context c){NotificationChannel channel=c.getSystemService(NotificationManager.class).getNotificationChannel("turns");return allowed(c)&&(channel==null||channel.getImportance()!=NotificationManager.IMPORTANCE_NONE);}
    static String waitingRound(JSONObject state){JSONObject round=state.optJSONObject("round");return round!=null&&round.optBoolean("waiting_for_you")?round.optString("id"):"";}
    static void post(Context c,int id,String channel,String title,String text,boolean camera){
        if(!allowed(c)||MainActivity.visible||CameraActivity.visible)return;channels(c);
        Notification publicView=new Notification.Builder(c,channel).setSmallIcon(R.drawable.ic_notification).setContentTitle("Twofold").setContentText("A private moment is waiting.").build();
        try{c.getSystemService(NotificationManager.class).notify(id,new Notification.Builder(c,channel).setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(text)
            .setContentIntent(open(c,camera)).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(publicView).build());}catch(SecurityException ignored){}
    }
    public static void newMoment(Context c){post(c,MOMENT_ID,"moments","A moment, just for you","Your partner’s photo is ready.",false);}
    static void onState(Context c,JSONObject before,JSONObject next,boolean notify){
        String round=waitingRound(next);
        boolean firstInvitation=false;
        boolean turnNotice=!round.isEmpty()&&Vault.prefs(c).getBoolean("turn_reminders",true)&&turnsAllowed(c);
        if(turnNotice){
            if(!round.equals(Vault.prefs(c).getString("reminder_round",""))){
                firstInvitation=true;
                Vault.prefs(c).edit().putString("reminder_round",round).putLong("reminder_started",System.currentTimeMillis()).apply();
                if(notify&&SyncEngine.pending(c)==0){
                    JSONObject partner=next.optJSONObject("partner");boolean locked=partner!=null&&partner.optBoolean("locked");
                    post(c,REMINDER_ID,"turns","Your turn ♡",locked?"Your partner shared a moment. Take yours to reveal it.":"Your partner shared a moment. Send one back?",true);
                }
            }
            ReminderJob.schedule(c,round);
        }else{ReminderJob.cancel(c);c.getSystemService(NotificationManager.class).cancel(REMINDER_ID);}
        JSONObject partner=next.optJSONObject("partner"),previous=before.optJSONObject("partner");
        boolean revealed=partner!=null&&!partner.optBoolean("locked")&&(previous==null||!partner.optString("id").equals(previous.optString("id"))||previous.optBoolean("locked"));
        if(notify&&revealed&&!firstInvitation)newMoment(c);
    }
    static void followUp(Context c,String expectedRound){
        String current=waitingRound(SyncEngine.state(c));
        if(!expectedRound.equals(current)||current.isEmpty()||!Vault.prefs(c).getBoolean("turn_reminders",true)||!Vault.prefs(c).getBoolean("turn_followup",true))return;
        if(current.equals(Vault.prefs(c).getString("followup_round","")))return;
        Vault.prefs(c).edit().putString("followup_round",current).apply();
        if(SyncEngine.pending(c)==0)post(c,REMINDER_ID,"turns","A little nudge ♡","Your partner’s moment is still waiting. Share whenever you’re ready.",true);
    }
}
