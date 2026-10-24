package org.twofold.app;

import android.app.*;
import android.content.*;
import org.json.*;
import org.unifiedpush.android.connector.UnifiedPush;
import org.unifiedpush.android.connector.data.*;
import static org.unifiedpush.android.connector.ConstantsKt.INSTANCE_DEFAULT;
import java.nio.charset.StandardCharsets;

/** ntfy is optional; all photo traffic still goes directly to the paired HTTPS server. */
final class PushDelivery {
    static final String NTFY_PACKAGE="io.heckel.ntfy";
    static boolean selected(Context c){return "ntfy".equals(Vault.prefs(c).getString("mode","direct"));}
    static boolean installed(Context c){try{return UnifiedPush.getDistributors(c).contains(NTFY_PACKAGE);}catch(Exception e){return false;}}
    static void enable(Context c){
        connect(c,true);
    }
    private static void connect(Context c,boolean force){
        if(!installed(c)){localStatus(c,"Open and configure the ntfy app, then reconnect.");return;}
        Vault.prefs(c).edit().putString("mode","ntfy").putBoolean("direct_paused",false).putLong("push_register_at",System.currentTimeMillis()).putBoolean("push_problem",false).apply();c.stopService(new Intent(c,DirectService.class));
        if(force)Vault.prefs(c).edit().putBoolean("push_dirty",true).putLong("push_retry_after",0).apply();
        try{if(!NTFY_PACKAGE.equals(UnifiedPush.getSavedDistributor(c)))UnifiedPush.saveDistributor(c,NTFY_PACKAGE);UnifiedPush.register(c,INSTANCE_DEFAULT,"Private Twofold update signals",null);localStatus(c,"Connecting to ntfy…");}
        catch(Exception e){localStatus(c,"Could not register with ntfy. Open ntfy and reconnect.");}
    }
    static void resume(Context c){if(selected(c)&&System.currentTimeMillis()-Vault.prefs(c).getLong("push_register_at",0)>21600000)connect(c,false);}
    static void disable(Context c){
        try{UnifiedPush.unregister(c,INSTANCE_DEFAULT);}catch(Exception ignored){}
        Vault.prefs(c).edit().remove("push_subscription").remove("push_challenge").putBoolean("push_unlink",true).putBoolean("push_dirty",false).putString("push_local_status","").apply();
        c.getSystemService(android.app.job.JobScheduler.class).cancel(PushSyncJob.ID);
    }
    static void endpoint(Context c,PushEndpoint endpoint){
        if(!selected(c)||!Vault.paired(c))return;
        PublicKeySet keys=endpoint.getPubKeySet();
        if(keys==null||endpoint.getTemporary()){localStatus(c,"Secure ntfy delivery is unavailable. Periodic checks continue.");return;}
        try{
            JSONObject subscription=new JSONObject().put("endpoint",endpoint.getUrl()).put("keys",new JSONObject().put("p256dh",keys.getPubKey()).put("auth",keys.getAuth()));
            String next=subscription.toString(),old=Vault.prefs(c).getString("push_subscription","");
            if(!next.equals(old)){Vault.prefs(c).edit().putString("push_subscription",next).putBoolean("push_dirty",true).putBoolean("push_problem",false).putLong("push_retry_after",0).remove("push_challenge").apply();PushSyncJob.schedule(c);}
            else if(Vault.prefs(c).getBoolean("push_dirty",false))PushSyncJob.schedule(c);
            localStatus(c,"Linked to ntfy. Confirming delivery…");
        }catch(Exception e){localStatus(c,"Could not read the ntfy registration. Reconnect to retry.");}
    }
    static void message(Context c,PushMessage message){
        if(!selected(c)||!Vault.paired(c)||!message.getDecrypted())return;
        byte[] content=message.getContent();if(content.length>1024)return;
        try{JSONObject value=new JSONObject(new String(content,StandardCharsets.UTF_8));if(!"sync".equals(value.optString("event")))return;
            String challenge=value.optString("challenge","");if(challenge.matches("[A-Za-z0-9_-]{32}"))Vault.prefs(c).edit().putString("push_challenge",challenge).apply();PushSyncJob.schedule(c);
        }catch(JSONException ignored){}
    }
    static void unavailable(Context c,boolean removed){if(!selected(c))return;if(removed){Vault.prefs(c).edit().remove("push_subscription").remove("push_challenge").putBoolean("push_unlink",true).apply();PushSyncJob.schedule(c);}Vault.prefs(c).edit().putBoolean("push_problem",true).apply();localStatus(c,"ntfy needs attention. Periodic checks continue.");}
    static void localStatus(Context c,String value){Vault.prefs(c).edit().putString("push_local_status",value).apply();UiUpdates.changed();}
    static String status(Context c){JSONObject push=SyncEngine.state(c).optJSONObject("push");if(push!=null&&!push.optBoolean("available"))return "Your Twofold server needs ntfy setup.";if(Vault.prefs(c).getBoolean("push_problem",false))return Vault.prefs(c).getString("push_local_status","");if(push!=null&&push.optBoolean("verified"))return push.optString("status","ntfy connected");return Vault.prefs(c).getString("push_local_status","Open ntfy, then connect it here.");}
    static void syncRegistration(Context c,JSONObject account){
        // Optional setup errors must never block uploads or the periodic fallback.
        try{
            if(Vault.prefs(c).getBoolean("push_unlink",false)){
                try{Api.json(Api.relay(account,"/v1/push"),"DELETE",null);}catch(Api.Failure e){if(e.status!=404&&e.status!=401)throw e;}
                Vault.prefs(c).edit().putBoolean("push_unlink",false).apply();
            }
            if(!selected(c))return;
            if(System.currentTimeMillis()<Vault.prefs(c).getLong("push_retry_after",0))return;
            String subscription=Vault.prefs(c).getString("push_subscription","");if(subscription.isEmpty())return;
            JSONObject server=SyncEngine.state(c).optJSONObject("push");
            if(Vault.prefs(c).getBoolean("push_dirty",false)||server==null||!server.optBoolean("has_endpoint")||!server.optBoolean("verified")){
                Api.json(Api.relay(account,"/v1/push"),"PUT",new JSONObject(subscription));
                if(subscription.equals(Vault.prefs(c).getString("push_subscription","")))Vault.prefs(c).edit().putBoolean("push_dirty",false).apply();
            }
            String challenge=Vault.prefs(c).getString("push_challenge","");
            if(!challenge.isEmpty()){
                try{Api.json(Api.relay(account,"/v1/push/confirm"),"POST",new JSONObject().put("challenge",challenge));}
                catch(Api.Failure e){if(e.status==400)Vault.prefs(c).edit().remove("push_challenge").apply();throw e;}
                if(challenge.equals(Vault.prefs(c).getString("push_challenge","")))Vault.prefs(c).edit().remove("push_challenge").apply();
            }
            Vault.prefs(c).edit().putLong("push_retry_after",0).putBoolean("push_problem",false).apply();
        }catch(Exception e){Vault.prefs(c).edit().putLong("push_retry_after",System.currentTimeMillis()+900000).putBoolean("push_problem",true).apply();localStatus(c,"ntfy setup needs attention. Periodic checks continue.");}
    }
    static void observe(Context c,JSONObject state){JSONObject push=state.optJSONObject("push");if(selected(c)&&System.currentTimeMillis()>=Vault.prefs(c).getLong("push_retry_after",0)&&push!=null&&push.optBoolean("available")&&!push.optBoolean("has_endpoint")&&!Vault.prefs(c).getString("push_subscription","").isEmpty()){Vault.prefs(c).edit().putBoolean("push_dirty",true).apply();PushSyncJob.schedule(c);}}
}
