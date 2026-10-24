package org.twofold.app;

import android.app.job.*;
import android.content.*;
import android.os.PersistableBundle;
import java.util.concurrent.*;

/** One inexact follow-up per pending shared round, without exact alarms or polling. */
public final class ReminderJob extends JobService {
    static final int ID=101;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private Future<?> work;
    @Override public boolean onStartJob(JobParameters params){
        String round=params.getExtras().getString("round","");
        work=executor.submit(()->{
            try{SyncEngine.sync(this,false);Notify.followUp(this,round);}catch(Exception e){SyncEngine.failure(this,e);}
            jobFinished(params,false);
        });return true;
    }
    @Override public boolean onStopJob(JobParameters params){if(work!=null)work.cancel(true);return false;}
    @Override public void onDestroy(){executor.shutdownNow();super.onDestroy();}
    static void schedule(Context c,String round){
        if(!Vault.paired(c)||round.isEmpty()||!Vault.prefs(c).getBoolean("turn_reminders",true)||!Vault.prefs(c).getBoolean("turn_followup",true)||round.equals(Vault.prefs(c).getString("followup_round",""))){cancel(c);return;}
        JobScheduler scheduler=c.getSystemService(JobScheduler.class);JobInfo existing=scheduler.getPendingJob(ID);
        if(existing!=null&&round.equals(existing.getExtras().getString("round","")))return;
        long first=Vault.prefs(c).getLong("reminder_started",System.currentTimeMillis());
        long delay=Math.max(60000,first+3600000-System.currentTimeMillis());
        PersistableBundle extras=new PersistableBundle();extras.putString("round",round);
        scheduler.schedule(new JobInfo.Builder(ID,new ComponentName(c,ReminderJob.class)).setExtras(extras)
            .setMinimumLatency(delay).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).build());
    }
    static void cancel(Context c){c.getSystemService(JobScheduler.class).cancel(ID);}
}
