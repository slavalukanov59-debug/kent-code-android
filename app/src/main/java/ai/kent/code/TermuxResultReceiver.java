package ai.kent.code;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Private callback target for the one-shot Termux RUN_COMMAND PendingIntent. */
public final class TermuxResultReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        TermuxBridge.accept(intent);
    }
}
