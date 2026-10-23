package local.piliplus.push;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Saves registration callbacks redirected from XMSF and delegates notifications to MiPushFramework. */
final class MiPushRelayProcessor {
    private static final String TAG = "PiliPlusPush";
    private static final String BILIBILI_PACKAGE = "tv.danmaku.bili";
    private static final String PREFS = "push_state";

    private static volatile ClassLoader frameworkLoader;

    private MiPushRelayProcessor() {}

    static void process(Context context, Intent intent) {
        byte[] payload = intent == null ? null : intent.getByteArrayExtra("mipush_payload");
        if (payload == null || payload.length == 0) {
            DebugLogSettings.log(TAG, "relay process result=skipped reason=payload_missing");
            return;
        }
        String stage = "resolve_framework_loader";
        try {
            ClassLoader loader = getFrameworkLoader(context);
            stage = "load_container";
            Class<?> containerType = Class.forName(
                    "com.xiaomi.xmpush.thrift.XmPushActionContainer", false, loader);
            Object container = containerType.getConstructor().newInstance();
            stage = "decode_container";
            deserialize(loader, container, payload);

            String packageName = stringField(container, "packageName");
            if (!BILIBILI_PACKAGE.equals(packageName)) {
                DebugLogSettings.log(TAG, "relay process result=ignored reason=package_mismatch");
                return;
            }
            Object action = field(container, "action");
            String actionName = action == null ? "unknown" : action.toString();
            boolean encrypted = booleanField(container, "encryptAction");
            DebugLogSettings.log(TAG, "relay process stage=decoded action=" + actionName
                    + " encrypted=" + encrypted + " payload_bytes=" + payload.length);

            if ("Registration".equals(actionName)) {
                stage = "process_registration_result";
                processRegistrationResult(context, loader, containerType, container);
            } else if ("SendMessage".equals(actionName)) {
                boolean fromNotification = intent.getBooleanExtra("mipush_notified", false);
                boolean opened = fromNotification
                        && PiliPlusPushModule.launchPiliPlusFromNotification(context);
                DebugLogSettings.log(TAG, "relay message delegated_to_framework=true"
                        + " notification_click_fallback=" + opened);
            } else {
                DebugLogSettings.log(TAG, "relay process result=ignored action=" + actionName);
            }
        } catch (Throwable error) {
            DebugLogSettings.log(TAG, "relay process result=failed stage=" + stage + " exception="
                    + rootCause(error).getClass().getSimpleName()
                    + " detail=" + safeExceptionDetail(rootCause(error)));
        }
    }

    static void captureRegistrationContainer(Context context, ClassLoader loader,
                                            Object container) {
        if (container == null) return;
        try {
            processRegistrationResult(context, loader, container.getClass(), container);
        } catch (Throwable error) {
            DebugLogSettings.log(TAG, "relay registration_result capture_failed exception="
                    + rootCause(error).getClass().getSimpleName());
        }
    }

    private static void processRegistrationResult(Context context, ClassLoader loader,
                                                  Class<?> containerType,
                                                  Object container) throws Exception {
        if (booleanField(container, "isRequest") || booleanField(container, "encryptAction")) {
            DebugLogSettings.log(TAG, "relay registration_result result=ignored reason=unexpected_container");
            return;
        }
        byte[] body = (byte[]) containerType.getMethod("getPushAction").invoke(container);
        Class<?> resultType = Class.forName(
                "com.xiaomi.xmpush.thrift.XmPushActionRegistrationResult", false, loader);
        Object result = resultType.getConstructor().newInstance();
        deserialize(loader, result, body);

        long errorCode = numberField(result, "errorCode");
        String resultPackage = stringField(result, "packageName");
        String appId = stringField(result, "appId");
        String regId = stringField(result, "regId");
        String regSecret = stringField(result, "regSecret");
        String region = stringField(result, "region");
        boolean success = BILIBILI_PACKAGE.equals(resultPackage) && errorCode == 0L
                && hasText(regId) && hasText(regSecret);

        SharedPreferences state = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String resultIdentity = resultPackage + "\u0000" + appId + "\u0000" + errorCode
                + "\u0000" + regId + "\u0000" + regSecret + "\u0000" + region;
        String captureFingerprint = Integer.toHexString(resultIdentity.hashCode());
        long now = SystemClock.elapsedRealtime();
        String previousFingerprint = state.getString("registration_capture_fingerprint", "");
        long previousCaptureTime = state.getLong("registration_capture_uptime", 0L);
        if (captureFingerprint.equals(previousFingerprint)
                && now - previousCaptureTime < 5_000L) {
            return;
        }
        state.edit()
                .putString("registration_capture_fingerprint", captureFingerprint)
                .putLong("registration_capture_uptime", now)
                .apply();
        state.edit()
                .putLong("registration_error_code", errorCode)
                .putBoolean("registration_result_received", true)
                .putBoolean("registration_success", success)
                .putBoolean("registration_reg_id_present", hasText(regId))
                .putBoolean("registration_reg_secret_present", hasText(regSecret))
                .putString("registration_package", resultPackage)
                .apply();

        if (success && BILIBILI_PACKAGE.equals(resultPackage)) {
            String[] credentials = PushRegistration.readCredentials(context);
            String appToken = credentials == null ? null
                    : PushRegistration.stripMetadataSuffix(credentials[1]);
            saveSdkRegistrationState(context, regId, regSecret, region);
            saveSdkRegistration(context, loader, appId, appToken, regId, regSecret, region);
            synchronizeServerAccountBinding(context);
        }
        DebugLogSettings.log(TAG, "relay registration_result received error_code=" + errorCode
                + " package_match=" + BILIBILI_PACKAGE.equals(resultPackage)
                + " app_id_present=" + hasText(appId)
                + " reg_id_present=" + hasText(regId)
                + " reg_secret_present=" + hasText(regSecret)
                + " region_present=" + hasText(region)
                + " success=" + success);
    }

    private static void saveSdkRegistration(Context context, ClassLoader loader, String appId,
                                            String appToken, String regId, String regSecret,
                                            String region) {
        try {
            Context identityContext = PushRegistration.createBilibiliIdentityContext(
                    context, true);
            Class<?> holderType = Class.forName(
                    "com.xiaomi.mipush.sdk.AppInfoHolder", false, loader);
            Object holder = invokeKotlinApi(holderType, "getInstance", identityContext);
            if (hasText(appId) && hasText(appToken)) {
                holderType.getMethod("putAppIDAndToken", String.class, String.class, String.class)
                        .invoke(holder, appId, appToken, null);
            }
            holderType.getMethod("putRegIDAndSecret", String.class, String.class, String.class)
                    .invoke(holder, regId, regSecret, region);
            DebugLogSettings.log(TAG, "relay registration_state saved_to_sdk_store=true");
        } catch (Throwable error) {
            DebugLogSettings.log(TAG, "relay registration_state sdk_store_failed exception="
                    + rootCause(error).getClass().getSimpleName());
        }
    }

    private static void saveSdkRegistrationState(Context context, String regId, String regSecret,
                                                 String region) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("sdk_reg_id", regId)
                .putString("sdk_reg_secret", regSecret)
                .putString("sdk_region", region)
                .apply();
    }

    /** Keep Bilibili's server-side push token bound to PiliPlus AccountType.main only. */
    static void updateMainAccount(Context context, String mainMid, String accessKey,
                                  String buvid3) {
        String normalizedMid = normalizeMid(mainMid);
        String normalizedAccessKey = accessKey == null ? "" : accessKey.trim();
        String normalizedBuvid3 = buvid3 == null ? "" : buvid3.trim();
        SharedPreferences state = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor accountState = state.edit()
                .putBoolean("main_account_state_received", true)
                .putBoolean("main_account_snapshot_refresh_pending", false)
                .putBoolean("main_account_present", !normalizedMid.isEmpty())
                .putString("main_account_mid", normalizedMid)
                .putBoolean("main_account_server_auth_present",
                        !normalizedMid.isEmpty() && !normalizedAccessKey.isEmpty());
        if (!normalizedMid.isEmpty() && !normalizedAccessKey.isEmpty()) {
            accountState.putString("main_account_access_key", normalizedAccessKey);
        } else {
            accountState.remove("main_account_access_key");
        }
        if (!normalizedBuvid3.isEmpty()) {
            accountState.putString("main_account_buvid3", normalizedBuvid3);
        } else {
            accountState.remove("main_account_buvid3");
        }
        accountState.apply();
        DebugLogSettings.log(TAG, "main_account_state updated main_account_present="
                + !normalizedMid.isEmpty() + " registration_ready="
                + state.getBoolean("registration_success", false)
                + " server_auth_present="
                + state.getBoolean("main_account_server_auth_present", false));
        synchronizeServerAccountBinding(context);
    }

    /** Remove the cached account snapshot and ask Bilibili to unbind the current token. */
    static void clearMainAccountSnapshot(Context context) {
        SharedPreferences state = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String boundMid = normalizeMid(state.getString("server_bound_main_mid", ""));
        String currentMid = normalizeMid(state.getString("main_account_mid", ""));
        String regId = state.getString("sdk_reg_id", "");
        String boundRegId = state.getString("server_bound_reg_id", "");
        if (boundMid.isEmpty()) boundMid = currentMid;
        if (!boundMid.isEmpty()) {
            PushTokenReporter.submit(context,
                    PushTokenReporter.EVENT_LOGOUT, boundMid, "", regId,
                    boundRegId, boundMid);
        }
        SharedPreferences.Editor snapshot = state.edit()
                .putBoolean("main_account_state_received", false)
                .putBoolean("main_account_snapshot_refresh_pending", false)
                .putBoolean("main_account_present", false)
                .putBoolean("main_account_server_auth_present", false)
                .remove("main_account_mid")
                .remove("main_account_access_key")
                .remove("main_account_buvid3");
        if (boundMid.isEmpty()) {
            snapshot.putString("account_binding_state", "no_main_account");
        }
        snapshot.apply();
        DebugLogSettings.log(TAG, "main_account_snapshot cleared server_unbind_requested="
                + !boundMid.isEmpty());
    }

    static void synchronizeServerAccountBinding(Context context) {
        SharedPreferences state = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!state.getBoolean("main_account_state_received", false)) {
            state.edit().putString("account_binding_state", "waiting_account_snapshot").apply();
            return;
        }

        String mainMid = normalizeMid(state.getString("main_account_mid", ""));
        String boundMid = normalizeMid(state.getString("server_bound_main_mid", ""));
        String accessKey = state.getString("main_account_access_key", "");
        String regId = state.getString("sdk_reg_id", "");
        String boundRegId = state.getString("server_bound_reg_id", "");
        boolean registrationReady = state.getBoolean("registration_success", false)
                && !regId.isEmpty();
        String channelFingerprint = PushTokenReporter.currentChannelFingerprint(context);

        if (mainMid.isEmpty()) {
            if (!boundMid.isEmpty() && registrationReady) {
                PushTokenReporter.submit(context,
                        PushTokenReporter.EVENT_LOGOUT, boundMid, "", regId,
                        boundRegId, boundMid);
            } else if (boundMid.isEmpty()) {
                state.edit().putString("account_binding_state", "no_main_account")
                        .remove("account_binding_error").apply();
            }
            return;
        }

        if (!registrationReady) {
            state.edit().putString("account_binding_state", "waiting_registration").apply();
            return;
        }
        if (accessKey.isEmpty()) {
            state.edit().putString("account_binding_state", "waiting_access_key").apply();
            return;
        }
        if (!PushTokenReporter.isApiCredentialConfigured()) {
            state.edit().putString("account_binding_state", "waiting_push_api_credentials")
                    .remove("account_binding_error").apply();
            return;
        }

        String currentRegFingerprint = PushTokenReporter.fingerprint(regId);
        String currentAuthFingerprint = PushTokenReporter.fingerprint(accessKey);
        boolean alreadyBound = mainMid.equals(boundMid)
                && currentRegFingerprint.equals(
                        state.getString("server_bound_reg_id_fingerprint", ""))
                && currentAuthFingerprint.equals(
                        state.getString("server_bound_auth_fingerprint", ""))
                && channelFingerprint.equals(
                        state.getString("server_bound_channel_fingerprint", ""));
        if (alreadyBound) {
            state.edit().putString("account_binding_state", "bound")
                    .remove("account_binding_error").apply();
            return;
        }

        if ("pending".equals(state.getString("account_binding_state", ""))) {
            long pendingAt = state.getLong("server_binding_request_time", 0L);
            long pendingAge = System.currentTimeMillis() - pendingAt;
            if (pendingAt > 0L && pendingAge >= 0L && pendingAge < 60_000L) {
                return;
            }
            state.edit()
                    .remove("server_binding_pending_request_id")
                    .remove("server_binding_pending_event")
                    .remove("server_binding_pending_mid")
                    .remove("server_binding_pending_reg_id_fingerprint")
                    .remove("server_binding_pending_auth_fingerprint")
                    .remove("server_binding_pending_channel_fingerprint")
                    .putString("account_binding_state", "waiting_registration")
                    .apply();
            DebugLogSettings.log(TAG, "bilibili_server_binding stale_pending cleared age_ms="
                    + Math.max(0L, pendingAge));
        }

        boolean sameBoundToken = mainMid.equals(boundMid)
                && currentRegFingerprint.equals(
                        state.getString("server_bound_reg_id_fingerprint", ""));
        if (!boundMid.isEmpty() && !sameBoundToken) {
            PushTokenReporter.submit(context,
                    PushTokenReporter.EVENT_LOGOUT, boundMid, "", regId,
                    boundRegId, boundMid);
        }
        PushTokenReporter.submit(context, PushTokenReporter.EVENT_LOGIN,
                mainMid, accessKey, regId, boundRegId, boundMid);
        DebugLogSettings.log(TAG, "bilibili_server_binding submitted=true previous_account_present="
                + !boundMid.isEmpty());
    }

    private static String normalizeMid(String value) {
        if (value == null) return "";
        String normalized = value.trim();
        if (normalized.isEmpty() || !normalized.matches("[0-9]{1,20}")) return "";
        return normalized;
    }

    private static ClassLoader getFrameworkLoader(Context context) throws Exception {
        ClassLoader existing = frameworkLoader;
        if (existing != null) {
            return existing;
        }
        synchronized (MiPushRelayProcessor.class) {
            if (frameworkLoader == null) {
                Context frameworkContext = context.createPackageContext("com.xiaomi.xmsf",
                        Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
                frameworkLoader = PushRegistration.createRelayFrameworkLoader(
                        context, frameworkContext);
            }
            return frameworkLoader;
        }
    }

    private static void deserialize(ClassLoader loader, Object target, byte[] payload)
            throws Exception {
        Class<?> utils = Class.forName(
                "com.xiaomi.xmpush.thrift.XmPushThriftSerializeUtils", false, loader);
        for (Method method : utils.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 2 && parameters[0].isInstance(target)
                    && parameters[1] == byte[].class) {
                method.setAccessible(true);
                method.invoke(null, target, payload);
                return;
            }
        }
        throw new NoSuchMethodException("thrift_deserializer_unavailable");
    }

    private static Object invokeKotlinApi(Class<?> owner, String name, Object... args)
            throws Exception {
        Method selected = null;
        for (Method method : owner.getDeclaredMethods()) {
            if (!name.equals(method.getName()) || method.getParameterTypes().length != args.length) {
                continue;
            }
            Class<?>[] parameters = method.getParameterTypes();
            boolean match = true;
            for (int index = 0; index < parameters.length; index++) {
                if (args[index] == null) {
                    continue;
                }
                Class<?> parameter = parameters[index];
                if (parameter.isPrimitive()) {
                    parameter = parameter == boolean.class ? Boolean.class
                            : parameter == int.class ? Integer.class
                            : parameter == long.class ? Long.class : parameter;
                }
                if (!parameter.isInstance(args[index])) {
                    match = false;
                    break;
                }
            }
            if (match) {
                selected = method;
                break;
            }
        }
        Object receiver = null;
        if (selected == null) {
            receiver = kotlinSingleton(owner);
            if (receiver == null) throw new NoSuchMethodException(owner.getName() + "." + name);
            for (Method method : receiver.getClass().getDeclaredMethods()) {
                if (!name.equals(method.getName()) || method.getParameterTypes().length != args.length) {
                    continue;
                }
                Class<?>[] parameters = method.getParameterTypes();
                boolean match = true;
                for (int index = 0; index < parameters.length; index++) {
                    if (args[index] == null) continue;
                    Class<?> parameter = parameters[index];
                    if (parameter.isPrimitive()) {
                        parameter = parameter == boolean.class ? Boolean.class
                                : parameter == int.class ? Integer.class
                                : parameter == long.class ? Long.class : parameter;
                    }
                    if (!parameter.isInstance(args[index])) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    selected = method;
                    break;
                }
            }
        }
        if (selected == null) {
            throw new NoSuchMethodException(owner.getName() + "." + name);
        }
        if (!java.lang.reflect.Modifier.isStatic(selected.getModifiers()) && receiver == null) {
            receiver = kotlinSingleton(owner);
        }
        selected.setAccessible(true);
        if (java.lang.reflect.Modifier.isStatic(selected.getModifiers())) {
            receiver = null;
        }
        return selected.invoke(receiver, args);
    }

    private static Object kotlinSingleton(Class<?> owner) {
        for (String fieldName : new String[] {"Companion", "INSTANCE"}) {
            try {
                return owner.getField(fieldName).get(null);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Object field(Object target, String name) {
        if (target == null) return null;
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static String stringField(Object target, String name) {
        Object value = field(target, name);
        return value instanceof String ? (String) value : null;
    }

    private static boolean booleanField(Object target, String name) {
        Object value = field(target, name);
        return value instanceof Boolean && (Boolean) value;
    }

    private static long numberField(Object target, String name) {
        Object value = field(target, name);
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isEmpty();
    }

    private static Throwable rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static String safeExceptionDetail(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isEmpty()) {
            return "none";
        }
        return message.replace(' ', '_').replace('\n', '_');
    }
}
