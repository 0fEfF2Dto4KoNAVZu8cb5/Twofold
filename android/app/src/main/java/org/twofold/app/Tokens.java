package org.twofold.app;
import android.util.Base64;
import java.security.SecureRandom;
public final class Tokens {
    public static String random(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return encode(bytes);}
    public static String encode(byte[] bytes){return Base64.encodeToString(bytes,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);}
    public static byte[] decode(String value){return Base64.decode(value,Base64.URL_SAFE|Base64.NO_WRAP);}
}
