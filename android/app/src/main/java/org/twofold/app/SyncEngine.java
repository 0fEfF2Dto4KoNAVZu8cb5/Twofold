package org.twofold.app;

import android.content.Context;
import android.graphics.Bitmap;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Private local files and authenticated HTTPS transport; no photo encryption layer. */
public final class SyncEngine {
    public static File queueRoot(Context c){File f=new File(c.getFilesDir(),"queue");f.mkdirs();return f;}
    public static int pending(Context c){File[] f=queueRoot(c).listFiles(x->x.isDirectory()&&!x.getName().startsWith("."));return f==null?0:f.length;}
    public static JSONObject state(Context c){try{return new JSONObject(Vault.prefs(c).getString("state","{}"));}catch(Exception e){return new JSONObject();}}
    static void write(File file,byte[] bytes)throws Exception{
        File temp=new File(file.getParentFile(),file.getName()+".tmp");
        try(FileOutputStream out=new FileOutputStream(temp)){out.write(bytes);out.getFD().sync();}
        Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    public static synchronized void enqueue(Context c,File first,File second,String caption,String capturedAt)throws Exception{
        enqueue(c,first,second,caption,capturedAt,1,"top_left");
    }
    public static synchronized void enqueue(Context c,File first,File second,String caption,String capturedAt,int mainPhoto,String corner)throws Exception{
        if(pending(c)>=64)throw new IOException("64 moments are waiting for delivery. Finish their uploads before adding more.");
        JSONObject config=Vault.load(c);if(config==null||!config.optBoolean("enrolled"))throw new IOException("Pair this phone first.");
        String id=UUID.randomUUID().toString();File folder=new File(queueRoot(c),"."+id);
        if(!folder.mkdir())throw new IOException("Could not save this moment.");
        try{
            boolean preserveMetadata=Vault.prefs(c).getBoolean("preserve_metadata",true);
            ImageTools.prepare(first,id,1,preserveMetadata);if(second!=null)ImageTools.prepare(second,id,2,preserveMetadata);
            JSONObject meta=new JSONObject().put("version",1).put("id",id).put("author_name",config.getString("name"))
                .put("slot",config.getInt("slot")).put("caption",caption).put("captured_at",capturedAt)
                .put("metadata_policy",preserveMetadata?"preserve":"strip_private")
                .put("layout",new JSONObject().put("main_photo",second==null?1:mainPhoto).put("inset_corner",corner));
            Files.copy(first.toPath(),new File(folder,"photo1").toPath());
            if(second!=null)Files.copy(second.toPath(),new File(folder,"photo2").toPath());
            for(String name:new String[]{"photo1","photo2"}){
                File saved=new File(folder,name);
                if(saved.exists())try(FileOutputStream out=new FileOutputStream(saved,true)){out.getFD().sync();}
            }
            write(new File(folder,"metadata"),meta.toString().getBytes(StandardCharsets.UTF_8));
            write(new File(folder,"preview"),ImageTools.preview(first,second,mainPhoto,corner));
            write(new File(folder,"record"),new JSONObject().put("id",id).put("version",1).toString().getBytes(StandardCharsets.UTF_8));
            if(!folder.renameTo(new File(queueRoot(c),id)))throw new IOException("Could not save this moment.");
        }finally{if(folder.exists())deleteTree(folder);}
    }
    public static synchronized void sync(Context context,boolean notify)throws Exception{
        Context c=context.getApplicationContext();JSONObject config=Vault.load(c);if(config==null||!config.optBoolean("enrolled"))return;
        PushDelivery.syncRegistration(c,config);
        File[] folders=queueRoot(c).listFiles(f->f.isDirectory()&&!f.getName().startsWith("."));
        if(folders!=null){
            Arrays.sort(folders,Comparator.comparingLong(File::lastModified));
            for(File folder:folders){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                List<Api.Part> parts=new ArrayList<>();
                for(String name:new String[]{"record","metadata","preview","photo1","photo2"}){
                    File f=new File(folder,name);if(f.exists())parts.add(new Api.Part(name,f));
                }
                Api.multipart(Api.relay(config,"/v1/posts"),parts);
                // The relay now durably owns the raw originals and the Immich retry queue.
                deleteTree(folder);
            }
        }
        JSONObject old=state(c),next=Api.get(config,"/v1/state");Set<String> keep=new HashSet<>();
        // Persist the gate before fetching images, so a lock change cannot reuse a
        // previously full preview in the feed or widget during a failed download.
        if(!Vault.prefs(c).edit().putString("state",next.toString()).commit())throw new IOException("Could not save the latest moment.");
        JSONObject incoming=next.optJSONObject("partner");
        if(incoming!=null&&incoming.optBoolean("locked"))new File(c.getFilesDir(),"latest-"+UUID.fromString(incoming.getString("id"))+".jpg").delete();
        PhotoWidget.updateAll(c);
        Notify.onState(c,old,next,notify);
        PushDelivery.observe(c,next);
        UiUpdates.changed();
        for(String which:new String[]{"own","partner"}){
            JSONObject row=next.optJSONObject(which);if(row==null)continue;
            String id=UUID.fromString(row.getString("id")).toString();File file=previewFile(c,row);keep.add(file.getName());
            if(!file.exists()){
                byte[] bytes=Api.media(config,id,"preview");Bitmap check=ImageTools.decode(bytes,16);check.recycle();write(file,bytes);
            }
        }
        if(!Vault.prefs(c).edit().putString("state",next.toString()).putLong("last_sync",System.currentTimeMillis())
            .putString("sync_error","").putString("archive_status",next.optString("archive_status")).commit())throw new IOException("Could not save the latest moment.");
        File[] cached=c.getFilesDir().listFiles(f->f.getName().startsWith("latest-"));
        if(cached!=null)for(File f:cached)if(!keep.contains(f.getName()))f.delete();
        File[] locked=c.getFilesDir().listFiles(f->f.getName().startsWith("locked-"));
        if(locked!=null)for(File f:locked)if(!keep.contains(f.getName()))f.delete();
        PhotoWidget.updateAll(c);
        UiUpdates.changed();
    }
    public static JSONObject metadata(JSONObject config,JSONObject row)throws Exception{return row.getJSONObject("metadata");}
    public static Bitmap picture(Context c,JSONObject row,int max)throws Exception{
        return ImageTools.decode(Files.readAllBytes(previewFile(c,row).toPath()),max);
    }
    static File previewFile(Context c,JSONObject row){String id=UUID.fromString(row.optString("id")).toString();return new File(c.getFilesDir(),(row.optBoolean("locked")?"locked-":"latest-")+id+".jpg");}
    public static String safeError(Exception e){
        if(e instanceof javax.net.ssl.SSLException)return "Could not verify the HTTPS connection.";
        if(e instanceof java.net.UnknownHostException)return "Server address could not be reached.";
        if(e instanceof java.net.SocketTimeoutException)return "Connection timed out; will retry.";
        if(e instanceof Api.Failure)return e.getMessage();
        if(e instanceof IOException&&e.getMessage()!=null)return e.getMessage();
        return "Could not complete synchronization; will retry.";
    }
    public static void failure(Context c,Exception e){Vault.prefs(c).edit().putString("sync_error",safeError(e)).apply();UiUpdates.changed();}
    public static void deleteTree(File f){File[] children=f.listFiles();if(children!=null)for(File child:children)deleteTree(child);f.delete();}
}
