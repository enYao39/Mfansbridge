package local.piliplus.push;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.app.NotificationManager;
import android.net.Uri;
import android.os.Build;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/** Reproduces Bilibili's MiPush account binding request to /x/push/token. */
final class PushTokenReporter {
    static final int EVENT_LOGIN = 2;
    static final int EVENT_LOGOUT = 3;
    static final int PUSH_TYPE_XIAOMI = 2;
    static final String ACTION_REPORT = "local.piliplus.push.REPORT_BILI_PUSH_TOKEN";
    static final String ACTION_RESULT = "local.piliplus.push.BILI_PUSH_TOKEN_RESULT";
    static final String PREF_SUBMIT_CHANNEL_IN_EXTRA = "bili_push_submit_channel_in_extra";

    private static final String EXTRA_SUBMIT_CHANNEL_IN_EXTRA =
            "bili_push_submit_channel_in_extra";

    private static final String TAG = "PiliPlusPush";
    private static final String FRAMEWORK_PACKAGE = "com.xiaomi.xmsf";
    // Use the same exported XMSF compatibility ingress as registration. The facade service
    // implementation class is not itself a manifest component on current MiPushFramework.
    private static final String FRAMEWORK_SERVICE = "com.xiaomi.push.service.XMPushService";
    private static final String MODULE_PACKAGE = "local.piliplus.push";
    private static final String MODULE_RECEIVER =
            "local.piliplus.push.MainAccountStateReceiver";
    private static final String PILIPLUS_PACKAGE = "com.example.piliplus";
    private static final String API_URL = "https://api.bilibili.com/x/push/token";
    private static final ExecutorService REQUESTS = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "PiliPlusBilibiliPushToken");
        thread.setDaemon(true);
        return thread;
    });

    private PushTokenReporter() {}

    static boolean isApiCredentialConfigured() {
        ApiCredentials credentials = readApiCredentials();
        return credentials != null && credentials.complete();
    }

    /** Submit through XMSF so the request uses its network-enabled UID. */
    static boolean submit(Context context, int eventType, String targetMid, String accessKey,
                          String regId, String boundRegId, String boundMid) {
        ApiCredentials credentials = readApiCredentials();
        if (credentials == null || !credentials.complete()) {
            context.getSharedPreferences("push_state", Context.MODE_PRIVATE).edit()
                    .putString("account_binding_state", "waiting_push_api_credentials")
                    .remove("account_binding_error")
                    .apply();
            DebugLogSettings.log(TAG, "bilibili_push_token request=skipped reason=push_api_credentials_missing");
            return false;
        }
        String token = eventType == EVENT_LOGOUT && hasText(boundRegId)
                ? boundRegId : regId;
        if (!hasText(token)) {
            context.getSharedPreferences("push_state", Context.MODE_PRIVATE).edit()
                    .putString("account_binding_state", "waiting_registration")
                    .apply();
            return false;
        }

        String requestId = UUID.randomUUID().toString();
        String accountBuvid = context.getSharedPreferences("push_state", Context.MODE_PRIVATE)
                .getString("main_account_buvid3", "");
        String buvid = resolveBuvid(context, accountBuvid, credentials.buvid);
        SharedPreferences state = context.getSharedPreferences("push_state", Context.MODE_PRIVATE);
        String channelJson = eventType == EVENT_LOGIN
                ? resolveChannelJson(context, credentials.channelJson) : "";
        boolean submitChannelInExtra = state.getBoolean(PREF_SUBMIT_CHANNEL_IN_EXTRA, true);
        String channelFingerprint = channelConfigurationFingerprint(channelJson,
                submitChannelInExtra);
        Intent request = new Intent(ACTION_REPORT);
        request.setComponent(new ComponentName(FRAMEWORK_PACKAGE, FRAMEWORK_SERVICE));
        request.putExtra("mipush_app_package", "tv.danmaku.bili");
        request.putExtra("bili_push_event_type", eventType);
        request.putExtra("bili_push_target_mid", normalizeMid(targetMid));
        request.putExtra("bili_push_previous_mid", normalizeMid(boundMid));
        request.putExtra("bili_push_access_key", eventType == EVENT_LOGIN
                ? valueOrEmpty(accessKey) : "");
        request.putExtra("bili_push_device_token", token);
        request.putExtra("bili_push_request_id", requestId);
        request.putExtra("bili_push_auth_fingerprint", fingerprint(accessKey));
        request.putExtra("bili_push_buvid_source", buvidSource(accountBuvid, credentials.buvid));
        request.putExtra("bili_push_app_id", credentials.appId);
        request.putExtra("bili_push_app_key", credentials.appKey);
        request.putExtra("bili_push_secret_key", credentials.secretKey);
        request.putExtra("bili_push_buvid", buvid);
        request.putExtra("bili_push_mobi_app", credentials.mobiApp);
        request.putExtra("bili_push_build", credentials.build);
        request.putExtra("bili_push_channel_json", channelJson);
        request.putExtra("bili_push_channel_fingerprint", channelFingerprint);
        request.putExtra(EXTRA_SUBMIT_CHANNEL_IN_EXTRA, submitChannelInExtra);
        request.putExtra("bili_push_extra_json", credentials.extraJson);
        // Persist before dispatch: the XMSF hook can complete a fast request and
        // return its result broadcast before startService() has otherwise recorded
        // the pending request locally.
        context.getSharedPreferences("push_state", Context.MODE_PRIVATE).edit()
                .putString("account_binding_state", "pending")
                .putString("server_binding_pending_request_id", requestId)
                .putInt("server_binding_pending_event", eventType)
                .putString("server_binding_pending_mid", normalizeMid(targetMid))
                .putString("server_binding_pending_reg_id_fingerprint", fingerprint(token))
                .putString("server_binding_pending_auth_fingerprint", fingerprint(accessKey))
                .putString("server_binding_pending_channel_fingerprint", channelFingerprint)
                .putLong("server_binding_request_time", System.currentTimeMillis())
                .remove("account_binding_error")
                .apply();
        try {
            ComponentName started = context.startService(request);
            if (started == null) {
                context.getSharedPreferences("push_state", Context.MODE_PRIVATE).edit()
                        .putString("account_binding_state", "failed")
                        .putString("account_binding_error", "xmsf_service_not_started")
                        .remove("server_binding_pending_request_id")
                        .remove("server_binding_xmsf_claimed_request_id")
                        .apply();
                DebugLogSettings.log(TAG, "bilibili_push_token request=failed reason=xmsf_service_not_started");
                return false;
            }
            DebugLogSettings.log(TAG, "bilibili_push_token request=submitted event=" + eventType
                    + " service=" + started.flattenToShortString());
            return true;
        } catch (Throwable error) {
            context.getSharedPreferences("push_state", Context.MODE_PRIVATE).edit()
                    .putString("account_binding_state", "failed")
                    .putString("account_binding_error", error.getClass().getSimpleName())
                    .remove("server_binding_pending_request_id")
                    .remove("server_binding_xmsf_claimed_request_id")
                    .apply();
            DebugLogSettings.log(TAG, "bilibili_push_token request=failed exception="
                    + error.getClass().getSimpleName());
            return false;
        }
    }

    /** Called by the XMSF hook after it recognizes this module's explicit service action. */
    static void reportFromXmsf(Context context, Intent intent) {
        if (context == null || intent == null || !ACTION_REPORT.equals(intent.getAction())) return;
        String requestId = intent.getStringExtra("bili_push_request_id");
        if (!claimPendingXmsfRequest(context,
                DebugLogSettings.METHOD_CLAIM_PUSH_TOKEN_REPORT, requestId)) {
            DebugLogSettings.log(TAG, "bilibili_push_token report=ignored reason=untrusted_request");
            return;
        }
        Intent request = new Intent(intent);
        REQUESTS.execute(() -> reportAndReturn(context.getApplicationContext(), request));
    }

    private static boolean claimPendingXmsfRequest(Context context, String method,
                                                   String requestId) {
        if (!hasText(requestId)) return false;
        try {
            android.os.Bundle result = context.getContentResolver().call(
                    DebugLogSettings.CONTENT_URI, method, requestId, null);
            return result != null && result.getBoolean("authorized", false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void reportAndReturn(Context context, Intent request) {
        int eventType = request.getIntExtra("bili_push_event_type", -1);
        String requestId = request.getStringExtra("bili_push_request_id");
        String targetMid = normalizeMid(request.getStringExtra("bili_push_target_mid"));
        String token = request.getStringExtra("bili_push_device_token");
        int httpCode = -1;
        int apiCode = Integer.MIN_VALUE;
        String apiMessage = "";
        String result = "failed";
        HttpURLConnection connection = null;
        try {
            if (eventType != EVENT_LOGIN && eventType != EVENT_LOGOUT) {
                throw new IllegalArgumentException("unsupported_event_type");
            }
            if (!hasText(token)) throw new IllegalArgumentException("device_token_missing");
            Map<String, String> fields = buildFields(context, request, eventType, token);
            logRequestFields(eventType, fields, request.getStringExtra("bili_push_buvid_source"));
            fields.put("sign", sign(fields, request.getStringExtra("bili_push_secret_key")));
            byte[] body = encodeForm(fields).getBytes(StandardCharsets.UTF_8);
            connection = (HttpURLConnection) new URL(API_URL).openConnection();
            connection.setRequestMethod("POST");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(15_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type",
                    "application/x-www-form-urlencoded; charset=UTF-8");
            connection.setRequestProperty("Accept", "application/json");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            httpCode = connection.getResponseCode();
            String response = readResponse(connection, httpCode);
            if (hasText(response)) {
                JSONObject json = new JSONObject(response);
                apiCode = json.optInt("code", Integer.MIN_VALUE);
                apiMessage = normalizeApiMessage(json.optString("message",
                        json.optString("msg", "")));
            }
            result = httpCode >= 200 && httpCode < 300 && apiCode == 0
                    ? "accepted" : "rejected";
        } catch (Throwable error) {
            result = "failed";
            DebugLogSettings.log(TAG, "bilibili_push_token report=failed event=" + eventType
                    + " exception=" + error.getClass().getSimpleName());
        } finally {
            if (connection != null) connection.disconnect();
        }

        Intent response = new Intent(ACTION_RESULT);
        response.setComponent(new ComponentName(MODULE_PACKAGE, MODULE_RECEIVER));
        response.putExtra("server_binding_request_id", valueOrEmpty(requestId));
        response.putExtra("server_binding_result", result);
        response.putExtra("server_binding_event", eventType);
        response.putExtra("server_binding_target_mid", targetMid);
        response.putExtra("server_binding_reg_id", token);
        response.putExtra("server_binding_reg_id_fingerprint", fingerprint(token));
        response.putExtra("server_binding_auth_fingerprint",
                request.getStringExtra("bili_push_auth_fingerprint"));
        response.putExtra("server_binding_channel_fingerprint",
                request.getStringExtra("bili_push_channel_fingerprint"));
        response.putExtra("server_binding_http_code", httpCode);
        response.putExtra("server_binding_api_code", apiCode);
        response.putExtra("server_binding_api_message", apiMessage);
        try {
            context.sendBroadcast(response);
        } catch (Throwable error) {
            DebugLogSettings.log(TAG, "bilibili_push_token result_dispatch=failed exception="
                    + error.getClass().getSimpleName());
        }
        DebugLogSettings.log(TAG, "bilibili_push_token report=" + result + " event=" + eventType
                + " http_code=" + httpCode + " api_code=" + apiCode
                + " api_message=" + (apiMessage.isEmpty() ? "<empty>" : apiMessage));
    }

    private static String normalizeApiMessage(String message) {
        if (message == null) return "";
        String normalized = message.replaceAll("[\\p{Cc}\\s]+", " ").trim();
        return normalized.length() > 160 ? normalized.substring(0, 160) : normalized;
    }

    private static Map<String, String> buildFields(Context context, Intent request,
                                                   int eventType, String token) {
        Map<String, String> fields = new TreeMap<>();
        fields.put("device_token", token);
        fields.put("push_sdk", String.valueOf(PUSH_TYPE_XIAOMI));
        fields.put("type", String.valueOf(eventType));
        fields.put("time_zone", String.valueOf(
                java.util.TimeZone.getDefault().getRawOffset() / 3_600_000));
        boolean notificationsEnabled = arePiliPlusNotificationsEnabled(context);
        fields.put("notify_switch", notificationsEnabled ? "1" : "0");
        fields.put("mobile_brand", Build.BRAND == null ? "" : Build.BRAND);
        fields.put("mobile_model", Build.MODEL == null ? "" : Build.MODEL);
        fields.put("mobile_version", Build.VERSION.RELEASE == null ? "" : Build.VERSION.RELEASE);
        fields.put("platform", "android");
        // BiliConfig's default Delegate in the inspected 9.15.0 APK returns empty
        // strings for both locale fields; DefaultRequestInterceptor still includes them.
        fields.put("c_locale", "");
        fields.put("s_locale", "");
        fields.put("app", valueOrEmpty(request.getStringExtra("bili_push_app_id")));
        fields.put("buvid", valueOrEmpty(request.getStringExtra("bili_push_buvid")));
        fields.put("mobi_app", valueOrEmpty(request.getStringExtra("bili_push_mobi_app")));
        fields.put("build", valueOrEmpty(request.getStringExtra("bili_push_build")));
        fields.put("mid", eventType == EVENT_LOGOUT ? "0"
                : normalizeMid(request.getStringExtra("bili_push_target_mid")));
        if (eventType == EVENT_LOGIN) {
            fields.put("access_key", valueOrEmpty(request.getStringExtra("bili_push_access_key")));
        }
        fields.put("ts", String.valueOf(System.currentTimeMillis() / 1000L));
        fields.put("appkey", valueOrEmpty(request.getStringExtra("bili_push_app_key")));
        String channel = request.getStringExtra("bili_push_channel_json");
        boolean submitChannelInExtra = request.getBooleanExtra(
                EXTRA_SUBMIT_CHANNEL_IN_EXTRA, true);
        if (eventType == EVENT_LOGIN && hasText(channel)) {
            fields.put("channel", channel);
        }
        String configuredExtra = request.getStringExtra("bili_push_extra_json");
        fields.put("extra", buildExtra(context, configuredExtra,
                eventType == EVENT_LOGIN && submitChannelInExtra ? channel : ""));
        return fields;
    }

    /** Build Bilibili's runtime extra and optionally mirror subscription states into it. */
    private static String buildExtra(Context context, String configuredExtra,
                                     String channelJson) {
        JSONObject extra;
        try {
            extra = new JSONObject(buildDefaultExtra(context));
        } catch (org.json.JSONException ignored) {
            extra = new JSONObject();
        }

        // Keep caller-supplied runtime fields, while retaining defaults when they are absent.
        if (hasText(configuredExtra)) {
            try {
                JSONObject configured = new JSONObject(configuredExtra);
                java.util.Iterator<String> keys = configured.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    extra.put(key, configured.get(key));
                }
            } catch (Throwable error) {
                DebugLogSettings.log(TAG, "bilibili_push_token extra_config=ignored reason=invalid_json");
            }
        }

        // Bilibili's MiPush report includes these saved subscription values in extra too.
        // Values are strings in the original report, even when a caller supplied booleans.
        if (hasText(channelJson)) {
            try {
                JSONObject channels = new JSONObject(channelJson);
                copyChannelStatus(channels, extra, "video_update");
                copyChannelStatus(channels, extra, "post_update");
                copyChannelStatus(channels, extra, "live_update");
            } catch (Throwable error) {
                DebugLogSettings.log(TAG, "bilibili_push_token channel_extra=ignored reason=invalid_json");
            }
        }
        return extra.toString();
    }

    private static void copyChannelStatus(JSONObject channels, JSONObject extra, String key)
            throws org.json.JSONException {
        if (!channels.has(key) || channels.isNull(key)) return;
        Object value = channels.get(key);
        String status;
        if (value instanceof Boolean) {
            status = (Boolean) value ? "1" : "0";
        } else {
            status = String.valueOf(value);
            if ("true".equalsIgnoreCase(status)) status = "1";
            else if ("false".equalsIgnoreCase(status)) status = "0";
        }
        extra.put(key, status);
    }

    /** Reproduce the SDK event fields Bilibili adds while the XMSF runtime is available. */
    private static String buildDefaultExtra(Context context) {
        JSONObject extra = new JSONObject();
        try {
            // Bilibili's extra builder writes app_channel whenever BPushConfig exists.
            // The standalone module has no Bilibili install-channel runtime, so use its
            // empty fallback; EXTRA_JSON remains available for an explicit channel value.
            extra.put("app_channel", "");
            extra.put("switch_type", "");
            try {
                android.content.pm.PackageInfo framework = context.getPackageManager()
                        .getPackageInfo(FRAMEWORK_PACKAGE, 0);
                extra.put("miui_service_framework_version", String.valueOf(framework.versionCode));
            } catch (Throwable ignored) {
                // Bilibili's EventInfo omits this field when the framework package is absent.
            }
            String miuiVersion = readSystemProperty("ro.miui.ui.version.name");
            if (hasText(miuiVersion)) extra.put("miui_version", miuiVersion);
        } catch (Throwable ignored) {
            // Keep the request usable if an optional runtime field cannot be inspected.
        }
        return extra.toString();
    }

    /** Use the same generated Buvid3 shape as PiliPlus when the saved account jar lacks one. */
    private static String resolveBuvid(Context context, String accountBuvid, String configuredBuvid) {
        if (hasText(accountBuvid)) return accountBuvid;
        if (hasText(configuredBuvid)) return configuredBuvid;

        Context appContext = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        synchronized (PushTokenReporter.class) {
            android.content.SharedPreferences state = appContext.getSharedPreferences(
                    "push_state", Context.MODE_PRIVATE);
            String stored = state.getString("push_report_buvid3", "");
            if (hasText(stored)) return stored;
            String generated = UUID.randomUUID().toString().toUpperCase(Locale.ROOT)
                    + String.format(Locale.ROOT, "%05d",
                    ThreadLocalRandom.current().nextInt(100_000)) + "infoc";
            state.edit().putString("push_report_buvid3", generated).apply();
            return generated;
        }
    }

    private static String buvidSource(String accountBuvid, String configuredBuvid) {
        if (hasText(accountBuvid)) return "main_account_cookie";
        if (hasText(configuredBuvid)) return "build_config";
        return "module_generated_fallback";
    }

    static String currentChannelFingerprint(Context context) {
        ApiCredentials credentials = readApiCredentials();
        String configured = credentials == null ? "" : credentials.channelJson;
        SharedPreferences state = context.getSharedPreferences("push_state", Context.MODE_PRIVATE);
        return channelConfigurationFingerprint(resolveChannelJson(context, configured),
                state.getBoolean(PREF_SUBMIT_CHANNEL_IN_EXTRA, true));
    }

    private static String channelConfigurationFingerprint(String channelJson,
            boolean submitChannelInExtra) {
        return fingerprint("channel=" + valueOrEmpty(channelJson)
                + "|submit_channel_in_extra=" + submitChannelInExtra);
    }

    private static String resolveChannelJson(Context context, String configuredJson) {
        android.content.SharedPreferences state = context.getSharedPreferences(
                "push_state", Context.MODE_PRIVATE);
        if (state.getBoolean("bili_channel_preferences_configured", false)) {
            JSONObject channels = new JSONObject();
            try {
                channels.put("video_update", state.getBoolean(
                        "bili_channel_video_update", true) ? "1" : "0");
                channels.put("post_update", state.getBoolean(
                        "bili_channel_post_update", true) ? "1" : "0");
                channels.put("live_update", state.getBoolean(
                        "bili_channel_live_update", true) ? "1" : "0");
            } catch (org.json.JSONException ignored) {
                return "";
            }
            return channels.toString();
        }

        if (!hasText(configuredJson)) return "";
        try {
            JSONObject channels = new JSONObject(configuredJson);
            return channels.length() == 0 ? "" : channels.toString();
        } catch (org.json.JSONException ignored) {
            DebugLogSettings.log(TAG, "bilibili_push_token channel_config=ignored reason=invalid_json");
            return "";
        }
    }

    /** Record the request shape without writing account tokens, RegID, or signing key values. */
    private static void logRequestFields(int eventType, Map<String, String> fields,
                                         String buvidSource) {
        String channel = valueOrEmpty(fields.get("channel"));
        String extra = valueOrEmpty(fields.get("extra"));
        DebugLogSettings.log(TAG, "bilibili_push_token fields event=" + eventType
                + " app_present=" + hasText(fields.get("app"))
                + " appkey_present=" + hasText(fields.get("appkey"))
                + " mid_present=" + hasText(fields.get("mid"))
                + " access_key_present=" + hasText(fields.get("access_key"))
                + " buvid_present=" + hasText(fields.get("buvid"))
                + " buvid_source=" + valueOrEmpty(buvidSource)
                + " device_token_present=" + hasText(fields.get("device_token"))
                + " channel_chars=" + channel.length()
                + " extra_chars=" + extra.length()
                + " notify_switch=" + valueOrEmpty(fields.get("notify_switch"))
                + " field_count=" + fields.size());
    }

    private static String readSystemProperty(String name) {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            Object value = properties.getMethod("get", String.class).invoke(null, name);
            return value instanceof String ? (String) value : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean arePiliPlusNotificationsEnabled(Context context) {
        try {
            Context target = context.createPackageContext(PILIPLUS_PACKAGE, 0);
            NotificationManager manager = (NotificationManager) target.getSystemService(
                    Context.NOTIFICATION_SERVICE);
            return manager == null || manager.areNotificationsEnabled();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static String sign(Map<String, String> fields, String secret) throws Exception {
        if (!hasText(secret)) throw new IllegalArgumentException("push_secret_missing");
        StringBuilder raw = new StringBuilder();
        for (Map.Entry<String, String> entry : new TreeMap<>(fields).entrySet()) {
            if (raw.length() > 0) raw.append('&');
            raw.append(Uri.encode(entry.getKey(), "-_.~"))
                    .append('=')
                    .append(Uri.encode(valueOrEmpty(entry.getValue()), "-_.~"));
        }
        MessageDigest digest = MessageDigest.getInstance("MD5");
        byte[] bytes = digest.digest((raw.toString() + secret).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return hex.toString();
    }

    private static String encodeForm(Map<String, String> fields) throws Exception {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> entry : new TreeMap<>(fields).entrySet()) {
            if (body.length() > 0) body.append('&');
            body.append(URLEncoder.encode(entry.getKey(), "UTF-8"))
                    .append('=')
                    .append(URLEncoder.encode(valueOrEmpty(entry.getValue()), "UTF-8"));
        }
        return body.toString();
    }

    private static String readResponse(HttpURLConnection connection, int httpCode)
            throws Exception {
        InputStream input = httpCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (input == null) return "";
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int read;
            while ((read = stream.read(buffer)) >= 0 && output.size() < 32_768) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static ApiCredentials readApiCredentials() {
        ApiCredentials empty = new ApiCredentials(
                "", "", "", "", "android", "9150300", "{}", "{}");
        try {
            Class<?> generated = Class.forName(
                    "local.piliplus.push.GeneratedBilibiliPushApiCredentials");
            return new ApiCredentials(
                    readGenerated(generated, "APP_ID"),
                    readGenerated(generated, "APP_KEY"),
                    readGenerated(generated, "SECRET_KEY"),
                    readGenerated(generated, "BUVID"),
                    readGenerated(generated, "MOBI_APP"),
                    readGenerated(generated, "BUILD"),
                    readGenerated(generated, "CHANNEL_JSON"),
                    readGenerated(generated, "EXTRA_JSON"));
        } catch (Throwable ignored) {
            return empty;
        }
    }

    private static String readGenerated(Class<?> generated, String field) throws Exception {
        Object value = generated.getField(field).get(null);
        return value instanceof String ? (String) value : "";
    }

    static String fingerprint(String value) {
        if (!hasText(value)) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(16);
            for (int index = 0; index < 8; index++) {
                result.append(String.format(java.util.Locale.ROOT, "%02x", digest[index] & 0xff));
            }
            return result.toString();
        } catch (Throwable ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static String normalizeMid(String value) {
        if (value == null) return "";
        String normalized = value.trim();
        return normalized.matches("[0-9]{1,20}") ? normalized : "";
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static final class ApiCredentials {
        final String appId;
        final String appKey;
        final String secretKey;
        final String buvid;
        final String mobiApp;
        final String build;
        final String channelJson;
        final String extraJson;

        ApiCredentials(String appId, String appKey, String secretKey, String buvid,
                       String mobiApp, String build, String channelJson, String extraJson) {
            this.appId = valueOrEmpty(appId);
            this.appKey = valueOrEmpty(appKey);
            this.secretKey = valueOrEmpty(secretKey);
            this.buvid = valueOrEmpty(buvid);
            this.mobiApp = hasText(mobiApp) ? mobiApp : "android";
            this.build = hasText(build) ? build : "9150300";
            this.channelJson = hasText(channelJson) ? channelJson : "{}";
            // Leave empty to build Bilibili's default runtime extra in the XMSF process.
            this.extraJson = valueOrEmpty(extraJson);
        }

        boolean complete() {
            return hasText(appId) && hasText(appKey) && hasText(secretKey);
        }
    }
}
