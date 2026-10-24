package org.twofold.app;

import android.app.job.*;
import android.content.*;
import java.util.concurrent.*;

public final class SyncJob extends JobService {
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private Future<?> task;
    @Override public boolean onStartJob(JobParameters params) {
        task=executor.submit(()->{
            boolean retry=false;
            try { SyncEngine.sync(this,true); }
            catch(Exception e){ SyncEngine.failure(this,e);retry=true; }
            jobFinished(params,retry);
        });
        return true;
    }
    @Override public boolean onStopJob(JobParameters params){ if(task!=null)task.cancel(true);return true; }
    @Override public void onDestroy(){executor.shutdownNow();super.onDestroy();}
    public static void schedule(Context c) {
        if(!Vault.paired(c))return;
        int minutes=Vault.prefs(c).getInt("interval",60);
        if(minutes!=15&&minutes!=30&&minutes!=60&&minutes!=120)minutes=60;
        JobScheduler scheduler=c.getSystemService(JobScheduler.class);
        JobInfo old=scheduler.getPendingJob(100);
        if(old!=null&&old.getIntervalMillis()==minutes*60000L)return;
        scheduler.schedule(new JobInfo.Builder(100,new ComponentName(c,SyncJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(minutes*60000L)
            .setPersisted(true).setBackoffCriteria(300000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build());
    }
}
