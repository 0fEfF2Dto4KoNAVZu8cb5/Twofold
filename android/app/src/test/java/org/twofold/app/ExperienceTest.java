package org.twofold.app;

import android.Manifest;
import android.app.*;
import android.app.job.*;
import android.content.*;
import android.content.SharedPreferences;
import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.exifinterface.media.ExifInterface;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import org.unifiedpush.android.connector.data.PushMessage;
import static org.junit.Assert.*;

/** Real Android UI/bitmap/notification code on a simulated API 35, with test-only credentials. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w393dp-h851dp",instrumentedPackages={"org.twofold.app"},shadows={ExperienceTest.FakeVault.class})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ExperienceTest {
    @Implements(value=Vault.class,isInAndroidSdk=false)
    public static class FakeVault {
        static JSONObject account;
        @Implementation public static SharedPreferences prefs(Context c){return c.getSharedPreferences("twofold",Context.MODE_PRIVATE);}
        @Implementation public static JSONObject load(Context c){return account;}
        @Implementation public static void save(Context c,JSONObject value){account=value;}
        @Implementation public static boolean paired(Context c){return account!=null&&account.optBoolean("enrolled");}
        @Implementation public static void clear(Context c){account=null;prefs(c).edit().clear().commit();}
    }
    private Context context;private ActivityController<MainActivity> controller;
    @Before public void setup()throws Exception{
        context=RuntimeEnvironment.getApplication();FakeVault.account=new JSONObject().put("server","https://example.invalid").put("token","fixture-only").put("name","Alex").put("slot",1).put("enrolled",true);
        Vault.prefs(context).edit().clear().putString("mode","periodic").putLong("last_sync",System.currentTimeMillis()).putBoolean("preserve_metadata",true).commit();
        MainActivity.visible=false;CameraActivity.visible=false;
        Shadows.shadowOf((Application)context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        context.getSystemService(NotificationManager.class).cancelAll();
        context.getSystemService(JobScheduler.class).cancelAll();
        SyncEngine.deleteTree(SyncEngine.queueRoot(context));
    }
    @After public void cleanup(){if(controller!=null)controller.pause().stop().destroy();UiUpdates.clear();MainActivity.visible=false;CameraActivity.visible=false;}
    private File photo(String name,int color,int width,int height)throws Exception{
        File f=new File(context.getCacheDir(),name);Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);bitmap.eraseColor(color);
        try(FileOutputStream out=new FileOutputStream(f)){assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,92,out));}bitmap.recycle();
        ExifInterface exif=new ExifInterface(f);exif.setLatLong(45.5,-87.25);exif.saveAttributes();return f;
    }
    private JSONObject row(String id,boolean locked)throws Exception{return new JSONObject().put("id",id).put("slot",2).put("photo_count",2).put("locked",locked).put("received_at","2026-10-23T12:00:00Z").put("metadata",new JSONObject().put("author_name","Jamie").put("captured_at","2026-10-23T12:00:00Z"));}
    private JSONObject pending(String id)throws Exception{return new JSONObject().put("protocol_version",2).put("preferences",new JSONObject().put("share_to_reveal",true)).put("round",new JSONObject().put("id",id).put("waiting_for_you",true)).put("partner",row(UUID.randomUUID().toString(),true));}
    private void cache(JSONObject row,int color)throws Exception{File f=photo("cache-fixture.jpg",color,240,320);Files.copy(f.toPath(),SyncEngine.previewFile(context,row).toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
    @Test public void everyCornerAndMainSelectionPreserveOriginals()throws Exception{
        File rear=photo("rear.jpg",Color.RED,640,800),front=photo("front.jpg",Color.BLUE,800,600);byte[] raw=Files.readAllBytes(rear.toPath()),raw2=Files.readAllBytes(front.toPath());
        for(String corner:new String[]{"top_left","top_right","bottom_left","bottom_right"}){
            byte[] preview=ImageTools.preview(rear,front,1,corner);Bitmap result=BitmapFactory.decodeByteArray(preview,0,preview.length);
            RectF inset=ImageTools.insetRect(result.getWidth(),result.getHeight(),800,600,corner);
            int inside=result.getPixel((int)inset.centerX(),(int)inset.centerY()),center=result.getPixel(result.getWidth()/2,result.getHeight()/2);
            assertTrue("Inset is blue at "+corner,Color.blue(inside)>200&&Color.red(inside)<50);assertTrue(Color.red(center)>200&&Color.blue(center)<50);
            assertEquals(800f/600f,inset.width()/inset.height(),0.01);result.recycle();
        }
        byte[] swapped=ImageTools.preview(rear,front,2,"bottom_right");Bitmap result=BitmapFactory.decodeByteArray(swapped,0,swapped.length);assertTrue(Color.blue(result.getPixel(result.getWidth()/2,result.getHeight()/2))>200);result.recycle();
        assertArrayEquals(raw,Files.readAllBytes(rear.toPath()));assertArrayEquals(raw2,Files.readAllBytes(front.toPath()));
    }
    @Test public void outboxSavesLayoutAndUnmodifiedMetadata()throws Exception{
        File a=photo("queue-a.jpg",Color.RED,240,320),b=photo("queue-b.jpg",Color.BLUE,320,240);byte[] raw=Files.readAllBytes(a.toPath());
        SyncEngine.enqueue(context,a,b,"Hello","2026-10-23T12:00:00Z",2,"bottom_right");File[] queue=SyncEngine.queueRoot(context).listFiles(f->f.isDirectory()&&!f.getName().startsWith("."));assertEquals(1,queue.length);
        JSONObject metadata=new JSONObject(new String(Files.readAllBytes(new File(queue[0],"metadata").toPath()),java.nio.charset.StandardCharsets.UTF_8));assertEquals(2,metadata.getJSONObject("layout").getInt("main_photo"));assertEquals("bottom_right",metadata.getJSONObject("layout").getString("inset_corner"));
        assertArrayEquals(raw,Files.readAllBytes(new File(queue[0],"photo1").toPath()));assertNotNull(new ExifInterface(new File(queue[0],"photo1")).getLatLong());
    }
    @Test public void lockedCacheDoesNotReuseAFullPreview()throws Exception{
        String id=UUID.randomUUID().toString();JSONObject locked=row(id,true),full=row(id,false);cache(full,Color.RED);cache(locked,Color.BLUE);
        Bitmap image=SyncEngine.picture(context,locked,240);assertTrue(Color.blue(image.getPixel(100,100))>200);image.recycle();assertNotEquals(SyncEngine.previewFile(context,full),SyncEngine.previewFile(context,locked));
    }
    @Test public void remindersDeduplicateAndStopAfterReply()throws Exception{
        String id=UUID.randomUUID().toString();JSONObject next=pending(id);Notify.onState(context,new JSONObject(),next,true);Notify.onState(context,next,next,true);
        ShadowNotificationManager manager=Shadows.shadowOf(context.getSystemService(NotificationManager.class));assertEquals(1,manager.getAllNotifications().size());
        JobInfo followup=context.getSystemService(JobScheduler.class).getPendingJob(ReminderJob.ID);assertNotNull(followup);assertTrue(followup.getMinLatencyMillis()>=3_500_000&&followup.getMinLatencyMillis()<=3_600_000);
        Vault.prefs(context).edit().putString("state",next.toString()).commit();Notify.followUp(context,id);Notification first=manager.getNotification(Notify.REMINDER_ID);Notify.followUp(context,id);assertSame(first,manager.getNotification(Notify.REMINDER_ID));assertEquals("A little nudge ♡",first.extras.getString(Notification.EXTRA_TITLE));
        JSONObject completed=new JSONObject(next.toString());completed.getJSONObject("round").put("waiting_for_you",false).put("complete",true);completed.getJSONObject("partner").put("locked",false);Notify.onState(context,next,completed,true);assertNull(manager.getNotification(Notify.REMINDER_ID));assertNull(context.getSystemService(JobScheduler.class).getPendingJob(ReminderJob.ID));assertNotNull(manager.getNotification(Notify.MOMENT_ID));
    }
    @Test public void remindersRespectOptOutAndQueuedPhotos()throws Exception{
        JSONObject next=pending(UUID.randomUUID().toString());Vault.prefs(context).edit().putBoolean("turn_reminders",false).commit();Notify.onState(context,new JSONObject(),next,true);assertNull(context.getSystemService(JobScheduler.class).getPendingJob(ReminderJob.ID));assertTrue(Shadows.shadowOf(context.getSystemService(NotificationManager.class)).getAllNotifications().isEmpty());
        Vault.prefs(context).edit().putBoolean("turn_reminders",true).commit();new File(SyncEngine.queueRoot(context),UUID.randomUUID().toString()).mkdir();Notify.onState(context,new JSONObject(),next,true);assertTrue(Shadows.shadowOf(context.getSystemService(NotificationManager.class)).getAllNotifications().isEmpty());
    }
    @Test public void openSharingSendsOneInvitationNotTwoAlerts()throws Exception{
        JSONObject next=pending(UUID.randomUUID().toString());next.getJSONObject("partner").put("locked",false);Notify.onState(context,new JSONObject(),next,true);
        assertEquals(1,Shadows.shadowOf(context.getSystemService(NotificationManager.class)).getAllNotifications().size());
        assertNotNull(Shadows.shadowOf(context.getSystemService(NotificationManager.class)).getNotification(Notify.REMINDER_ID));
    }
    @Test public void encryptedPushCallbacksRespectOptOutAndSetupBackoff()throws Exception{
        String challenge="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        byte[] body=("{\"event\":\"sync\",\"challenge\":\""+challenge+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Vault.prefs(context).edit().putString("mode","ntfy").commit();
        PushDelivery.message(context,new PushMessage(body,false));assertNull(context.getSystemService(JobScheduler.class).getPendingJob(PushSyncJob.ID));
        PushDelivery.message(context,new PushMessage(body,true));assertEquals(challenge,Vault.prefs(context).getString("push_challenge",""));assertNotNull(context.getSystemService(JobScheduler.class).getPendingJob(PushSyncJob.ID));
        context.getSystemService(JobScheduler.class).cancel(PushSyncJob.ID);
        Vault.prefs(context).edit().putString("push_subscription","{}").putLong("push_retry_after",System.currentTimeMillis()+900000).commit();
        PushDelivery.observe(context,new JSONObject().put("push",new JSONObject().put("available",true).put("has_endpoint",false)));assertNull(context.getSystemService(JobScheduler.class).getPendingJob(PushSyncJob.ID));
        Vault.prefs(context).edit().putString("mode","direct").remove("push_challenge").commit();PushDelivery.message(context,new PushMessage(body,true));assertFalse(Vault.prefs(context).contains("push_challenge"));
    }
    @Test public void widgetClearsFullImageWhenLockedPreviewIsMissing()throws Exception{
        String id=UUID.randomUUID().toString();JSONObject full=row(id,false),locked=row(id,true);cache(full,Color.RED);
        Vault.prefs(context).edit().putString("state",new JSONObject().put("partner",full).toString()).commit();
        View widget=PhotoWidget.views(context).apply(context,new FrameLayout(context));
        ImageView image=widget.findViewById(R.id.widget_photo);assertTrue(Color.red(((BitmapDrawable)image.getDrawable()).getBitmap().getPixel(50,50))>200);
        Vault.prefs(context).edit().putString("state",new JSONObject().put("partner",locked).toString()).commit();PhotoWidget.views(context).reapply(context,widget);
        assertTrue(image.getDrawable()==null||((BitmapDrawable)image.getDrawable()).getBitmap()==null);
        assertEquals(View.VISIBLE,widget.findViewById(R.id.widget_empty).getVisibility());
        assertTrue(((TextView)widget.findViewById(R.id.widget_empty)).getText().toString().contains("Your turn"));
        assertEquals(ImageView.ScaleType.FIT_CENTER,image.getScaleType());
    }
    private View find(View root,String text){if(root instanceof TextView&&text.contentEquals(((TextView)root).getText()))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=find(((ViewGroup)root).getChildAt(i),text);if(found!=null)return found;}return null;}
    private View described(View root,String text){if(text.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=described(((ViewGroup)root).getChildAt(i),text);if(found!=null)return found;}return null;}
    private void render(String name)throws Exception{
        View decor=controller.get().getWindow().getDecorView();float density=context.getResources().getDisplayMetrics().density;int width=Math.round(393*density),height=Math.round(851*density);decor.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));decor.layout(0,0,width,height);
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);decor.draw(new Canvas(bitmap));File directory=new File("build/test-ui");directory.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(directory,name))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
    }
    @Test public void homeHasOneClearCaptureAction()throws Exception{
        JSONObject state=pending(UUID.randomUUID().toString());cache(state.getJSONObject("partner"),Color.rgb(55,60,80));Vault.prefs(context).edit().putString("state",state.toString()).commit();controller=Robolectric.buildActivity(MainActivity.class).setup();View root=controller.get().getWindow().getDecorView();
        assertNotNull(find(root,"Take yours to reveal"));assertNull(find(root,"Send"));assertNull(find(root,"Archived to Immich"));assertNotNull(described(root,"Settings"));render("feed.png");
    }
    @Test public void reviewControlsPersistAndRender()throws Exception{
        File a=photo("review-a.jpg",Color.rgb(80,130,125),640,800),b=photo("review-b.jpg",Color.rgb(180,125,95),600,800);Bundle state=new Bundle();state.putString("page","review");state.putString("first",a.getPath());state.putString("second",b.getPath());state.putString("corner","top_left");state.putInt("main_photo",1);
        controller=Robolectric.buildActivity(MainActivity.class).create(state).start().resume().visible();View root=controller.get().getWindow().getDecorView();View corner=described(root,"Bottom right inset");assertNotNull(corner);corner.performClick();assertEquals("bottom_right",Vault.prefs(context).getString("inset_corner",""));find(controller.get().getWindow().getDecorView(),"Swap").performClick();assertEquals(2,Vault.prefs(context).getInt("main_photo",0));assertNotNull(find(controller.get().getWindow().getDecorView(),"Share moment"));render("review.png");
    }
}
