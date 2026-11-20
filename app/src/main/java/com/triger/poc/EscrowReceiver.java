package com.triger.poc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Будильник, который запускает авто-возврат просроченных эскроу-сделок. */
public class EscrowReceiver extends BroadcastReceiver {
    private static final String TAG = "EscrowReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        final PendingResult pendingResult = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                EscrowManager.processDueDeals(app);
            } catch (Throwable t) {
                Log.w(TAG, "escrow expiry failed", t);
            } finally {
                pendingResult.finish();
            }
        }, "EscrowReceiver").start();
    }
}
