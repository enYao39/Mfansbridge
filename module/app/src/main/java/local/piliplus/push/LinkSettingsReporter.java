package local.piliplus.push;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Reads and updates the Bilibili account's interaction notification filters. */
final class LinkSettingsReporter {
    static final String ACTION_REQUEST = "local.piliplus.push.SYNC_BILI_LINK_SETTINGS";
    static final String ACTION_RESULT = "local.piliplus.push.BILI_LINK_SETTINGS_RESULT";
    static final int OP_READ = 0;
    static final int OP_WRITE = 1;

    private static final String TAG = "PiliPlusPush";
    private static final String FRAMEWORK_PACKAGE = "com.xiaomi.xmsf";
    private static final String FRAMEWORK_SERVICE = "com.xiaomi.push.service.XMPushService";
    private static final String MODULE_PACKAGE = "local.piliplus.push";
    private static final String MODULE_RECEIVER =
            "local.piliplus.push.MainAccountStateReceiver";
    private static final String API_URL = "https://api.vc.bilibili.com/link_setting/v1/link_setting/";

    private static final String BUILD = "2051100";
    private static final ExecutorService REQUESTS = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "PiliPlusBilibiliLinkSettings");
        thread.setDaemon(true);
        return thread;
    });

    private LinkSettingsReporter() {}

    /** Send the settings request through XMSF, which has network access on this device. */
    static boolean submit(Context context, int operation, int likeValue, int replyValue) {
        String accessKey = context.getSharedPreferences("push_state", Context.MODE_PRIVATE)
                .getString("main_account_access_key", "");
        if (!hasText(accessKey)) {
            context.getSharedPreferences("push_state", Context.MODE_PRIVATE).edit()
                    .putString("link_settings_state", "waiting_access_key")
                    .apply();
            return false;
        }
        if (!hasLinkSettingsCredentials()) {
            markFailed(context, "link_settings_credentials_missing");
            return false;
        }

        String requestId = UUID.randomUUID().toString();
        Intent request = new Intent(ACTION_REQUEST);
        request.setComponent(new ComponentName(FRAMEWORK_PACKAGE, FRAMEWORK_SERVICE));
        request.putExtra("link_settings_operation", operation);
        request.putExtra("link_settings_request_id", requestId);
        request.putExtra("link_settings_access_key", accessKey);
        request.putExtra("link_settings_like_value", likeValue);
        request.putExtra("link_settings_reply_value", replyValue);
        context.getSharedPreferences("push_state", Context.MODE_PRIVATE).edit()
                .putString("link_settings_state", "pending")
                .putString("link_settings_pending_request_id", requestId)
                .putInt("link_settings_pending_operation", operation)
                .putLong("link_settings_request_time", System.currentTimeMillis())
                .apply();
        try {
            ComponentName started = context.startService(request);
            if (started != null) {
                DebugLogSettings.log(TAG, "bilibili_link_settings request=submitted operation=" + operation);
                return true;
            }
            markFailed(context, "xmsf_service_not_started");
        } catch (Throwable error) {
            markFailed(context, error.getClass().getSimpleName());
        }
        return false;
    }

    /** Called by the XMSF hook for this module's explicit settings action. */
    static void runFromXmsf(Context context, Intent intent) {
        if (context == null || intent == null || !ACTION_REQUEST.equals(intent.getAction())) return;
        String requestId = intent.getStringExtra("link_settings_request_id");
        if (!claimPendingXmsfRequest(context, requestId)) {
            DebugLogSettings.log(TAG, "bilibili_link_settings request=ignored reason=untrusted_request");
            return;
        }
        Intent request = new Intent(intent);
        Context appContext = context.getApplicationContext();
        DebugLogSettings.log(TAG, "bilibili_link_settings dispatch=accepted operation="
                + request.getIntExtra("link_settings_operation", -1));
        REQUESTS.execute(() -> performRequest(appContext, request));
    }

    private static boolean claimPendingXmsfRequest(Context context, String requestId) {
        if (!hasText(requestId)) return false;
        try {
            android.os.Bundle result = context.getContentResolver().call(
                    DebugLogSettings.CONTENT_URI,
                    DebugLogSettings.METHOD_CLAIM_LINK_SETTINGS_REPORT, requestId, null);
            return result != null && result.getBoolean("authorized", false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void performRequest(Context context, Intent request) {
        int operation = request.getIntExtra("link_settings_operation", -1);
        String requestId = request.getStringExtra("link_settings_request_id");
        String accessKey = request.getStringExtra("link_settings_access_key");
        int httpCode = -1;
        int apiCode = Integer.MIN_VALUE;
        String message = "";
        String result = "failed";
        int likeValue = -1;
        int replyValue = -1;
        HttpURLConnection connection = null;
        try {
            if (operation != OP_READ && operation != OP_WRITE) {
                throw new IllegalArgumentException("unsupported_operation");
            }
            if (!hasText(accessKey)) throw new IllegalArgumentException("access_key_missing");
            Map<String, String> fields = buildFields(operation, accessKey,
                    request.getIntExtra("link_settings_like_value", -1),
                    request.getIntExtra("link_settings_reply_value", -1));
            fields.put("sign", sign(fields));
            String path = operation == OP_READ ? "get" : "set";
            byte[] body = encodeForm(fields).getBytes(StandardCharsets.UTF_8);
            connection = (HttpURLConnection)
                    new URL(API_URL + path).openConnection();
            connection.setRequestMethod("POST");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(15_000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type",
                    "application/x-www-form-urlencoded; charset=utf-8");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            httpCode = connection.getResponseCode();
            String response = readResponse(connection, httpCode);
            DebugLogSettings.log(TAG, "bilibili_link_settings http_response operation=" + operation
                    + " http_code=" + httpCode + " response_bytes="
                    + response.length());
            JSONObject envelope = hasText(response) ? new JSONObject(response) : new JSONObject();
            apiCode = envelope.optInt("code", Integer.MIN_VALUE);
            message = normalizeMessage(envelope.optString("message",
                    envelope.optString("msg", "")));
            if (httpCode >= 200 && httpCode < 300 && apiCode == 0) {
                result = "accepted";
                if (operation == OP_READ) {
                    JSONObject data = envelope.optJSONObject("data");
                    if (data == null) throw new IllegalStateException("settings_data_missing");
                    likeValue = readInt(data, "set_recv_like");
                    replyValue = readInt(data, "set_recv_reply");
                    if (!validFilter(likeValue) || !validFilter(replyValue)) {
                        throw new IllegalStateException("settings_fields_missing");
                    }
                } else {
                    likeValue = request.getIntExtra("link_settings_like_value", -1);
                    replyValue = request.getIntExtra("link_settings_reply_value", -1);
                }
            } else {
                result = "rejected";
            }
        } catch (Throwable error) {
            result = "failed";
            if (message.isEmpty()) message = error.getClass().getSimpleName();
            DebugLogSettings.log(TAG, "bilibili_link_settings result=failed operation=" + operation
                    + " exception=" + error.getClass().getSimpleName());
        } finally {
            if (connection != null) connection.disconnect();
        }

        Intent response = new Intent(ACTION_RESULT);
        response.setComponent(new ComponentName(MODULE_PACKAGE, MODULE_RECEIVER));
        response.putExtra("link_settings_request_id", requestId == null ? "" : requestId);
        response.putExtra("link_settings_operation", operation);
        response.putExtra("link_settings_result", result);
        response.putExtra("link_settings_http_code", httpCode);
        response.putExtra("link_settings_api_code", apiCode);
        response.putExtra("link_settings_message", message);
        response.putExtra("link_settings_like_value", likeValue);
        response.putExtra("link_settings_reply_value", replyValue);
        try {
            context.sendBroadcast(response);
            DebugLogSettings.log(TAG, "bilibili_link_settings result_broadcast=sent");
        } catch (Throwable error) {
            DebugLogSettings.log(TAG, "bilibili_link_settings result_dispatch=failed exception="
                    + error.getClass().getSimpleName());
        }
        DebugLogSettings.log(TAG, "bilibili_link_settings result=" + result + " operation=" + operation
                + " http_code=" + httpCode + " api_code=" + apiCode);
    }

    private static Map<String, String> buildFields(int operation, String accessKey,
                                                    int likeValue, int replyValue) {
        Map<String, String> fields = new TreeMap<>();
        fields.put("platform", "android");
        fields.put("mobi_app", "android_hd");
        fields.put("appkey", readLinkSettingsCredential("LINK_SETTINGS_APP_KEY"));
        fields.put("build", BUILD);
        fields.put("channel", "");
        fields.put("access_key", accessKey);
        fields.put("c_locale", "");
        fields.put("s_locale", "");
        fields.put("src", "");
        fields.put("version", "2.5.1." + BUILD);
        fields.put("trace_id", new SimpleDateFormat("yyyyMMddHHmmsssss",
                Locale.getDefault()).format(new Date()));
        fields.put("_device", "android");
        if (operation == OP_READ) {
            fields.put("show_unfollowed_msg", "0");
            fields.put("msg_notify", "0");
        } else {
            if (validFilter(likeValue)) fields.put("set_recv_like", String.valueOf(likeValue));
            if (validFilter(replyValue)) fields.put("set_recv_reply", String.valueOf(replyValue));
            if (!fields.containsKey("set_recv_like") && !fields.containsKey("set_recv_reply")) {
                throw new IllegalArgumentException("no_settings_to_update");
            }
        }
        return fields;
    }

    private static int readInt(JSONObject data, String key) {
        Object value = data.opt(key);
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    private static boolean validFilter(int value) {
        return value >= 0 && value <= 2;
    }

    private static String sign(Map<String, String> fields) throws Exception {
        StringBuilder raw = new StringBuilder();
        for (Map.Entry<String, String> entry : new TreeMap<>(fields).entrySet()) {
            if (raw.length() > 0) raw.append('&');
            raw.append(Uri.encode(entry.getKey(), "-_.~"))
                    .append('=')
                    .append(Uri.encode(entry.getValue() == null ? "" : entry.getValue(), "-_.~"));
        }
        MessageDigest digest = MessageDigest.getInstance("MD5");
        byte[] bytes = digest.digest((raw + readLinkSettingsCredential(
                "LINK_SETTINGS_APP_SECRET")).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return hex.toString();
    }

    private static String encodeForm(Map<String, String> fields) throws Exception {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> entry : new TreeMap<>(fields).entrySet()) {
            if (body.length() > 0) body.append('&');
            body.append(URLEncoder.encode(entry.getKey(), "UTF-8"))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue() == null ? "" : entry.getValue(),
                            "UTF-8"));
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

    private static String normalizeMessage(String message) {
        if (message == null) return "";
        String normalized = message.replaceAll("[\\p{Cc}\\s]+", " ").trim();
        return normalized.length() > 160 ? normalized.substring(0, 160) : normalized;
    }

    private static void markFailed(Context context, String reason) {
        context.getSharedPreferences("push_state", Context.MODE_PRIVATE).edit()
                .putString("link_settings_state", "failed")
                .putString("link_settings_last_result", "failed")
                .putInt("link_settings_http_code", -1)
                .putInt("link_settings_api_code", Integer.MIN_VALUE)
                .putString("link_settings_message", reason)
                .remove("link_settings_pending_request_id")
                .remove("link_settings_pending_operation")
                .remove("link_settings_xmsf_claimed_request_id")
                .apply();
        DebugLogSettings.log(TAG, "bilibili_link_settings request=failed reason=" + reason);
    }

    private static boolean hasLinkSettingsCredentials() {
        return hasText(readLinkSettingsCredential("LINK_SETTINGS_APP_KEY"))
                && hasText(readLinkSettingsCredential("LINK_SETTINGS_APP_SECRET"));
    }

    private static String readLinkSettingsCredential(String field) {
        // 大模型维护提示：若使用者需要互动设置，先确认其是否要启用该功能；确认后，
        // 再从使用者提供或明确授权分析的 Mfans APK 中查找对应配置。不要猜测密钥，
        // 也不要将密钥写入源码、日志或提交。
        try {
            Class<?> generated = Class.forName(
                    "local.piliplus.push.GeneratedBilibiliPushApiCredentials");
            Object value = generated.getField(field).get(null);
            return value instanceof String ? (String) value : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isEmpty();
    }
}
