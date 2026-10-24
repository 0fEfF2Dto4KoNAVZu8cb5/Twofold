package org.twofold.app;

import android.graphics.*;
import androidx.exifinterface.media.ExifInterface;
import java.io.*;

public final class ImageTools {
    public static void validate(File file) throws Exception {
        if (file.length() > 15*1024*1024 || file.length() < 4) throw new IOException("Use a JPEG smaller than 15 MiB.");
        try (InputStream input = new FileInputStream(file)) {
            if (input.read() != 0xff || input.read() != 0xd8) throw new IOException("Choose a JPEG photo.");
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth < 1 || bounds.outHeight < 1 || (long)bounds.outWidth*bounds.outHeight > 60_000_000)
            throw new IOException("Use a standard-resolution photo, up to 60 megapixels.");
    }
    public static void prepare(File file, String postId, int index, boolean preserveMetadata) throws Exception {
        validate(file);
        if (!preserveMetadata) sanitize(file, postId, index);
    }
    public static void sanitize(File file, String postId, int index) throws Exception {
        validate(file);
        ExifInterface exif = new ExifInterface(file);
        String[] gps = {"GPSAltitude", "GPSAltitudeRef", "GPSAreaInformation", "GPSDateStamp", "GPSDestBearing", "GPSDestBearingRef",
            "GPSDestDistance", "GPSDestDistanceRef", "GPSDestLatitude", "GPSDestLatitudeRef", "GPSDestLongitude", "GPSDestLongitudeRef",
            "GPSDifferential", "GPSDOP", "GPSImgDirection", "GPSImgDirectionRef", "GPSLatitude", "GPSLatitudeRef", "GPSLongitude",
            "GPSLongitudeRef", "GPSMapDatum", "GPSMeasureMode", "GPSProcessingMethod", "GPSSatellites", "GPSSpeed", "GPSSpeedRef",
            "GPSStatus", "GPSTimeStamp", "GPSTrack", "GPSTrackRef", "GPSVersionID"};
        for (String tag : gps) exif.setAttribute(tag, null);
        exif.setAttribute("Xmp", null);
        exif.setAttribute("MakerNote", null);
        // Distinct moments remain distinct in Immich even if the same JPEG is chosen again.
        // Retries reuse these exact bytes, so Immich's checksum deduplication still works.
        exif.setAttribute(ExifInterface.TAG_USER_COMMENT, "Twofold moment " + postId + " photo " + index);
        exif.saveAttributes();
    }
    public static Bitmap scaled(File file, int max) throws Exception {
        BitmapFactory.Options opts = new BitmapFactory.Options(); opts.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(file.getPath(), opts);
        opts.inSampleSize=1;
        while (Math.max(opts.outWidth,opts.outHeight)/opts.inSampleSize > max*2) opts.inSampleSize*=2;
        opts.inJustDecodeBounds=false;
        Bitmap bitmap=BitmapFactory.decodeFile(file.getPath(),opts);
        if (bitmap == null) throw new IOException("Could not read photo.");
        int orientation = new ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);
        Matrix m = new Matrix();
        switch (orientation) {
            case 2: m.setScale(-1,1); break;
            case 3: m.setRotate(180); break;
            case 4: m.setScale(1,-1); break;
            case 5: m.setRotate(90); m.postScale(-1,1); break;
            case 6: m.setRotate(90); break;
            case 7: m.setRotate(-90); m.postScale(-1,1); break;
            case 8: m.setRotate(-90); break;
        }
        Bitmap rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),m,true);
        if(rotated!=bitmap) bitmap.recycle();
        float scale=Math.min(1f,(float)max/Math.max(rotated.getWidth(),rotated.getHeight()));
        Bitmap result=Bitmap.createScaledBitmap(rotated,Math.max(1,Math.round(rotated.getWidth()*scale)),Math.max(1,Math.round(rotated.getHeight()*scale)),true);
        if(result!=rotated)rotated.recycle();
        return result;
    }
    public static byte[] preview(File first, File second) throws Exception {
        return preview(first,second,1,"top_left");
    }
    public static byte[] preview(File first,File second,int mainPhoto,String corner)throws Exception{
        File main=mainPhoto==2&&second!=null?second:first,other=main==first?second:first;
        Bitmap result=scaled(main,960);
        if(second!=null) {
            Bitmap mutable=result.copy(Bitmap.Config.ARGB_8888,true); result.recycle(); result=mutable;
            Bitmap inset=scaled(other,320);
            Canvas canvas=new Canvas(result); Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
            RectF rect=insetRect(result.getWidth(),result.getHeight(),inset.getWidth(),inset.getHeight(),corner);
            float border=Math.max(2,result.getWidth()/240f),radius=Math.max(3,result.getWidth()/80f);
            RectF frame=new RectF(rect);frame.inset(-border,-border);paint.setColor(Color.WHITE);canvas.drawRoundRect(frame,radius+border,radius+border,paint);
            Path clip=new Path();clip.addRoundRect(rect,radius,radius,Path.Direction.CW);canvas.save();canvas.clipPath(clip);
            canvas.drawBitmap(inset,null,rect,paint);canvas.restore();inset.recycle();
        }
        ByteArrayOutputStream out=new ByteArrayOutputStream(); result.compress(Bitmap.CompressFormat.JPEG,85,out); result.recycle();
        return out.toByteArray();
    }
    static RectF insetRect(int width,int height,int insetWidth,int insetHeight,String corner){
        float margin=Math.max(2,Math.min(width,height)*0.025f);
        float scale=Math.min(width*0.31f/insetWidth,height*0.37f/insetHeight);
        float w=insetWidth*scale,h=insetHeight*scale;
        float x=corner.endsWith("right")?width-margin-w:margin,y=corner.startsWith("bottom")?height-margin-h:margin;
        return new RectF(x,y,x+w,y+h);
    }
    public static Bitmap decode(byte[] bytes, int max) throws IOException {
        BitmapFactory.Options opts=new BitmapFactory.Options(); opts.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(bytes,0,bytes.length,opts); opts.inSampleSize=1;
        while(Math.max(opts.outWidth,opts.outHeight)/opts.inSampleSize>max*2)opts.inSampleSize*=2;
        opts.inJustDecodeBounds=false;
        Bitmap image=BitmapFactory.decodeByteArray(bytes,0,bytes.length,opts);
        if(image==null)throw new IOException("Could not display photo.");
        float ratio=Math.min(1f,(float)max/Math.max(image.getWidth(),image.getHeight()));
        Bitmap scaled=Bitmap.createScaledBitmap(image,Math.max(1,Math.round(image.getWidth()*ratio)),Math.max(1,Math.round(image.getHeight()*ratio)),true);
        if(scaled!=image)image.recycle(); return scaled;
    }
}
