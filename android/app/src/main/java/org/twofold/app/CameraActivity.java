package org.twofold.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.hardware.camera2.*;
import android.hardware.camera2.params.*;
import android.location.*;
import android.media.*;
import android.os.*;
import android.util.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;

/** Foreground-only Camera2 capture. No Google services or camera framework dependency. */
public final class CameraActivity extends Activity {
    private static final int CAMERA_PERMISSION=401,LOCATION_PERMISSION=402;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler worker;
    private CameraManager manager;
    static volatile boolean visible;
    private FrameLayout views;
    private TextView status,locationStatus;
    private Button shutter,flip,sequential;
    private Switch geotag;
    private OrientationEventListener orientationListener;
    private volatile int orientation=0,generation;
    private volatile boolean resumed;
    // Camera state below is used only on the camera thread.
    private final List<Lens> lenses=new ArrayList<>();
    private boolean dual,busy,starting;
    private volatile boolean concurrent,forceSequential,singleFront,returning;
    private final Set<Lens> retiring=Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<Runnable> afterRetire=new ArrayList<>();
    private int sequentialIndex;
    private String author="",group="";
    private Location shotLocation;
    private long shutterWall;
    // Location callbacks and UI state stay on the main thread.
    private LocationManager locations;
    private Location latestLocation;
    private boolean locationEnabled,locationListening;
    private int locationGeneration;
    private final LocationListener locationListener=new LocationListener(){
        @Override public void onLocationChanged(Location location){if(resumed&&locationEnabled&&fresh(location)){latestLocation=new Location(location);stopLocation();showLocation();refreshLocationLater();}}
        @Override public void onProviderEnabled(String provider){showLocation();}
        @Override public void onProviderDisabled(String provider){latestLocation=null;stopLocation();showLocation();}
        @Override public void onStatusChanged(String provider,int value,Bundle extras){}
    };
    private final class Lens {
        String id;boolean front,opening,closing,ready,saved;
        CameraCharacteristics properties;
        TextureView view;Size previewSize,jpegSize;
        Surface preview;ImageReader reader;CameraDevice device;CameraCaptureSession session;
        Runnable afterClose;
        byte[] jpeg;TotalCaptureResult result;
        File file;long wallTime;Long sensorTime;String clockSource="capture_callback";
        int previewFrames;
    }

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE|WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        dual=getIntent().getBooleanExtra("dual",true);
        forceSequential=state!=null&&state.getBoolean("sequential");
        singleFront=state!=null?state.getBoolean("front"):getIntent().getBooleanExtra("front",false);
        try{author=Vault.load(this).optString("name","");}catch(Exception ignored){}
        manager=getSystemService(CameraManager.class);locations=getSystemService(LocationManager.class);
        thread=new HandlerThread("twofold-camera");thread.start();worker=new Handler(thread.getLooper());
        orientationListener=new OrientationEventListener(this){@Override public void onOrientationChanged(int degrees){if(degrees!=ORIENTATION_UNKNOWN)orientation=((degrees+45)/90*90)%360;}};
        buildUi();
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView label(String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(Color.WHITE);t.setPadding(0,dp(5),0,dp(5));return t;}
    private Button button(LinearLayout parent,String value,Runnable action){Button b=new Button(this);b.setAllCaps(false);b.setText(value);parent.addView(b,new LinearLayout.LayoutParams(-1,dp(52)));b.setOnClickListener(v->action.run());return b;}
    private void buildUi(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(MainActivity.PAPER);root.setPadding(dp(16),dp(12),dp(16),dp(12));
        root.setOnApplyWindowInsetsListener((v,insets)->{
            if(Build.VERSION.SDK_INT>=30){Insets i=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(dp(16),i.top+dp(8),dp(16),i.bottom+dp(8));}
            else v.setPadding(dp(16),insets.getSystemWindowInsetTop()+dp(8),dp(16),insets.getSystemWindowInsetBottom()+dp(8));return insets;
        });
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton close=new ImageButton(this);close.setImageDrawable(new Icons("close",MainActivity.INK));close.setBackgroundColor(Color.TRANSPARENT);close.setPadding(dp(13),dp(13),dp(13),dp(13));close.setContentDescription("Close camera");header.addView(close,new LinearLayout.LayoutParams(dp(48),dp(48)));close.setOnClickListener(v->finish());
        TextView heading=label("Your moment",22);heading.setGravity(Gravity.CENTER);header.addView(heading,new LinearLayout.LayoutParams(0,dp(52),1));header.addView(new View(this),new LinearLayout.LayoutParams(dp(48),1));root.addView(header);
        status=label("Opening camera…",14);root.addView(status);
        views=new FrameLayout(this);views.setBackgroundColor(MainActivity.PAPER);root.addView(views,new LinearLayout.LayoutParams(-1,0,1));
        geotag=new Switch(this);geotag.setText("Save location");geotag.setTextColor(Color.WHITE);
        locationEnabled=Vault.prefs(this).getBoolean("camera_geotag",false)&&Vault.prefs(this).getBoolean("preserve_metadata",true);
        geotag.setChecked(locationEnabled);geotag.setEnabled(Vault.prefs(this).getBoolean("preserve_metadata",true));root.addView(geotag);
        locationStatus=label("",12);root.addView(locationStatus);
        geotag.setOnCheckedChangeListener((v,checked)->{
            locationEnabled=checked;Vault.prefs(this).edit().putBoolean("camera_geotag",checked).apply();
            if(checked&&!locationPermission())new AlertDialog.Builder(this).setTitle("Add location to your photos?")
                .setMessage("Twofold gets a satellite GPS fix only while this camera screen is open, then stops. Precise location permission is needed. Tags go into the JPEGs shared with your partner and saved in Immich.")
                .setPositiveButton("Allow location",(d,w)->requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},LOCATION_PERMISSION))
                .setNegativeButton("Keep it off",(d,w)->geotag.setChecked(false)).setOnCancelListener(d->geotag.setChecked(false)).show();
            updateLocation();
        });
        shutter=button(root,dual?"Capture moment":"Take photo",this::take);android.graphics.drawable.GradientDrawable shutterStyle=new android.graphics.drawable.GradientDrawable();shutterStyle.setColor(MainActivity.ACCENT);shutterStyle.setCornerRadius(dp(28));shutter.setBackground(shutterStyle);shutter.setTextColor(MainActivity.PAPER);shutter.setEnabled(false);
        flip=button(root,"Flip camera",()->{singleFront=!singleFront;initialize();});flip.setVisibility(dual?View.GONE:View.VISIBLE);
        sequential=button(root,"Use sequential capture",()->{forceSequential=true;initialize();});sequential.setVisibility(View.GONE);
        setContentView(root);root.requestApplyInsets();showLocation();
    }
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putBoolean("sequential",forceSequential);out.putBoolean("front",singleFront);}
    @Override protected void onResume(){
        super.onResume();visible=true;resumed=true;orientationListener.enable();updateLocation();
        if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)initialize();
        else requestPermissions(new String[]{Manifest.permission.CAMERA},CAMERA_PERMISSION);
    }
    @Override protected void onPause(){
        visible=false;resumed=false;orientationListener.disable();stopLocation();worker.post(()->{generation++;busy=false;retireAll(null);});super.onPause();
    }
    @Override protected void onDestroy(){
        ui.removeCallbacksAndMessages(null);worker.post(()->retireAll(()->thread.quitSafely()));super.onDestroy();
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){
        super.onRequestPermissionsResult(code,permissions,results);
        if(code==CAMERA_PERMISSION){
            if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED){if(resumed)initialize();}
            else{status.setText("Camera access is needed to take photos. You can still select a saved JPEG in Twofold.");shutter.setEnabled(false);}
        }else if(code==LOCATION_PERMISSION){
            if(!locationPermission()){geotag.setChecked(false);Toast.makeText(this,"Precise location is needed for satellite GPS tags. The camera still works without GPS.",Toast.LENGTH_LONG).show();}updateLocation();
        }
    }
    private boolean active(int token){return resumed&&!isFinishing()&&token==generation;}
    private void initialize(){
        shutter.setText(dual?"Capture moment":"Take photo");shutter.setOnClickListener(v->take());
        geotag.setEnabled(Vault.prefs(this).getBoolean("preserve_metadata",true));
        shutter.setEnabled(false);flip.setEnabled(false);sequential.setVisibility(View.GONE);status.setText("Checking your cameras…");
        worker.post(()->{
            int token=++generation;busy=false;starting=false;
            retireAll(()->{if(active(token))discover(token);});
        });
    }
    private void retireAll(Runnable done){
        List<Lens> old=new ArrayList<>(lenses);lenses.clear();
        if(done!=null)afterRetire.add(done);
        retiring.addAll(old);
        for(Lens lens:old)close(lens,()->{if(!returning&&lens.file!=null)lens.file.delete();retiring.remove(lens);retired();});
        retired();
    }
    private void retired(){if(retiring.isEmpty()){List<Runnable> actions=new ArrayList<>(afterRetire);afterRetire.clear();for(Runnable action:actions)action.run();}}
    private void close(Lens lens,Runnable after){
        Runnable previous=lens.afterClose;lens.afterClose=()->{if(previous!=null)previous.run();if(after!=null)after.run();};
        lens.ready=false;lens.closing=true;
        if(lens.session!=null){lens.session.close();lens.session=null;}
        if(lens.reader!=null){lens.reader.close();lens.reader=null;}
        if(lens.preview!=null){lens.preview.release();lens.preview=null;}
        if(lens.device!=null)lens.device.close();else if(!lens.opening)closed(lens);
    }
    private void closed(Lens lens){lens.opening=false;lens.device=null;Runnable action=lens.afterClose;lens.afterClose=null;if(action!=null)action.run();}
    private Lens lens(String id)throws CameraAccessException{Lens l=new Lens();l.id=id;l.properties=manager.getCameraCharacteristics(id);l.front=Objects.equals(l.properties.get(CameraCharacteristics.LENS_FACING),CameraCharacteristics.LENS_FACING_FRONT);return l;}
    private void discover(int token){
        try{
            Map<String,Lens> available=new LinkedHashMap<>();String rear=null,front=null;
            for(String id:manager.getCameraIdList()){
                Lens l=lens(id);if(l.properties.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)==null)continue;
                Integer facing=l.properties.get(CameraCharacteristics.LENS_FACING);
                if(Objects.equals(facing,CameraCharacteristics.LENS_FACING_BACK)){available.put(id,l);if(rear==null)rear=id;}
                else if(Objects.equals(facing,CameraCharacteristics.LENS_FACING_FRONT)){available.put(id,l);if(front==null)front=id;}
            }
            concurrent=false;
            if(dual&&rear!=null&&front!=null&&Build.VERSION.SDK_INT>=30&&!forceSequential){
                outer:for(Set<String> pair:manager.getConcurrentCameraIds())for(String a:pair)for(String b:pair){
                    Lens x=available.get(a),y=available.get(b);
                    if(x!=null&&y!=null&&!x.front&&y.front){rear=a;front=b;concurrent=true;break outer;}
                }
            }
            if(dual){
                if(rear==null||front==null)throw new IOException("This phone needs both a front and rear camera for a pair. Take a single photo instead.");
                lenses.add(available.get(rear));lenses.add(available.get(front));
            }else{
                String chosen=singleFront?front:rear;if(chosen==null)chosen=singleFront?rear:front;
                if(chosen==null)throw new IOException("No usable camera is available.");lenses.add(available.get(chosen));
            }
            for(Lens l:lenses){
                StreamConfigurationMap map=l.properties.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                l.jpegSize=chooseSize(map.getOutputSizes(ImageFormat.JPEG),concurrent?1920:4096,concurrent?1440:3072);
                l.previewSize=choosePreview(map.getOutputSizes(SurfaceTexture.class),l.jpegSize);
            }
            sequentialIndex=0;
            List<Lens> current=new ArrayList<>(lenses);
            ui.post(()->{if(!active(token))return;views.removeAllViews();
                for(Lens l:current){
                    FrameLayout box=new FrameLayout(this);android.graphics.drawable.GradientDrawable outline=new android.graphics.drawable.GradientDrawable();outline.setColor(MainActivity.SURFACE);outline.setCornerRadius(dp(20));if(dual&&l.front)outline.setStroke(dp(2),Color.WHITE);box.setBackground(outline);box.setClipToOutline(true);if(dual&&l.front)box.setPadding(dp(2),dp(2),dp(2),dp(2));
                    l.view=new TextureView(this);box.addView(l.view,new FrameLayout.LayoutParams(-1,-1));
                    TextView tag=label(l.front?"Selfie":"Rear",12);tag.setPadding(dp(8),dp(3),dp(8),dp(3));tag.setBackgroundColor(0x88000000);FrameLayout.LayoutParams badge=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.START);badge.setMargins(dp(8),0,0,dp(8));box.addView(tag,badge);
                    FrameLayout.LayoutParams placement=dual&&l.front?new FrameLayout.LayoutParams(dp(108),dp(144),Gravity.TOP|Gravity.END):new FrameLayout.LayoutParams(-1,-1);
                    if(dual&&l.front)placement.setMargins(dp(12),dp(12),dp(12),0);views.addView(box,placement);
                    l.view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){
                        public void onSurfaceTextureAvailable(SurfaceTexture t,int w,int h){transform(l);maybeOpen(token,current);}
                        public void onSurfaceTextureSizeChanged(SurfaceTexture t,int w,int h){transform(l);}
                        public boolean onSurfaceTextureDestroyed(SurfaceTexture t){return true;}
                        public void onSurfaceTextureUpdated(SurfaceTexture t){}
                    });
                }
                status.setText(dual?(concurrent?"Front + rear together":"Two quick shots · rear, then selfie"):"Single photo · "+(current.get(0).front?"front":"rear")+" camera");
                if(dual&&!concurrent)views.getChildAt(1).setAlpha(0.45f);
                flip.setEnabled(true);maybeOpen(token,current);
            });
        }catch(Exception e){fail(token,e.getMessage()==null?"Could not read camera capabilities.":e.getMessage());}
    }
    private static Size chooseSize(Size[] choices,int maxWidth,int maxHeight)throws IOException{
        if(choices==null||choices.length==0)throw new IOException("This camera does not offer JPEG photos.");
        Size best=null,smallest=choices[0];
        for(Size s:choices){if(area(s)<area(smallest))smallest=s;if(Math.max(s.getWidth(),s.getHeight())<=maxWidth&&Math.min(s.getWidth(),s.getHeight())<=maxHeight&&(best==null||area(s)>area(best)))best=s;}
        return best==null?smallest:best;
    }
    private static long area(Size s){return (long)s.getWidth()*s.getHeight();}
    private static Size choosePreview(Size[] choices,Size jpeg)throws IOException{
        Size best=null;double aspect=(double)jpeg.getWidth()/jpeg.getHeight();
        if(choices!=null)for(Size s:choices)if(s.getWidth()<=1280&&s.getHeight()<=720&&Math.abs((double)s.getWidth()/s.getHeight()-aspect)<0.03&&(best==null||area(s)>area(best)))best=s;
        return best==null?chooseSize(choices,1280,720):best;
    }
    private void transform(Lens l){
        if(l.view==null||l.previewSize==null)return;int w=l.view.getWidth(),h=l.view.getHeight();if(w==0||h==0)return;
        int sensor=Optional.ofNullable(l.properties.get(CameraCharacteristics.SENSOR_ORIENTATION)).orElse(90);
        int rotation=getWindowManager().getDefaultDisplay().getRotation()*90;
        int relative=(sensor-rotation*(l.front?1:-1)+360)%360;
        float pw=relative%180==0?l.previewSize.getWidth():l.previewSize.getHeight(),ph=relative%180==0?l.previewSize.getHeight():l.previewSize.getWidth();
        // TextureView already compensates sensor orientation. Undo its stretching,
        // compensate display rotation, then fit-center without cropping the preview.
        float originalWidth=sensor%180==0?l.previewSize.getWidth():l.previewSize.getHeight();
        float originalHeight=sensor%180==0?l.previewSize.getHeight():l.previewSize.getWidth();
        Matrix matrix=new Matrix();matrix.setScale(originalWidth/w,originalHeight/h,w/2f,h/2f);
        matrix.postRotate(-rotation,w/2f,h/2f);float scale=Math.min(w/pw,h/ph);matrix.postScale(scale*(l.front?-1:1),scale,w/2f,h/2f);l.view.setTransform(matrix);
    }
    private void maybeOpen(int token,List<Lens> current){
        // TextureView availability belongs to the UI thread.
        if(!active(token)||current.isEmpty())return;
        for(Lens l:current)if(l.view==null||!l.view.isAvailable())return;
        worker.post(()->{
            if(!active(token)||starting)return;starting=true;
            try{
                for(Lens l:lenses)prepare(l,token);
                if(concurrent&&Build.VERSION.SDK_INT>=30){
                    if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){fail(token,"Camera permission is needed. Allow it in Android app settings.");return;}
                    Map<String,SessionConfiguration> sessions=new HashMap<>();for(Lens l:lenses)sessions.put(l.id,configuration(l,token));
                    if(!manager.isConcurrentSessionConfigurationSupported(sessions)){fail(token,"This camera configuration cannot run together. Use sequential capture.");return;}
                }
                if(concurrent)for(Lens l:lenses)open(l,token);else open(lenses.get(0),token);
                worker.postDelayed(()->{if(active(token)&&!busy){for(Lens l:lenses)if((concurrent||l==lenses.get(0))&&!l.ready){fail(token,"Camera preview timed out. Close other camera apps and try again.");return;}}},15000);
            }catch(Exception e){fail(token,"Could not open the cameras. Close other camera apps and try again.");}
        });
    }
    private void prepare(Lens l,int token){
        SurfaceTexture texture=l.view.getSurfaceTexture();if(texture==null)throw new IllegalStateException("Preview is unavailable");
        texture.setDefaultBufferSize(l.previewSize.getWidth(),l.previewSize.getHeight());l.preview=new Surface(texture);
        l.reader=ImageReader.newInstance(l.jpegSize.getWidth(),l.jpegSize.getHeight(),ImageFormat.JPEG,2);
        l.reader.setOnImageAvailableListener(reader->{
            try(Image image=reader.acquireNextImage()){
                if(image==null||!active(token)||!busy)return;ByteBuffer data=image.getPlanes()[0].getBuffer();
                if(data.remaining()>15*1024*1024)throw new IOException("Camera photo exceeds 15 MiB.");
                l.jpeg=new byte[data.remaining()];data.get(l.jpeg);saveIfComplete(l,token);
            }catch(Exception e){fail(token,"Could not save the captured JPEG. Try again.");}
        },worker);
    }
    @SuppressLint("MissingPermission") private void open(Lens l,int token)throws CameraAccessException{
        l.opening=true;l.closing=false;
        try{manager.openCamera(l.id,new CameraDevice.StateCallback(){
            @Override public void onOpened(CameraDevice device){
                l.opening=false;l.device=device;if(!active(token)||l.closing){device.close();return;}
                if(concurrent){for(Lens other:lenses)if(other.device==null)return;for(Lens other:lenses)configure(other,token);}else configure(l,token);
            }
            @Override public void onDisconnected(CameraDevice device){l.device=device;device.close();if(active(token))fail(token,"Camera disconnected. Try again.");}
            @Override public void onError(CameraDevice device,int error){l.device=device;device.close();if(active(token))fail(token,"Camera unavailable. Close other camera apps, or use sequential capture.");}
            @Override public void onClosed(CameraDevice device){closed(l);}
        },worker);}catch(CameraAccessException|RuntimeException e){l.opening=false;throw e;}
    }
    private CameraCaptureSession.StateCallback sessionCallback(Lens l,int token){return new CameraCaptureSession.StateCallback(){
        @Override public void onConfigured(CameraCaptureSession session){
            if(!active(token)||l.closing){session.close();return;}l.session=session;
            try{
                CaptureRequest.Builder request=l.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);request.addTarget(l.preview);auto(request,l);
                session.setRepeatingRequest(request.build(),new CameraCaptureSession.CaptureCallback(){
                    @Override public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest r,TotalCaptureResult result){
                        if(!active(token)||l.ready)return;
                        Integer ae=result.get(CaptureResult.CONTROL_AE_STATE),af=result.get(CaptureResult.CONTROL_AF_STATE);
                        boolean settled=(ae==null||ae==CaptureResult.CONTROL_AE_STATE_CONVERGED||ae==CaptureResult.CONTROL_AE_STATE_LOCKED||ae==CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED)
                            &&(af==null||af!=CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN&&af!=CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN);
                        if(++l.previewFrames<45&&!settled)return;l.ready=true;
                        if(busy&&!concurrent&&sequentialIndex==1){ui.post(()->{if(active(token)&&views.getChildCount()>1)views.getChildAt(1).setAlpha(1f);});worker.postDelayed(()->capture(l,token),600);}
                        else ready(token);
                    }
                },worker);
            }catch(Exception e){fail(token,"Could not start the camera preview.");}
        }
        @Override public void onConfigureFailed(CameraCaptureSession session){session.close();if(active(token))fail(token,"These camera streams cannot run together. Try sequential capture.");}
    };}
    @TargetApi(28) private SessionConfiguration configuration(Lens l,int token){
        return new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,Arrays.asList(new OutputConfiguration(l.preview),new OutputConfiguration(l.reader.getSurface())),r->worker.post(r),sessionCallback(l,token));
    }
    private void configure(Lens l,int token){
        try{if(Build.VERSION.SDK_INT>=28)l.device.createCaptureSession(configuration(l,token));else l.device.createCaptureSession(Arrays.asList(l.preview,l.reader.getSurface()),sessionCallback(l,token),worker);}
        catch(Exception e){fail(token,"Could not configure the camera. Try sequential capture.");}
    }
    private void auto(CaptureRequest.Builder request,Lens lens){
        request.set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO);request.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON);
        int[] modes=lens.properties.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        int af=CaptureRequest.CONTROL_AF_MODE_OFF;if(modes!=null)for(int m:modes)if(m==CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)af=m;
        request.set(CaptureRequest.CONTROL_AF_MODE,af);
        Range<Integer>[] ranges=lens.properties.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        Range<Integer> chosen=null;if(ranges!=null)for(Range<Integer> r:ranges)if(r.getUpper()<=30&&(chosen==null||r.getUpper()>chosen.getUpper()||r.getUpper().equals(chosen.getUpper())&&r.getLower()<chosen.getLower()))chosen=r;
        if(chosen!=null)request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,chosen);
    }
    private void ready(int token){
        if(concurrent)for(Lens l:lenses)if(!l.ready)return;
        ui.post(()->{if(active(token)){shutter.setEnabled(true);flip.setEnabled(true);}});
    }
    private void take(){
        if(locationEnabled&&!fresh(latestLocation)){
            new AlertDialog.Builder(this).setTitle("Location is not ready")
                .setMessage("Wait for a location fix to include GPS, or take this moment without location tags.")
                .setNegativeButton("Keep waiting",null).setPositiveButton("Take without GPS",(d,w)->takeNow()).show();return;
        }
        takeNow();
    }
    private void takeNow(){
        shutter.setEnabled(false);flip.setEnabled(false);geotag.setEnabled(false);sequential.setVisibility(View.GONE);
        final Location fix=locationEnabled&&locationPermission()&&fresh(latestLocation)?new Location(latestLocation):null;
        worker.post(()->{
            int token=generation;if(!active(token)||busy||lenses.isEmpty())return;
            busy=true;group=UUID.randomUUID().toString();shotLocation=fix;shutterWall=System.currentTimeMillis();
            ui.post(()->status.setText(dual?(concurrent?"Capturing both cameras…":"Capturing rear, then front…"):"Taking photo…"));
            worker.postDelayed(()->{if(active(token)&&busy)fail(token,"Capture timed out. No partial pair was sent. Try again.");},20000);
            if(concurrent)for(Lens l:lenses)capture(l,token);else capture(lenses.get(0),token);
        });
    }
    private void capture(Lens l,int token){
        if(!active(token)||!busy)return;
        try{
            CaptureRequest.Builder request=l.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);auto(request,l);request.addTarget(l.reader.getSurface());request.set(CaptureRequest.JPEG_QUALITY,(byte)92);
            int sensor=Optional.ofNullable(l.properties.get(CameraCharacteristics.SENSOR_ORIENTATION)).orElse(90);
            request.set(CaptureRequest.JPEG_ORIENTATION,(sensor+(l.front?-orientation:orientation)+360)%360);
            if(shotLocation!=null)request.set(CaptureRequest.JPEG_GPS_LOCATION,shotLocation);
            l.session.capture(request.build(),new CameraCaptureSession.CaptureCallback(){
                @Override public void onCaptureStarted(CameraCaptureSession session,CaptureRequest r,long time,long frame){
                    l.sensorTime=time;l.wallTime=System.currentTimeMillis();
                    Integer source=l.properties.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
                    if(Objects.equals(source,CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME)){
                        long elapsed=SystemClock.elapsedRealtimeNanos()-time;if(elapsed>=0&&elapsed<10_000_000_000L){l.wallTime-=elapsed/1_000_000;l.clockSource="sensor_realtime";}
                    }
                }
                @Override public void onCaptureCompleted(CameraCaptureSession session,CaptureRequest r,TotalCaptureResult result){if(active(token)&&busy){try{l.result=result;saveIfComplete(l,token);}catch(Exception e){fail(token,"Could not write photo metadata. No partial pair was sent.");}}}
                @Override public void onCaptureFailed(CameraCaptureSession session,CaptureRequest r,CaptureFailure failure){if(active(token))fail(token,"One camera could not take its photo. No partial pair was sent.");}
            },worker);
        }catch(Exception e){fail(token,"Could not capture the photo. Try again.");}
    }
    private void saveIfComplete(Lens l,int token)throws Exception{
        if(l.jpeg==null||l.result==null||l.saved||!active(token))return;
        File directory=new File(getCacheDir(),"camera");if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Camera storage is unavailable.");
        l.file=new File(directory,"capture-"+UUID.randomUUID()+".jpg");
        try(FileOutputStream out=new FileOutputStream(l.file)){out.write(l.jpeg);out.getFD().sync();}l.jpeg=null;
        CaptureMetadata.tag(l.file,l.result,l.wallTime==0?shutterWall:l.wallTime,shotLocation,author,l.id,l.front,dual?(concurrent?"concurrent":"sequential"):"single",group,l.clockSource,l.sensorTime);
        ImageTools.validate(l.file);l.saved=true;
        if(dual&&!concurrent&&sequentialIndex==0){sequentialIndex=1;close(l,()->{try{if(active(token))open(lenses.get(1),token);}catch(Exception e){fail(token,"Could not open the front camera. No partial pair was sent.");}});return;}
        for(Lens other:lenses)if(!other.saved)return;
        busy=false;
        Intent result=new Intent().putExtra("first",lenses.get(0).file.getPath()).putExtra("captured_at",Instant.ofEpochMilli(shutterWall).toString());
        if(dual)result.putExtra("second",lenses.get(1).file.getPath());
        ui.post(()->{if(active(token)){returning=true;setResult(RESULT_OK,result);finish();}});
    }
    private void fail(int token,String message){
        if(!active(token))return;busy=false;starting=true;int next=++generation;
        retireAll(null);
        ui.post(()->{if(!active(next))return;status.setText(message);shutter.setText("Try again");shutter.setEnabled(true);shutter.setOnClickListener(v->{shutter.setText(dual?"Capture moment":"Take photo");shutter.setOnClickListener(w->take());geotag.setEnabled(Vault.prefs(this).getBoolean("preserve_metadata",true));initialize();});
            flip.setEnabled(false);sequential.setVisibility(dual&&!forceSequential?View.VISIBLE:View.GONE);
        });
    }
    private boolean locationPermission(){return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    private static boolean fresh(Location fix){if(fix==null)return false;long age=SystemClock.elapsedRealtimeNanos()-fix.getElapsedRealtimeNanos();return age>=0&&age<=120_000_000_000L;}
    @SuppressLint("MissingPermission") private void updateLocation(){
        stopLocation();latestLocation=null;
        if(!resumed||!locationEnabled||!locationPermission()){showLocation();return;}
        for(String provider:new String[]{LocationManager.GPS_PROVIDER})try{
            if(locations.isProviderEnabled(provider)){
                Location known=locations.getLastKnownLocation(provider);if(fresh(known)){latestLocation=known;refreshLocationLater();continue;}
                locations.requestLocationUpdates(provider,5000,0,locationListener,Looper.getMainLooper());locationListening=true;
            }
        }catch(IllegalArgumentException|SecurityException ignored){}
        showLocation();int token=locationGeneration;
        ui.postDelayed(()->{if(token==locationGeneration&&locationListening){stopLocation();showLocation();if(resumed&&locationEnabled&&!fresh(latestLocation))locationStatus.setText("No GPS fix. Toggle location to retry, or take without GPS.");}},45000);
    }
    private void refreshLocationLater(){
        if(latestLocation==null)return;int token=locationGeneration;
        long age=(SystemClock.elapsedRealtimeNanos()-latestLocation.getElapsedRealtimeNanos())/1_000_000;
        ui.postDelayed(()->{if(token==locationGeneration&&resumed&&locationEnabled)updateLocation();},Math.max(1000,120001-age));
    }
    private void stopLocation(){locationGeneration++;if(locationListening){try{locations.removeUpdates(locationListener);}catch(SecurityException ignored){}locationListening=false;}}
    private void showLocation(){
        if(!Vault.prefs(this).getBoolean("preserve_metadata",true)&&!locationEnabled)locationStatus.setText("Enable full metadata in Settings to add GPS.");
        else if(!locationEnabled)locationStatus.setText("Location off");
        else if(!locationPermission())locationStatus.setText("Allow precise location for GPS tags.");
        else if(fresh(latestLocation))locationStatus.setText("GPS ready"+(latestLocation.hasAccuracy()?" · ±"+Math.round(latestLocation.getAccuracy())+" m":""));
        else locationStatus.setText("Finding location…");
    }
}
