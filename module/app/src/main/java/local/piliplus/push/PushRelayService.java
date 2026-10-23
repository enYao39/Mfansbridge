package local.piliplus.push;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Module-owned entry point for MiPush delivery redirected from XMSF. */
public final class PushRelayService extends Service {
    private static final String TAG = "PiliPlusPush";
    private static final String ACTION_RECEIVE = "com.xiaomi.mipush.RECEIVE_MESSAGE";
    private static final int MAX_QUEUED_DELIVERIES = 32;
    private static final ThreadPoolExecutor RELAY_EXECUTOR = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_QUEUED_DELIVERIES), runnable -> {
                Thread thread = new Thread(runnable, "PiliPlusMiPushRelay");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        DebugLogSettings.initializeLocal(this);
        if (intent == null || !ACTION_RECEIVE.equals(intent.getAction())) {
            DebugLogSettings.log(TAG, "relay rejected reason=unexpected_intent");
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        String expectedToken = getSharedPreferences("push_state", MODE_PRIVATE)
                .getString("relay_auth_token", "");
        String suppliedToken = intent.getStringExtra(DebugLogSettings.EXTRA_RELAY_AUTH_TOKEN);
        if (expectedToken.isEmpty() || !expectedToken.equals(suppliedToken)) {
            DebugLogSettings.log(TAG, "relay rejected reason=unauthorized_sender");
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        byte[] payload = intent.getByteArrayExtra("mipush_payload");
        DebugLogSettings.log(TAG, "relay received payload_present=" + (payload != null)
                + " payload_bytes=" + (payload == null ? 0 : payload.length)
                + " from_notification=" + intent.getBooleanExtra("mipush_notified", false));
        Intent received = new Intent(intent);
        try {
            RELAY_EXECUTOR.execute(() -> {
                try {
                    MiPushRelayProcessor.process(getApplicationContext(), received);
                } finally {
                    stopSelf(startId);
                }
            });
        } catch (RejectedExecutionException rejected) {
            DebugLogSettings.log(TAG, "relay rejected reason=queue_full");
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
