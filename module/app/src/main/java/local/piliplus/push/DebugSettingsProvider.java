package local.piliplus.push;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

import java.util.UUID;

/** UID-checked IPC bridge for settings and authenticated cross-process requests. */
public final class DebugSettingsProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (DebugLogSettings.METHOD_GET_ENABLED.equals(method)) {
            return isAllowedCaller() ? debugLogsEnabled() : Bundle.EMPTY;
        }
        if (DebugLogSettings.METHOD_GET_RELAY_AUTH_TOKEN.equals(method)) {
            return isXmsfCaller() ? relayAuthToken() : Bundle.EMPTY;
        }
        if (DebugLogSettings.METHOD_REPORT_MAIN_ACCOUNT.equals(method)) {
            return isPiliPlusCaller() ? reportMainAccount(extras) : Bundle.EMPTY;
        }
        if (DebugLogSettings.METHOD_SHOULD_READ_ACCOUNT_SNAPSHOT.equals(method)) {
            return isPiliPlusCaller() ? shouldReadAccountSnapshot() : Bundle.EMPTY;
        }
        if (DebugLogSettings.METHOD_CONSUME_ACCOUNT_SNAPSHOT_REQUEST.equals(method)) {
            return isPiliPlusCaller() ? consumeAccountSnapshotRequest(arg) : Bundle.EMPTY;
        }
        if (DebugLogSettings.METHOD_CLAIM_PUSH_TOKEN_REPORT.equals(method)) {
            return isXmsfCaller() ? claimPendingRequest(arg,
                    "server_binding_pending_request_id",
                    "server_binding_xmsf_claimed_request_id") : Bundle.EMPTY;
        }
        if (DebugLogSettings.METHOD_CLAIM_LINK_SETTINGS_REPORT.equals(method)) {
            return isXmsfCaller() ? claimPendingRequest(arg,
                    "link_settings_pending_request_id",
                    "link_settings_xmsf_claimed_request_id") : Bundle.EMPTY;
        }
        return Bundle.EMPTY;
    }

    private Bundle debugLogsEnabled() {
        boolean enabled = getContext() != null
                && getContext().getSharedPreferences(DebugLogSettings.PREFS,
                        android.content.Context.MODE_PRIVATE)
                        .getBoolean(DebugLogSettings.PREF_ENABLED, false);
        Bundle result = new Bundle();
        result.putBoolean(DebugLogSettings.PREF_ENABLED, enabled);
        return result;
    }

    private synchronized Bundle relayAuthToken() {
        if (getContext() == null) return Bundle.EMPTY;
        android.content.SharedPreferences state = getContext().getSharedPreferences(
                DebugLogSettings.PREFS, android.content.Context.MODE_PRIVATE);
        String token = state.getString("relay_auth_token", "");
        if (token.isEmpty()) {
            token = UUID.randomUUID().toString() + UUID.randomUUID();
            if (!state.edit().putString("relay_auth_token", token).commit()) {
                return Bundle.EMPTY;
            }
        }
        Bundle result = new Bundle();
        result.putString(DebugLogSettings.EXTRA_RELAY_AUTH_TOKEN, token);
        return result;
    }

    private synchronized Bundle reportMainAccount(Bundle extras) {
        Bundle result = new Bundle();
        if (getContext() == null || extras == null) return result;
        String mid = extras.getString("main_account_mid", "");
        String accessKey = extras.getString("main_account_access_key", "");
        String buvid3 = extras.getString("main_account_buvid3", "");
        if (mid == null || accessKey == null || buvid3 == null
                || (!mid.isEmpty() && !mid.matches("[0-9]{1,20}"))
                || accessKey.length() > 4096 || buvid3.length() > 512) {
            result.putBoolean("accepted", false);
            return result;
        }
        MiPushRelayProcessor.updateMainAccount(getContext(), mid, accessKey, buvid3);
        result.putBoolean("accepted", true);
        return result;
    }

    private Bundle shouldReadAccountSnapshot() {
        Bundle result = new Bundle();
        if (getContext() == null) return result;
        android.content.SharedPreferences state = getContext().getSharedPreferences(
                DebugLogSettings.PREFS, android.content.Context.MODE_PRIVATE);
        boolean shouldRead = !state.getBoolean("main_account_state_received", false)
                || state.getBoolean("main_account_snapshot_refresh_pending", false);
        result.putBoolean("should_read", shouldRead);
        return result;
    }

    private synchronized Bundle consumeAccountSnapshotRequest(String nonce) {
        Bundle result = new Bundle();
        if (getContext() == null || nonce == null || nonce.isEmpty()) return result;
        android.content.SharedPreferences state = getContext().getSharedPreferences(
                DebugLogSettings.PREFS, android.content.Context.MODE_PRIVATE);
        String expected = state.getString("main_account_snapshot_request_nonce", "");
        long requestedAt = state.getLong("main_account_snapshot_request_time", 0L);
        long age = System.currentTimeMillis() - requestedAt;
        boolean authorized = nonce.equals(expected) && requestedAt > 0L
                && age >= 0L && age <= 30_000L;
        if (authorized) {
            authorized = state.edit()
                    .remove("main_account_snapshot_request_nonce")
                    .remove("main_account_snapshot_request_time")
                    .commit();
        }
        result.putBoolean("authorized", authorized);
        return result;
    }

    private synchronized Bundle claimPendingRequest(String requestId, String pendingKey,
                                                    String claimedKey) {
        Bundle result = new Bundle();
        if (getContext() == null || requestId == null || requestId.isEmpty()) return result;
        android.content.SharedPreferences state = getContext().getSharedPreferences(
                DebugLogSettings.PREFS, android.content.Context.MODE_PRIVATE);
        String pendingId = state.getString(pendingKey, "");
        String claimedId = state.getString(claimedKey, "");
        boolean authorized = requestId.equals(pendingId) && !requestId.equals(claimedId);
        if (authorized) {
            authorized = state.edit().putString(claimedKey, requestId).commit();
        }
        result.putBoolean("authorized", authorized);
        return result;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        return 0;
    }

    private boolean isAllowedCaller() {
        return hasCallingPackage("local.piliplus.push", "com.xiaomi.xmsf",
                "com.example.piliplus", "com.example.piliplus.debug",
                "com.example.piliplus.dev");
    }

    private boolean isPiliPlusCaller() {
        return hasCallingPackage("com.example.piliplus", "com.example.piliplus.debug",
                "com.example.piliplus.dev");
    }

    private boolean isXmsfCaller() {
        return hasCallingPackage("com.xiaomi.xmsf");
    }

    private boolean hasCallingPackage(String... allowedPackages) {
        if (getContext() == null) return false;
        String[] packages;
        try {
            packages = getContext().getPackageManager()
                    .getPackagesForUid(Binder.getCallingUid());
        } catch (Throwable ignored) {
            return false;
        }
        if (packages == null) return false;
        for (String packageName : packages) {
            for (String allowed : allowedPackages) {
                if (allowed.equals(packageName)) return true;
            }
        }
        return false;
    }
}
