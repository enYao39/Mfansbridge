package local.piliplus.push;

import android.app.Application;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.SystemClock;
import android.os.Build;
import android.net.Uri;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.view.View;
import android.view.ViewGroup;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

/** LSPosed registration and narrowly scoped MiPush delivery relay for PiliPlus. */
public final class PiliPlusPushModule extends XposedModule {
    private static final String TAG = "PiliPlusPush";
    private static final String MODULE_PACKAGE = "local.piliplus.push";
    private static final String FRAMEWORK_PACKAGE = "com.xiaomi.xmsf";
    private static final String FRAMEWORK_SERVER_PACKAGE = "io.github.magisk317.mipush";
    private static final String BILIBILI_DONOR_PACKAGE = "tv.danmaku.bili";
    private static final String PILIPLUS_PACKAGE = "com.example.piliplus";
    private static final String EXTRA_INTERNAL_FLUTTER_ROUTE =
            "local.piliplus.push.INTERNAL_FLUTTER_ROUTE";
    private static final Set<String> TARGET_PACKAGES = Set.of(
            "com.example.piliplus",
            "com.example.piliplus.debug",
            "com.example.piliplus.dev");
    private static final String MIPUSH_CLIENT = "com.xiaomi.mipush.sdk.MiPushClient";
    private static final String[] BILIBILI_CLICK_URI_EXTRAS = {
            "scheme", "pushUrlScheme", "push_scheme", "url", "pushUrl"
    };
    private static final Set<String> PILIPLUS_SUPPORTED_BILIBILI_HOSTS = Set.of(
            "root", "pgc", "space", "video", "live", "bangumi", "opus",
            "search", "article", "comment", "following", "album", "medialist",
            "browser", "m.bilibili.com", "cheese", "history", "main", "livearea",
            "rank", "login", "music", "download");

    private final Set<String> initializedPackages = ConcurrentHashMap.newKeySet();
    private final Set<String> registerHooks = ConcurrentHashMap.newKeySet();
    private final Set<String> observerHooks = ConcurrentHashMap.newKeySet();
    private final Set<String> xmsfApiReportRequests = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean xmsfBilibiliRegistrationStarted = new AtomicBoolean();
    private final AtomicLong xmsfBilibiliRegistrationUntil = new AtomicLong();
    private volatile Context xmsfApplicationContext;
    private volatile String xmsfRelayAuthToken;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log("module loaded api=" + getApiVersion());
    }

    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        installSystemServerReceiverResolverObserver(param.getClassLoader());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        String packageName = param.getPackageName();
        if ("com.xiaomi.xmsf".equals(packageName)) {
            installXmsfDebugLogObserver();
            installXmsfPiliPlusIconAliasHook(param.getDefaultClassLoader());
            installBilibiliAliasPolicyHook(param.getDefaultClassLoader());
            installXmsfBilibiliPackagePresenceHook(param.getDefaultClassLoader());
            installXmsfPendingIntentClickRedirector(param.getDefaultClassLoader());
            installXmsfNotificationTargetRedirector(param.getDefaultClassLoader());
            installXmsfBilibiliNotificationPermissionAlias(param.getDefaultClassLoader());
            installXmsfBroadcastObserver(param.getDefaultClassLoader());
            installXmsfRuntimeObserver(param.getDefaultClassLoader());
            installXmsfIngressObserver(param.getDefaultClassLoader());
            installXmsfRegistrationPipelineObserver(param.getDefaultClassLoader());
            return;
        }
        if (FRAMEWORK_SERVER_PACKAGE.equals(packageName)) {
            log("framework manager loaded; push runtime remains in " + FRAMEWORK_PACKAGE);
            return;
        }
        if (!TARGET_PACKAGES.contains(packageName) || !initializedPackages.add(packageName)) {
            return;
        }

        ClassLoader classLoader = param.getDefaultClassLoader();
        log("target loaded package=" + packageName
                + " mipush_sdk_visible=" + classExists(MIPUSH_CLIENT, classLoader));
        installPiliPlusFlutterRouteBridge(classLoader);
        installApplicationAttachObserver(packageName, classLoader);
    }

    /** Deliver a notification route after Flutter has created its home route. */
    private void installPiliPlusFlutterRouteBridge(ClassLoader classLoader) {
        String ownerName = "android.app.Activity";
        try {
            Class<?> owner = Activity.class;
            int installed = 0;
            for (Method method : owner.getDeclaredMethods()) {
                if (!"onPostResume".equals(method.getName())
                        || method.getParameterCount() != 0
                        || Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                String key = "piliplus:flutter-route-bridge:" + method.toGenericString();
                if (!observerHooks.add(key)) {
                    continue;
                }
                deoptimize(method);
                hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            Object receiver = chain.getThisObject();
                            if (receiver instanceof Activity) {
                                Activity activity = (Activity) receiver;
                                Intent launchIntent = activity.getIntent();
                                String route = launchIntent == null ? null
                                        : launchIntent.getStringExtra(
                                                EXTRA_INTERNAL_FLUTTER_ROUTE);
                                if (TARGET_PACKAGES.contains(activity.getPackageName())
                                        && isInternalFlutterRoute(route)) {
                                    launchIntent.removeExtra(EXTRA_INTERNAL_FLUTTER_ROUTE);
                                    activity.setIntent(launchIntent);
                                    new Handler(Looper.getMainLooper()).postDelayed(
                                            () -> pushFlutterRoute(activity, route), 650L);
                                    log("Flutter notification route queued route=" + route);
                                }
                            }
                            return result;
                        });
                installed++;
            }
            log("PiliPlus Flutter route bridge hooks installed=" + installed
                    + " lifecycle=Activity.onPostResume");
        } catch (Throwable error) {
            log("PiliPlus Flutter route bridge unavailable exception="
                    + error.getClass().getSimpleName());
        }
    }

    private static boolean isInternalFlutterRoute(String route) {
        return "/whisper".equals(route) || "/replyMe".equals(route)
                || "/likeMe".equals(route) || "/atMe".equals(route)
                || "/sysMsg".equals(route);
    }

    private static void pushFlutterRoute(Activity activity, String route) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        String stage = "get_flutter_engine";
        try {
            Object engine = findFlutterEngine(activity);
            if (engine == null) {
                throw new IllegalStateException("flutter_engine_unavailable");
            }
            stage = "get_navigation_channel";
            Object navigationChannel = invokeNoArgMethod(engine, "getNavigationChannel");
            if (navigationChannel != null) {
                stage = "push_route";
                Method pushRoute = findMethod(navigationChannel.getClass(), "pushRoute",
                        String.class);
                if (pushRoute != null) {
                    pushRoute.invoke(navigationChannel, route);
                    log("Flutter notification route sent route=" + route
                            + " bridge=public_navigation_channel");
                    return;
                }
            }

            // PiliPlus's release APK also obfuscates FlutterEngine and NavigationChannel API
            // method names. Its DEX retains the MethodChannel name "flutter/navigation".
            stage = "find_navigation_method_channel";
            Object methodChannel = findObjectWithStringField(engine,
                    "flutter/navigation", 2,
                    Collections.newSetFromMap(new IdentityHashMap<>()));
            if (methodChannel == null) {
                throw new IllegalStateException("navigation_method_channel_unavailable");
            }
            stage = "push_route";
            Method invokeMethod = findMethod(methodChannel.getClass(), null,
                    String.class, Object.class, null);
            if (invokeMethod == null) {
                throw new IllegalStateException("method_channel_invoke_unavailable");
            }
            invokeMethod.invoke(methodChannel, "pushRoute", route, null);
            log("Flutter notification route sent route=" + route
                    + " bridge=flutter_navigation_method_channel");
        } catch (Throwable error) {
            log("Flutter notification route failed stage=" + stage
                    + " exception=" + rootExceptionName(error)
                    + " host=" + flutterHostShape(activity));
        }
    }

    private static String flutterHostShape(Activity activity) {
        StringBuilder shape = new StringBuilder();
        for (Class<?> owner = activity.getClass(); owner != null;
                owner = owner.getSuperclass()) {
            if (shape.length() > 0) shape.append('>');
            shape.append(owner.getName());
            for (Field field : owner.getDeclaredFields()) {
                String name = field.getName().toLowerCase(java.util.Locale.ROOT);
                if (name.contains("engine") || name.contains("delegate")) {
                    shape.append('[').append(field.getName()).append(']');
                }
            }
        }
        return shape.toString();
    }

    private static Object findFlutterEngine(Activity activity) throws Exception {
        Object engine = invokeNoArgMethod(activity, "getFlutterEngine");
        if (engine != null) return engine;

        // PiliPlus's release APK obfuscates FlutterActivity to w8 and its delegate to qp.
        // The Activity does not expose getFlutterEngine(), but FlutterView keeps this stable
        // public accessor and is attached to the live engine after the first frame.
        View decor = activity.getWindow() == null
                ? null : activity.getWindow().getDecorView();
        engine = findAttachedFlutterEngine(decor);
        if (engine != null) return engine;

        Object delegate = readNamedField(activity, "delegate");
        if (delegate == null) return null;
        engine = invokeNoArgMethod(delegate, "getFlutterEngine");
        if (engine != null) return engine;
        return readNamedField(delegate, "flutterEngine");
    }

    private static Object findAttachedFlutterEngine(View view) throws Exception {
        if (view == null) return null;
        Object engine = invokeNoArgMethod(view, "getAttachedFlutterEngine");
        if (engine != null) return engine;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                engine = findAttachedFlutterEngine(group.getChildAt(index));
                if (engine != null) return engine;
            }
        }
        return null;
    }

    private static Object findObjectWithStringField(Object root, String value, int depth,
            Set<Object> visited) throws Exception {
        if (root == null || depth < 0 || !visited.add(root)) return null;
        for (Class<?> owner = root.getClass(); owner != null; owner = owner.getSuperclass()) {
            for (Field field : owner.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                    continue;
                }
                field.setAccessible(true);
                Object candidate = field.get(root);
                if (candidate instanceof String && value.equals(candidate)) return root;
                if (candidate != null && depth > 0
                        && !candidate.getClass().getName().startsWith("java.")) {
                    Object found = findObjectWithStringField(candidate, value, depth - 1,
                            visited);
                    if (found != null) return found;
                }
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> owner, String name, Class<?>... parameters) {
        for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (name != null && !name.equals(method.getName())) continue;
                Class<?>[] actual = method.getParameterTypes();
                if (actual.length != parameters.length) continue;
                boolean matches = true;
                for (int index = 0; index < actual.length; index++) {
                    if (parameters[index] == null) {
                        matches &= actual[index].isInterface();
                    } else if (parameters[index] == Object.class) {
                        matches &= !actual[index].isPrimitive();
                    } else {
                        matches &= actual[index] == parameters[index];
                    }
                }
                if (matches) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        return null;
    }

    private static Object invokeNoArgMethod(Object receiver, String methodName)
            throws Exception {
        for (Class<?> owner = receiver.getClass(); owner != null;
                owner = owner.getSuperclass()) {
            try {
                Method method = owner.getDeclaredMethod(methodName);
                method.setAccessible(true);
                return method.invoke(receiver);
            } catch (NoSuchMethodException ignored) {
                // Continue through Flutter's Activity and delegate superclass chain.
            }
        }
        return null;
    }

    private static Object readNamedField(Object receiver, String fieldName) throws Exception {
        for (Class<?> owner = receiver.getClass(); owner != null;
                owner = owner.getSuperclass()) {
            try {
                Field field = owner.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(receiver);
            } catch (NoSuchFieldException ignored) {
                // Continue through Flutter's Activity and delegate superclass chain.
            }
        }
        return null;
    }

    /** Reuse MiPushFramework's bundled Bilibili ANIP glyph for PiliPlus notifications. */
    private void installXmsfPiliPlusIconAliasHook(ClassLoader classLoader) {
        String ownerName = "com.xiaomi.xmsf.provider.IconPackProvider";
        try {
            Class<?> owner = Class.forName(ownerName, false, classLoader);
            int installed = 0;
            for (Method method : owner.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (!"query".equals(method.getName()) || types.length != 5
                        || types[0] != Uri.class) {
                    continue;
                }
                String key = "xmsf:piliplus-icon-alias:" + method.toGenericString();
                if (!observerHooks.add(key)) {
                    continue;
                }
                deoptimize(method);
                hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            List<Object> args = chain.getArgs();
                            if (args.isEmpty() || !(args.get(0) instanceof Uri)) {
                                return chain.proceed();
                            }
                            Uri requestUri = (Uri) args.get(0);
                            if (!"com.xiaomi.xmsf.iconpack".equals(requestUri.getAuthority())
                                    || !"icon".equals(requestUri.getLastPathSegment())
                                    || !PILIPLUS_PACKAGE.equals(
                                            requestUri.getQueryParameter("package"))) {
                                return chain.proceed();
                            }

                            Uri.Builder aliasedUri = requestUri.buildUpon().clearQuery();
                            for (String name : requestUri.getQueryParameterNames()) {
                                List<String> values = requestUri.getQueryParameters(name);
                                for (String value : values) {
                                    aliasedUri.appendQueryParameter(
                                            name,
                                            "package".equals(name)
                                                    ? BILIBILI_DONOR_PACKAGE : value);
                                }
                            }
                            Object[] replacementArgs = args.toArray();
                            replacementArgs[0] = aliasedUri.build();
                            Object result = chain.proceed(replacementArgs);
                            log("xmsf icon alias requested=" + PILIPLUS_PACKAGE
                                    + " source=" + BILIBILI_DONOR_PACKAGE
                                    + " hit=" + (result != null));
                            return result;
                        });
                installed++;
            }
            log("xmsf PiliPlus icon alias hooks installed=" + installed);
        } catch (Throwable error) {
            log("xmsf PiliPlus icon alias hook unavailable exception="
                    + error.getClass().getSimpleName());
        }
    }

    private void installSystemServerReceiverResolverObserver(ClassLoader classLoader) {
        String ownerName = "com.android.server.pm.PackageManagerInternalBase";
        try {
            Class<?> owner = Class.forName(ownerName, false, classLoader);
            int installed = 0;
            for (Method method : owner.getDeclaredMethods()) {
                boolean receiverQuery = "queryIntentReceivers".equals(method.getName());
                boolean serviceQuery = "queryIntentServices".equals(method.getName());
                if ((!receiverQuery && !serviceQuery)
                        || !hasIntentParameter(method)) {
                    continue;
                }
                String key = "systemserver:" + method.toGenericString();
                if (!observerHooks.add(key)) {
                    continue;
                }
                deoptimize(method);
                hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            Intent intent = firstIntent(chain.getArgs());
                            if (isTargetMiPushIntent(intent)) {
                                log("systemserver " + method.getName() + " action=" + intent.getAction()
                                        + " target=" + targetPackage(intent)
                                        + " result_count=" + resultCount(result)
                                        + " components=" + componentNames(result, receiverQuery));
                            }
                            return result;
                        });
                installed++;
            }
            log("systemserver receiver resolver observers installed=" + installed);
        } catch (Throwable error) {
            log("systemserver receiver resolver observer failed exception="
                    + error.getClass().getSimpleName());
        }
    }

    private void installXmsfBroadcastObserver(ClassLoader classLoader) {
        try {
            Class<?> owner = Class.forName("android.app.ContextImpl", false, classLoader);
            int installed = 0;
            for (Method method : owner.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                boolean isBroadcast = "sendBroadcast".equals(method.getName())
                        && types.length >= 1 && types[0] == Intent.class;
                boolean isServiceStart = "startService".equals(method.getName())
                        && types.length >= 1 && types[0] == Intent.class;
                boolean isServiceBind = "bindService".equals(method.getName())
                        && types.length >= 1 && types[0] == Intent.class;
                if (!isBroadcast && !isServiceStart && !isServiceBind) {
                    continue;
                }
                String key = "xmsf:" + method.toGenericString();
                if (!observerHooks.add(key)) {
                    continue;
                }
                deoptimize(method);
                hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            List<Object> args = chain.getArgs();
                            Intent intent = firstIntent(args);
                            if (isServiceStart && isBilibiliPushHandlerIntent(intent)) {
                                Context relayContext = chain.getThisObject() instanceof Context
                                        ? (Context) chain.getThisObject() : xmsfApplicationContext;
                                String relayAuthToken = getRelayAuthToken(relayContext);
                                if (relayAuthToken.isEmpty()) {
                                    log("xmsf relay redirect=skipped reason=auth_token_unavailable");
                                    return chain.proceed();
                                }
                                Intent relayIntent = new Intent(intent);
                                relayIntent.setPackage(null);
                                relayIntent.setComponent(new ComponentName(
                                        "local.piliplus.push",
                                        "local.piliplus.push.PushRelayService"));
                                relayIntent.putExtra(DebugLogSettings.EXTRA_RELAY_AUTH_TOKEN,
                                        relayAuthToken);
                                Object[] replacementArgs = args.toArray();
                                for (int index = 0; index < replacementArgs.length; index++) {
                                    if (replacementArgs[index] == intent) {
                                        replacementArgs[index] = relayIntent;
                                        break;
                                    }
                                }
                                log("xmsf relay redirect target=tv.danmaku.bili"
                                        + " component=" + relayIntent.getComponent().flattenToShortString());
                                Object result = chain.proceed(replacementArgs);
                                log("xmsf relay startService result="
                                        + (result == null ? "null" : result.toString()));
                                return result;
                            }
                            boolean targetIntent = isTargetMiPushIntent(intent)
                                    || isBilibiliMiPushIntent(intent);
                            if (targetIntent) {
                                Object permission = "sendBroadcast".equals(method.getName())
                                        && args.size() > 1 ? args.get(1) : null;
                                log("xmsf " + method.getName() + " action=" + intent.getAction()
                                        + " target=" + targetPackage(intent)
                                        + " explicit_component=" + componentName(intent)
                                        + " extras=" + intentExtraNames(intent)
                                        + " receiver_permission_present=" + (permission != null));
                            }
                            Object result = chain.proceed();
                            if (targetIntent && isServiceStart) {
                                log("xmsf startService result="
                                        + (result == null ? "null" : result.toString()));
                            }
                            return result;
                        });
                installed++;
            }
            log("xmsf delivery observers installed=" + installed);
        } catch (Throwable error) {
            log("xmsf delivery observer failed exception=" + error.getClass().getSimpleName());
        }
    }

    private void installXmsfPendingIntentClickRedirector(ClassLoader classLoader) {
        try {
            Class<?> owner = Class.forName("android.app.PendingIntent", false, classLoader);
            int installed = 0;
            for (Method method : owner.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                boolean factory = "getActivity".equals(method.getName())
                        || "getService".equals(method.getName());
                if (!factory || !Modifier.isStatic(method.getModifiers())
                        || types.length != 4 || types[0] != Context.class
                        || types[1] != int.class || types[2] != Intent.class
                        || types[3] != int.class) {
                    continue;
                }
                String key = "xmsf:pending_intent:" + method.toGenericString();
                if (!observerHooks.add(key)) {
                    continue;
                }
                deoptimize(method);
                hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            List<Object> args = chain.getArgs();
                            Context context = args.get(0) instanceof Context
                                    ? (Context) args.get(0) : null;
                            Intent clickIntent = args.get(2) instanceof Intent
                                    ? (Intent) args.get(2) : null;
                            if (context == null || !FRAMEWORK_PACKAGE.equals(context.getPackageName())
                                    || !isBilibiliNotificationClick(clickIntent)) {
                                return chain.proceed();
                            }

                            NotificationClickRoute route = resolvePiliPlusClickRoute(
                                    clickIntent, classLoader);
                            Intent redirect = buildPiliPlusClickIntent(context, route);
                            if (redirect == null) {
                                log("notification click redirect skipped reason=no_piliplus_route");
                                return chain.proceed();
                            }
                            int requestCode = (Integer) args.get(1);
                            PendingIntent pendingIntent = PendingIntent.getActivity(context,
                                    requestCode, redirect,
                                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                            log("notification click redirected source=" + method.getName()
                                    + " target=" + targetPackage(redirect)
                                    + " route=" + route.kind
                                    + " source_route=" + summarizeClickUri(route.sourceUri)
                                    + " target_route=" + summarizeClickUri(redirect.getData()));
                            return pendingIntent;
                        });
                installed++;
            }
            log("xmsf notification click hooks installed=" + installed);
        } catch (Throwable error) {
            log("xmsf notification click hook failed exception="
                    + error.getClass().getSimpleName());
        }
    }

    /** Keep Bilibili as the MiPush identity, but publish its notifications under installed PiliPlus. */
    private void installXmsfNotificationTargetRedirector(ClassLoader classLoader) {
        int installed = 0;
        installed += hookNotificationPackageMethods(classLoader,
                "io.github.magisk317.mipush.notification.NotificationManagerEx", true);
        // These bridge/backend classes are explicitly kept by MiPushFramework for LSPosed
        // integrations. Hook them as well as NotificationManagerEx so package attribution is
        // redirected whether the framework reaches its compatibility layer or its Xposed path.
        installed += hookNotificationPackageMethods(classLoader,
                "io.github.magisk317.mipush.notification.NotificationHookBridge", true);
        installed += hookNotificationPackageMethods(classLoader,
                "io.github.magisk317.mipush.notification.NotificationHookBackend", true);
        installed += hookNotificationPackageMethods(classLoader,
                "com.xiaomi.push.service.NotificationManagerPlatformSupport", false);
        installed += hookNotificationPackageMethods(classLoader,
                "com.xiaomi.push.service.NotificationManagerHelper$Companion", false);
        log("xmsf notification target hooks installed=" + installed);
    }

    /** Evaluate Bilibili push permission against the installed PiliPlus notification owner. */
    private void installXmsfBilibiliNotificationPermissionAlias(ClassLoader classLoader) {
        String ownerName = "com.xiaomi.channel.commonutils.android.AppInfoUtils";
        try {
            Class<?> owner = Class.forName(ownerName, false, classLoader);
            int installed = 0;
            for (Method method : owner.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (!"getAppNotificationOp".equals(method.getName())
                        || types.length != 3
                        || !Context.class.isAssignableFrom(types[0])
                        || types[1] != String.class || types[2] != boolean.class) {
                    continue;
                }
                String key = "xmsf:bilibili-notification-permission:" + method.toGenericString();
                if (!observerHooks.add(key)) {
                    continue;
                }
                deoptimize(method);
                hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            List<Object> args = chain.getArgs();
                            if (args.size() != 3 || !BILIBILI_DONOR_PACKAGE.equals(args.get(1))
                                    || !(args.get(0) instanceof Context)) {
                                return chain.proceed();
                            }
                            String piliPackage = findInstalledPiliPlusPackage((Context) args.get(0));
                            if (piliPackage == null) {
                                return chain.proceed();
                            }
                            Object[] replacementArgs = args.toArray();
                            replacementArgs[1] = piliPackage;
                            Object result = chain.proceed(replacementArgs);
                            log("notification permission aliased registration_identity="
                                    + BILIBILI_DONOR_PACKAGE + " display_package=" + piliPackage
                                    + " result=" + (result == null ? "unknown" : result.toString()));
                            return result;
                        });
                installed++;
            }
            log("xmsf Bilibili notification permission hooks installed=" + installed);
        } catch (Throwable error) {
            log("xmsf Bilibili notification permission hook failed exception="
                    + error.getClass().getSimpleName());
        }
    }

    private int hookNotificationPackageMethods(ClassLoader classLoader, String ownerName,
            boolean captureContext) {
        try {
            Class<?> owner = Class.forName(ownerName, false, classLoader);
            int installed = 0;
            for (Method method : owner.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                boolean init = captureContext && "init".equals(method.getName())
                        && types.length == 1 && Context.class.isAssignableFrom(types[0]);
                boolean helperFactory = ownerName.endsWith("NotificationManagerHelper$Companion")
                        && "from".equals(method.getName()) && types.length == 2
                        && Context.class.isAssignableFrom(types[0]) && types[1] == String.class;
                boolean packageMethod = NOTIFICATION_PACKAGE_METHODS.contains(method.getName())
                        && types.length > 0 && types[0] == String.class;
                if (!init && !helperFactory && !packageMethod) {
                    continue;
                }
                String key = "xmsf:notification-target:" + method.toGenericString();
                if (!observerHooks.add(key)) {
                    continue;
                }
                deoptimize(method);
                hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            List<Object> args = chain.getArgs();
                            if (init && !args.isEmpty() && args.get(0) instanceof Context) {
                                xmsfApplicationContext = ((Context) args.get(0)).getApplicationContext();
                                DebugLogSettings.initializeRemote(xmsfApplicationContext);
                                return chain.proceed();
                            }
                            int packageIndex = helperFactory ? 1 : 0;
                            if (args.size() <= packageIndex
                                    || !BILIBILI_DONOR_PACKAGE.equals(args.get(packageIndex))) {
                                return chain.proceed();
                            }
                            Context context = helperFactory && args.get(0) instanceof Context
                                    ? (Context) args.get(0) : xmsfApplicationContext;
                            if (context == null) {
                                context = readNotificationManagerContext(owner, chain.getThisObject());
                            }
                            String piliPackage = context == null
                                    ? null : findInstalledPiliPlusPackage(context);
                            if (piliPackage == null) {
                                log("notification target redirect skipped reason=no_installed_piliplus"
                                        + " source=" + owner.getSimpleName() + "." + method.getName());
                                return chain.proceed();
                            }
                            Object[] replacementArgs = args.toArray();
                            replacementArgs[packageIndex] = piliPackage;
                            String notificationMetadata = notificationMetadata(method.getName(),
                                    replacementArgs);
                            log("notification target redirected source=" + owner.getSimpleName()
                                    + "." + method.getName() + " registration_identity="
                                    + BILIBILI_DONOR_PACKAGE + " display_package=" + piliPackage
                                    + notificationMetadata);
                            return chain.proceed(replacementArgs);
                        });
                installed++;
            }
            if (installed == 0) {
                log("notification target hooks unavailable owner=" + ownerName
                        + " reason=no_matching_methods");
            } else {
                log("notification target hooks installed owner=" + ownerName
                        + " count=" + installed);
            }
            return installed;
        } catch (Throwable error) {
            log("notification target hooks unavailable owner=" + ownerName
                    + " exception=" + error.getClass().getSimpleName());
            return 0;
        }
    }

    /** Record only the notification channel identifiers and labels supplied by MiPush. */
    private String notificationMetadata(String methodName, Object[] args) {
        if (!(methodName.startsWith("createNotificationChannel")
                || methodName.startsWith("notify"))) {
            return "";
        }
        List<String> channels = new ArrayList<>();
        for (Object arg : args) {
            appendNotificationMetadata(arg, channels);
        }
        return channels.isEmpty() ? "" : " channels=" + String.join(";", channels);
    }

    private void appendNotificationMetadata(Object value, List<String> channels) {
        if (value == null || channels.size() >= 16) {
            return;
        }
        if (value instanceof Collection<?>) {
            for (Object item : (Collection<?>) value) {
                appendNotificationMetadata(item, channels);
            }
            return;
        }
        String simpleName = value.getClass().getSimpleName();
        boolean channel = "NotificationChannel".equals(simpleName);
        boolean group = "NotificationChannelGroup".equals(simpleName);
        boolean notification = "Notification".equals(simpleName);
        if (!channel && !group && !notification) {
            return;
        }
        String id = invokeNotificationString(value,
                notification ? "getChannelId" : "getId");
        if (id == null || id.isEmpty()) {
            return;
        }
        String name = notification ? null : invokeNotificationString(value, "getName");
        channels.add((group ? "group:" : "channel:") + sanitizeMetadata(id)
                + (name == null || name.isEmpty() ? "" : "/" + sanitizeMetadata(name)));
    }

    private static String invokeNotificationString(Object target, String methodName) {
        try {
            Object value = target.getClass().getMethod(methodName).invoke(target);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String sanitizeMetadata(String value) {
        String sanitized = value.replace('\n', ' ').replace('\r', ' ');
        return sanitized.length() <= 96 ? sanitized : sanitized.substring(0, 96);
    }

    private Context readNotificationManagerContext(Class<?> owner, Object receiver) {
        try {
            for (Field field : owner.getDeclaredFields()) {
                if (!Context.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                Object target = Modifier.isStatic(field.getModifiers()) ? null : receiver;
                Object value = field.get(target);
                if (value instanceof Context) {
                    Context context = ((Context) value).getApplicationContext();
                    xmsfApplicationContext = context;
                    DebugLogSettings.initializeRemote(context);
                    return context;
                }
            }
        } catch (Throwable ignored) {
            // Use the lifecycle-captured XMSF context when the Framework hides its context field.
        }
        return xmsfApplicationContext;
    }

    private static final Set<String> NOTIFICATION_PACKAGE_METHODS = Set.of(
            "areNotificationsEnabled", "cancel", "createNotificationChannel",
            "createNotificationChannelGroup", "createNotificationChannelGroups",
            "createNotificationChannels", "deleteNotificationChannel",
            "cancelAsTargetPackage", "createTargetNotificationChannelGroups",
            "createTargetNotificationChannels",
            "deleteNotificationChannelGroup", "findPreferredTargetChannel",
            "findPublishFallbackChannel", "getActiveNotifications", "getDirectPackageNotificationChannel",
            "getNotificationChannel", "getNotificationChannelGroup", "getNotificationChannelGroups",
            "getNotificationChannels", "getNotificationManagerForPackage",
            "getTargetNotificationChannel", "getTargetNotificationChannels", "isTargetPackageAvailable",
            "notify", "notifyAsPackage", "notifyAsTargetPackage", "notifyDetailed",
            "supportsTargetChannelProvisioning");

    private static String findInstalledPiliPlusPackage(Context context) {
        String[] candidates = {
                "com.example.piliplus",
                "com.example.piliplus.debug",
                "com.example.piliplus.dev"
        };
        PackageManager packageManager = context.getPackageManager();
        for (String candidate : candidates) {
            try {
                PackageInfo packageInfo = packageManager.getPackageInfo(candidate, 0);
                if (packageInfo != null) {
                    return candidate;
                }
            } catch (PackageManager.NameNotFoundException ignored) {
                // Try the next installed PiliPlus build variant.
            }
        }
        return null;
    }

    private static boolean isBilibiliNotificationClick(Intent intent) {
        if (intent == null) {
            return false;
        }
        ComponentName component = intent.getComponent();
        if (component != null && BILIBILI_DONOR_PACKAGE.equals(component.getPackageName())
                && intent.getBooleanExtra("mipush_notified", false)) {
            return true;
        }
        Uri clickUri = notificationClickUri(intent);
        if (intent.getBooleanExtra("mipush_notified", false)
                && clickUri != null
                && "bilibili".equalsIgnoreCase(clickUri.getScheme())) {
            return true;
        }
        if (isBilibiliNotificationIdentity(intent.getData())) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                String identifier = intent.getIdentifier();
                if (identifier != null
                        && isBilibiliNotificationIdentity(Uri.parse(identifier))) {
                    return true;
                }
            } catch (Throwable ignored) {
                // Intents without an identifier use their data URI or explicit component.
            }
        }
        for (String category : intent.getCategories() == null
                ? Collections.<String>emptySet() : intent.getCategories()) {
            if (category.startsWith(BILIBILI_DONOR_PACKAGE + ".mipush_action.")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBilibiliNotificationIdentity(Uri uri) {
        return uri != null && "mipush-action".equals(uri.getScheme())
                && BILIBILI_DONOR_PACKAGE.equals(uri.getAuthority());
    }

    static boolean launchPiliPlusFromNotification(Context context) {
        Intent intent = buildPiliPlusClickIntent(context,
                new NotificationClickRoute(Uri.parse("bilibili://root"), null, "root_fallback"));
        if (intent == null) {
            return false;
        }
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(intent);
            return true;
        } catch (Throwable error) {
            log("notification click fallback failed exception="
                    + error.getClass().getSimpleName());
            return false;
        }
    }

    private static final class NotificationClickRoute {
        final Uri targetUri;
        final Uri sourceUri;
        final String kind;
        final String internalFlutterRoute;

        NotificationClickRoute(Uri targetUri, Uri sourceUri, String kind) {
            this(targetUri, sourceUri, kind, null);
        }

        NotificationClickRoute(Uri targetUri, Uri sourceUri, String kind,
                String internalFlutterRoute) {
            this.targetUri = targetUri;
            this.sourceUri = sourceUri;
            this.kind = kind;
            this.internalFlutterRoute = internalFlutterRoute;
        }
    }

    /** Bilibili's MiPush click activity reads its destination from the "scheme" extra. */
    private static Uri notificationClickUri(Intent original) {
        if (original == null) {
            return null;
        }
        Uri data = original.getData();
        if (data != null && !isBilibiliNotificationIdentity(data)) {
            return data;
        }
        for (String extra : BILIBILI_CLICK_URI_EXTRAS) {
            try {
                String value = original.getStringExtra(extra);
                if (value != null && !value.trim().isEmpty()) {
                    Uri candidate = Uri.parse(value.trim());
                    if (candidate.getScheme() != null) {
                        return candidate;
                    }
                }
            } catch (Throwable ignored) {
                // A click extra with a different type is not a URI.
            }
        }
        return null;
    }

    private static NotificationClickRoute resolvePiliPlusClickRoute(
            Intent original, ClassLoader classLoader) {
        Uri source = notificationClickUri(original);
        MiPushClickPayload payload = null;
        if (source == null || ("bilibili".equalsIgnoreCase(source.getScheme())
                && "im".equalsIgnoreCase(source.getHost())
                && clickUriPriority(source) < 300)) {
            payload = notificationClickPayloadFromPayload(original, classLoader);
            if (payload != null && payload.clickUri != null
                    && (source == null
                            || clickUriPriority(payload.clickUri) > clickUriPriority(source))) {
                source = payload.clickUri;
            }
        }
        if (source != null && "bilibili".equalsIgnoreCase(source.getScheme())
                && "im".equalsIgnoreCase(source.getHost())) {
            List<String> segments = source.getPathSegments();
            String interactionRoute = interactionNotificationRoute(segments);
            if (interactionRoute != null) {
                return new NotificationClickRoute(null, source,
                        internalRouteKind(interactionRoute), interactionRoute);
            }
            // WhisperDetailController reads Get.arguments['talkerId']. Flutter's Android
            // initial-route extra carries only a route string, so a query parameter leaves
            // Get.arguments null and crashes the page. Open the inbox until PiliPlus has an
            // argument-aware deep-link bridge.
            log("private message click route=inbox reason=flutter_arguments_unavailable");
            return new NotificationClickRoute(null, source, "private_message_inbox",
                    "/whisper");
        }
        if (isPiliPlusSupportedClickUri(source)) {
            return new NotificationClickRoute(source, source, "preserved_supported_route");
        }
        return new NotificationClickRoute(Uri.parse("bilibili://root"), source,
                "unsupported_route_root");
    }

    private static final class MiPushClickPayload {
        Uri clickUri;
        int clickUriPriority = -1;

        void considerClickUri(Uri candidate) {
            if (candidate == null || !"bilibili".equalsIgnoreCase(candidate.getScheme())) return;
            int priority = clickUriPriority(candidate);
            if (priority > clickUriPriority) {
                clickUri = candidate;
                clickUriPriority = priority;
            }
        }
    }

    /** The Framework service-click fallback carries the original MiPush envelope as bytes. */
    private static MiPushClickPayload notificationClickPayloadFromPayload(
            Intent original, ClassLoader classLoader) {
        if (original == null || classLoader == null) {
            return null;
        }
        byte[] payload;
        try {
            payload = original.getByteArrayExtra("mipush_payload");
        } catch (Throwable ignored) {
            return null;
        }
        if (payload == null || payload.length == 0) {
            return null;
        }

        String stage = "load_container";
        try {
            Class<?> containerType = Class.forName(
                    "com.xiaomi.xmpush.thrift.XmPushActionContainer", false, classLoader);
            Object container = containerType.getConstructor().newInstance();
            stage = "decode_container";
            deserializePushPayload(classLoader, container, payload);
            String packageName = readStringField(container, "packageName");
            if (!BILIBILI_DONOR_PACKAGE.equals(packageName)) {
                log("click payload ignored reason=package_mismatch");
                return null;
            }

            MiPushClickPayload clickPayload = new MiPushClickPayload();
            collectMiPushClickPayload(container, "container", clickPayload,
                    new IdentityHashMap<>(), 0);
            Object action = readField(container, "action");
            String actionName = action == null ? "" : action.toString();
            if ("SendMessage".equals(actionName)) {
                String actionStage = "read_push_action";
                try {
                    Object actionBytes = containerType.getMethod("getPushAction").invoke(container);
                    if (actionBytes instanceof byte[] && ((byte[]) actionBytes).length > 0) {
                        actionStage = "decode_send_message_action";
                        Class<?> sendMessageType = Class.forName(
                                "com.xiaomi.xmpush.thrift.XmPushActionSendMessage",
                                false, classLoader);
                        Object sendMessage = sendMessageType.getConstructor().newInstance();
                        deserializePushPayload(classLoader, sendMessage, (byte[]) actionBytes);
                        collectMiPushClickPayload(sendMessage, "sendMessage", clickPayload,
                                new IdentityHashMap<>(), 0);
                    }
                } catch (Throwable error) {
                    // A malformed or version-specific action must not discard the URI already
                    // decoded from the container's PushMetaInfo; retain inbox navigation.
                    log("click payload send_message decode failed stage=" + actionStage
                            + " exception=" + rootExceptionName(error));
                }
            }
            log("click payload decoded action=" + (actionName.isEmpty() ? "unknown" : actionName)
                    + " route=" + (clickPayload.clickUri == null
                            ? "missing" : summarizeClickUri(clickPayload.clickUri)));
            return clickPayload;
        } catch (Throwable error) {
            log("click payload decode failed stage=" + stage + " exception="
                    + rootExceptionName(error));
            return null;
        }
    }

    private static String rootExceptionName(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName();
    }

    private static void deserializePushPayload(ClassLoader classLoader, Object target,
            byte[] payload) throws Exception {
        Class<?> utils = Class.forName(
                "com.xiaomi.xmpush.thrift.XmPushThriftSerializeUtils", false, classLoader);
        for (Method method : utils.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (Modifier.isStatic(method.getModifiers()) && parameters.length == 2
                    && parameters[0].isInstance(target) && parameters[1] == byte[].class) {
                method.setAccessible(true);
                method.invoke(null, target, payload);
                return;
            }
        }
        throw new NoSuchMethodException("thrift_deserializer_unavailable");
    }

    private static void collectMiPushClickPayload(Object value, String fieldPath,
            MiPushClickPayload result, IdentityHashMap<Object, Boolean> visited, int depth) {
        if (value == null || depth > 12) return;
        if (value instanceof CharSequence) {
            String text = value.toString();
            result.considerClickUri(parseEmbeddedBilibiliUri(text));
            collectMiPushClickJson(text, fieldPath, result, visited, depth + 1);
            return;
        }
        if (value instanceof Uri) {
            result.considerClickUri((Uri) value);
            return;
        }
        if (value instanceof Number) return;
        if (value instanceof byte[] || value instanceof Boolean || value instanceof Character
                || value instanceof Class<?> || value instanceof Enum<?>) return;
        if (visited.put(value, Boolean.TRUE) != null) return;

        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                try {
                    collectMiPushClickPayload(object.get(key), childFieldPath(fieldPath, key),
                            result, visited, depth + 1);
                } catch (Throwable ignored) {
                    // Skip individual malformed JSON values and keep scanning the payload.
                }
            }
            return;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int index = 0; index < array.length(); index++) {
                try {
                    collectMiPushClickPayload(array.get(index), fieldPath, result,
                            visited, depth + 1);
                } catch (Throwable ignored) {
                    // Skip individual malformed JSON values and keep scanning the payload.
                }
            }
            return;
        }
        if (value instanceof Bundle) {
            Bundle bundle = (Bundle) value;
            for (String key : bundle.keySet()) {
                collectMiPushClickPayload(bundle.get(key), childFieldPath(fieldPath, key),
                        result, visited, depth + 1);
            }
            return;
        }
        if (value instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String key = entry.getKey() == null ? "" : entry.getKey().toString();
                collectMiPushClickPayload(entry.getValue(), childFieldPath(fieldPath, key),
                        result, visited, depth + 1);
            }
            return;
        }
        if (value instanceof Collection<?>) {
            for (Object child : (Collection<?>) value) {
                collectMiPushClickPayload(child, fieldPath, result, visited, depth + 1);
            }
            return;
        }
        if (value.getClass().isArray()) {
            if (!value.getClass().getComponentType().isPrimitive()) {
                for (Object child : (Object[]) value) {
                    collectMiPushClickPayload(child, fieldPath, result, visited, depth + 1);
                }
            }
            return;
        }

        for (Class<?> type = value.getClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
                try {
                    field.setAccessible(true);
                    collectMiPushClickPayload(field.get(value),
                            childFieldPath(fieldPath, field.getName()), result, visited, depth + 1);
                } catch (Throwable ignored) {
                    // Framework and generated Thrift fields can be inaccessible on some builds.
                }
            }
        }
    }

    private static void collectMiPushClickJson(String value, String fieldPath,
            MiPushClickPayload result, IdentityHashMap<Object, Boolean> visited, int depth) {
        if (value.length() < 2 || value.length() > 65536) return;
        String trimmed = value.trim();
        try {
            if (trimmed.startsWith("{")) {
                collectMiPushClickPayload(new JSONObject(trimmed), fieldPath,
                        result, visited, depth + 1);
            } else if (trimmed.startsWith("[")) {
                collectMiPushClickPayload(new JSONArray(trimmed), fieldPath,
                        result, visited, depth + 1);
            }
        } catch (Throwable ignored) {
            // Most MiPush strings are ordinary text; only parse valid JSON containers.
        }
    }

    private static String childFieldPath(String parent, String child) {
        if (parent == null || parent.isEmpty()) return child;
        if (child == null || child.isEmpty()) return parent;
        return parent + "." + child;
    }

    private static int clickUriPriority(Uri uri) {
        if ("im".equalsIgnoreCase(uri.getHost())) {
            List<String> segments = uri.getPathSegments();
            if (segments.size() >= 3 && "chat".equalsIgnoreCase(segments.get(0))
                    && "personal".equalsIgnoreCase(segments.get(1))
                    && isNumeric(segments.get(2))) return 300;
            if (segments.size() >= 2 && "compose".equalsIgnoreCase(segments.get(0))
                    && "notification".equalsIgnoreCase(segments.get(1))) return 350;
            return 100;
        }
        return 100;
    }

    private static Uri parseEmbeddedBilibiliUri(String value) {
        if (value == null || value.isEmpty()) return null;
        String normalized = value.replace("\\/", "/")
                .replace("\\u003a", ":").replace("\\u003A", ":")
                .replace("\\u002f", "/").replace("\\u002F", "/");
        String decoded = Uri.decode(normalized);
        int start = indexOfIgnoreCase(normalized, "bilibili://");
        if (start < 0) {
            normalized = decoded;
            start = indexOfIgnoreCase(normalized, "bilibili://");
        }
        if (start < 0) return null;
        int end = start;
        while (end < normalized.length()) {
            char current = normalized.charAt(end);
            if (Character.isWhitespace(current) || current == '"' || current == '\''
                    || current == '<' || current == '>' || current == ','
                    || current == ']' || current == '}' || current == '\\') {
                break;
            }
            end++;
        }
        while (end > start && ".;:!?".indexOf(normalized.charAt(end - 1)) >= 0) {
            end--;
        }
        try {
            Uri candidate = Uri.parse(normalized.substring(start, end));
            return "bilibili".equalsIgnoreCase(candidate.getScheme()) ? candidate : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int indexOfIgnoreCase(String source, String query) {
        return source.toLowerCase(java.util.Locale.ROOT)
                .indexOf(query.toLowerCase(java.util.Locale.ROOT));
    }

    /** Map Bilibili's IM notification entry links to PiliPlus's corresponding inbox page. */
    private static String interactionNotificationRoute(List<String> segments) {
        if (segments == null || segments.isEmpty()) {
            return null;
        }
        StringBuilder path = new StringBuilder();
        for (String segment : segments) {
            if (path.length() > 0) path.append('/');
            path.append(segment.toLowerCase(java.util.Locale.ROOT));
        }
        String normalized = path.toString();
        if (normalized.contains("notification/real/reply")
                || normalized.contains("notification/reply")
                || normalized.endsWith("/reply")) {
            return "/replyMe";
        }
        if (normalized.contains("notification/real/like")
                || normalized.contains("notification/like")
                || normalized.endsWith("/like")) {
            return "/likeMe";
        }
        if (normalized.contains("notification/real/at")
                || normalized.contains("notification/at")
                || normalized.endsWith("/at")) {
            return "/atMe";
        }
        if (normalized.contains("notification/real/system")
                || normalized.contains("notification/system")
                || normalized.endsWith("/sys")) {
            return "/sysMsg";
        }
        return null;
    }

    private static String internalRouteKind(String route) {
        if (route == null) return "unknown";
        switch (route) {
            case "/whisperDetail":
                return "private_chat_detail";
            case "/whisper":
                return "private_message_inbox";
            case "/replyMe":
                return "reply_notifications";
            case "/likeMe":
                return "like_notifications";
            case "/atMe":
                return "at_notifications";
            case "/sysMsg":
                return "system_notifications";
            default:
                return "unknown";
        }
    }

    private static boolean isPiliPlusSupportedClickUri(Uri uri) {
        if (uri == null || uri.getScheme() == null) {
            return false;
        }
        String scheme = uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if ("bilibili".equals(scheme)) {
            String host = uri.getHost();
            return host != null && PILIPLUS_SUPPORTED_BILIBILI_HOSTS.contains(
                    host.toLowerCase(java.util.Locale.ROOT));
        }
        if ("http".equals(scheme) || "https".equals(scheme)) {
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            host = host.toLowerCase(java.util.Locale.ROOT);
            return "b23.tv".equals(host) || host.endsWith(".b23.tv")
                    || "bilibili.com".equals(host) || host.endsWith(".bilibili.com");
        }
        return false;
    }

    private static boolean isNumeric(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String summarizeClickUri(Uri uri) {
        if (uri == null) {
            return "none";
        }
        StringBuilder summary = new StringBuilder();
        if (uri.getScheme() != null) {
            summary.append(uri.getScheme()).append("://");
        }
        if (uri.getHost() != null) {
            summary.append(uri.getHost());
        }
        for (String segment : uri.getPathSegments()) {
            summary.append('/').append(isNumeric(segment) ? "{id}" : segment);
        }
        return summary.length() == 0 ? "unparsed" : summary.toString();
    }

    private static Intent buildPiliPlusClickIntent(Context context,
            NotificationClickRoute route) {
        for (String packageName : TARGET_PACKAGES) {
            if (route != null && route.internalFlutterRoute != null) {
                Intent launcher = context.getPackageManager().getLaunchIntentForPackage(packageName);
                if (launcher != null && launcher.getComponent() != null) {
                    String internalRoute = route.internalFlutterRoute;
                    Intent deepLink = new Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_LAUNCHER)
                            .setComponent(launcher.getComponent())
                            // Start the app at its root, then let the Flutter route bridge push
                            // the destination. This keeps the home page underneath the inbox
                            // so Back returns to PiliPlus instead of the previous app.
                            .putExtra("route", "/")
                            .putExtra(EXTRA_INTERNAL_FLUTTER_ROUTE, internalRoute)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                    | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    return deepLink;
                }
            }
            if (route != null && route.targetUri != null) {
                Intent originalRoute = new Intent(Intent.ACTION_VIEW, route.targetUri)
                        .setPackage(packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                if (context.getPackageManager().resolveActivity(originalRoute, 0) != null) {
                    return originalRoute;
                }
            }
            Intent launcher = context.getPackageManager().getLaunchIntentForPackage(packageName);
            if (launcher != null) {
                return launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            }
        }
        Uri fallback = route == null || route.targetUri == null
                ? Uri.parse("bilibili://root") : route.targetUri;
        return new Intent(Intent.ACTION_VIEW, fallback)
                .setPackage("com.example.piliplus")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    private void installXmsfRuntimeObserver(ClassLoader classLoader) {
        String ownerName = "com.xiaomi.xmsf.push.service.MiPushFacadeService";
        try {
            Class<?> owner = Class.forName(ownerName, false, classLoader);
            int installed = 0;
            for (Method method : owner.getDeclaredMethods()) {
                if (!("handleRuntimeIntent".equals(method.getName())
                        || "forwardToPushServiceMain".equals(method.getName()))
                        || method.getParameterCount() < 1
                        || method.getParameterTypes()[0] != Intent.class) {
                    continue;
                }
                String key = "xmsf-runtime:" + method.toGenericString();
                if (!observerHooks.add(key)) {
                    continue;
                }
                deoptimize(method);
                hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            List<Object> args = chain.getArgs();
                            Intent intent = firstIntent(args);
                            Object result = chain.proceed();
                            if (isBilibiliMiPushIntent(intent)
                                    || isTargetMiPushIntent(intent)) {
                                log("xmsf runtime method=" + method.getName()
                                        + " action=" + intent.getAction()
                                        + " target=" + targetPackage(intent));
                            }
                            return result;
                        });
                installed++;
            }
            log("xmsf runtime observers installed=" + installed);
        } catch (Throwable error) {
            log("xmsf runtime observer failed exception="
                    + error.getClass().getSimpleName());
        }
    }

    private void installXmsfIngressObserver(ClassLoader classLoader) {
        String ownerName = "com.xiaomi.xmsf.push.service.ExternalPushIngress";
        try {
            Class<?> owner = Class.forName(ownerName, false, classLoader);
            int installed = 0;
            for (Method method : owner.getDeclaredMethods()) {
                if (!("validateBoundMessage".equals(method.getName())
                        || "validateStart".equals(method.getName()))) {
                    continue;
                }
                String key = "xmsf-ingress:" + method.toGenericString();
                if (!observerHooks.add(key)) {
                    continue;
                }
                deoptimize(method);
                hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            List<Object> args = chain.getArgs();
                            Intent candidate = firstIntent(args);
                            if (candidate == null) {
                                for (Object arg : args) {
                                    if (arg instanceof Message && ((Message) arg).obj instanceof Intent) {
                                        candidate = (Intent) ((Message) arg).obj;
                                        break;
                                    }
                                }
                            }
                            boolean tokenReport = candidate != null
                                    && PushTokenReporter.ACTION_REPORT.equals(
                                            candidate.getAction());
                            boolean linkSettingsReport = candidate != null
                                    && LinkSettingsReporter.ACTION_REQUEST.equals(
                                            candidate.getAction());
                            if (tokenReport || linkSettingsReport) {
                                String requestId = candidate.getStringExtra(tokenReport
                                        ? "bili_push_request_id" : "link_settings_request_id");
                                if (requestId != null && !requestId.isEmpty()
                                        && xmsfApiReportRequests.add(requestId)) {
                                    Context reportContext = xmsfApplicationContext;
                                    if (reportContext == null) {
                                        for (Object arg : args) {
                                            if (arg instanceof Context) {
                                                reportContext = ((Context) arg)
                                                        .getApplicationContext();
                                                break;
                                            }
                                        }
                                    }
                                    if (reportContext != null) {
                                        if (tokenReport) {
                                            PushTokenReporter.reportFromXmsf(
                                                    reportContext, candidate);
                                        } else {
                                            LinkSettingsReporter.runFromXmsf(
                                                    reportContext, candidate);
                                        }
                                        log((tokenReport ? "bilibili_push_token" :
                                                "bilibili_link_settings") + " ingress=intercepted"
                                                + " method=" + method.getName());
                                    } else {
                                        log((tokenReport ? "bilibili_push_token" :
                                                "bilibili_link_settings") + " ingress=skipped"
                                                + " reason=xmsf_context_unavailable");
                                    }
                                }
                            }
                            Object result = chain.proceed();
                            if (result != null) {
                                Intent validatedIntent = null;
                                String rejection = null;
                                try {
                                    Method getIntent = result.getClass().getMethod("getIntent");
                                    Object value = getIntent.invoke(result);
                                    if (value instanceof Intent) {
                                        validatedIntent = (Intent) value;
                                    }
                                    Method getReason = result.getClass()
                                            .getMethod("getRejectionReason");
                                    Object reason = getReason.invoke(result);
                                    rejection = reason == null ? null : reason.toString();
                                } catch (Throwable ignored) {
                                    // Preserve the framework result if this build changes the wrapper.
                                }
                                if (isBilibiliMiPushIntent(candidate)) {
                                    log("xmsf ingress method=" + method.getName()
                                            + " action=" + (candidate.getAction() == null
                                            ? "null" : candidate.getAction())
                                            + " target=" + targetPackage(candidate)
                                            + " local_policy="
                                            + (rejection == null ? "accepted" : "rejected")
                                            + (rejection == null ? "" : " reason=" + rejection)
                                            + " normalized_action="
                                            + (validatedIntent == null ? "none"
                                            : validatedIntent.getAction()));
                                }
                            }
                            return result;
                        });
                installed++;
            }
            log("xmsf ingress observers installed=" + installed);
        } catch (Throwable error) {
            log("xmsf ingress observer failed exception="
                    + error.getClass().getSimpleName());
        }
    }

    /** Observe the target app's registration job, send call, and matching server response. */
    private void installXmsfRegistrationPipelineObserver(ClassLoader classLoader) {
        int installed = 0;
        installed += hookFrameworkRegistrationRequest(classLoader);
        installed += hookRegistrationJob(classLoader);
        installed += hookRegistrationSend(classLoader);
        installed += hookRegistrationResult(classLoader);
        installed += hookRegistrationContainerCapture(classLoader);
        installed += hookPacketDelegateSend(classLoader);
        installed += hookChannelStatus(classLoader);
        installed += hookRegistrationEntry(classLoader);
        installed += hookMissingBilibiliTargetCheck(classLoader);
        log("bilibili registration pipeline observers installed=" + installed);
    }

    /** Run manual Bilibili registration from the exported MiPush facade's XMSF process. */
    private int hookFrameworkRegistrationRequest(ClassLoader classLoader) {
        String ownerName = "com.xiaomi.xmsf.push.service.MiPushFacadeService";
        try {
            Class<?> owner = Class.forName(ownerName, false, classLoader);
            Method method = owner.getDeclaredMethod(
                    "onStartCommand", Intent.class, int.class, int.class);
            String key = "bilibili-framework-registration-request:" + method.toGenericString();
            if (!observerHooks.add(key)) {
                return 0;
            }
            deoptimize(method);
            hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Intent intent = firstIntent(chain.getArgs());
                        boolean pushTokenRequest = intent != null
                                && PushTokenReporter.ACTION_REPORT.equals(intent.getAction());
                        boolean linkSettingsRequest = intent != null
                                && LinkSettingsReporter.ACTION_REQUEST.equals(
                                        intent.getAction());
                        boolean registrationRequest = intent != null
                                && intent.getAction() == null
                                && BILIBILI_DONOR_PACKAGE.equals(targetPackage(intent));
                        if (pushTokenRequest) {
                            Object service = chain.getThisObject();
                            PushTokenReporter.reportFromXmsf(
                                    service instanceof Context ? (Context) service
                                            : xmsfApplicationContext,
                                    intent);
                            log("bilibili_push_token xmsf_dispatch=queued");
                            // Consume our private action here. Letting MiPush's facade
                            // interpret it as an SDK request can drop or rewrite its fields.
                            return 2; // Service.START_NOT_STICKY
                        }
                        if (linkSettingsRequest) {
                            String requestId = intent.getStringExtra("link_settings_request_id");
                            if (requestId == null || xmsfApiReportRequests.add(requestId)) {
                                Object service = chain.getThisObject();
                                LinkSettingsReporter.runFromXmsf(
                                        service instanceof Context ? (Context) service
                                                : xmsfApplicationContext,
                                        intent);
                            }
                            log("bilibili_link_settings xmsf_dispatch=queued");
                            return 2; // Service.START_NOT_STICKY
                        }
                        boolean forced = registrationRequest && intent.getBooleanExtra(
                                PushRegistration.FORCE_REGISTER_EXTRA, false);
                        if (registrationRequest) {
                            // Claim the wake before the facade forwards its sanitized copy to the
                            // core, so the core observer does not schedule a duplicate SDK call.
                            xmsfBilibiliRegistrationStarted.set(true);
                            log("bilibili_framework_register received forced=" + forced
                                    + " extras=" + intentExtraNames(intent));
                        }
                        Object result = chain.proceed();
                        if (registrationRequest) {
                            scheduleBilibiliRegistrationFromXmsf(
                                    chain.getThisObject(), forced);
                        }
                        return result;
                    });
            log("bilibili framework registration facade hook installed");
            return 1;
        } catch (Throwable error) {
            log("bilibili framework registration facade hook unavailable exception="
                    + error.getClass().getSimpleName());
            return 0;
        }
    }

    /** Let XMSF accept Bilibili's MiPush identity even when the Bilibili APK is not installed. */
    private void installXmsfBilibiliPackagePresenceHook(ClassLoader classLoader) {
        int installed = 0;
        try {
            Class<?> owner = Class.forName("android.app.ApplicationPackageManager", false,
                    classLoader);
            for (Method method : owner.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                boolean legacy = types.length == 2 && types[0] == String.class
                        && types[1] == int.class;
                boolean modernPackageInfo = types.length == 2 && types[0] == String.class
                        && "android.content.pm.PackageManager$PackageInfoFlags"
                                .equals(types[1].getName());
                boolean modernApplicationInfo = types.length == 2 && types[0] == String.class
                        && "android.content.pm.PackageManager$ApplicationInfoFlags"
                                .equals(types[1].getName());
                boolean packageInfoQuery = "getPackageInfo".equals(method.getName())
                        && (legacy || modernPackageInfo)
                        && method.getReturnType() == PackageInfo.class;
                boolean applicationInfoQuery = "getApplicationInfo".equals(method.getName())
                        && (legacy || modernApplicationInfo)
                        && method.getReturnType() == ApplicationInfo.class;
                if (!packageInfoQuery && !applicationInfoQuery) {
                    continue;
                }
                String key = "bilibili-package-presence:" + method.toGenericString();
                if (!observerHooks.add(key)) {
                    continue;
                }
                deoptimize(method);
                hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            List<Object> args = chain.getArgs();
                            if (!args.isEmpty()
                                    && BILIBILI_DONOR_PACKAGE.equals(args.get(0))) {
                                if (applicationInfoQuery) {
                                    ApplicationInfo info = new ApplicationInfo();
                                    info.packageName = BILIBILI_DONOR_PACKAGE;
                                    info.flags |= ApplicationInfo.FLAG_INSTALLED;
                                    info.enabled = true;
                                    log("bilibili_package_presence alias=xmsf_identity"
                                            + " query=getApplicationInfo installed_apk_required=false");
                                    return info;
                                }
                                PackageInfo info = new PackageInfo();
                                info.packageName = BILIBILI_DONOR_PACKAGE;
                                info.applicationInfo = new ApplicationInfo();
                                info.applicationInfo.packageName = BILIBILI_DONOR_PACKAGE;
                                info.applicationInfo.flags |= ApplicationInfo.FLAG_INSTALLED;
                                log("bilibili_package_presence alias=xmsf_identity"
                                        + " query=getPackageInfo installed_apk_required=false");
                                return info;
                            }
                            return chain.proceed();
                        });
                installed++;
            }
            log("bilibili_package_presence hooks installed=" + installed);
        } catch (Throwable error) {
            log("bilibili_package_presence hook failed exception="
                    + error.getClass().getSimpleName());
        }
    }

    /** Avoid XMSF's absent-app acknowledgement branch for messages relayed to this module. */
    private int hookMissingBilibiliTargetCheck(ClassLoader classLoader) {
        String ownerName = "com.xiaomi.push.service.MIPushEventProcessor$Companion";
        try {
            Class<?> owner = Class.forName(ownerName, false, classLoader);
            int installed = 0;
            for (Method method : owner.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (!"resolveMissingTargetPackage".equals(method.getName())
                        || types.length != 2
                        || !Context.class.isAssignableFrom(types[0])
                        || !"com.xiaomi.xmpush.thrift.XmPushActionContainer"
                                .equals(types[1].getName())
                        || method.getReturnType() != String.class) {
                    continue;
                }
                String key = "bilibili-missing-target:" + method.toGenericString();
                if (!observerHooks.add(key)) {
                    continue;
                }
                deoptimize(method);
                hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            List<Object> args = chain.getArgs();
                            if (args.size() == 2
                                    && BILIBILI_DONOR_PACKAGE.equals(
                                            resolveContainerTargetPackage(args.get(1), classLoader))) {
                                log("bilibili_missing_target_check bypassed"
                                        + " relay_package=" + MODULE_PACKAGE);
                                return null;
                            }
                            return chain.proceed();
                        });
                installed++;
            }
            log("bilibili_missing_target observers "
                    + (installed == 0 ? "unavailable reason=method_missing" : "installed=" + installed));
            return installed;
        } catch (Throwable error) {
            log("bilibili_missing_target observers unavailable exception="
                    + error.getClass().getSimpleName());
            return 0;
        }
    }

    private static String resolveContainerTargetPackage(Object container, ClassLoader classLoader) {
        if (container == null) {
            return null;
        }
        try {
            Class<?> helper = Class.forName(
                    "com.xiaomi.push.service.MIPushNotificationHelper", false, classLoader);
            for (Method method : helper.getDeclaredMethods()) {
                if (!"getTargetPackage".equals(method.getName())
                        || method.getParameterCount() != 1
                        || !Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                Object target = method.invoke(null, container);
                return target instanceof String ? (String) target : null;
            }
        } catch (Throwable ignored) {
            // Fall back to the container's package when this Framework build changes its helper.
        }
        return readStringField(container, "packageName");
    }

    private int hookRegistrationEntry(ClassLoader classLoader) {
        String ownerName = "com.xiaomi.push.service.XMPushServiceCore";
        try {
            Class<?> owner = Class.forName(ownerName, false, classLoader);
            int installed = 0;
            for (Method method : owner.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                boolean handleIntent = "handleIntent".equals(method.getName())
                        && types.length == 1 && Intent.class.isAssignableFrom(types[0]);
                boolean startCommand = "onStartCommand".equals(method.getName())
                        && types.length == 3 && Intent.class.isAssignableFrom(types[0]);
                boolean registerRequest = "registerForMiPushApp".equals(method.getName())
                        && types.length == 2 && types[0] == byte[].class
                        && types[1] == String.class;
                if (!handleIntent && !startCommand && !registerRequest) {
                    continue;
                }
                String key = "bilibili-registration-entry:" + method.toGenericString();
                if (!observerHooks.add(key)) {
                    continue;
                }
                deoptimize(method);
                hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            List<Object> args = chain.getArgs();
                            if (handleIntent || startCommand) {
                                Intent intent = firstIntent(args);
                                boolean pushTokenReport = intent != null
                                        && PushTokenReporter.ACTION_REPORT.equals(
                                                intent.getAction());
                                boolean linkSettingsReport = intent != null
                                        && LinkSettingsReporter.ACTION_REQUEST.equals(
                                                intent.getAction());
                                if (pushTokenReport || linkSettingsReport) {
                                    String requestId = intent.getStringExtra(pushTokenReport
                                            ? "bili_push_request_id" : "link_settings_request_id");
                                    if (requestId == null || xmsfApiReportRequests.add(requestId)) {
                                        Context reportContext = chain.getThisObject() instanceof Context
                                                ? ((Context) chain.getThisObject()).getApplicationContext()
                                                : xmsfApplicationContext;
                                        if (reportContext != null) {
                                            if (pushTokenReport) {
                                                PushTokenReporter.reportFromXmsf(
                                                        reportContext, intent);
                                            } else {
                                                LinkSettingsReporter.runFromXmsf(
                                                        reportContext, intent);
                                            }
                                            log((pushTokenReport ? "bilibili_push_token"
                                                    : "bilibili_link_settings")
                                                    + " xmsf_service=dispatched stage="
                                                    + (startCommand ? "onStartCommand" : "handleIntent"));
                                        } else {
                                            log((pushTokenReport ? "bilibili_push_token"
                                                    : "bilibili_link_settings")
                                                    + " xmsf_service=skipped reason=context_unavailable");
                                        }
                                    }
                                    return chain.proceed();
                                }
                                if (!isBilibiliMiPushIntent(intent)) {
                                    return chain.proceed();
                                }
                                boolean retryRegistrationFromXmsf = startCommand
                                        && intent.getAction() == null
                                        && BILIBILI_DONOR_PACKAGE.equals(targetPackage(intent));
                                boolean forcedRegistration = retryRegistrationFromXmsf
                                        && intent.getBooleanExtra(
                                                PushRegistration.FORCE_REGISTER_EXTRA, false);
                                log("bilibili xmsf_service stage="
                                        + (startCommand ? "start_command_received" : "intent_received")
                                        + " action="
                                        + (intent.getAction() == null ? "null" : intent.getAction())
                                        + " target=" + targetPackage(intent)
                                        + " xmsf_register_candidate=" + retryRegistrationFromXmsf
                                        + " forced_register=" + forcedRegistration
                                        + " component=" + componentName(intent)
                                        + " extras=" + intentExtraNames(intent));
                                try {
                                    Object result = chain.proceed();
                                    boolean shouldRegisterFromXmsf = retryRegistrationFromXmsf
                                            && (forcedRegistration
                                            || xmsfBilibiliRegistrationStarted.compareAndSet(
                                                    false, true));
                                    if (shouldRegisterFromXmsf) {
                                        xmsfBilibiliRegistrationStarted.set(true);
                                        Object service = chain.getThisObject();
                                        scheduleBilibiliRegistrationFromXmsf(
                                                service, forcedRegistration);
                                    }
                                    log("bilibili xmsf_service stage="
                                            + (handleIntent ? "intent_handled" : "start_command_returned"));
                                    return result;
                                } catch (Throwable error) {
                                    log("bilibili xmsf_service stage="
                                            + (handleIntent ? "intent_threw" : "start_command_threw")
                                            + " exception=" + error.getClass().getSimpleName());
                                    throw error;
                                }
                            } else {
                                if (args.size() != 2
                                        || !BILIBILI_DONOR_PACKAGE.equals(args.get(1))) {
                                    return chain.proceed();
                                }
                                Object payload = args.get(0);
                                log("bilibili registration_entry stage=registerForMiPushApp"
                                        + " payload_bytes=" + (payload instanceof byte[]
                                        ? ((byte[]) payload).length : -1));
                            }
                            try {
                                Object result = chain.proceed();
                                log("bilibili xmsf_service stage="
                                        + (handleIntent ? "intent_handled"
                                        : startCommand ? "start_command_returned"
                                        : "registration_entry_returned"));
                                return result;
                            } catch (Throwable error) {
                                log("bilibili xmsf_service stage="
                                        + (handleIntent ? "intent_threw"
                                        : startCommand ? "start_command_threw"
                                        : "registration_entry_threw")
                                        + " exception=" + error.getClass().getSimpleName());
                                throw error;
                            }
                        });
                installed++;
            }
            log("bilibili registration_entry observers "
                    + (installed == 0 ? "unavailable reason=method_missing" : "installed=" + installed));
            return installed;
        } catch (Throwable error) {
            log("bilibili registration_entry observers unavailable exception="
                    + error.getClass().getSimpleName());
            return 0;
        }
    }

    /** Retry through the same SDK entry point under XMSF's network-enabled UID after a wake. */
    private void scheduleBilibiliRegistrationFromXmsf(Object serviceObject, boolean forced) {
        if (!(serviceObject instanceof Context)) {
            log("bilibili xmsf_identity_register skipped reason=context_unavailable");
            return;
        }
        Context xmsfContext = (Context) serviceObject;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                xmsfBilibiliRegistrationUntil.set(SystemClock.elapsedRealtime() + 60_000L);
                log("bilibili xmsf_identity_register stage=invoking package="
                        + BILIBILI_DONOR_PACKAGE);
                String status = forced
                        ? PushRegistration.forceRegister(
                                xmsfContext, this::installIsolatedBilibiliSdkObservers)
                        : PushRegistration.register(
                                xmsfContext, this::installIsolatedBilibiliSdkObservers);
                log("bilibili xmsf_identity_register stage=returned status=" + status);
            } catch (Throwable error) {
                log("bilibili xmsf_identity_register stage=threw exception="
                        + error.getClass().getSimpleName());
            }
        }, 350);
    }

    /** Instrument the isolated SDK loader before its asynchronous initialization starts. */
    private void installIsolatedBilibiliSdkObservers(ClassLoader classLoader) {
        int installed = 0;
        try {
            Class<?> owner = Class.forName(
                    "com.xiaomi.mipush.sdk.PushServiceClient", false, classLoader);
            for (Method method : owner.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                boolean registration = "register".equals(method.getName())
                        && types.length == 2 && types[1] == boolean.class;
                boolean serviceCall = "callService".equals(method.getName())
                        && types.length == 1 && Intent.class.isAssignableFrom(types[0]);
                boolean serviceStart = "startServiceSafely".equals(method.getName())
                        && types.length == 1 && Intent.class.isAssignableFrom(types[0]);
                boolean serviceBind = "bindServiceSafely".equals(method.getName())
                        && types.length == 1 && Intent.class.isAssignableFrom(types[0]);
                if (!registration && !serviceCall && !serviceStart && !serviceBind) {
                    continue;
                }
                String key = "bilibili-isolated-sdk:" + method.toGenericString();
                if (!observerHooks.add(key)) {
                    continue;
                }
                deoptimize(method);
                hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            List<Object> args = chain.getArgs();
                            if (registration) {
                                Object request = args.isEmpty() ? null : args.get(0);
                                String packageName = readStringAccessor(request, "getPackageName");
                                if (packageName == null) {
                                    packageName = readStringField(request, "packageName");
                                }
                                boolean appIdPresent = hasNonEmptyStringAccessor(request, "getAppId")
                                        || hasNonEmptyStringField(request, "appId");
                                boolean appTokenPresent = hasNonEmptyStringAccessor(request, "getToken")
                                        || hasNonEmptyStringField(request, "token");
                                log("bilibili isolated_sdk stage=register_entered package="
                                        + (packageName == null ? "unknown" : packageName)
                                        + " app_id_present=" + appIdPresent
                                        + " app_token_present=" + appTokenPresent
                                        + " env_changed=" + (args.size() > 1 ? args.get(1) : "unknown"));
                            } else {
                                Intent intent = args.isEmpty() || !(args.get(0) instanceof Intent)
                                        ? null : (Intent) args.get(0);
                                String stage = serviceCall ? "call_service"
                                        : serviceStart ? "start_service" : "bind_service";
                                log("bilibili isolated_sdk stage=" + stage + " action="
                                        + (intent == null || intent.getAction() == null
                                        ? "null" : intent.getAction())
                                        + " target=" + targetPackage(intent)
                                        + " component=" + componentName(intent)
                                        + " extras=" + intentExtraNames(intent));
                            }
                            try {
                                Object result = chain.proceed();
                                log("bilibili isolated_sdk stage="
                                        + (registration ? "register_returned"
                                        : serviceCall ? "call_service_returned"
                                        : serviceStart ? "start_service_returned"
                                        : "bind_service_returned"));
                                return result;
                            } catch (Throwable error) {
                                log("bilibili isolated_sdk stage="
                                        + (registration ? "register_threw"
                                        : serviceCall ? "call_service_threw"
                                        : serviceStart ? "start_service_threw"
                                        : "bind_service_threw")
                                        + " exception=" + error.getClass().getSimpleName());
                                throw error;
                            }
                        });
                installed++;
            }
            Method messengerSend = Messenger.class.getDeclaredMethod("send", Message.class);
            String messengerKey = "bilibili-isolated-sdk:" + messengerSend.toGenericString();
            if (observerHooks.add(messengerKey)) {
                hook(messengerSend)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            List<Object> args = chain.getArgs();
                            Message message = args.isEmpty() || !(args.get(0) instanceof Message)
                                    ? null : (Message) args.get(0);
                            Intent intent = message == null || !(message.obj instanceof Intent)
                                    ? null : (Intent) message.obj;
                            boolean target = message != null && message.what == 17
                                    && isBilibiliMiPushIntent(intent);
                            if (target) {
                                log("bilibili xmsf_bound_send stage=entered action="
                                        + (intent.getAction() == null ? "null" : intent.getAction())
                                        + " target=" + targetPackage(intent)
                                        + " extras=" + intentExtraNames(intent));
                            }
                            try {
                                Object result = chain.proceed();
                                if (target) {
                                    log("bilibili xmsf_bound_send stage=returned");
                                }
                                return result;
                            } catch (Throwable error) {
                                if (target) {
                                    log("bilibili xmsf_bound_send stage=threw exception="
                                            + error.getClass().getSimpleName());
                                }
                                throw error;
                            }
                        });
                installed++;
            }
        } catch (Throwable error) {
            log("bilibili isolated_sdk observer_install_failed exception="
                    + error.getClass().getSimpleName());
        }
        log("bilibili isolated_sdk observers installed=" + installed);
    }

    private static String readStringAccessor(Object instance, String name) {
        if (instance == null) {
            return null;
        }
        try {
            Object value = instance.getClass().getMethod(name).invoke(instance);
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean hasNonEmptyStringAccessor(Object instance, String name) {
        String value = readStringAccessor(instance, name);
        return value != null && !value.isEmpty();
    }

    private static String intentExtraNames(Intent intent) {
        if (intent == null || intent.getExtras() == null) {
            return "none";
        }
        List<String> names = new ArrayList<>(intent.getExtras().keySet());
        Collections.sort(names);
        return names.isEmpty() ? "none" : String.join(",", names);
    }

    private int hookRegistrationJob(ClassLoader classLoader) {
        String ownerName = "com.xiaomi.push.service.MIPushAppRegisterJob";
        try {
            Class<?> owner = Class.forName(ownerName, false, classLoader);
            for (Method method : owner.getDeclaredMethods()) {
                if (!"process".equals(method.getName()) || method.getParameterCount() != 0) {
                    continue;
                }
                String key = "bilibili-register-job:" + method.toGenericString();
                if (!observerHooks.add(key)) {
                    continue;
                }
                deoptimize(method);
                hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object job = chain.getThisObject();
                            if (!BILIBILI_DONOR_PACKAGE.equals(readStringField(job, "packageName"))) {
                                return chain.proceed();
                            }
                            Object pushService = readField(job, "pushService");
                            log("bilibili registration_job stage=started connected="
                                    + booleanMethodValue(pushService, "isConnected")
                                    + " chid5=" + chidFiveSnapshot(classLoader));
                            try {
                                Object result = chain.proceed();
                                log("bilibili registration_job stage=returned");
                                return result;
                            } catch (Throwable error) {
                                log("bilibili registration_job stage=threw exception="
                                        + error.getClass().getSimpleName());
                                throw error;
                            }
                        });
                log("bilibili registration_job observer installed");
                return 1;
            }
            log("bilibili registration_job observer unavailable reason=method_missing");
        } catch (Throwable error) {
            log("bilibili registration_job observer unavailable exception="
                    + error.getClass().getSimpleName());
        }
        return 0;
    }

    private int hookRegistrationSend(ClassLoader classLoader) {
        String ownerName = "com.xiaomi.push.service.MIPushHelper";
        try {
            Class<?> owner = Class.forName(ownerName, false, classLoader);
            int installed = 0;
            for (Method method : owner.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (!"sendPacket".equals(method.getName()) || types.length != 3
                        || !"com.xiaomi.push.service.IPushServiceAction".equals(types[0].getName())
                        || types[1] != String.class || types[2] != byte[].class) {
                    continue;
                }
                String key = "bilibili-register-send:" + method.toGenericString();
                if (!observerHooks.add(key)) {
                    continue;
                }
                deoptimize(method);
                hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            List<Object> args = chain.getArgs();
                            if (args.size() != 3 || !BILIBILI_DONOR_PACKAGE.equals(args.get(1))) {
                                return chain.proceed();
                            }
                            int payloadBytes = args.get(2) instanceof byte[]
                                    ? ((byte[]) args.get(2)).length : -1;
                            log("bilibili registration_send stage=entered payload_bytes="
                                    + payloadBytes + " chid5=" + chidFiveSnapshot(classLoader));
                            try {
                                Object result = chain.proceed();
                                log("bilibili registration_send stage=returned");
                                return result;
                            } catch (Throwable error) {
                                log("bilibili registration_send stage=threw exception="
                                        + error.getClass().getSimpleName());
                                throw error;
                            }
                        });
                installed++;
            }
            if (installed == 0) {
                log("bilibili registration_send observer unavailable reason=method_missing");
            } else {
                log("bilibili registration_send observer installed");
            }
            return installed;
        } catch (Throwable error) {
            log("bilibili registration_send observer unavailable exception="
                    + error.getClass().getSimpleName());
            return 0;
        }
    }

    private int hookRegistrationResult(ClassLoader classLoader) {
        String expectedAppId = readBilibiliAppId();
        String ownerName = "com.xiaomi.mipush.sdk.PushMessageProcessorActionResultSupport";
        try {
            Class<?> owner = Class.forName(ownerName, false, classLoader);
            int installed = 0;
            for (Method method : owner.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (!"processRegistrationResult".equals(method.getName()) || types.length < 1
                        || !"com.xiaomi.xmpush.thrift.XmPushActionRegistrationResult"
                                .equals(types[0].getName())) {
                    continue;
                }
                String key = "bilibili-register-result:" + method.toGenericString();
                if (!observerHooks.add(key)) {
                    continue;
                }
                deoptimize(method);
                hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            List<Object> args = chain.getArgs();
                            Object registrationResult = args.isEmpty() ? null : args.get(0);
                            String resultPackage = readStringField(registrationResult, "packageName");
                            String resultAppId = readStringField(registrationResult, "appId");
                            boolean packageMatch = BILIBILI_DONOR_PACKAGE.equals(resultPackage);
                            boolean appIdMatch = expectedAppId != null
                                    && expectedAppId.equals(resultAppId);
                            if (!packageMatch && !appIdMatch) {
                                return chain.proceed();
                            }
                            try {
                                Object errorCode = readField(registrationResult, "errorCode");
                                boolean regIdPresent = hasNonEmptyStringField(
                                        registrationResult, "regId");
                                boolean regSecretPresent = hasNonEmptyStringField(
                                        registrationResult, "regSecret");
                                log("bilibili registration_result stage=received error_code="
                                        + (errorCode == null ? "unknown" : errorCode.toString())
                                        + " package_match=" + packageMatch
                                        + " app_id_match=" + appIdMatch
                                        + " reg_id_present=" + regIdPresent
                                        + " reg_secret_present=" + regSecretPresent);
                                Object result = chain.proceed();
                                log("bilibili registration_result stage=processed");
                                return result;
                            } catch (Throwable error) {
                                log("bilibili registration_result stage=handler_threw exception="
                                        + error.getClass().getSimpleName());
                                throw error;
                            }
                        });
                installed++;
            }
            if (installed == 0) {
                log("bilibili registration_result observer unavailable reason=method_missing");
            } else {
                log("bilibili registration_result observer installed matcher="
                        + (expectedAppId == null ? "package_name" : "package_name_or_app_id"));
            }
            return installed;
        } catch (Throwable error) {
            log("bilibili registration_result observer unavailable exception="
                    + error.getClass().getSimpleName());
            return 0;
        }
    }

    /** Capture the XMSF-side result when the donor app is absent and its SDK callback cannot run. */
    private int hookRegistrationContainerCapture(ClassLoader classLoader) {
        String ownerName = "com.xiaomi.push.service.MIPushEventProcessor$Companion";
        String containerName = "com.xiaomi.xmpush.thrift.XmPushActionContainer";
        try {
            Class<?> owner = Class.forName(ownerName, false, classLoader);
            int installed = 0;
            for (Method method : owner.getDeclaredMethods()) {
                if (!"buildContainer".equals(method.getName())
                        || !containerName.equals(method.getReturnType().getName())) {
                    continue;
                }
                String key = "bilibili-registration-container:" + method.toGenericString();
                if (!observerHooks.add(key)) {
                    continue;
                }
                deoptimize(method);
                hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object container = chain.proceed();
                            if (container == null
                                    || !"Registration".equals(String.valueOf(
                                            readField(container, "action")))
                                    || Boolean.TRUE.equals(readField(container, "isRequest"))
                                    || !BILIBILI_DONOR_PACKAGE.equals(
                                            readStringField(container, "packageName"))) {
                                return container;
                            }
                            Context context = readNotificationManagerContext(
                                    owner, chain.getThisObject());
                            if (context == null) {
                                context = xmsfApplicationContext;
                            }
                            if (context == null) {
                                log("bilibili registration_result capture_skipped"
                                        + " reason=xmsf_context_unavailable");
                                return container;
                            }
                            log("bilibili registration_result captured_from_xmsf=true");
                            MiPushRelayProcessor.captureRegistrationContainer(
                                    context, classLoader, container);
                            return container;
                        });
                installed++;
            }
            log("bilibili registration_result container_capture "
                    + (installed == 0 ? "unavailable reason=method_missing"
                    : "installed=" + installed));
            return installed;
        } catch (Throwable error) {
            log("bilibili registration_result container_capture unavailable exception="
                    + error.getClass().getSimpleName());
            return 0;
        }
    }

    private int hookPacketDelegateSend(ClassLoader classLoader) {
        String ownerName = "com.xiaomi.push.service.XMPushServicePacketDelegate";
        try {
            Class<?> owner = Class.forName(ownerName, false, classLoader);
            int installed = 0;
            for (Method method : owner.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (!"sendMessage".equals(method.getName()) || types.length != 3
                        || types[0] != String.class || types[1] != byte[].class
                        || types[2] != boolean.class) {
                    continue;
                }
                String key = "bilibili-packet-send:" + method.toGenericString();
                if (!observerHooks.add(key)) {
                    continue;
                }
                deoptimize(method);
                hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            List<Object> args = chain.getArgs();
                            if (args.size() != 3 || !BILIBILI_DONOR_PACKAGE.equals(args.get(0))) {
                                return chain.proceed();
                            }
                            Object payload = args.get(1);
                            Object delegate = chain.getThisObject();
                            Object pushService = readField(delegate, "service");
                            log("bilibili packet_dispatch stage=entered connected="
                                    + booleanMethodValue(pushService, "isConnected")
                                    + " cache_if_unavailable=" + args.get(2)
                                    + " payload_bytes=" + (payload instanceof byte[]
                                    ? ((byte[]) payload).length : -1)
                                    + " chid5=" + chidFiveSnapshot(classLoader));
                            try {
                                Object result = chain.proceed();
                                log("bilibili packet_dispatch stage=returned");
                                return result;
                            } catch (Throwable error) {
                                log("bilibili packet_dispatch stage=threw exception="
                                        + error.getClass().getSimpleName());
                                throw error;
                            }
                        });
                installed++;
            }
            log("bilibili packet_dispatch observer "
                    + (installed == 0 ? "unavailable reason=method_missing" : "installed"));
            return installed;
        } catch (Throwable error) {
            log("bilibili packet_dispatch observer unavailable exception="
                    + error.getClass().getSimpleName());
            return 0;
        }
    }

    private int hookChannelStatus(ClassLoader classLoader) {
        String ownerName = "com.xiaomi.push.service.PushClientsManager$ClientLoginInfo";
        try {
            Class<?> owner = Class.forName(ownerName, false, classLoader);
            int installed = 0;
            for (Method method : owner.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (!"setStatus".equals(method.getName()) || types.length != 5
                        || types[1] != int.class || types[2] != int.class) {
                    continue;
                }
                String key = "bilibili-chid5-status:" + method.toGenericString();
                if (!observerHooks.add(key)) {
                    continue;
                }
                deoptimize(method);
                hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object client = chain.getThisObject();
                            if (!BILIBILI_DONOR_PACKAGE.equals(readStringField(client, "pkgName"))
                                    || !"5".equals(readStringField(client, "chid"))) {
                                return chain.proceed();
                            }
                            Object previous = readField(client, "status");
                            List<Object> args = chain.getArgs();
                            Object requested = args.get(0);
                            Object type = args.get(1);
                            Object reasonCode = args.get(2);
                            log("bilibili chid5 transition from=" + previous
                                    + " to=" + requested
                                    + " event_type=" + type
                                    + " reason_code=" + reasonCode
                                    + " reason_message_present=" + (args.get(3) != null)
                                    + " error_type_present=" + (args.get(4) != null));
                            Object result = chain.proceed();
                            log("bilibili chid5 transition completed state="
                                    + readField(client, "status"));
                            return result;
                        });
                installed++;
            }
            log("bilibili chid5 status observer "
                    + (installed == 0 ? "unavailable reason=method_missing" : "installed"));
            return installed;
        } catch (Throwable error) {
            log("bilibili chid5 status observer unavailable exception="
                    + error.getClass().getSimpleName());
            return 0;
        }
    }

    private static String chidFiveSnapshot(ClassLoader classLoader) {
        try {
            Class<?> managerClass = Class.forName(
                    "com.xiaomi.push.service.PushClientsManager", false, classLoader);
            Object manager = managerClass.getMethod("getInstance").invoke(null);
            Object clients = managerClass.getMethod("getAllClientLoginInfoByChid", String.class)
                    .invoke(manager, "5");
            if (!(clients instanceof Collection<?>)) {
                return "unknown";
            }
            int count = 0;
            List<String> statuses = new ArrayList<>();
            for (Object client : (Collection<?>) clients) {
                if (!BILIBILI_DONOR_PACKAGE.equals(readStringField(client, "pkgName"))) {
                    continue;
                }
                count++;
                Object status = readField(client, "status");
                statuses.add(status == null ? "unknown" : status.toString());
            }
            Collections.sort(statuses);
            return "bilibili_clients=" + count + " statuses="
                    + (statuses.isEmpty() ? "none" : String.join(",", statuses));
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static String booleanMethodValue(Object instance, String name) {
        if (instance == null) {
            return "unknown";
        }
        try {
            Object value = instance.getClass().getMethod(name).invoke(instance);
            return value instanceof Boolean ? value.toString() : "unknown";
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static String readBilibiliAppId() {
        try {
            Class<?> credentials = Class.forName(
                    "local.piliplus.push.GeneratedBilibiliCredentials");
            String metadata = (String) credentials.getField("APP_ID_METADATA").get(null);
            if (metadata == null || metadata.length() < 2) {
                return null;
            }
            return metadata.substring(0, metadata.length() - 1);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean hasNonEmptyStringField(Object instance, String name) {
        Object value = readField(instance, name);
        return value instanceof String && !((String) value).isEmpty();
    }

    private static String readStringField(Object instance, String name) {
        Object value = readField(instance, name);
        return value instanceof String ? (String) value : null;
    }

    private static Object readField(Object instance, String name) {
        if (instance == null) {
            return null;
        }
        for (Class<?> type = instance.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(instance);
            } catch (NoSuchFieldException ignored) {
                // Check the superclass; generated framework models may inherit shared fields.
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    /** Permit only PiliPlus's own UID to submit requests using Bilibili's package identity. */
    private void installBilibiliAliasPolicyHook(ClassLoader classLoader) {
        String ownerName = "com.xiaomi.xmsf.push.service.ExternalPushIntentPolicy";
        try {
            Class<?> owner = Class.forName(ownerName, false, classLoader);
            int installed = 0;
            for (Method method : owner.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (!method.getName().contains("CallerPackageAllowed")
                        || types.length != 3 || !Context.class.isAssignableFrom(types[0])
                        || types[1] != int.class || types[2] != String.class) {
                    continue;
                }
                String key = "bilibili-alias-policy:" + method.toGenericString();
                if (!observerHooks.add(key)) {
                    continue;
                }
                deoptimize(method);
                hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            List<Object> args = chain.getArgs();
                            if (args.size() == 3 && args.get(0) instanceof Context
                                    && args.get(1) instanceof Integer
                                    && BILIBILI_DONOR_PACKAGE.equals(args.get(2))) {
                                Context serviceContext = (Context) args.get(0);
                                int callingUid = (Integer) args.get(1);
                                String[] callerPackages = serviceContext.getPackageManager()
                                        .getPackagesForUid(callingUid);
                                if (contains(callerPackages, "com.example.piliplus")
                                        || contains(callerPackages, MODULE_PACKAGE)) {
                                    log("bilibili_alias_policy allow caller=module_or_piliplus"
                                            + " requested_package=" + BILIBILI_DONOR_PACKAGE);
                                    return true;
                                }
                                if (contains(callerPackages, FRAMEWORK_PACKAGE)
                                        && SystemClock.elapsedRealtime()
                                        < xmsfBilibiliRegistrationUntil.get()) {
                                    log("bilibili_alias_policy allow caller=xmsf_registration_window"
                                            + " requested_package=" + BILIBILI_DONOR_PACKAGE);
                                    return true;
                                }
                            }
                            return chain.proceed();
                        });
                installed++;
            }
            log("bilibili_alias_policy hook installed=" + installed);
        } catch (Throwable error) {
            log("bilibili_alias_policy hook failed exception="
                    + error.getClass().getSimpleName());
        }
    }

    private static boolean contains(String[] values, String expected) {
        if (values == null) {
            return false;
        }
        for (String value : values) {
            if (expected.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private void installApplicationAttachObserver(String packageName, ClassLoader classLoader) {
        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            deoptimize(attach);
            hook(attach)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        List<Object> args = chain.getArgs();
                        if (!args.isEmpty() && args.get(0) instanceof Context) {
                            inspectApplication(packageName, (Context) args.get(0), classLoader);
                        } else {
                            log("application attach context unavailable package=" + packageName);
                        }
                        return result;
                    });
            log("Application.attach observer installed package=" + packageName);
        } catch (Throwable error) {
            log("Application.attach observer failed package=" + packageName
                    + " exception=" + error.getClass().getSimpleName());
        }
    }

    private void installXmsfDebugLogObserver() {
        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            String key = "xmsf:debug-log-settings-observer";
            if (!observerHooks.add(key)) return;
            deoptimize(attach);
            hook(attach)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        List<Object> args = chain.getArgs();
                        if (!args.isEmpty() && args.get(0) instanceof Context) {
                            Context context = (Context) args.get(0);
                            if (FRAMEWORK_PACKAGE.equals(context.getPackageName())) {
                                xmsfApplicationContext = context.getApplicationContext();
                                DebugLogSettings.initializeRemote(xmsfApplicationContext);
                            }
                        }
                        return result;
                    });
        } catch (Throwable ignored) {
            // NotificationManager context capture below provides a later initialization point.
        }
    }

    private void inspectApplication(String packageName, Context context, ClassLoader classLoader) {
        if (!packageName.equals(context.getPackageName())) {
            log("context package mismatch expected=" + packageName
                    + " actual=" + context.getPackageName());
            return;
        }

        DebugLogSettings.initializeRemote(context);
        boolean sdkVisible = classExists(MIPUSH_CLIENT, classLoader);
        String processName = context.getApplicationInfo().processName;
        log("application attached package=" + packageName
                + " process=" + (processName == null ? "unknown" : processName)
                + " mipush_sdk_visible=" + sdkVisible);
        if (packageName.equals(processName)) {
            PiliPlusAccountWatcher.start(context);
        }
        inspectFrameworkSdk(context);
        inspectDeclaredComponents(context);
        if (sdkVisible) {
            installRegisterObserver(classLoader, packageName);
        }
    }

    /** Check whether the framework package can supply SDK bytecode without initializing it. */
    private void inspectFrameworkSdk(Context targetContext) {
        try {
            Context frameworkContext = targetContext.createPackageContext(
                    FRAMEWORK_PACKAGE,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            ClassLoader frameworkLoader = frameworkContext.getClassLoader();
            boolean clientVisible = classExists(MIPUSH_CLIENT, frameworkLoader);
            boolean configVisible = classExists(
                    "com.xiaomi.mipush.sdk.PushConfiguration", frameworkLoader);
            boolean regionVisible = classExists(
                    "com.xiaomi.push.service.module.PushChannelRegion", frameworkLoader);
            boolean registerSignature = false;
            boolean companionRegisterSignature = false;
            List<String> registerMethods = new ArrayList<>();
            if (clientVisible) {
                Class<?> client = Class.forName(MIPUSH_CLIENT, false, frameworkLoader);
                for (Method method : client.getDeclaredMethods()) {
                    if (method.getName().toLowerCase().contains("register")) {
                        registerMethods.add(method.toGenericString());
                        if ("registerPush".equals(method.getName())
                                && method.getParameterCount() == 3
                                && method.getParameterTypes()[0] == Context.class
                                && method.getParameterTypes()[1] == String.class
                                && method.getParameterTypes()[2] == String.class) {
                            registerSignature = true;
                        }
                    }
                }
                Class<?> companion = Class.forName(
                        MIPUSH_CLIENT + "$Companion", false, frameworkLoader);
                companionRegisterSignature = companion.getDeclaredMethod(
                        "registerPush", Context.class, String.class, String.class) != null;
            }
            log("framework_sdk_status package=" + FRAMEWORK_PACKAGE
                    + " client_visible=" + clientVisible
                    + " register_signature=" + registerSignature
                    + " companion_register_signature=" + companionRegisterSignature
                    + " push_configuration_visible=" + configVisible
                    + " region_visible=" + regionVisible
                    + " register_methods=" + (registerMethods.isEmpty()
                    ? "none" : String.join(" | ", registerMethods)));
        } catch (Throwable error) {
            String detail = error.getMessage();
            if (detail != null) {
                detail = detail.replace('\n', ' ').replace('\r', ' ');
                if (detail.length() > 180) {
                    detail = detail.substring(0, 180);
                }
            }
            log("framework_sdk_status failed exception=" + error.getClass().getSimpleName()
                    + " detail=" + (detail == null ? "none" : detail));
        }
    }

    private void inspectDeclaredComponents(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            int flags = PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS;
            PackageInfo info = pm.getPackageInfo(context.getPackageName(), flags);
            List<String> components = new ArrayList<>();
            if (info.services != null) {
                for (ServiceInfo service : info.services) {
                    if (looksLikeMiPush(service.name)) {
                        components.add("service:" + service.name);
                    }
                }
            }
            if (info.receivers != null) {
                for (ActivityInfo receiver : info.receivers) {
                    if (looksLikeMiPush(receiver.name)) {
                        components.add("receiver:" + receiver.name);
                    }
                }
            }
            Collections.sort(components);
            log("declared_mipush_components count=" + components.size()
                    + " components=" + (components.isEmpty()
                    ? "none" : String.join(",", components)));
        } catch (Throwable error) {
            log("declared component query failed exception=" + error.getClass().getSimpleName());
        }
    }

    private void installRegisterObserver(ClassLoader classLoader, String packageName) {
        if (!registerHooks.add(packageName)) {
            return;
        }
        try {
            Class<?> owner = Class.forName(MIPUSH_CLIENT, false, classLoader);
            Method method = owner.getDeclaredMethod(
                    "registerPush", Context.class, String.class, String.class);
            if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != void.class) {
                log("registerPush signature mismatch package=" + packageName);
                return;
            }
            deoptimize(method);
            hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        log("MiPushClient.registerPush called package=" + packageName);
                        return chain.proceed();
                    });
            log("registerPush observer installed package=" + packageName);
        } catch (Throwable error) {
            log("registerPush observer unavailable package=" + packageName
                    + " exception=" + error.getClass().getSimpleName());
        }
    }

    private static boolean classExists(String name, ClassLoader classLoader) {
        try {
            Class.forName(name, false, classLoader);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean looksLikeMiPush(String componentName) {
        String lower = componentName == null ? "" : componentName.toLowerCase();
        return lower.contains("mipush") || lower.contains("xiaomi.push")
                || lower.contains("xmpush") || lower.contains("xiaomi.mipush");
    }

    private static boolean hasIntentParameter(Method method) {
        for (Class<?> type : method.getParameterTypes()) {
            if (Intent.class.isAssignableFrom(type)) {
                return true;
            }
        }
        return false;
    }

    private static Intent firstIntent(List<Object> args) {
        for (Object arg : args) {
            if (arg instanceof Intent) {
                return (Intent) arg;
            }
        }
        return null;
    }

    private static boolean isTargetMiPushIntent(Intent intent) {
        if (intent == null) {
            return false;
        }
        String action = intent.getAction();
        return ("com.xiaomi.mipush.RECEIVE_MESSAGE".equals(action)
                || "com.xiaomi.mipush.miui.RECEIVE_MESSAGE".equals(action)
                || "com.xiaomi.mipush.ERROR".equals(action)
                || "com.xiaomi.push.new_msg".equals(action))
                && TARGET_PACKAGES.contains(targetPackage(intent));
    }

    private static boolean isBilibiliMiPushIntent(Intent intent) {
        if (intent == null) {
            return false;
        }
        String action = intent.getAction();
        return (action == null
                || "com.xiaomi.mipush.REGISTER_APP".equals(action)
                || "com.xiaomi.mipush.CLEAR_NOTIFICATION".equals(action)
                || "com.xiaomi.mipush.RECEIVE_MESSAGE".equals(action)
                || "com.xiaomi.mipush.miui.RECEIVE_MESSAGE".equals(action)
                || "com.xiaomi.mipush.ERROR".equals(action)
                || "com.xiaomi.push.new_msg".equals(action))
                && BILIBILI_DONOR_PACKAGE.equals(targetPackage(intent));
    }

    private static boolean isBilibiliPushHandlerIntent(Intent intent) {
        if (intent == null || !"com.xiaomi.mipush.RECEIVE_MESSAGE".equals(intent.getAction())) {
            return false;
        }
        ComponentName component = intent.getComponent();
        return component != null
                && BILIBILI_DONOR_PACKAGE.equals(component.getPackageName())
                && "com.xiaomi.mipush.sdk.PushMessageHandler".equals(component.getClassName());
    }

    private static String componentName(Intent intent) {
        return intent == null || intent.getComponent() == null
                ? "none" : intent.getComponent().flattenToShortString();
    }

    private static String targetPackage(Intent intent) {
        if (intent == null) {
            return "unknown";
        }
        String appPackage = intent.getStringExtra("mipush_app_package");
        if (appPackage == null) {
            appPackage = intent.getStringExtra("ext_pkg_name");
        }
        if (appPackage != null) {
            return appPackage;
        }
        if (intent.getComponent() != null) {
            return intent.getComponent().getPackageName();
        }
        if (intent.getPackage() != null) {
            return intent.getPackage();
        }
        return "unknown";
    }

    private String getRelayAuthToken(Context context) {
        String cached = xmsfRelayAuthToken;
        if (cached != null && !cached.isEmpty()) return cached;
        if (context == null) return "";
        try {
            Context appContext = context.getApplicationContext();
            if (appContext != null) context = appContext;
            Bundle result = context.getContentResolver().call(
                    DebugLogSettings.CONTENT_URI,
                    DebugLogSettings.METHOD_GET_RELAY_AUTH_TOKEN, null, null);
            String token = result == null ? null
                    : result.getString(DebugLogSettings.EXTRA_RELAY_AUTH_TOKEN);
            if (token == null || token.isEmpty()) return "";
            xmsfRelayAuthToken = token;
            return token;
        } catch (Throwable error) {
            log("xmsf relay auth token unavailable exception="
                    + error.getClass().getSimpleName());
            return "";
        }
    }

    private static int resultCount(Object result) {
        if (result instanceof List<?>) {
            return ((List<?>) result).size();
        }
        if (result != null) {
            try {
                Object list = result.getClass().getMethod("getList").invoke(result);
                if (list instanceof List<?>) {
                    return ((List<?>) list).size();
                }
            } catch (Throwable ignored) {
                // Keep the observer read-only if this Android version uses another wrapper.
            }
        }
        return -1;
    }

    private static String componentNames(Object result, boolean receiverQuery) {
        List<?> entries = null;
        if (result instanceof List<?>) {
            entries = (List<?>) result;
        } else if (result != null) {
            try {
                Object list = result.getClass().getMethod("getList").invoke(result);
                if (list instanceof List<?>) {
                    entries = (List<?>) list;
                }
            } catch (Throwable ignored) {
                return "unknown";
            }
        }
        if (entries == null || entries.isEmpty()) {
            return "none";
        }
        List<String> names = new ArrayList<>();
        for (Object entry : entries) {
            try {
                Object info = entry.getClass().getField(
                        receiverQuery ? "activityInfo" : "serviceInfo").get(entry);
                String name = (String) info.getClass().getField("name").get(info);
                if (name != null) {
                    names.add(name);
                }
            } catch (Throwable ignored) {
                names.add("unreadable");
            }
        }
        Collections.sort(names);
        return String.join(",", names);
    }

    private static void log(String message) {
        DebugLogSettings.log(TAG, message);
    }
}
