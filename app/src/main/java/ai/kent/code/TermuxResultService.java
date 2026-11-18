package ai.kent.code;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Private callback target for Termux RUN_COMMAND PendingIntent results. */
public final class TermuxResultService extends Service {
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        TermuxBridge.accept(intent);
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
