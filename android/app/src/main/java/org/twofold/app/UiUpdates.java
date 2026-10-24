package org.twofold.app;
import android.os.Handler;
import android.os.Looper;
import java.lang.ref.WeakReference;
/** In-process UI events; no exported broadcast or cross-app IPC is needed. */
public final class UiUpdates {
    private static volatile WeakReference<Runnable> callback=new WeakReference<>(null);
    public static void listen(Runnable value){callback=new WeakReference<>(value);}
    public static void clear(){callback.clear();}
    public static void changed(){Runnable task=callback.get();if(task!=null)new Handler(Looper.getMainLooper()).post(task);}
}
