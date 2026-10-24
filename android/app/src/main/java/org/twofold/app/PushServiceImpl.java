package org.twofold.app;

import org.unifiedpush.android.connector.PushService;
import org.unifiedpush.android.connector.FailedReason;
import org.unifiedpush.android.connector.data.*;
import static org.unifiedpush.android.connector.ConstantsKt.INSTANCE_DEFAULT;

/** The maintained connector validates distributor callbacks and decrypts RFC8291. */
public final class PushServiceImpl extends PushService {
    @Override public void onNewEndpoint(PushEndpoint endpoint,String instance){if(INSTANCE_DEFAULT.equals(instance))PushDelivery.endpoint(this,endpoint);}
    @Override public void onMessage(PushMessage message,String instance){if(INSTANCE_DEFAULT.equals(instance))PushDelivery.message(this,message);}
    @Override public void onRegistrationFailed(FailedReason reason,String instance){if(INSTANCE_DEFAULT.equals(instance))PushDelivery.unavailable(this,false);}
    @Override public void onUnregistered(String instance){if(INSTANCE_DEFAULT.equals(instance))PushDelivery.unavailable(this,true);}
    @Override public void onTempUnavailable(String instance){if(INSTANCE_DEFAULT.equals(instance))PushDelivery.unavailable(this,false);}
}
