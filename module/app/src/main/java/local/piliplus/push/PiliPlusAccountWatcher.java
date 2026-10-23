package local.piliplus.push;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reads only the AccountType.main entry from PiliPlus's Hive account box. */
final class PiliPlusAccountWatcher {
    private static final String TAG = "PiliPlusPush";
    private static final String PILIPLUS_PACKAGE = "com.example.piliplus";
    private static final String ACTION_REQUEST_ACCOUNT_STATE =
            "local.piliplus.push.REQUEST_ACCOUNT_STATE";
    private static final String EXTRA_MAIN_ACCOUNT_MID = "main_account_mid";
    private static final String EXTRA_MAIN_ACCOUNT_ACCESS_KEY = "main_account_access_key";
    private static final String EXTRA_MAIN_ACCOUNT_BUVID3 = "main_account_buvid3";
    private static final long STARTUP_CHECK_DELAY_SECONDS = 5L;
    private static final long MISSING_BOX_RETRY_DELAY_SECONDS = 3L;

    private static final ScheduledExecutorService EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "PiliPlusMainAccountWatcher");
                thread.setDaemon(true);
                return thread;
            });
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final AtomicBoolean SNAPSHOT_REQUEST_IN_FLIGHT = new AtomicBoolean();

    private PiliPlusAccountWatcher() {}

    static void start(Context context) {
        if (!STARTED.compareAndSet(false, true)) return;
        Context appContext = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        File accountFile = new File(new File(appContext.getFilesDir(), "hive"), "account.hive");
        registerSnapshotRequestReceiver(appContext, accountFile);
        EXECUTOR.schedule(() -> {
            if (!shouldReadSnapshot(appContext)) {
                DebugLogSettings.log(TAG,
                        "main_account_watch startup_snapshot_read=skipped reason=snapshot_current");
                return;
            }
            if (!readAndReport(appContext, accountFile)) {
                EXECUTOR.schedule(() -> readAndReport(appContext, accountFile),
                        MISSING_BOX_RETRY_DELAY_SECONDS, TimeUnit.SECONDS);
            }
        },
                STARTUP_CHECK_DELAY_SECONDS, TimeUnit.SECONDS);
        DebugLogSettings.log(TAG, "main_account_watch started package=" + appContext.getPackageName()
                + " account_box_initially_present=" + accountFile.isFile());
    }

    private static boolean shouldReadSnapshot(Context context) {
        try {
            android.os.Bundle result = context.getContentResolver().call(
                    DebugLogSettings.CONTENT_URI,
                    DebugLogSettings.METHOD_SHOULD_READ_ACCOUNT_SNAPSHOT, null, null);
            return result != null && result.getBoolean("should_read", false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Ask a running PiliPlus process to refresh its account snapshot on explicit user action. */
    static void requestFreshSnapshot(Context context) {
        try {
            String nonce = UUID.randomUUID().toString() + UUID.randomUUID();
            context.getSharedPreferences("push_state", Context.MODE_PRIVATE).edit()
                    .putBoolean("main_account_snapshot_refresh_pending", true)
                    .putString("main_account_snapshot_request_nonce", nonce)
                    .putLong("main_account_snapshot_request_time", System.currentTimeMillis())
                    .apply();
            Intent request = new Intent(ACTION_REQUEST_ACCOUNT_STATE);
            request.setPackage(PILIPLUS_PACKAGE);
            request.putExtra(DebugLogSettings.EXTRA_ACCOUNT_SNAPSHOT_NONCE, nonce);
            context.sendBroadcast(request);
            DebugLogSettings.log(TAG, "main_account_watch refresh_requested target=" + PILIPLUS_PACKAGE);
        } catch (Throwable error) {
            DebugLogSettings.log(TAG, "main_account_watch refresh_request_failed exception="
                    + error.getClass().getSimpleName());
        }
    }

    private static void registerSnapshotRequestReceiver(Context context, File accountFile) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                if (intent != null
                        && ACTION_REQUEST_ACCOUNT_STATE.equals(intent.getAction())) {
                    if (!SNAPSHOT_REQUEST_IN_FLIGHT.compareAndSet(false, true)) return;
                    String nonce = intent.getStringExtra(
                            DebugLogSettings.EXTRA_ACCOUNT_SNAPSHOT_NONCE);
                    PendingResult pendingResult = goAsync();
                    try {
                        EXECUTOR.execute(() -> {
                            try {
                                if (!isAuthorizedSnapshotRequest(context, nonce)) {
                                    DebugLogSettings.log(TAG,
                                            "main_account_watch refresh_request=rejected reason=invalid_nonce");
                                    return;
                                }
                                if (!readAndReport(context, accountFile)) {
                                    EXECUTOR.schedule(() -> readAndReport(context, accountFile),
                                            MISSING_BOX_RETRY_DELAY_SECONDS, TimeUnit.SECONDS);
                                }
                            } finally {
                                SNAPSHOT_REQUEST_IN_FLIGHT.set(false);
                                pendingResult.finish();
                            }
                        });
                    } catch (RejectedExecutionException rejected) {
                        SNAPSHOT_REQUEST_IN_FLIGHT.set(false);
                        pendingResult.finish();
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_REQUEST_ACCOUNT_STATE);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            DebugLogSettings.log(TAG, "main_account_watch refresh_receiver_installed=true");
        } catch (Throwable error) {
            DebugLogSettings.log(TAG, "main_account_watch refresh_receiver_failed exception="
                    + error.getClass().getSimpleName());
        }
    }

    private static boolean isAuthorizedSnapshotRequest(Context context, String nonce) {
        if (nonce == null || nonce.length() != 72) return false;
        try {
            android.os.Bundle result = context.getContentResolver().call(
                    DebugLogSettings.CONTENT_URI,
                    DebugLogSettings.METHOD_CONSUME_ACCOUNT_SNAPSHOT_REQUEST,
                    nonce, null);
            return result != null && result.getBoolean("authorized", false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean readAndReport(Context context, File accountFile) {
        if (!accountFile.isFile()) {
            DebugLogSettings.log(TAG, "main_account_watch snapshot_read_skipped reason=account_box_not_ready");
            return false;
        }
        try {
            MainAccountState state = HiveAccountReader.read(accountFile);
            if (state.valid) {
                reportSnapshot(context, state.mid, state.accessKey, state.buvid3);
                return true;
            }
            DebugLogSettings.log(TAG, "main_account_watch snapshot_read_skipped reason=invalid_account_box");
            return false;
        } catch (Throwable error) {
            DebugLogSettings.log(TAG, "main_account_watch read_failed exception="
                    + error.getClass().getSimpleName() + " detail="
                    + (error.getMessage() == null ? "none"
                    : error.getMessage().replace(' ', '_').replace('\n', '_')));
            return false;
        }
    }

    private static void reportSnapshot(Context context, String mainMid, String accessKey,
                                       String buvid3) {
        String normalized = mainMid == null ? "" : mainMid;
        String normalizedAccessKey = accessKey == null ? "" : accessKey;
        String normalizedBuvid3 = buvid3 == null ? "" : buvid3;
        DebugLogSettings.log(TAG, "main_account_watch snapshot_read main_account_present="
                + !normalized.isEmpty() + " server_auth_present="
                + (!normalized.isEmpty() && !normalizedAccessKey.isEmpty())
                + " buvid_present=" + !normalizedBuvid3.isEmpty());
        sendAccountState(context, normalized, normalizedAccessKey, normalizedBuvid3);
    }

    private static void sendAccountState(Context context, String mainMid, String accessKey,
                                         String buvid3) {
        try {
            android.os.Bundle snapshot = new android.os.Bundle();
            snapshot.putString(EXTRA_MAIN_ACCOUNT_MID, mainMid == null ? "" : mainMid);
            snapshot.putString(EXTRA_MAIN_ACCOUNT_ACCESS_KEY,
                    accessKey == null ? "" : accessKey);
            snapshot.putString(EXTRA_MAIN_ACCOUNT_BUVID3, buvid3 == null ? "" : buvid3);
            android.os.Bundle result = context.getContentResolver().call(
                    DebugLogSettings.CONTENT_URI, DebugLogSettings.METHOD_REPORT_MAIN_ACCOUNT,
                    null, snapshot);
            boolean accepted = result != null && result.getBoolean("accepted", false);
            DebugLogSettings.log(TAG, "main_account_watch dispatch_accepted=" + accepted
                    + " main_account_present=" + (mainMid != null && !mainMid.isEmpty()));
        } catch (Throwable error) {
            DebugLogSettings.log(TAG, "main_account_watch dispatch_failed exception="
                    + error.getClass().getSimpleName());
        }
    }

    private static final class MainAccountState {
        final boolean valid;
        final String mid;
        final String accessKey;
        final String buvid3;

        MainAccountState(boolean valid, String mid, String accessKey, String buvid3) {
            this.valid = valid;
            this.mid = mid;
            this.accessKey = accessKey;
            this.buvid3 = buvid3;
        }
    }

    /** Minimal reader for the Hive CE frame and the two PiliPlus account adapters it needs. */
    private static final class HiveAccountReader {
        private static final long MAX_ACCOUNT_BOX_BYTES = 16L * 1024L * 1024L;
        private static final int MAX_ACCOUNT_FRAMES = 10_000;
        private static final int MAX_COLLECTION_ITEMS = 10_000;
        private static final int MAX_NESTING_DEPTH = 64;
        private static final int TYPE_NULL = 0;
        private static final int TYPE_INT = 1;
        private static final int TYPE_DOUBLE = 2;
        private static final int TYPE_BOOL = 3;
        private static final int TYPE_STRING = 4;
        private static final int TYPE_BYTES = 5;
        private static final int TYPE_INT_LIST = 6;
        private static final int TYPE_DOUBLE_LIST = 7;
        private static final int TYPE_BOOL_LIST = 8;
        private static final int TYPE_STRING_LIST = 9;
        private static final int TYPE_LIST = 10;
        private static final int TYPE_MAP = 11;
        private static final int TYPE_HIVE_LIST = 12;
        private static final int TYPE_INT_SET = 13;
        private static final int TYPE_DOUBLE_SET = 14;
        private static final int TYPE_STRING_SET = 15;
        private static final int TYPE_DATE_TIME = 16;
        private static final int TYPE_BIG_INT = 17;
        private static final int TYPE_DATE_TIME_WITH_TIMEZONE = 18;
        private static final int TYPE_DYNAMIC_SET = 19;
        private static final int TYPE_DURATION = 20;
        private static final int TYPE_CUSTOM = 21;
        private static final int PILIPLUS_COOKIE_JAR_TYPE = 8;
        private static final int PILIPLUS_LOGIN_ACCOUNT_TYPE = 9;

        static MainAccountState read(File file) throws IOException {
            if (!file.isFile()) {
                return new MainAccountState(false, null, null, null);
            }
            if (Files.size(file.toPath()) > MAX_ACCOUNT_BOX_BYTES) {
                throw new IOException("account_box_too_large");
            }
            byte[] bytes = Files.readAllBytes(file.toPath());
            if (bytes.length == 0) {
                return new MainAccountState(true, null, null, null);
            }

            Map<String, AccountRecord> accounts = new LinkedHashMap<>();
            int offset = 0;
            int frames = 0;
            while (offset + 4 <= bytes.length) {
                int frameLength = readInt32(bytes, offset);
                if (frameLength < 9 || offset + frameLength > bytes.length) {
                    // Hive appends frames; an incomplete tail can appear during a write.
                    break;
                }
                Cursor cursor = new Cursor(bytes, offset + 4, offset + frameLength - 4);
                Object key = cursor.readKey();
                Object value = cursor.position >= cursor.limit ? null : cursor.readValue();
                if (!(key instanceof String)) {
                    return new MainAccountState(false, null, null, null);
                }
                String mid = (String) key;
                if (value instanceof AccountRecord) {
                    accounts.put(mid, (AccountRecord) value);
                } else if (value == null) {
                    accounts.remove(mid);
                }
                if (frames >= MAX_ACCOUNT_FRAMES) {
                    throw new IOException("too_many_account_frames");
                }
                frames++;
                offset += frameLength;
            }
            if (frames == 0) {
                return new MainAccountState(false, null, null, null);
            }
            for (Map.Entry<String, AccountRecord> account : accounts.entrySet()) {
                if (account.getValue().mainAccount) {
                    return new MainAccountState(true, account.getKey(),
                            account.getValue().accessKey, account.getValue().buvid3);
                }
            }
            return new MainAccountState(true, null, null, null);
        }

        private static int readInt32(byte[] bytes, int offset) {
            return (bytes[offset] & 0xff)
                    | ((bytes[offset + 1] & 0xff) << 8)
                    | ((bytes[offset + 2] & 0xff) << 16)
                    | ((bytes[offset + 3] & 0xff) << 24);
        }

        private static final class AccountRecord {
            final boolean mainAccount;
            final String accessKey;
            final String buvid3;

            AccountRecord(boolean mainAccount, String accessKey, String buvid3) {
                this.mainAccount = mainAccount;
                this.accessKey = accessKey;
                this.buvid3 = buvid3;
            }
        }

        private static final class Cursor {
            final byte[] bytes;
            final int limit;
            int position;
            private int nestingDepth;

            Cursor(byte[] bytes, int position, int limit) throws IOException {
                if (position < 0 || limit < position || limit > bytes.length) {
                    throw new IOException("invalid_frame_bounds");
                }
                this.bytes = bytes;
                this.position = position;
                this.limit = limit;
            }

            Object readValue() throws IOException {
                if (nestingDepth >= MAX_NESTING_DEPTH) {
                    throw new IOException("value_nesting_too_deep");
                }
                nestingDepth++;
                try {
                    int type = readUnsignedByte();
                    switch (type) {
                    case TYPE_NULL:
                        return null;
                    case TYPE_INT:
                        return readInt64();
                    case TYPE_DOUBLE:
                        return Double.longBitsToDouble(readInt64());
                    case TYPE_BOOL:
                        return readUnsignedByte() != 0;
                    case TYPE_STRING:
                        return readString();
                    case TYPE_BYTES:
                        skip(readLength());
                        return null;
                    case TYPE_INT_LIST:
                    case TYPE_DOUBLE_LIST:
                        skip(multiply(readLength(), 8));
                        return null;
                    case TYPE_BOOL_LIST:
                        skip(readLength());
                        return null;
                    case TYPE_STRING_LIST:
                        return readStringCollection();
                    case TYPE_LIST:
                        return readList();
                    case TYPE_MAP:
                        return readMap();
                    case TYPE_HIVE_LIST:
                        return readList();
                    case TYPE_INT_SET:
                    case TYPE_DOUBLE_SET:
                        skip(multiply(readLength(), 8));
                        return null;
                    case TYPE_STRING_SET:
                        return readStringCollection();
                    case TYPE_DATE_TIME:
                    case TYPE_DATE_TIME_WITH_TIMEZONE:
                    case TYPE_DURATION:
                        skip(8);
                        return null;
                    case TYPE_BIG_INT:
                        return readString();
                    case TYPE_DYNAMIC_SET:
                        return readList();
                    case TYPE_CUSTOM:
                        return readCustomValue(readUnsignedShort());
                    default:
                        if (type >= 32) {
                            // Hive encodes the common custom adapter IDs as typeId + 32.
                            return readCustomValue(type - 32);
                        }
                        throw new IOException("unknown_value_type_" + type
                                + "_at_" + (position - 1));
                    }
                } finally {
                    nestingDepth--;
                }
            }

            Object readKey() throws IOException {
                int type = readUnsignedByte();
                if (type == 0) {
                    return readInt32Value();
                }
                if (type == 1) {
                    int length = readUnsignedByte();
                    require(length);
                    String value = new String(bytes, position, length, StandardCharsets.UTF_8);
                    position += length;
                    return value;
                }
                throw new IOException("unknown_key_type_" + type);
            }

            private Object readCustomValue(int typeId) throws IOException {
                if (typeId == PILIPLUS_LOGIN_ACCOUNT_TYPE) {
                    return readAccount();
                }
                if (typeId == PILIPLUS_COOKIE_JAR_TYPE) {
                    Object cookieMap = readMap();
                    if (cookieMap instanceof Map<?, ?>) {
                        Object buvid = ((Map<?, ?>) cookieMap).get("buvid3");
                        return new CookieJarRecord(buvid instanceof String ? (String) buvid : null);
                    }
                    return new CookieJarRecord(null);
                }
                // AccountType is an enum adapter; Hive stores its ordinal as one byte.
                return readUnsignedByte();
            }

            private AccountRecord readAccount() throws IOException {
                int fieldCount = readUnsignedByte();
                boolean hasMainType = false;
                String accessKey = null;
                String buvid3 = null;
                for (int i = 0; i < fieldCount; i++) {
                    int field = readUnsignedByte();
                    Object value = readValue();
                    if (field == 0 && value instanceof CookieJarRecord) {
                        buvid3 = ((CookieJarRecord) value).buvid3;
                    } else if (field == 1 && value instanceof String) {
                        accessKey = (String) value;
                    } else if (field == 3) {
                        if (!(value instanceof Iterable<?>)) continue;
                        for (Object accountType : (Iterable<?>) value) {
                            if (accountType instanceof Number) {
                                if (((Number) accountType).intValue() == 0) {
                                    hasMainType = true;
                                }
                            }
                        }
                    }
                }
                return new AccountRecord(hasMainType, hasMainType ? accessKey : null,
                        hasMainType ? buvid3 : null);
            }

            private Object readList() throws IOException {
                int length = readCollectionLength();
                java.util.ArrayList<Object> values = new java.util.ArrayList<>(length);
                for (int i = 0; i < length; i++) {
                    values.add(readValue());
                }
                return values;
            }

            private Object readMap() throws IOException {
                int length = readCollectionLength();
                Map<Object, Object> values = new LinkedHashMap<>(length);
                for (int i = 0; i < length; i++) {
                    Object key = readValue();
                    Object value = readValue();
                    values.put(key, value);
                }
                return values;
            }

            private Object readStringCollection() throws IOException {
                int length = readCollectionLength();
                java.util.ArrayList<String> values = new java.util.ArrayList<>(length);
                for (int i = 0; i < length; i++) {
                    values.add(readString());
                }
                return values;
            }

            private String readString() throws IOException {
                int length = readLength();
                require(length);
                String value = new String(bytes, position, length, StandardCharsets.UTF_8);
                position += length;
                return value;
            }

            private int readLength() throws IOException {
                int length = readInt32Value();
                if (length < 0 || length > 1_000_000) {
                    throw new IOException("invalid_collection_length");
                }
                return length;
            }

            private int readCollectionLength() throws IOException {
                int length = readLength();
                if (length > MAX_COLLECTION_ITEMS) {
                    throw new IOException("collection_too_large");
                }
                return length;
            }

            private int readInt32Value() throws IOException {
                require(4);
                int length = readInt32(bytes, position);
                position += 4;
                return length;
            }

            private int readUnsignedByte() throws IOException {
                require(1);
                return bytes[position++] & 0xff;
            }

            private int readUnsignedShort() throws IOException {
                require(2);
                int value = (bytes[position] & 0xff) | ((bytes[position + 1] & 0xff) << 8);
                position += 2;
                return value;
            }

            private long readInt64() throws IOException {
                require(8);
                long value = 0;
                for (int i = 0; i < 8; i++) {
                    value |= ((long) bytes[position + i] & 0xffL) << (i * 8);
                }
                position += 8;
                return value;
            }

            private void skip(int count) throws IOException {
                require(count);
                position += count;
            }

            private void require(int count) throws IOException {
                if (count < 0 || position + count > limit) {
                    throw new IOException("unexpected_end_of_frame");
                }
            }

            private int multiply(int value, int factor) throws IOException {
                if (value > Integer.MAX_VALUE / factor) {
                    throw new IOException("collection_overflow");
                }
                return value * factor;
            }
        }

        private static final class CookieJarRecord {
            final String buvid3;

            CookieJarRecord(String buvid3) {
                this.buvid3 = buvid3;
            }
        }
    }
}
