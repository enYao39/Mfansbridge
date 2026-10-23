package local.piliplus.push;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.IBinder;
import android.provider.Settings;

import dalvik.system.DexClassLoader;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;

/** Runs the Bilibili MiPush registration sequence from the module's ordinary app process. */
final class PushRegistration {
    private static final String TAG = "PiliPlusPush";
    private static final String FRAMEWORK_PACKAGE = "com.xiaomi.xmsf";
    // XMPushServiceCore is intentionally private in MiPushFramework. Third-party ingress goes
    // through the exported compatibility facade, which forwards requests to the core service.
    private static final String FRAMEWORK_SERVICE = "com.xiaomi.push.service.XMPushService";
    private static final String BILIBILI_PACKAGE = "tv.danmaku.bili";
    static final String FORCE_REGISTER_EXTRA =
            "local.piliplus.push.extra.FORCE_BILIBILI_REGISTER";
    private static final String MIPUSH_CLIENT = "com.xiaomi.mipush.sdk.MiPushClient";
    // Public client metadata copied from the supplied mainland Bilibili APK. Bilibili's
    // wrapper removes the final obfuscation character before passing each value to MiPush.
    private static final String BILIBILI_APP_ID_METADATA = "2882303761517136414L";
    private static final String BILIBILI_APP_KEY_METADATA = "5701713650414L";

    private PushRegistration() {}

    interface SdkObserverInstaller {
        void install(ClassLoader loader);
    }

    static String register(Context context) {
        return register(context, null);
    }

    static String register(Context context, SdkObserverInstaller observerInstaller) {
        return registerInternal(context, observerInstaller, false);
    }

    /** Ask XMSF to run the SDK registration under its network-enabled process identity. */
    static String requestFrameworkRegistration(Context context, boolean forced) {
        SharedPreferences state = context.getSharedPreferences("push_state", Context.MODE_PRIVATE);
        state.edit()
                .putBoolean("registration_result_received", false)
                .putBoolean("registration_success", false)
                .remove("registration_error_code")
                .remove("registration_package")
                .putBoolean("registration_request_submitted", false)
                .putBoolean("registration_request_forced", forced)
                .putString("registration_request_status", "pending")
                .apply();

        try {
            Intent request = new Intent();
            request.setComponent(new ComponentName(FRAMEWORK_PACKAGE, FRAMEWORK_SERVICE));
            request.putExtra("mipush_app_package", BILIBILI_PACKAGE);
            request.putExtra(FORCE_REGISTER_EXTRA, forced);
            ComponentName started = context.startService(request);
            if (started == null) {
                DebugLogSettings.log(TAG, "bilibili_framework_register rejected reason=service_not_started"
                        + " forced=" + forced);
                state.edit()
                        .putString("registration_request_status", "failed")
                        .putString("registration_request_detail",
                                "MiPushFramework did not accept the registration request.")
                        .apply();
                return "Registration failed: MiPushFramework did not accept the request.";
            }
            state.edit()
                    .putBoolean("registration_request_submitted", true)
                    .putLong("registration_request_time", System.currentTimeMillis())
                    .putBoolean("registration_request_forced", forced)
                    .putString("registration_request_status", "submitted")
                    .putString("registration_request_detail",
                            "Request sent to MiPushFramework under its service process.")
                    .apply();
            DebugLogSettings.log(TAG, "bilibili_framework_register submitted component="
                    + started.flattenToShortString() + " forced=" + forced);
            return "Registration request submitted to MiPushFramework for tv.danmaku.bili.";
        } catch (Throwable error) {
            String detail = error.getClass().getSimpleName();
            state.edit()
                    .putString("registration_request_status", "failed")
                    .putString("registration_request_detail", detail)
                    .apply();
            DebugLogSettings.log(TAG, "bilibili_framework_register failed exception=" + detail);
            return "Registration failed: " + detail + ".";
        }
    }

    /** Reset this module's isolated SDK registration state and submit a fresh request. */
    static String forceRegister(Context context, SdkObserverInstaller observerInstaller) {
        SharedPreferences state = context.getSharedPreferences("push_state", Context.MODE_PRIVATE);
        state.edit()
                .putBoolean("registration_result_received", false)
                .putBoolean("registration_success", false)
                .remove("registration_error_code")
                .remove("registration_package")
                .apply();
        return registerInternal(context, observerInstaller, true);
    }

    private static String registerInternal(Context context,
                                          SdkObserverInstaller observerInstaller,
                                          boolean forced) {
        String[] values = readCredentials(context);
        if (values == null) {
            DebugLogSettings.log(TAG, "bilibili_identity_register skipped reason=credentials_missing");
            return "Skipped: this build has no local Bilibili credentials.";
        }

        String appId = stripMetadataSuffix(values[0]);
        String appKey = stripMetadataSuffix(values[1]);
        if (appId == null || appKey == null) {
            DebugLogSettings.log(TAG, "bilibili_identity_register skipped reason=invalid_credentials");
            return "Skipped: local credential metadata is incomplete.";
        }
        DebugLogSettings.log(TAG, "bilibili_identity_register credentials app_id_present=true app_key_present=true");
        logRuntimePreflight(context);

        String stage = "resolve_framework_sdk";
        try {
            Context appContext = context.getApplicationContext();
            if (appContext == null) {
                appContext = context;
            }
            Context frameworkContext = appContext.createPackageContext(
                    FRAMEWORK_PACKAGE,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            boolean isolateFrameworkState = FRAMEWORK_PACKAGE.equals(appContext.getPackageName());
            ClassLoader frameworkLoader = isolateFrameworkState
                    ? createIsolatedFrameworkLoader(frameworkContext)
                    : frameworkContext.getClassLoader();
            if (observerInstaller != null) {
                observerInstaller.install(frameworkLoader);
            }
            // Keep all SDK files isolated from both PiliPlus and XMSF, for registration,
            // response persistence, account commands, and future retries.
            Context identityContext = new BilibiliIdentityApplication(
                    appContext, frameworkLoader, true);

            Class<?> clientClass = Class.forName(MIPUSH_CLIENT, false, frameworkLoader);
            Class<?> companionClass = Class.forName(
                    MIPUSH_CLIENT + "$Companion", false, frameworkLoader);
            Class<?> configurationClass = Class.forName(
                    "com.xiaomi.mipush.sdk.PushConfiguration", false, frameworkLoader);
            Class<?> regionClass = Class.forName(
                    "com.xiaomi.push.service.module.PushChannelRegion", false, frameworkLoader);

            stage = "configure_china_region";
            Object configuration = configurationClass.getConstructor().newInstance();
            @SuppressWarnings({"rawtypes", "unchecked"})
            Object chinaRegion = Enum.valueOf((Class<? extends Enum>) regionClass, "China");
            Field regionField = configurationClass.getDeclaredField("region");
            regionField.setAccessible(true);
            regionField.set(configuration, chinaRegion);

            stage = "resolve_register_push";
            Method register = companionClass.getDeclaredMethod(
                    "registerPush", Context.class, String.class, String.class,
                    configurationClass);
            Object companion = clientClass.getField("Companion").get(null);

            if (forced) {
                stage = "resolve_app_info_holder";
                Class<?> appInfoHolderClass = Class.forName(
                        "com.xiaomi.mipush.sdk.AppInfoHolder", false, frameworkLoader);
                Method getAppInfoHolder;
                Object appInfoHolderReceiver;
                try {
                    getAppInfoHolder = appInfoHolderClass.getMethod("getInstance", Context.class);
                    appInfoHolderReceiver = null;
                } catch (NoSuchMethodException noStaticBridge) {
                    Class<?> appInfoHolderCompanionClass = Class.forName(
                            "com.xiaomi.mipush.sdk.AppInfoHolder$Companion",
                            false, frameworkLoader);
                    appInfoHolderReceiver = appInfoHolderClass.getField("Companion").get(null);
                    getAppInfoHolder = appInfoHolderCompanionClass.getMethod(
                            "getInstance", Context.class);
                }
                Object appInfoHolder = getAppInfoHolder.invoke(
                        appInfoHolderReceiver, identityContext);
                stage = "clear_sdk_registration_state";
                appInfoHolderClass.getMethod("clear").invoke(appInfoHolder);

                // registerPush() skips its registration branch for five seconds after any
                // initialize pass. Reset that SDK throttle too, using the same isolated
                // SharedPreferences instance the SDK will read. Deleting the XML file alone
                // is insufficient because Android caches SharedPreferences in process memory.
                identityContext.getSharedPreferences("mipush_extra", Context.MODE_PRIVATE)
                        .edit().remove("last_reg_request").apply();
                DebugLogSettings.log(TAG, "bilibili_force_register sdk_state_cleared=true throttle_reset=true");
            }

            DebugLogSettings.log(TAG, "bilibili_identity_register invoking package="
                    + identityContext.getPackageName() + " region=China forced=" + forced);
            stage = "invoke_register_push";
            register.invoke(companion, identityContext, appId, appKey, configuration);
            DebugLogSettings.log(TAG, "bilibili_identity_register invocation_returned package="
                    + identityContext.getPackageName());
            context.getSharedPreferences("push_state", Context.MODE_PRIVATE).edit()
                    .putBoolean("registration_request_submitted", true)
                    .putLong("registration_request_time", System.currentTimeMillis())
                    .putBoolean("registration_request_forced", forced)
                    .putString("registration_request_status", "submitted")
                    .apply();
            return "Registration request submitted for tv.danmaku.bili (China).";
        } catch (Throwable error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            DebugLogSettings.log(TAG, "bilibili_identity_register failed stage=" + stage + " exception="
                    + cause.getClass().getSimpleName());
            return "Registration failed at " + stage + ": "
                    + cause.getClass().getSimpleName() + ".";
        }
    }

    static Context createBilibiliIdentityApplication(Context base, ClassLoader frameworkLoader) {
        return new BilibiliIdentityApplication(base, frameworkLoader, true);
    }

    /** Keep MiPush SDK singletons and local preferences separate from XMSF's own registration. */
    static ClassLoader createIsolatedFrameworkLoader(Context frameworkContext)
            throws Exception {
        return createIsolatedFrameworkLoader(frameworkContext, frameworkContext);
    }

    static ClassLoader createIsolatedFrameworkLoader(Context storageContext,
                                                     Context frameworkContext)
            throws Exception {
        ApplicationInfo frameworkInfo = frameworkContext.getApplicationInfo();
        List<String> dexPaths = new ArrayList<>();
        if (frameworkInfo.sourceDir != null) {
            dexPaths.add(frameworkInfo.sourceDir);
        }
        if (frameworkInfo.splitSourceDirs != null) {
            Collections.addAll(dexPaths, frameworkInfo.splitSourceDirs);
        }
        if (dexPaths.isEmpty()) {
            throw new IllegalStateException("framework APK path unavailable");
        }
        File optimizedDirectory = new File(
                storageContext.getCodeCacheDir(), "piliplus-bilibili-mipush");
        if (!optimizedDirectory.isDirectory() && !optimizedDirectory.mkdirs()) {
            throw new IllegalStateException("framework code cache unavailable");
        }
        ClassLoader loader = new DexClassLoader(
                String.join(File.pathSeparator, dexPaths),
                optimizedDirectory.getAbsolutePath(),
                frameworkInfo.nativeLibraryDir,
                ClassLoader.getSystemClassLoader());
        DebugLogSettings.log(TAG, "bilibili_identity_register sdk_loader=isolated framework_apk_count="
                + dexPaths.size() + " preferences=namespaced");
        return loader;
    }

    /** Separate reader used by the module process, which needs types from XMSF's runtime loader. */
    static ClassLoader createRelayFrameworkLoader(Context storageContext, Context frameworkContext)
            throws Exception {
        ApplicationInfo frameworkInfo = frameworkContext.getApplicationInfo();
        List<String> dexPaths = new ArrayList<>();
        if (frameworkInfo.sourceDir != null) {
            dexPaths.add(frameworkInfo.sourceDir);
        }
        if (frameworkInfo.splitSourceDirs != null) {
            Collections.addAll(dexPaths, frameworkInfo.splitSourceDirs);
        }
        if (dexPaths.isEmpty()) {
            throw new IllegalStateException("framework APK path unavailable");
        }
        File optimizedDirectory = new File(storageContext.getCodeCacheDir(),
                "piliplus-bilibili-relay");
        if (!optimizedDirectory.isDirectory() && !optimizedDirectory.mkdirs()) {
            throw new IllegalStateException("relay code cache unavailable");
        }
        return new RelayFrameworkClassLoader(
                String.join(File.pathSeparator, dexPaths),
                optimizedDirectory.getAbsolutePath(),
                frameworkInfo.nativeLibraryDir,
                frameworkContext.getClassLoader());
    }

    /** Child-load MiPush classes and share runtime types resolved by XMSF's package loader. */
    private static final class RelayFrameworkClassLoader extends DexClassLoader {
        RelayFrameworkClassLoader(String dexPath, String optimizedDirectory,
                                  String librarySearchPath, ClassLoader parent) {
            super(dexPath, optimizedDirectory, librarySearchPath, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!isRelayChildClass(name)) {
                return super.loadClass(name, resolve);
            }
            synchronized (this) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException missingFromFrameworkApk) {
                        loaded = getParent().loadClass(name);
                    }
                }
                if (resolve && loaded.getClassLoader() == this) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        private static boolean isRelayChildClass(String name) {
            return name.startsWith("com.xiaomi.xmpush.thrift.")
                    || name.startsWith("com.xiaomi.mipush.sdk.")
                    || name.startsWith("com.xiaomi.channel.commonutils.")
                    || name.startsWith("com.xiaomi.push.");
        }
    }

    static Context createBilibiliIdentityContext(Context base, boolean isolatePreferences) {
        return new BilibiliIdentityContext(base, isolatePreferences);
    }

    static String[] readCredentials(Context context) {
        String[] generated = readGeneratedCredentials();
        if (generated != null) {
            DebugLogSettings.log(TAG, "bilibili_identity_register credential_source=local_build_override");
            return generated;
        }
        DebugLogSettings.log(TAG, "bilibili_identity_register credential_source=embedded_module_config");
        return new String[] {BILIBILI_APP_ID_METADATA, BILIBILI_APP_KEY_METADATA};
    }

    private static String[] readGeneratedCredentials() {
        try {
            Class<?> generated = Class.forName(
                    "local.piliplus.push.GeneratedBilibiliCredentials");
            return new String[] {
                    (String) generated.getField("APP_ID_METADATA").get(null),
                    (String) generated.getField("APP_KEY_METADATA").get(null)
            };
        } catch (Throwable ignored) {
            return null;
        }
    }

    static String stripMetadataSuffix(String value) {
        if (value == null || value.length() < 2) {
            return null;
        }
        // Bilibili's BPushKeys.MiKeys removes one obfuscation character from each value.
        return value.substring(0, value.length() - 1);
    }

    private static void logRuntimePreflight(Context context) {
        boolean networkPermission = context.checkSelfPermission(
                "android.permission.ACCESS_NETWORK_STATE")
                == PackageManager.PERMISSION_GRANTED;
        boolean internetPermission = context.checkSelfPermission(
                "android.permission.INTERNET") == PackageManager.PERMISSION_GRANTED;
        boolean activeNetwork = false;
        boolean internetCapability = false;
        boolean validatedNetwork = false;
        try {
            ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(
                    Context.CONNECTIVITY_SERVICE);
            Network network = connectivity == null ? null : connectivity.getActiveNetwork();
            NetworkCapabilities capabilities = connectivity == null || network == null
                    ? null : connectivity.getNetworkCapabilities(network);
            activeNetwork = network != null;
            internetCapability = capabilities != null
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            validatedNetwork = capabilities != null
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Throwable error) {
            DebugLogSettings.log(TAG, "bilibili_identity_register preflight_network_exception="
                    + error.getClass().getSimpleName());
        }
        boolean provisioned = false;
        try {
            provisioned = Settings.Global.getInt(
                    context.getContentResolver(), Settings.Global.DEVICE_PROVISIONED, 0) == 1;
        } catch (Throwable error) {
            DebugLogSettings.log(TAG, "bilibili_identity_register preflight_provision_exception="
                    + error.getClass().getSimpleName());
        }
        DebugLogSettings.log(TAG, "bilibili_identity_register preflight network_permission=" + networkPermission
                + " internet_permission=" + internetPermission
                + " active_network=" + activeNetwork
                + " internet_capability=" + internetCapability
                + " validated_network=" + validatedNetwork
                + " device_provisioned=" + provisioned);
    }

    private static final class BilibiliIdentityContext extends ContextWrapper {
        private final ApplicationInfo applicationInfo;
        private final boolean isolatePreferences;
        private final Map<ServiceConnection, ServiceConnection> observedConnections =
                Collections.synchronizedMap(new WeakHashMap<>());

        BilibiliIdentityContext(Context base, boolean isolatePreferences) {
            super(base);
            this.isolatePreferences = isolatePreferences;
            ApplicationInfo copy = new ApplicationInfo(base.getApplicationInfo());
            copy.packageName = BILIBILI_PACKAGE;
            copy.processName = BILIBILI_PACKAGE;
            applicationInfo = copy;
        }

        @Override
        public ComponentName startService(Intent service) {
            logDispatchIntent("startService", service);
            try {
                ComponentName result = super.startService(service);
                DebugLogSettings.log(TAG, "bilibili_sdk_dispatch route=startService returned="
                        + (result == null ? "null" : result.flattenToShortString()));
                return result;
            } catch (RuntimeException error) {
                DebugLogSettings.log(TAG, "bilibili_sdk_dispatch route=startService threw="
                        + error.getClass().getSimpleName());
                throw error;
            }
        }

        @Override
        public boolean bindService(Intent service, ServiceConnection connection, int flags) {
            logDispatchIntent("bindService", service);
            try {
                ServiceConnection observed = observeConnection(connection);
                boolean result = super.bindService(service, observed, flags);
                if (!result) {
                    observedConnections.remove(connection);
                }
                DebugLogSettings.log(TAG, "bilibili_sdk_dispatch route=bindService returned=" + result);
                return result;
            } catch (RuntimeException error) {
                DebugLogSettings.log(TAG, "bilibili_sdk_dispatch route=bindService threw="
                        + error.getClass().getSimpleName());
                throw error;
            }
        }

        @Override
        public boolean bindService(Intent service, int flags, Executor executor,
                                   ServiceConnection connection) {
            logDispatchIntent("bindServiceExecutor", service);
            try {
                ServiceConnection observed = observeConnection(connection);
                boolean result = super.bindService(service, flags, executor, observed);
                if (!result) {
                    observedConnections.remove(connection);
                }
                DebugLogSettings.log(TAG, "bilibili_sdk_dispatch route=bindServiceExecutor returned=" + result);
                return result;
            } catch (RuntimeException error) {
                DebugLogSettings.log(TAG, "bilibili_sdk_dispatch route=bindServiceExecutor threw="
                        + error.getClass().getSimpleName());
                throw error;
            }
        }

        @Override
        public void unbindService(ServiceConnection connection) {
            ServiceConnection observed = observedConnections.remove(connection);
            DebugLogSettings.log(TAG, "bilibili_sdk_dispatch route=unbindService"
                    + " observer_mapping_present=" + (observed != null));
            super.unbindService(observed == null ? connection : observed);
        }

        @Override
        public String getPackageName() {
            return BILIBILI_PACKAGE;
        }

        @Override
        public String getOpPackageName() {
            return BILIBILI_PACKAGE;
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public ApplicationInfo getApplicationInfo() {
            return applicationInfo;
        }

        @Override
        public SharedPreferences getSharedPreferences(String name, int mode) {
            String storageName = isolatePreferences ? "piliplus_bili_" + name : name;
            SharedPreferences preferences = super.getSharedPreferences(storageName, mode);
            String lowerName = name == null ? "" : name.toLowerCase();
            if (lowerName.contains("mipush") || lowerName.contains("push")) {
                DebugLogSettings.log(TAG, "bilibili_sdk_state preferences file=" + name
                        + " key_count=" + preferences.getAll().size());
            }
            return preferences;
        }

        private static void logDispatchIntent(String route, Intent intent) {
            if (intent == null) {
                DebugLogSettings.log(TAG, "bilibili_sdk_dispatch route=" + route + " intent=null");
                return;
            }
            List<String> names = new ArrayList<>();
            if (intent.getExtras() != null) {
                names.addAll(intent.getExtras().keySet());
            }
            Collections.sort(names);
            byte[] payload = intent.getByteArrayExtra("mipush_payload");
            ComponentName component = intent.getComponent();
            String target = intent.getStringExtra("mipush_app_package");
            DebugLogSettings.log(TAG, "bilibili_sdk_dispatch route=" + route
                    + " action=" + (intent.getAction() == null ? "null" : intent.getAction())
                    + " target=" + (target == null ? "unknown" : target)
                    + " component=" + (component == null ? "none" : component.flattenToShortString())
                    + " payload_bytes=" + (payload == null ? 0 : payload.length)
                    + " app_id_present=" + intent.hasExtra("mipush_app_id")
                    + " app_token_present=" + intent.hasExtra("mipush_app_token")
                    + " extras=" + (names.isEmpty() ? "none" : String.join(",", names)));
        }

        private ServiceConnection observeConnection(ServiceConnection original) {
            if (original == null) {
                return null;
            }
            ServiceConnection observed = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    DebugLogSettings.log(TAG, "bilibili_sdk_dispatch route=serviceConnection"
                            + " event=connected component=" + name.flattenToShortString()
                            + " binder_present=" + (service != null));
                    original.onServiceConnected(name, service);
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    DebugLogSettings.log(TAG, "bilibili_sdk_dispatch route=serviceConnection"
                            + " event=disconnected component=" + name.flattenToShortString());
                    original.onServiceDisconnected(name);
                }

                @Override
                public void onBindingDied(ComponentName name) {
                    DebugLogSettings.log(TAG, "bilibili_sdk_dispatch route=serviceConnection"
                            + " event=binding_died component=" + name.flattenToShortString());
                    original.onBindingDied(name);
                }

                @Override
                public void onNullBinding(ComponentName name) {
                    DebugLogSettings.log(TAG, "bilibili_sdk_dispatch route=serviceConnection"
                            + " event=null_binding component=" + name.flattenToShortString());
                    original.onNullBinding(name);
                }
            };
            observedConnections.put(original, observed);
            return observed;
        }
    }

    /** MiPush SDK internals cast their application context to android.app.Application. */
    private static final class BilibiliIdentityApplication extends Application {
        private final ClassLoader frameworkClassLoader;

        BilibiliIdentityApplication(Context base, ClassLoader frameworkClassLoader,
                                   boolean isolatePreferences) {
            super();
            this.frameworkClassLoader = frameworkClassLoader;
            attachBaseContext(new BilibiliIdentityContext(base, isolatePreferences));
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public ClassLoader getClassLoader() {
            return frameworkClassLoader;
        }
    }
}
