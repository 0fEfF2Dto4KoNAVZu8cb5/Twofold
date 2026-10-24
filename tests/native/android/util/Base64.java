package android.util;
/** Host-test shim only. The Android app uses the platform implementation. */
public final class Base64 {
    public static final int DEFAULT=0, NO_WRAP=2, NO_PADDING=1, URL_SAFE=8;
    public static String encodeToString(byte[] data,int flags){
        java.util.Base64.Encoder encoder=(flags&URL_SAFE)!=0?java.util.Base64.getUrlEncoder():java.util.Base64.getEncoder();
        if((flags&NO_PADDING)!=0)encoder=encoder.withoutPadding();return encoder.encodeToString(data);
    }
    public static byte[] decode(String data,int flags){return ((flags&URL_SAFE)!=0?java.util.Base64.getUrlDecoder():java.util.Base64.getDecoder()).decode(data);}
}
