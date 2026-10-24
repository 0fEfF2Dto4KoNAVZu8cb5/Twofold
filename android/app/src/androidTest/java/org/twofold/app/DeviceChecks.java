package org.twofold.app;

import android.app.*;
import android.app.job.*;
import android.content.*;
import android.graphics.*;
import android.location.Location;
import android.os.SystemClock;
import android.os.Bundle;
import android.view.WindowManager;
import androidx.exifinterface.media.ExifInterface;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.Files;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.util.Arrays;
import javax.net.ssl.*;

/** Test-APK-only device checks. No test CA or test hooks are included in release. */
public final class DeviceChecks extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle args){this.args=args;start();}
    private void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private File jpeg(Context c,int color,String name)throws Exception{
        File f=new File(c.getCacheDir(),name);Bitmap b=Bitmap.createBitmap(240,320,Bitmap.Config.ARGB_8888);b.eraseColor(color);
        try(FileOutputStream out=new FileOutputStream(f)){b.compress(Bitmap.CompressFormat.JPEG,90,out);}b.recycle();
        ExifInterface exif=new ExifInterface(f);exif.setLatLong(45.0,-87.0);exif.saveAttributes();return f;
    }
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            check("ranchu".equals(android.os.Build.HARDWARE)||"goldfish".equals(android.os.Build.HARDWARE),"Device checks are restricted to an emulator");
            // Trust one ephemeral fixture CA for this instrumentation process only.
            byte[] certificate=android.util.Base64.decode(args.getString("ca"),android.util.Base64.DEFAULT);
            KeyStore trusted=KeyStore.getInstance(KeyStore.getDefaultType());trusted.load(null,null);
            trusted.setCertificateEntry("fixture",CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(certificate)));
            TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(trusted);
            SSLContext tls=SSLContext.getInstance("TLS");tls.init(null,tm.getTrustManagers(),new SecureRandom());HttpsURLConnection.setDefaultSSLSocketFactory(tls.getSocketFactory());
            Context c=getTargetContext();
            check(!Vault.paired(c)||Vault.prefs(c).getBoolean("device_test_fixture",false),"Refusing to replace non-test pairing data");
            Vault.prefs(c).edit().putBoolean("device_test_fixture",true).commit();
            JSONObject config=new JSONObject().put("server",args.getString("relay")).put("token",args.getString("token"))
                .put("slot",1).put("name","Mike").put("enrolled",true);
            Vault.save(c,config);check(Vault.load(c).getString("token").equals(config.getString("token")),"Keystore roundtrip");
            File a=jpeg(c,Color.rgb(20,120,100),"test-first.jpg"),b=jpeg(c,Color.rgb(200,140,70),"test-second.jpg");
            Bitmap before=BitmapFactory.decodeFile(a.getPath());
            Location fix=new Location("fixture");fix.setLatitude(45);fix.setLongitude(-87);fix.setAccuracy(12);fix.setTime(1792758896123L);fix.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            CaptureMetadata.tag(a,null,1792758896123L,fix,"Mike","0",false,"concurrent","fixture-pair","capture_callback",123456789L);
            ExifInterface tagged=new ExifInterface(a);Bitmap after=BitmapFactory.decodeFile(a.getPath());
            check(before.sameAs(after),"Metadata writing preserves JPEG pixels");before.recycle();after.recycle();
            check("Mike".equals(tagged.getAttribute(ExifInterface.TAG_ARTIST)),"Embedded camera author");
            check(tagged.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)!=null,"Capture time includes timezone");
            JSONObject capture=new JSONObject(tagged.getAttribute(ExifInterface.TAG_USER_COMMENT));
            check("rear".equals(capture.getString("camera_facing"))&&"fixture-pair".equals(capture.getString("capture_group")),"Camera role and pair identifier");
            Vault.prefs(c).edit().putBoolean("preserve_metadata",true).commit();
            byte[] source=Files.readAllBytes(a.toPath());
            SyncEngine.enqueue(c,a,b,"A private & happy moment","2026-10-23T12:34:56Z");
            check(SyncEngine.pending(c)==1,"Durable outbox entry");
            check(new ExifInterface(a).getLatLong()!=null,"JPEG GPS preserved");
            File[] queued=SyncEngine.queueRoot(c).listFiles(File::isDirectory);
            byte[] original=Files.readAllBytes(new File(queued[0],"photo1").toPath());
            check(Arrays.equals(source,original),"Full-metadata originals remain byte-identical");
            check((original[0]&255)==255&&(original[1]&255)==216,"Raw JPEG outbox file");
            SyncEngine.sync(c,false);check(SyncEngine.pending(c)==0,"Server durably received raw originals");
            check(SyncEngine.state(c).getJSONObject("own").getInt("photo_count")==2,"Latest two-photo set");
            check(SyncEngine.state(c).optJSONObject("partner")!=null,"Partner's latest set");
            Bitmap preview=SyncEngine.picture(c,SyncEngine.state(c).getJSONObject("partner"),400);check(preview.getWidth()<=800,"Native JPEG preview");preview.recycle();
            Vault.prefs(c).edit().putString("mode","periodic").commit();
            SyncJob.schedule(c);JobInfo job=c.getSystemService(JobScheduler.class).getPendingJob(100);
            check(job!=null&&job.getIntervalMillis()==3600000L,"Hourly recovery job scheduled");
            Intent open=new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Activity activity=startActivitySync(open);waitForIdleSync();
            runOnMainSync(()->check((activity.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0,"Screenshots blocked"));
            File next=jpeg(c,Color.rgb(80,90,180),"test-next.jpg");
            Vault.prefs(c).edit().putBoolean("preserve_metadata",false).commit();
            SyncEngine.enqueue(c,next,null,"A second moment","2026-10-23T13:34:56Z");
            check(new ExifInterface(next).getLatLong()==null,"Optional private-metadata stripping removes GPS");
            SyncEngine.sync(c,false);check(SyncEngine.pending(c)==0,"Latest JPEG delivered");
            check(SyncEngine.state(c).getJSONObject("own").getInt("photo_count")==1,"Latest set replaced");
            File[] latest=c.getFilesDir().listFiles(f->f.getName().startsWith("latest-"));check(latest!=null&&latest.length==2,"Only latest own and partner previews retained");
            PhotoWidget.updateAll(c);
            Vault.prefs(c).edit().putString("mode","direct").putBoolean("direct_paused",false).commit();
            runOnMainSync(()->DirectService.start(c));
            long deadline=System.currentTimeMillis()+15000;
            while(System.currentTimeMillis()<deadline&&!Vault.prefs(c).getString("direct_status","").contains("connected"))Thread.sleep(200);
            check(Vault.prefs(c).getString("direct_status","").contains("connected"),"Direct foreground SSE connects");
            c.stopService(new Intent(c,DirectService.class));
            runOnMainSync(activity::finish);
            result.putString("stream","DEVICE_CHECKS_PASSED: Keystore token, byte-identical originals, preserved GPS, optional stripping, raw relay, latest-only cache, scheduled recovery, secure window, direct SSE.");
            finish(Activity.RESULT_OK,result);
        }catch(Throwable e){StringWriter out=new StringWriter();e.printStackTrace(new PrintWriter(out));result.putString("stream",out.toString());finish(Activity.RESULT_CANCELED,result);}
    }
}
