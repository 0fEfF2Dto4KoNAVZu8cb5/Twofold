package org.twofold.app;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.*;
import android.os.*;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;

public final class DirectService extends Service {
    private volatile boolean running;
    private volatile HttpURLConnection active;
    private Thread worker;
    private final Object reconnect=new Object();
    private ConnectivityManager.NetworkCallback networkCallback;
    @Override public void onCreate() {
        super.onCreate();
        networkCallback=new ConnectivityManager.NetworkCallback(){
            @Override public void onAvailable(Network network){wakeConnection();}
            @Override public void onLost(Network network){wakeConnection();}
        };
        getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(networkCallback);
    }
    private void wakeConnection(){ HttpURLConnection c=active;if(c!=null)c.disconnect();synchronized(reconnect){reconnect.notifyAll();} }
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        if(intent!=null&&"pause".equals(intent.getAction())) {
            Vault.prefs(this).edit().putBoolean("direct_paused",true).apply(); stopSelf();return START_NOT_STICKY;
        }
        if(!Vault.paired(this)||!"direct".equals(Vault.prefs(this).getString("mode","direct"))||Vault.prefs(this).getBoolean("direct_paused",false)){
            stopSelf();return START_NOT_STICKY;
        }
        Notification notification=Notify.connection(this,"Connecting to your private server…");
        if(Build.VERSION.SDK_INT>=34)startForeground(Notify.CONNECTION_ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(Notify.CONNECTION_ID,notification);
        if(!running){running=true;worker=new Thread(this::listen,"twofold-direct");worker.start();}
        return START_STICKY;
    }
    private void status(String text){
        Vault.prefs(this).edit().putString("direct_status",text).apply();
        getSystemService(NotificationManager.class).notify(Notify.CONNECTION_ID,Notify.connection(this,text));
    }
    private void sync() throws Exception {
        PowerManager.WakeLock lock=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Twofold:sync");
        lock.acquire(120000);
        try{SyncEngine.sync(this,true);}finally{if(lock.isHeld())lock.release();}
    }
    private void listen() {
        int delay=5;
        while(running) {
            HttpURLConnection c=null;
            try {
                JSONObject config=Vault.load(this); if(config==null)break;
                c=Api.relay(config,"/v1/events");active=c;
                c.setRequestProperty("Accept","text/event-stream");c.setReadTimeout(180000);
                int code=c.getResponseCode();
                if(code!=200)throw new Api.Failure(code,"Direct connection returned HTTP "+code+".");
                status("Direct updates connected"); delay=5;
                try(BufferedReader reader=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8))) {
                    String line;
                    while(running&&(line=reader.readLine())!=null) {
                        if(line.startsWith("data:")) {
                            try{sync();}catch(Exception e){SyncEngine.failure(this,e);}
                        }
                    }
                }
            } catch(Exception e) {
                if(running){status("Waiting to reconnect; periodic checks remain enabled");SyncEngine.failure(this,e);}
                if(e instanceof Api.Failure&&((Api.Failure)e).status==401){
                    Vault.prefs(this).edit().putBoolean("direct_paused",true).apply();break;
                }
            } finally {active=null;if(c!=null)c.disconnect();}
            if(running) {
                synchronized(reconnect){try{reconnect.wait((delay+ThreadLocalRandom.current().nextInt(Math.max(1,delay/3)))*1000L);}catch(InterruptedException e){break;}}
                delay=Math.min(300,delay*2);
            }
        }
        running=false;stopSelf();
    }
    @Override public void onDestroy(){
        running=false;wakeConnection();if(worker!=null)worker.interrupt();
        if(networkCallback!=null)getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkCallback);
        Vault.prefs(this).edit().putString("direct_status","Direct updates stopped").apply();
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
    public static void start(Context c) {
        if(!Vault.paired(c)||!"direct".equals(Vault.prefs(c).getString("mode","direct"))||Vault.prefs(c).getBoolean("direct_paused",false))return;
        try{c.startForegroundService(new Intent(c,DirectService.class));}
        catch(RuntimeException e){Vault.prefs(c).edit().putString("direct_status","Open Twofold to resume direct updates").apply();}
    }
}
