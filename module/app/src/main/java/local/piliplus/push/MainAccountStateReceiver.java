package local.piliplus.push;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Receives authenticated server-request results from the MiPushFramework process. */
public final class MainAccountStateReceiver extends BroadcastReceiver {
    private static final String TAG = "PiliPlusPush";
    private static final String ACTION_SERVER_BINDING_RESULT =
            PushTokenReporter.ACTION_RESULT;

    @Override
    public void onReceive(Context context, Intent intent) {
        DebugLogSettings.initializeLocal(context);
        if (intent == null) {
            return;
        }
        if (ACTION_SERVER_BINDING_RESULT.equals(intent.getAction())) {
            handleServerBindingResult(context, intent);
            return;
        }
        if (LinkSettingsReporter.ACTION_RESULT.equals(intent.getAction())) {
            handleLinkSettingsResult(context, intent);
            return;
        }
        // Account snapshots enter through DebugSettingsProvider, which authenticates the
        // calling package UID before accepting access-key data.
    }

    private static void handleLinkSettingsResult(Context context, Intent intent) {
        SharedPreferences state = context.getSharedPreferences("push_state", Context.MODE_PRIVATE);
        String requestId = intent.getStringExtra("link_settings_request_id");
        String pendingId = state.getString("link_settings_pending_request_id", "");
        boolean currentRequest = requestId != null && requestId.equals(pendingId);
        if (!currentRequest) {
            DebugLogSettings.log("PiliPlusPush", "bilibili_link_settings result=ignored reason=stale_request");
            return;
        }
        String result = intent.getStringExtra("link_settings_result");
        if (result == null) result = "failed";
        int operation = intent.getIntExtra("link_settings_operation", -1);
        int httpCode = intent.getIntExtra("link_settings_http_code", -1);
        int apiCode = intent.getIntExtra("link_settings_api_code", Integer.MIN_VALUE);
        int likeValue = intent.getIntExtra("link_settings_like_value", -1);
        int replyValue = intent.getIntExtra("link_settings_reply_value", -1);
        String message = intent.getStringExtra("link_settings_message");
        if (message == null) message = "";

        SharedPreferences.Editor update = state.edit()
                .putString("link_settings_last_result", result)
                .putInt("link_settings_last_operation", operation)
                .putInt("link_settings_http_code", httpCode)
                .putInt("link_settings_api_code", apiCode)
                .putString("link_settings_message", message);
        if ("accepted".equals(result)
                && likeValue >= 0 && likeValue <= 2
                && replyValue >= 0 && replyValue <= 2) {
            update.putInt("link_settings_like_value", likeValue)
                    .putInt("link_settings_reply_value", replyValue)
                    .putBoolean("link_settings_loaded", true)
                    .putLong("link_settings_applied_at", System.currentTimeMillis());
        }
        update.putString("link_settings_state", "accepted".equals(result)
                        ? "ready" : result)
                .remove("link_settings_pending_request_id")
                .remove("link_settings_pending_operation")
                .remove("link_settings_xmsf_claimed_request_id");
        update.apply();
        DebugLogSettings.log("PiliPlusPush", "bilibili_link_settings result=" + result
                + " operation=" + operation + " http_code=" + httpCode
                + " api_code=" + apiCode);
    }

    private static void handleServerBindingResult(Context context, Intent intent) {
        SharedPreferences state = context.getSharedPreferences("push_state", Context.MODE_PRIVATE);
        String requestId = intent.getStringExtra("server_binding_request_id");
        String result = intent.getStringExtra("server_binding_result");
        int event = intent.getIntExtra("server_binding_event", -1);
        String targetMid = intent.getStringExtra("server_binding_target_mid");
        String regId = intent.getStringExtra("server_binding_reg_id");
        String regIdFingerprint = intent.getStringExtra("server_binding_reg_id_fingerprint");
        String authFingerprint = intent.getStringExtra("server_binding_auth_fingerprint");
        String channelFingerprint = intent.getStringExtra("server_binding_channel_fingerprint");
        int httpCode = intent.getIntExtra("server_binding_http_code", -1);
        int apiCode = intent.getIntExtra("server_binding_api_code", Integer.MIN_VALUE);
        String apiMessage = intent.getStringExtra("server_binding_api_message");
        if (apiMessage == null) apiMessage = "";
        boolean accepted = "accepted".equals(result);
        SharedPreferences.Editor update = state.edit()
                .putString("account_binding_last_result", result == null ? "failed" : result)
                .putInt("account_binding_last_event", event)
                .putInt("account_binding_http_code", httpCode)
                .putInt("account_binding_api_code", apiCode)
                .putString("account_binding_api_message", apiMessage);

        String boundMid = state.getString("server_bound_main_mid", "");
        String boundRegFingerprint = state.getString("server_bound_reg_id_fingerprint", "");
        String pendingMid = state.getString("server_binding_pending_mid", "");
        String pendingRegFingerprint = state.getString(
                "server_binding_pending_reg_id_fingerprint", "");
        String pendingAuthFingerprint = state.getString(
                "server_binding_pending_auth_fingerprint", "");
        String pendingChannelFingerprint = state.getString(
                "server_binding_pending_channel_fingerprint", "");
        if (accepted && event == PushTokenReporter.EVENT_LOGIN) {
            update.putString("server_bound_main_mid", targetMid == null ? "" : targetMid)
                    .putString("server_bound_reg_id_fingerprint",
                            regIdFingerprint == null ? "" : regIdFingerprint)
                    .putString("server_bound_reg_id",
                            regId == null ? "" : regId)
                    .putString("server_bound_auth_fingerprint",
                            authFingerprint == null ? "" : authFingerprint)
                    .putString("server_bound_channel_fingerprint",
                            channelFingerprint == null ? "" : channelFingerprint);
        } else if (accepted && event == PushTokenReporter.EVENT_LOGOUT
                && boundMid.equals(targetMid)
                && (boundRegFingerprint.isEmpty()
                || boundRegFingerprint.equals(regIdFingerprint))) {
            update.remove("server_bound_main_mid")
                    .remove("server_bound_reg_id_fingerprint")
                    .remove("server_bound_reg_id")
                    .remove("server_bound_auth_fingerprint")
                    .remove("server_bound_channel_fingerprint");
        }

        boolean currentRequest = requestId != null && requestId.equals(
                state.getString("server_binding_pending_request_id", ""));
        if (currentRequest) {
            update.remove("server_binding_pending_request_id")
                    .remove("server_binding_pending_event")
                    .remove("server_binding_pending_mid")
                    .remove("server_binding_pending_reg_id_fingerprint")
                    .remove("server_binding_pending_auth_fingerprint")
                    .remove("server_binding_pending_channel_fingerprint")
                    .remove("server_binding_xmsf_claimed_request_id");
            if (accepted && event == PushTokenReporter.EVENT_LOGIN) {
                update.putString("account_binding_state", "bound")
                        .remove("account_binding_error");
            } else if (accepted && event == PushTokenReporter.EVENT_LOGOUT) {
                update.putString("account_binding_state",
                        state.getBoolean("main_account_present", false)
                                ? "waiting_registration" : "no_main_account")
                        .remove("account_binding_error");
            } else {
                update.putString("account_binding_state", "failed")
                        .putString("account_binding_error",
                                "http_" + httpCode + "_api_" + apiCode
                                        + (apiMessage.isEmpty() ? "" : " · " + apiMessage));
            }
        }
        update.apply();
        DebugLogSettings.log(TAG, "bilibili_server_binding result=" + (result == null ? "failed" : result)
                + " event=" + event + " http_code=" + httpCode + " api_code=" + apiCode
                + " api_message=" + (apiMessage.isEmpty() ? "<empty>" : apiMessage));
        boolean loginRequestBecameStale = accepted
                && event == PushTokenReporter.EVENT_LOGIN
                && (!currentRequest || !requestStillMatchesDesiredState(
                        context, pendingMid, pendingRegFingerprint,
                        pendingAuthFingerprint, pendingChannelFingerprint));
        if (accepted && (event == PushTokenReporter.EVENT_LOGOUT
                || loginRequestBecameStale)) {
            // A completed current login is already the desired binding. Reconcile only
            // when its snapshot became stale while in flight, or after a logout.
            MiPushRelayProcessor.synchronizeServerAccountBinding(context);
        }
    }

    private static boolean requestStillMatchesDesiredState(Context context, String pendingMid,
                                                             String pendingRegFingerprint,
                                                             String pendingAuthFingerprint,
                                                             String pendingChannelFingerprint) {
        SharedPreferences state = context.getSharedPreferences("push_state", Context.MODE_PRIVATE);
        String currentMid = state.getString("main_account_mid", "");
        String currentRegFingerprint = PushTokenReporter.fingerprint(
                state.getString("sdk_reg_id", ""));
        String currentAuthFingerprint = PushTokenReporter.fingerprint(
                state.getString("main_account_access_key", ""));
        String currentChannelFingerprint = PushTokenReporter
                .currentChannelFingerprint(context);
        return currentMid.equals(pendingMid)
                && currentRegFingerprint.equals(pendingRegFingerprint)
                && currentAuthFingerprint.equals(pendingAuthFingerprint)
                && currentChannelFingerprint.equals(pendingChannelFingerprint);
    }
}
