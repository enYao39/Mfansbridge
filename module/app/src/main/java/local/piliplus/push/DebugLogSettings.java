package local.piliplus.push;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/** Shares the Overview debug-log preference with the injected XMSF and PiliPlus processes. */
final class DebugLogSettings {
    static final String PREFS = "push_state";
    static final String PREF_ENABLED = "debug_logs_enabled";
    static final String AUTHORITY = "local.piliplus.push.debugsettings";
    static final String METHOD_GET_ENABLED = "get_debug_logs_enabled";
    static final String METHOD_GET_RELAY_AUTH_TOKEN = "get_relay_auth_token";
    static final String METHOD_REPORT_MAIN_ACCOUNT = "report_main_account";
    static final String METHOD_SHOULD_READ_ACCOUNT_SNAPSHOT =
            "should_read_account_snapshot";
    static final String METHOD_CONSUME_ACCOUNT_SNAPSHOT_REQUEST =
            "consume_account_snapshot_request";
    static final String METHOD_CLAIM_PUSH_TOKEN_REPORT = "claim_push_token_report";
    static final String METHOD_CLAIM_LINK_SETTINGS_REPORT = "claim_link_settings_report";
    static final String EXTRA_RELAY_AUTH_TOKEN =
            "local.piliplus.push.extra.RELAY_AUTH_TOKEN";
    static final String EXTRA_ACCOUNT_SNAPSHOT_NONCE =
            "local.piliplus.push.extra.ACCOUNT_SNAPSHOT_NONCE";
    static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/settings");

    private static volatile boolean enabled;
    private static ContentObserver remoteObserver;

    private DebugLogSettings() {}

    static void initializeLocal(Context context) {
        if (context == null) return;
        enabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_ENABLED, false);
    }

    static void setEnabled(Context context, boolean value) {
        if (context == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(PREF_ENABLED, value)
                .apply();
        enabled = value;
        context.getContentResolver().notifyChange(CONTENT_URI, null);
    }

    static synchronized void initializeRemote(Context context) {
        if (context == null || remoteObserver != null) return;
        Context appContext = context.getApplicationContext();
        if (appContext == null) appContext = context;
        final Context observerContext = appContext;
        ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                refreshRemote(observerContext);
            }
        };
        try {
            observerContext.getContentResolver().registerContentObserver(
                    CONTENT_URI, false, observer);
            remoteObserver = observer;
        } catch (Throwable ignored) {
            // Keep diagnostics disabled when cross-process settings cannot be reached.
        }
        refreshRemote(observerContext);
    }

    static void log(String tag, String message) {
        if (enabled) Log.i(tag, message);
    }

    private static void refreshRemote(Context context) {
        try {
            ContentResolver resolver = context.getContentResolver();
            Bundle result = resolver.call(CONTENT_URI, METHOD_GET_ENABLED, null, null);
            if (result != null) enabled = result.getBoolean(PREF_ENABLED, false);
        } catch (Throwable ignored) {
            // Preserve the last known value if a transient provider lookup fails.
        }
    }
}
