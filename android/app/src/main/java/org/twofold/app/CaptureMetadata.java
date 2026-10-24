package org.twofold.app;

import android.hardware.camera2.*;
import android.location.Location;
import android.os.Build;
import androidx.exifinterface.media.ExifInterface;
import org.json.JSONObject;
import java.io.File;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Adds Twofold context to a new camera JPEG without recompressing its pixels. */
final class CaptureMetadata {
    static void tag(File file, TotalCaptureResult result, long wallTime, Location location,
                    String author, String cameraId, boolean front, String mode, String group,
                    String clockSource, Long sensorTime) throws Exception {
        ExifInterface exif=new ExifInterface(file);
        ZonedDateTime date=Instant.ofEpochMilli(wallTime).atZone(ZoneId.systemDefault());
        String timestamp=date.format(DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss",Locale.ROOT));
        String offset=date.getOffset().getId();if("Z".equals(offset))offset="+00:00";
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL,timestamp);
        exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED,timestamp);
        exif.setAttribute(ExifInterface.TAG_DATETIME,timestamp);
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL,offset);
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED,offset);
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME,offset);
        String subsec=String.format(Locale.ROOT,"%03d",Math.floorMod(wallTime,1000));
        exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,subsec);
        exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,subsec);
        if(exif.getAttribute(ExifInterface.TAG_MAKE)==null)exif.setAttribute(ExifInterface.TAG_MAKE,Build.MANUFACTURER);
        if(exif.getAttribute(ExifInterface.TAG_MODEL)==null)exif.setAttribute(ExifInterface.TAG_MODEL,Build.MODEL);
        exif.setAttribute(ExifInterface.TAG_SOFTWARE,"Twofold 0.1.0-alpha.4");
        exif.setAttribute(ExifInterface.TAG_ARTIST,author);
        exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION,"Twofold · "+author+" · "+(front?"front":"rear")+" camera");
        JSONObject context=new JSONObject().put("app","Twofold").put("capture_group",group)
            .put("author",author).put("camera_id",cameraId).put("camera_facing",front?"front":"rear")
            .put("capture_mode",mode).put("captured_at",Instant.ofEpochMilli(wallTime).toString())
            .put("time_source",clockSource);
        if(sensorTime!=null)context.put("sensor_timestamp_ns",sensorTime);
        if(result!=null){
            Long exposure=result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            Integer iso=result.get(CaptureResult.SENSOR_SENSITIVITY);
            Float aperture=result.get(CaptureResult.LENS_APERTURE),focal=result.get(CaptureResult.LENS_FOCAL_LENGTH);
            if(exposure!=null&&exposure>0){long gcd=gcd(exposure,1_000_000_000L);exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME,(exposure/gcd)+"/"+(1_000_000_000L/gcd));}
            if(iso!=null)exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,iso.toString());
            if(aperture!=null)exif.setAttribute(ExifInterface.TAG_F_NUMBER,aperture.toString());
            if(focal!=null)exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH,focal.toString());
            Integer flash=result.get(CaptureResult.FLASH_STATE);
            if(flash!=null)exif.setAttribute(ExifInterface.TAG_FLASH,(flash==CaptureResult.FLASH_STATE_FIRED||flash==CaptureResult.FLASH_STATE_PARTIAL)?"1":"0");
        }
        if(location!=null){
            exif.setGpsInfo(location);
            if(location.hasAccuracy())exif.setAttribute(ExifInterface.TAG_GPS_H_POSITIONING_ERROR,Float.toString(location.getAccuracy()));
            context.put("location_accuracy_m",location.hasAccuracy()?location.getAccuracy():JSONObject.NULL)
                .put("location_fix_at",Instant.ofEpochMilli(location.getTime()).toString());
        }
        // EXIF UserComment's ASCII form remains readable by other metadata tools.
        StringBuilder ascii=new StringBuilder();
        for(char c:context.toString().toCharArray())if(c>127)ascii.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else ascii.append(c);
        exif.setAttribute(ExifInterface.TAG_USER_COMMENT,ascii.toString());
        exif.saveAttributes();
    }
    private static long gcd(long a,long b){while(b!=0){long next=a%b;a=b;b=next;}return a;}
}
