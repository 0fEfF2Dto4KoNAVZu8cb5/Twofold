package org.twofold.app;

import android.app.job.*;
import android.content.*;
import android.os.Build;
import java.util.*;
import java.util.concurrent.*;

/** Coalesced system work queue; a challenge arriving during sync is not lost. */
public final class PushSyncJob extends JobService {
    static final int ID=102;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();private Future<?> work;
    @Override public boolean onStartJob(JobParameters params){
        work=executor.submit(()->{
            boolean retry=false;
            try{
                while(!Thread.currentThread().isInterrupted()){
                    List<JobWorkItem> batch=new ArrayList<>();JobWorkItem item;while((item=params.dequeueWork())!=null)batch.add(item);
                    if(batch.isEmpty())break;
                    SyncEngine.sync(this,true);
                    for(JobWorkItem done:batch)params.completeWork(done);
                }
            }catch(Exception e){SyncEngine.failure(this,e);retry=true;}
            jobFinished(params,retry);
        });return true;
    }
    @Override public boolean onStopJob(JobParameters params){if(work!=null)work.cancel(true);return true;}
    @Override public void onDestroy(){executor.shutdownNow();super.onDestroy();}
    static void schedule(Context c){
        if(!Vault.paired(c)||!PushDelivery.selected(c))return;
        JobScheduler scheduler=c.getSystemService(JobScheduler.class);
        JobInfo.Builder b=new JobInfo.Builder(ID,new ComponentName(c,PushSyncJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setBackoffCriteria(300000,JobInfo.BACKOFF_POLICY_EXPONENTIAL);
        if(Build.VERSION.SDK_INT>=31)b.setExpedited(true);
        try{if(scheduler.enqueue(b.build(),new JobWorkItem(new Intent("org.twofold.app.SYNC")))==JobScheduler.RESULT_SUCCESS)return;}catch(IllegalArgumentException ignored){}
        JobInfo fallback=new JobInfo.Builder(ID,new ComponentName(c,PushSyncJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setBackoffCriteria(300000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build();
        scheduler.enqueue(fallback,new JobWorkItem(new Intent("org.twofold.app.SYNC")));
    }
}
