package local.piliplus.push;

import android.app.Activity;
import android.app.ActionBar;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.transition.ChangeBounds;
import android.transition.Fade;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Rounded status dashboard for registration, primary account binding, and notification routing. */
public final class ModuleStatusActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<ExpandableCard> expandableCards = new ArrayList<>();
    private Palette palette;
    private LinearLayout overviewContent;
    private Bundle restoredOverviewState;
    private TextView registrationValue;
    private TextView registrationDetail;
    private TextView accountValue;
    private TextView accountDetail;
    private TextView deliveryValue;
    private TextView deliveryDetail;
    private Button registerButton;
    private Button snapshotButton;
    private Button clearSnapshotButton;
    private Button syncChannelsButton;
    private Switch launcherHideSwitch;
    private Switch videoUpdateSwitch;
    private Switch postUpdateSwitch;
    private Switch liveUpdateSwitch;
    private TextView channelStatus;
    private RadioGroup likeFilterGroup;
    private RadioGroup replyFilterGroup;
    private TextView interactionStatus;
    private Button readInteractionSettingsButton;
    private Button saveInteractionSettingsButton;
    private boolean restoringChannelSettings;
    private long renderedLinkSettingsAt = -1L;
    private boolean linkSettingsRequestInFlight;
    private boolean requestInFlight;
    private boolean clearSnapshotInFlight;

    private final Runnable refreshUi = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            handler.postDelayed(this, 1500L);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        restoredOverviewState = state;
        DebugLogSettings.initializeLocal(this);
        ActionBar actionBar = getActionBar();
        if (actionBar != null) actionBar.hide();
        palette = Palette.forNight(this, isNightMode());
        configureWindow();
        setContentView(buildScreen());
        refreshStatus();
        handler.postDelayed(refreshUi, 1500L);
        SharedPreferences prefs = statePrefs();
        if (!prefs.getBoolean("registration_request_submitted", false)) {
            submitRegistration(false);
        }
        new Thread(() -> MiPushRelayProcessor.synchronizeServerAccountBinding(
                getApplicationContext()), "PiliPlusAccountBindingStartup").start();
        requestInteractionSettings(false);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        for (ExpandableCard section : expandableCards) {
            outState.putBoolean("overview_expanded_" + section.key, section.expanded);
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        handler.removeCallbacks(refreshUi);
        handler.postDelayed(refreshUi, 1500L);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshUi);
        super.onPause();
    }

    private View buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(8));
        root.setBackgroundColor(palette.background);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars()
                                | android.view.WindowInsets.Type.displayCutout());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(dp(16) + left, dp(12) + top,
                    dp(16) + right, dp(8) + bottom);
            return insets;
        });
        root.addView(buildHero(), matchWrap());
        LinearLayout.LayoutParams pageParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(buildOverviewPage(), pageParams);
        return root;
    }

    private View buildOverviewPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(12), 0, dp(32));
        overviewContent = content;
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        content.addView(buildRegistrationCard(), matchWrap());
        addSpace(content, 14);
        content.addView(buildAccountCard(), matchWrap());
        addSpace(content, 14);
        content.addView(buildPushChannelCard(), matchWrap());
        addSpace(content, 14);
        content.addView(buildMiscellaneousCard(), matchWrap());
        addSpace(content, 14);
        content.addView(buildAboutCard(), matchWrap());
        return scroll;
    }

    private ExpandableCard createExpandableCard(String key, String title, String summary) {
        LinearLayout card = card(palette.surfaceContainer, 0);
        card.setClipToOutline(true);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(18), dp(20), dp(18));
        header.setClickable(true);
        header.setFocusable(true);
        setSelectableBackground(header);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = text(title, 16, palette.text, false);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        labels.addView(titleView, matchWrap());
        TextView summaryView = text(summary, 14, palette.secondaryText, false);
        LinearLayout.LayoutParams summaryParams = matchWrap();
        summaryParams.topMargin = dp(4);
        labels.addView(summaryView, summaryParams);
        header.addView(labels, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = text("›", 24, palette.secondaryText, false);
        arrow.setGravity(Gravity.CENTER);
        arrow.setRotation(90f);
        arrow.setContentDescription("展开" + title);
        LinearLayout.LayoutParams arrowParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        arrowParams.leftMargin = dp(12);
        header.addView(arrow, arrowParams);

        View divider = new View(this);
        divider.setBackgroundColor(palette.outlineVariant);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        dividerParams.leftMargin = dp(20);
        dividerParams.rightMargin = dp(20);
        divider.setVisibility(View.GONE);

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(0, 0, 0, dp(20));
        details.setVisibility(View.GONE);

        ExpandableCard section = new ExpandableCard(key, card, header, divider, details,
                arrow, title);
        header.setContentDescription(title + "。" + summary + "。展开或收起");
        header.setOnClickListener(view -> setExpandableCardExpanded(section,
                !section.expanded, true));
        card.addView(header, matchWrap());
        card.addView(divider, dividerParams);
        card.addView(details, matchWrap());
        expandableCards.add(section);
        return section;
    }

    private void finishExpandableCard(ExpandableCard section) {
        boolean expanded = restoredOverviewState != null
                && restoredOverviewState.getBoolean("overview_expanded_" + section.key, false);
        if (expanded) setExpandableCardExpanded(section, true, false);
    }

    private void setExpandableCardExpanded(ExpandableCard section, boolean expanded,
                                           boolean animate) {
        if (section.expanded == expanded) return;
        section.expanded = expanded;
        if (animate && overviewContent != null) {
            TransitionSet transition = new TransitionSet()
                    .setOrdering(TransitionSet.ORDERING_TOGETHER);
            transition.addTransition(new ChangeBounds().setDuration(expanded ? 240L : 200L));
            transition.addTransition(new Fade(expanded ? Fade.IN : Fade.OUT)
                    .setDuration(expanded ? 180L : 150L));
            TransitionManager.beginDelayedTransition(overviewContent, transition);
        }
        section.divider.setVisibility(expanded ? View.VISIBLE : View.GONE);
        section.details.setVisibility(expanded ? View.VISIBLE : View.GONE);
        section.arrow.animate().cancel();
        if (animate) {
            section.arrow.animate().rotation(expanded ? -90f : 90f).setDuration(220L).start();
        } else {
            section.arrow.setRotation(expanded ? -90f : 90f);
        }
        section.arrow.setContentDescription(expanded ? "收起" + section.title
                : "展开" + section.title);
    }

    private void addDetailText(LinearLayout parent, String value, int topMarginDp) {
        TextView view = text(value, 14, palette.secondaryText, false);
        LinearLayout.LayoutParams params = matchWrap();
        params.leftMargin = dp(20);
        params.rightMargin = dp(20);
        params.topMargin = dp(topMarginDp);
        parent.addView(view, params);
    }

    private void addCategoryButton(LinearLayout parent, Button button, int topMarginDp) {
        LinearLayout.LayoutParams params = matchWrap();
        params.leftMargin = dp(20);
        params.rightMargin = dp(20);
        params.topMargin = dp(topMarginDp);
        parent.addView(button, params);
    }

    private void addCategoryDivider(LinearLayout parent, int topMarginDp, int bottomMarginDp) {
        View divider = new View(this);
        divider.setBackgroundColor(palette.outlineVariant);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        params.leftMargin = dp(20);
        params.rightMargin = dp(20);
        params.topMargin = dp(topMarginDp);
        params.bottomMargin = dp(bottomMarginDp);
        parent.addView(divider, params);
    }

    private void addSwitchPreferenceRow(LinearLayout parent, String title, String summary,
                                        int iconResource, Switch toggle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(16), dp(20), dp(16));
        row.setClickable(true);
        row.setFocusable(true);
        setSelectableBackground(row);

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconResource);
        icon.setImageTintList(ColorStateList.valueOf(palette.primaryText));
        icon.setPadding(dp(10), dp(10), dp(10), dp(10));
        icon.setBackground(round(palette.primaryContainer, 21));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(42), dp(42));
        row.addView(icon, iconParams);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        labelsParams.leftMargin = dp(14);
        row.addView(labels, labelsParams);
        labels.addView(text(title, 16, palette.text, false), matchWrap());
        TextView summaryView = text(summary, 14, palette.secondaryText, false);
        LinearLayout.LayoutParams summaryParams = matchWrap();
        summaryParams.topMargin = dp(3);
        labels.addView(summaryView, summaryParams);

        LinearLayout.LayoutParams toggleParams = wrap();
        toggleParams.leftMargin = dp(12);
        row.addView(toggle, toggleParams);
        row.setContentDescription(title + "。" + summary);
        row.setOnClickListener(view -> toggle.setChecked(!toggle.isChecked()));
        parent.addView(row, matchWrap());
    }

    private View buildMiscellaneousCard() {
        ExpandableCard section = createExpandableCard("miscellaneous", "其他设置",
                "Launcher 与调试");
        addDetailHeading(section.details, "Launcher 图标", "桌面入口", 16);
        launcherHideSwitch = new Switch(this);
        launcherHideSwitch.setChecked(isLauncherHidden());
        launcherHideSwitch.setOnCheckedChangeListener((button, hidden) -> setLauncherHidden(hidden));
        addSwitchPreferenceRow(section.details, "隐藏桌面图标",
                "从 Launcher 抽屉中移除 PiliPlus Push 入口",
                android.R.drawable.ic_menu_view, launcherHideSwitch);

        addCategoryDivider(section.details, 8, 8);
        addDetailHeading(section.details, "调试", "日志与 Push 提交方式", 0);
        Switch debugSwitch = new Switch(this);
        debugSwitch.setChecked(statePrefs().getBoolean(DebugLogSettings.PREF_ENABLED, false));
        debugSwitch.setOnCheckedChangeListener((button, enabled) -> {
            DebugLogSettings.setEnabled(this, enabled);
            Toast.makeText(this, enabled ? "详细日志已开启" : "详细日志已关闭",
                    Toast.LENGTH_SHORT).show();
        });
        addSwitchPreferenceRow(section.details, "输出详细 Logcat 日志",
                "排查推送和跳转问题时开启",
                android.R.drawable.ic_menu_info_details, debugSwitch);
        addDetailText(section.details,
                "关闭后不记录详细诊断日志。", 0);

        addCategoryDivider(section.details, 8, 0);
        addDebugSubmissionSwitch(section.details, "Push 订阅提交方式",
                PushTokenReporter.PREF_SUBMIT_CHANNEL_IN_EXTRA);
        addDetailText(section.details,
                "关闭：仅提交 channel；开启：同时写入 extra。两种方式对推送的具体影响尚不明确；修改后需重新保存并同步。",
                0);
        finishExpandableCard(section);
        return section.card;
    }

    private void addDebugSubmissionSwitch(LinearLayout parent, String label, String prefKey) {
        Switch toggle = new Switch(this);
        toggle.setChecked(statePrefs().getBoolean(prefKey, true));
        toggle.setOnCheckedChangeListener((button, enabled) -> {
            statePrefs().edit().putBoolean(prefKey, enabled).apply();
            Toast.makeText(this, enabled ? "提交方式已开启" : "提交方式已关闭",
                    Toast.LENGTH_SHORT).show();
        });
        addSwitchPreferenceRow(parent, label,
                "切换格式；具体推送影响尚不明确",
                android.R.drawable.ic_menu_manage, toggle);
    }

    private void addDetailHeading(LinearLayout parent, String title, String summary,
                                  int topMarginDp) {
        TextView titleView = text(title, 16, palette.text, false);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams titleParams = matchWrap();
        titleParams.leftMargin = dp(20);
        titleParams.rightMargin = dp(20);
        titleParams.topMargin = dp(topMarginDp);
        parent.addView(titleView, titleParams);
        if (summary != null && !summary.isEmpty()) {
            TextView summaryView = text(summary, 14, palette.secondaryText, false);
            LinearLayout.LayoutParams summaryParams = matchWrap();
            summaryParams.leftMargin = dp(20);
            summaryParams.rightMargin = dp(20);
            summaryParams.topMargin = dp(4);
            parent.addView(summaryView, summaryParams);
        }
    }

    private void setSelectableBackground(View view) {
        TypedValue value = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true)
                && value.resourceId != 0) {
            view.setBackgroundResource(value.resourceId);
        }
    }

    private RadioGroup buildFilterGroup(LinearLayout card, String title, String prefKey,
                                       boolean loaded) {
        TextView label = text(title, 15, palette.text, true);
        LinearLayout.LayoutParams labelParams = matchWrap();
        labelParams.leftMargin = dp(20);
        labelParams.rightMargin = dp(20);
        labelParams.topMargin = dp(16);
        card.addView(label, labelParams);

        RadioGroup group = new RadioGroup(this);
        group.setOrientation(LinearLayout.VERTICAL);
        String[] labels = {"所有人", "我关注的人", "不接收提醒"};
        int value = statePrefs().getInt(prefKey, -1);
        for (int i = 0; i < labels.length; i++) {
            RadioButton option = new RadioButton(this);
            option.setId(i + 1);
            option.setText(labels[i]);
            option.setTextSize(14);
            option.setTextColor(palette.text);
            option.setEnabled(loaded);
            group.addView(option, matchWrap());
            if (loaded && value == i) group.check(i + 1);
        }
        LinearLayout.LayoutParams groupParams = matchWrap();
        groupParams.leftMargin = dp(20);
        groupParams.rightMargin = dp(20);
        card.addView(group, groupParams);
        return group;
    }

    private void requestInteractionSettings(boolean save) {
        if (linkSettingsRequestInFlight) return;
        SharedPreferences prefs = statePrefs();
        boolean loaded = prefs.getBoolean("link_settings_loaded", false);
        int likeValue = selectedFilterValue(likeFilterGroup);
        int replyValue = selectedFilterValue(replyFilterGroup);
        if (save && (!loaded || likeValue < 0 || replyValue < 0)) {
            Toast.makeText(this, "请先读取 Mfans 当前提醒设置。", Toast.LENGTH_SHORT).show();
            return;
        }
        linkSettingsRequestInFlight = true;
        refreshInteractionSettings(prefs);
        boolean submitted = LinkSettingsReporter.submit(getApplicationContext(),
                save ? LinkSettingsReporter.OP_WRITE : LinkSettingsReporter.OP_READ,
                likeValue, replyValue);
        if (!submitted) linkSettingsRequestInFlight = false;
        refreshInteractionSettings(prefs);
        if (!submitted) {
            Toast.makeText(this, "没有可用的主账号登录快照，无法读取设置。",
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, save ? "设置已提交，等待 Mfans 确认。" : "正在读取账号设置…",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private int selectedFilterValue(RadioGroup group) {
        if (group == null || group.getCheckedRadioButtonId() <= 0) return -1;
        return group.getCheckedRadioButtonId() - 1;
    }

    private void refreshInteractionSettings(SharedPreferences prefs) {
        if (interactionStatus == null) return;
        long updatedAt = prefs.getLong("link_settings_applied_at", 0L);
        if (updatedAt != renderedLinkSettingsAt) {
            renderedLinkSettingsAt = updatedAt;
            boolean loaded = prefs.getBoolean("link_settings_loaded", false);
            if (loaded) {
                setFilterValue(likeFilterGroup,
                        prefs.getInt("link_settings_like_value", -1));
                setFilterValue(replyFilterGroup,
                        prefs.getInt("link_settings_reply_value", -1));
            }
        }

        String state = prefs.getString("link_settings_state", "");
        if ("pending".equals(state)) {
            long requestTime = prefs.getLong("link_settings_request_time", 0L);
            if (requestTime <= 0L || System.currentTimeMillis() - requestTime > 45_000L) {
                state = "timeout";
                prefs.edit().putString("link_settings_state", state)
                        .putString("link_settings_message", requestTime <= 0L
                                ? "旧版本遗留的 pending 请求已清理"
                                : "XMSF 45 秒内未返回设置结果")
                        .apply();
            }
        }
        linkSettingsRequestInFlight = "pending".equals(state);
        boolean loaded = prefs.getBoolean("link_settings_loaded", false);
        setFilterGroupEnabled(likeFilterGroup, loaded && !linkSettingsRequestInFlight);
        setFilterGroupEnabled(replyFilterGroup, loaded && !linkSettingsRequestInFlight);
        if ("pending".equals(state)) {
            int operation = prefs.getInt("link_settings_pending_operation",
                    LinkSettingsReporter.OP_READ);
            interactionStatus.setText(operation == LinkSettingsReporter.OP_WRITE
                    ? "正在保存提醒设置…" : "正在读取 Mfans 账号设置…");
            interactionStatus.setTextColor(palette.primaryText);
        } else if ("timeout".equals(state)) {
            interactionStatus.setText("读取超时：未收到 XMSF 回包；可重试。若反复发生，请检查模块的 XMSF 作用域。");
            interactionStatus.setTextColor(palette.error);
        } else if ("waiting_access_key".equals(state)) {
            interactionStatus.setText("还没有主账号登录凭据；先登录 PiliPlus 并获取账号快照。");
            interactionStatus.setTextColor(palette.secondaryText);
        } else if ("failed".equals(state) || "rejected".equals(state)) {
            interactionStatus.setText("读取失败：" + linkSettingsErrorText(prefs));
            interactionStatus.setTextColor(palette.error);
        } else if (loaded) {
            String result = prefs.getString("link_settings_last_result", "accepted");
            if ("rejected".equals(result) || "failed".equals(result)) {
                interactionStatus.setText("同步失败：" + linkSettingsErrorText(prefs));
                interactionStatus.setTextColor(palette.error);
            } else {
                interactionStatus.setText("已读取当前设置；保存后同步到服务端。");
                interactionStatus.setTextColor(palette.success);
            }
        } else {
            interactionStatus.setText("先读取账号当前值，再选择提醒范围。");
            interactionStatus.setTextColor(palette.secondaryText);
        }
        if (readInteractionSettingsButton != null) {
            readInteractionSettingsButton.setEnabled(!linkSettingsRequestInFlight);
        }
        if (saveInteractionSettingsButton != null) {
            saveInteractionSettingsButton.setEnabled(loaded && !linkSettingsRequestInFlight);
        }
    }

    private void setFilterGroupEnabled(RadioGroup group, boolean enabled) {
        if (group == null) return;
        for (int i = 0; i < group.getChildCount(); i++) {
            group.getChildAt(i).setEnabled(enabled);
        }
    }

    private void setFilterValue(RadioGroup group, int value) {
        if (group != null && value >= 0 && value <= 2) group.check(value + 1);
    }

    private String linkSettingsErrorText(SharedPreferences prefs) {
        int http = prefs.getInt("link_settings_http_code", -1);
        int api = prefs.getInt("link_settings_api_code", Integer.MIN_VALUE);
        String message = prefs.getString("link_settings_message", "");
        if (http < 0 && message != null && !message.isEmpty()) return message;
        return "HTTP " + http + " · API " + api
                + (message == null || message.isEmpty() ? "" : " · " + message);
    }

    private View buildHero() {
        LinearLayout card = card(palette.primaryContainer, 20);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);

        ImageView icon = new ImageView(this);
        int iconResource = getResources().getIdentifier(
                "ic_launcher", "mipmap", getPackageName());
        if (iconResource != 0) icon.setImageResource(iconResource);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setPadding(dp(6), dp(6), dp(6), dp(6));
        icon.setBackground(round(palette.surface, 22));
        icon.setContentDescription("PiliPlus Push 图标");
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(68), dp(68));
        iconParams.rightMargin = dp(16);
        card.addView(icon, iconParams);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text("PiliPlus Push", 24, palette.primaryText, true), matchWrap());
        TextView subtitle = text("通过 MiPushFramework 接收 Mfans 推送", 14,
                palette.secondaryText, false);
        LinearLayout.LayoutParams subtitleParams = matchWrap();
        subtitleParams.topMargin = dp(4);
        labels.addView(subtitle, subtitleParams);
        card.addView(labels, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return card;
    }

    private View buildRegistrationCard() {
        ExpandableCard section = createExpandableCard("registration", "注册诊断",
                "MiPush 注册状态与通知跳转");
        registrationValue = text("正在读取注册状态…", 17, palette.text, true);
        LinearLayout.LayoutParams valueParams = matchWrap();
        valueParams.leftMargin = dp(20);
        valueParams.rightMargin = dp(20);
        valueParams.topMargin = dp(16);
        section.details.addView(registrationValue, valueParams);
        registrationDetail = text("注册身份：tv.danmaku.bili", 14,
                palette.secondaryText, false);
        LinearLayout.LayoutParams detailParams = matchWrap();
        detailParams.leftMargin = dp(20);
        detailParams.rightMargin = dp(20);
        detailParams.topMargin = dp(7);
        section.details.addView(registrationDetail, detailParams);

        registerButton = new Button(this);
        registerButton.setText("强制重新注册");
        registerButton.setAllCaps(false);
        registerButton.setTextSize(15);
        registerButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        registerButton.setTextColor(palette.buttonText);
        registerButton.setPadding(dp(18), dp(11), dp(18), dp(11));
        registerButton.setBackground(round(palette.primary, 18));
        registerButton.setElevation(0f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            registerButton.setStateListAnimator(null);
        }
        registerButton.setOnClickListener(view -> submitRegistration(true));
        addCategoryButton(section.details, registerButton, 16);
        addDetailText(section.details, "重新请求 MiPush 注册。", 8);

        addCategoryDivider(section.details, 14, 10);
        TextView pushStatusTitle = text("通知跳转", 16, palette.text, true);
        LinearLayout.LayoutParams pushTitleParams = matchWrap();
        pushTitleParams.leftMargin = dp(20);
        pushTitleParams.rightMargin = dp(20);
        section.details.addView(pushStatusTitle, pushTitleParams);
        deliveryValue = text("等待注册回包或推送", 15, palette.text, true);
        LinearLayout.LayoutParams deliveryValueParams = matchWrap();
        deliveryValueParams.leftMargin = dp(20);
        deliveryValueParams.rightMargin = dp(20);
        deliveryValueParams.topMargin = dp(7);
        section.details.addView(deliveryValue, deliveryValueParams);
        deliveryDetail = text("点击通知后跳转到 PiliPlus。", 14,
                palette.secondaryText, false);
        LinearLayout.LayoutParams deliveryDetailParams = matchWrap();
        deliveryDetailParams.leftMargin = dp(20);
        deliveryDetailParams.rightMargin = dp(20);
        deliveryDetailParams.topMargin = dp(4);
        section.details.addView(deliveryDetail, deliveryDetailParams);
        finishExpandableCard(section);
        return section.card;
    }

    private View buildAccountCard() {
        ExpandableCard section = createExpandableCard("account", "主账号",
                "账号快照与 Mfans 绑定状态");
        accountValue = text("等待 PiliPlus 主进程上报…", 17, palette.text, true);
        LinearLayout.LayoutParams valueParams = matchWrap();
        valueParams.leftMargin = dp(20);
        valueParams.rightMargin = dp(20);
        valueParams.topMargin = dp(16);
        section.details.addView(accountValue, valueParams);
        accountDetail = text("同步主账号到 Mfans 推送服务。", 14,
                palette.secondaryText, false);
        LinearLayout.LayoutParams detailParams = matchWrap();
        detailParams.leftMargin = dp(20);
        detailParams.rightMargin = dp(20);
        detailParams.topMargin = dp(7);
        section.details.addView(accountDetail, detailParams);

        snapshotButton = accountActionButton("强制获取账号快照", palette.primary,
                palette.buttonText, view -> requestAccountSnapshot());
        addCategoryButton(section.details, snapshotButton, 16);

        clearSnapshotButton = accountActionButton("清除账号快照", palette.surface,
                palette.text, view -> clearAccountSnapshot());
        addCategoryButton(section.details, clearSnapshotButton, 8);
        finishExpandableCard(section);
        return section.card;
    }

    private Button accountActionButton(String label, int background, int foreground,
                                       View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(foreground);
        button.setPadding(dp(16), dp(9), dp(16), dp(9));
        button.setBackground(round(background, 16));
        button.setElevation(0f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            button.setStateListAnimator(null);
        }
        button.setOnClickListener(listener);
        return button;
    }

    private View buildPushChannelCard() {
        ExpandableCard section = createExpandableCard("bili_push", "Mfans 消息设置",
                "内容订阅与互动提醒");
        addDetailHeading(section.details, "内容更新订阅",
                "视频、动态 / 稿件与直播更新", 16);
        addDetailText(section.details, "管理 Mfans 内容订阅。", 8);

        SharedPreferences prefs = statePrefs();
        videoUpdateSwitch = channelSwitch(prefs.getBoolean(
                "bili_channel_video_update_draft", true), "bili_channel_video_update_draft");
        postUpdateSwitch = channelSwitch(prefs.getBoolean(
                "bili_channel_post_update_draft", true), "bili_channel_post_update_draft");
        liveUpdateSwitch = channelSwitch(prefs.getBoolean(
                "bili_channel_live_update_draft", true), "bili_channel_live_update_draft");
        addSwitchPreferenceRow(section.details, "视频更新推送", "关注的视频有新动态时提醒",
                android.R.drawable.ic_media_play, videoUpdateSwitch);
        addSwitchPreferenceRow(section.details, "动态 / 稿件更新推送", "关注对象发布动态或稿件时提醒",
                android.R.drawable.ic_menu_edit, postUpdateSwitch);
        addSwitchPreferenceRow(section.details, "直播更新推送", "关注对象开播时提醒",
                android.R.drawable.ic_menu_myplaces, liveUpdateSwitch);

        channelStatus = text("", 13, palette.secondaryText, false);
        LinearLayout.LayoutParams channelStatusParams = matchWrap();
        channelStatusParams.leftMargin = dp(20);
        channelStatusParams.rightMargin = dp(20);
        channelStatusParams.topMargin = dp(8);
        section.details.addView(channelStatus, channelStatusParams);
        Button readSavedButton = accountActionButton("恢复本机已保存设置",
                palette.surface, palette.text, view -> restoreSavedPushChannelSettings());
        addCategoryButton(section.details, readSavedButton, 12);
        syncChannelsButton = accountActionButton("保存并同步到 Mfans",
                palette.primary, palette.buttonText, view -> saveAndSyncPushChannels());
        addCategoryButton(section.details, syncChannelsButton, 8);

        addCategoryDivider(section.details, 16, 12);
        addDetailHeading(section.details, "互动消息提醒",
                "收到的点赞、评论与回复提醒范围", 0);
        addDetailText(section.details, "先读取当前提醒设置，再调整范围。", 8);
        readInteractionSettingsButton = accountActionButton("重新读取 Mfans 设置",
                palette.surface, palette.text, view -> requestInteractionSettings(false));
        addCategoryButton(section.details, readInteractionSettingsButton, 8);

        boolean loaded = prefs.getBoolean("link_settings_loaded", false);
        likeFilterGroup = buildFilterGroup(section.details, "收到的赞",
                "link_settings_like_value", loaded);
        replyFilterGroup = buildFilterGroup(section.details, "收到的评论与回复",
                "link_settings_reply_value", loaded);
        interactionStatus = text("", 13, palette.secondaryText, false);
        LinearLayout.LayoutParams interactionStatusParams = matchWrap();
        interactionStatusParams.leftMargin = dp(20);
        interactionStatusParams.rightMargin = dp(20);
        interactionStatusParams.topMargin = dp(8);
        section.details.addView(interactionStatus, interactionStatusParams);
        saveInteractionSettingsButton = accountActionButton("保存并同步提醒设置",
                palette.primary, palette.buttonText, view -> requestInteractionSettings(true));
        addCategoryButton(section.details, saveInteractionSettingsButton, 8);

        refreshPushChannelStatus(prefs);
        refreshInteractionSettings(prefs);
        finishExpandableCard(section);
        return section.card;
    }

    private Switch channelSwitch(boolean checked, String draftKey) {
        Switch toggle = new Switch(this);
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener((button, enabled) -> {
            if (restoringChannelSettings) return;
            SharedPreferences prefs = statePrefs();
            prefs.edit().putBoolean(draftKey, enabled)
                    .putBoolean("bili_channel_draft_changed", true).apply();
            if (channelStatus != null) {
                channelStatus.setText("开关已更改；点击下方按钮同步。 ");
                channelStatus.setTextColor(palette.primaryText);
            }
        });
        return toggle;
    }

    private void restoreSavedPushChannelSettings() {
        SharedPreferences prefs = statePrefs();
        boolean video = prefs.getBoolean("bili_channel_video_update", true);
        boolean post = prefs.getBoolean("bili_channel_post_update", true);
        boolean live = prefs.getBoolean("bili_channel_live_update", true);
        restoringChannelSettings = true;
        if (videoUpdateSwitch != null) videoUpdateSwitch.setChecked(video);
        if (postUpdateSwitch != null) postUpdateSwitch.setChecked(post);
        if (liveUpdateSwitch != null) liveUpdateSwitch.setChecked(live);
        restoringChannelSettings = false;
        prefs.edit()
                .putBoolean("bili_channel_video_update_draft", video)
                .putBoolean("bili_channel_post_update_draft", post)
                .putBoolean("bili_channel_live_update_draft", live)
                .putBoolean("bili_channel_draft_changed", false)
                .apply();
        refreshPushChannelStatus(prefs);
        Toast.makeText(this, "已恢复本机上次保存的订阅设置。", Toast.LENGTH_SHORT).show();
    }

    private void saveAndSyncPushChannels() {
        SharedPreferences prefs = statePrefs();
        boolean video = videoUpdateSwitch != null && videoUpdateSwitch.isChecked();
        boolean post = postUpdateSwitch != null && postUpdateSwitch.isChecked();
        boolean live = liveUpdateSwitch != null && liveUpdateSwitch.isChecked();
        prefs.edit()
                .putBoolean("bili_channel_preferences_configured", true)
                .putBoolean("bili_channel_video_update", video)
                .putBoolean("bili_channel_post_update", post)
                .putBoolean("bili_channel_live_update", live)
                .putBoolean("bili_channel_video_update_draft", video)
                .putBoolean("bili_channel_post_update_draft", post)
                .putBoolean("bili_channel_live_update_draft", live)
                .putBoolean("bili_channel_draft_changed", false)
                .apply();
        refreshPushChannelStatus(prefs);
        new Thread(() -> MiPushRelayProcessor.synchronizeServerAccountBinding(
                getApplicationContext()), "PiliPlusPushChannelSync").start();
        Toast.makeText(this, "订阅设置已保存；主账号和 RegID 就绪后会提交到 Mfans。",
                Toast.LENGTH_LONG).show();
    }

    private void refreshPushChannelStatus(SharedPreferences prefs) {
        if (channelStatus == null) return;
        if (prefs.getBoolean("bili_channel_draft_changed", false)) {
            channelStatus.setText("开关已更改；点击下方按钮同步。");
            channelStatus.setTextColor(palette.primaryText);
            return;
        }
        if (!prefs.getBoolean("bili_channel_preferences_configured", false)) {
            channelStatus.setText("尚未提交设置；默认开关为开启状态，点击同步后才会应用到账号。");
            channelStatus.setTextColor(palette.secondaryText);
            return;
        }
        String bindingState = prefs.getString("account_binding_state", "");
        if ("pending".equals(bindingState)) {
            channelStatus.setText("正在等待 Mfans 确认订阅设置…");
            channelStatus.setTextColor(palette.primaryText);
            return;
        }
        String currentFingerprint = PushTokenReporter.currentChannelFingerprint(this);
        String boundFingerprint = prefs.getString("server_bound_channel_fingerprint", "");
        if ("bound".equals(bindingState) && currentFingerprint.equals(boundFingerprint)) {
            String mid = prefs.getString("main_account_mid", "");
            channelStatus.setText("已同步到主账号 UID " + mid + "。变更开关后请再次同步。");
            channelStatus.setTextColor(palette.success);
        } else if ("failed".equals(bindingState)) {
            channelStatus.setText("同步失败：" + prefs.getString("account_binding_error",
                    "等待重试"));
            channelStatus.setTextColor(palette.error);
        } else {
            channelStatus.setText("设置已保存在本机；等待主账号快照和 MiPush 注册就绪后提交。");
            channelStatus.setTextColor(palette.secondaryText);
        }
    }

    private View buildAboutCard() {
        LinearLayout card = card(palette.surfaceContainer, 0);
        TextView title = text("关于", 16, palette.text, false);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams titleParams = matchWrap();
        titleParams.topMargin = dp(18);
        titleParams.leftMargin = dp(20);
        titleParams.rightMargin = dp(20);
        card.addView(title, titleParams);

        String version = "未知";
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (packageInfo.versionName != null) {
                version = packageInfo.versionName.replaceFirst("[^0-9.].*$", "");
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        addInfoRow(card, "版本", version);
        addInfoRow(card, "模块 ID", "piliplus-push");
        addInfoRow(card, "作者", "codex、mikufans");
        return card;
    }

    private void addInfoRow(LinearLayout card, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(10), dp(20), dp(10));
        row.addView(text(label, 14, palette.secondaryText, false),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView valueView = text(value, 14, palette.text, false);
        valueView.setGravity(Gravity.END);
        row.addView(valueView, wrap());
        card.addView(row, matchWrap());
    }

    private void refreshStatus() {
        if (registrationValue == null) return;
        refreshLauncherSwitch();
        SharedPreferences prefs = statePrefs();
        boolean received = prefs.getBoolean("registration_result_received", false);
        boolean success = prefs.getBoolean("registration_success", false);
        long code = prefs.getLong("registration_error_code", -1L);
        String requestStatus = prefs.getString("registration_request_status", "");
        boolean forced = prefs.getBoolean("registration_request_forced", false);

        if (received && success) {
            registrationValue.setText("注册成功");
            registrationValue.setTextColor(palette.success);
            registrationDetail.setText("结果码 0 · tv.danmaku.bili");
        } else if (received) {
            registrationValue.setText("注册返回错误");
            registrationValue.setTextColor(palette.error);
            registrationDetail.setText("结果码 " + code + " · tv.danmaku.bili");
        } else if ("failed".equals(requestStatus)) {
            registrationValue.setText("注册调用失败");
            registrationValue.setTextColor(palette.error);
            registrationDetail.setText(prefs.getString("registration_request_detail",
                    "请查看模块诊断日志。"));
        } else if (prefs.getBoolean("registration_request_submitted", false)) {
            registrationValue.setText("注册请求已提交");
            registrationValue.setTextColor(palette.primaryText);
            String attempt = forced ? "强制重注册 · 等待回包" : "等待回包";
            registrationDetail.setText(attempt + " · tv.danmaku.bili");
        } else {
            registrationValue.setText("尚未注册");
            registrationValue.setTextColor(palette.text);
            registrationDetail.setText("身份 tv.danmaku.bili");
        }

        refreshAccountStatus(prefs);
        refreshPushChannelStatus(prefs);
        refreshInteractionSettings(prefs);
        if (received && success) {
            deliveryValue.setText("注册成功 · 等待推送");
            deliveryValue.setTextColor(palette.success);
        } else {
            deliveryValue.setText("等待注册回包或推送");
            deliveryValue.setTextColor(palette.text);
        }
        if (requestInFlight && registerButton != null) {
            registerButton.setEnabled(false);
            registerButton.setText("正在提交…");
        } else if (registerButton != null) {
            registerButton.setEnabled(true);
            registerButton.setText("强制重新注册");
        }
    }

    private void refreshLauncherSwitch() {
        if (launcherHideSwitch == null) return;
        boolean hidden = isLauncherHidden();
        if (launcherHideSwitch.isChecked() != hidden) {
            launcherHideSwitch.setChecked(hidden);
        }
    }

    private boolean isLauncherHidden() {
        int state = getPackageManager().getComponentEnabledSetting(launcherEntryActivity());
        return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
    }

    private void setLauncherHidden(boolean hidden) {
        int state = hidden
                ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                : PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        try {
            getPackageManager().setComponentEnabledSetting(
                    launcherEntryActivity(), state, PackageManager.DONT_KILL_APP);
            Toast.makeText(this, hidden
                    ? "桌面图标已隐藏；可通过 pilipluspush://settings 恢复。"
                    : "桌面图标已恢复。", Toast.LENGTH_LONG).show();
        } catch (RuntimeException exception) {
            refreshLauncherSwitch();
            Toast.makeText(this, "设置 Launcher 图标失败：" + exception.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private ComponentName launcherEntryActivity() {
        return new ComponentName(this, LauncherEntryActivity.class);
    }

    private void refreshAccountStatus(SharedPreferences prefs) {
        boolean refreshPending = prefs.getBoolean("main_account_snapshot_refresh_pending", false);
        if (!prefs.getBoolean("main_account_state_received", false)) {
            accountValue.setText(refreshPending ? "正在获取账号快照…" : "尚无账号快照");
            accountValue.setTextColor(palette.text);
            accountDetail.setText(refreshPending
                    ? "正在请求 PiliPlus 更新快照。"
                    : "PiliPlus 启动时自动读取，也可手动刷新。");
            return;
        }
        String bindingState = prefs.getString("account_binding_state", "waiting_registration");
        if (!prefs.getBoolean("main_account_present", false)) {
            accountValue.setText("快照已获取 · 当前没有已登录的主账号");
            accountValue.setTextColor(palette.secondaryText);
            accountDetail.setText(refreshPending ? "正在更新快照…" : "当前没有已登录的主账号。");
            return;
        }

        String mid = prefs.getString("main_account_mid", "");
        if ("bound".equals(bindingState)) {
            accountValue.setText("主账号 UID " + mid + " · Mfans 服务端绑定已接受");
            accountValue.setTextColor(palette.success);
            accountDetail.setText("Mfans 已接受设备与主账号绑定。");
        } else if ("pending".equals(bindingState)) {
            accountValue.setText("主账号 UID " + mid + " · 正在提交服务端绑定");
            accountValue.setTextColor(palette.primaryText);
            accountDetail.setText("等待 Mfans 确认绑定。");
        } else if ("waiting_push_api_credentials".equals(bindingState)) {
            accountValue.setText("已识别主账号 UID " + mid + " · 缺少 Mfans 推送参数");
            accountValue.setTextColor(palette.secondaryText);
            accountDetail.setText("缺少 Mfans 推送接口参数。");
        } else if ("failed".equals(bindingState)) {
            accountValue.setText("已识别主账号 UID " + mid + " · 服务端绑定失败");
            accountValue.setTextColor(palette.error);
            accountDetail.setText("绑定失败：" + prefs.getString("account_binding_error", "unknown"));
        } else if ("waiting_access_key".equals(bindingState)) {
            accountValue.setText("已识别主账号 UID " + mid + " · 缺少登录凭据");
            accountValue.setTextColor(palette.secondaryText);
            accountDetail.setText("账号快照缺少登录凭据。");
        } else {
            accountValue.setText("已识别主账号 UID " + mid);
            accountValue.setTextColor(palette.text);
            accountDetail.setText(prefs.getBoolean("main_account_server_auth_present", false)
                    ? "等待 MiPush 注册就绪后绑定。"
                    : "账号快照暂缺登录凭据。");
        }
        if (refreshPending) {
            accountDetail.append("\n正在请求更新账号快照…");
        }
    }

    private void requestAccountSnapshot() {
        if (snapshotButton == null || !snapshotButton.isEnabled()) return;
        PiliPlusAccountWatcher.requestFreshSnapshot(getApplicationContext());
        Toast.makeText(this,
                "已请求 PiliPlus 读取主账号快照；若应用进程未运行，下次启动时会读取。",
                Toast.LENGTH_LONG).show();
        refreshStatus();
    }

    private void clearAccountSnapshot() {
        if (clearSnapshotInFlight) return;
        clearSnapshotInFlight = true;
        if (clearSnapshotButton != null) {
            clearSnapshotButton.setEnabled(false);
            clearSnapshotButton.setText("正在清除…");
        }
        new Thread(() -> {
            MiPushRelayProcessor.clearMainAccountSnapshot(getApplicationContext());
            handler.post(() -> {
                clearSnapshotInFlight = false;
                if (clearSnapshotButton != null) {
                    clearSnapshotButton.setEnabled(true);
                    clearSnapshotButton.setText("清除账号快照");
                }
                refreshStatus();
                Toast.makeText(this, "账号快照已清除。", Toast.LENGTH_SHORT).show();
            });
        }, "PiliPlusAccountSnapshotClear").start();
    }

    private void submitRegistration(boolean forced) {
        if (requestInFlight) return;
        requestInFlight = true;
        refreshStatus();
        new Thread(() -> {
            String result = PushRegistration.requestFrameworkRegistration(
                    getApplicationContext(), forced);
            boolean failed = result == null || result.startsWith("Registration failed")
                    || result.startsWith("Skipped:");
            SharedPreferences.Editor editor = statePrefs().edit()
                    .putString("registration_request_status", failed ? "failed" : "submitted")
                    .putString("registration_request_detail", result == null
                            ? "注册方法未返回结果。" : result);
            editor.apply();
            handler.post(() -> {
                requestInFlight = false;
                refreshStatus();
            });
        }, "PiliPlusPushRegistration").start();
    }

    private SharedPreferences statePrefs() {
        return getSharedPreferences("push_state", MODE_PRIVATE);
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(palette.background);
        window.setNavigationBarColor(palette.background);
        window.getDecorView().setSystemUiVisibility(isNightMode() ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    private boolean isNightMode() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private LinearLayout card(int color, int paddingDp) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp));
        card.setBackground(round(color, 28));
        card.setClipToOutline(true);
        return card;
    }

    private TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setLineSpacing(dp(2), 1f);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private void addSpace(LinearLayout parent, int heightDp) {
        View space = new View(this);
        parent.addView(space, new LinearLayout.LayoutParams(1, dp(heightDp)));
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class ExpandableCard {
        final String key;
        final LinearLayout card;
        final LinearLayout header;
        final View divider;
        final LinearLayout details;
        final TextView arrow;
        final String title;
        boolean expanded;

        ExpandableCard(String key, LinearLayout card, LinearLayout header, View divider,
                       LinearLayout details, TextView arrow, String title) {
            this.key = key;
            this.card = card;
            this.header = header;
            this.divider = divider;
            this.details = details;
            this.arrow = arrow;
            this.title = title;
        }
    }

    private static final class Palette {
        final int background;
        final int surface;
        final int surfaceContainer;
        final int outlineVariant;
        final int primary;
        final int primaryContainer;
        final int primaryText;
        final int text;
        final int secondaryText;
        final int buttonText;
        final int success;
        final int error;

        private Palette(Context context, boolean dark) {
            background = systemColor(context, dark ? "system_neutral1_900" : "system_neutral1_10",
                    dark ? 0xFF141318 : 0xFFF6F3F5);
            surface = systemColor(context, dark ? "system_neutral1_800" : "system_neutral1_0",
                    dark ? 0xFF211F25 : 0xFFFFFFFF);
            surfaceContainer = systemColor(context, dark ? "system_neutral2_800" : "system_neutral2_50",
                    dark ? 0xFF2B2930 : 0xFFEFECF0);
            outlineVariant = systemColor(context, dark ? "system_neutral2_700" : "system_neutral2_200",
                    dark ? 0xFF49454F : 0xFFDAD6DB);
            primary = systemColor(context, dark ? "system_accent1_200" : "system_accent1_600",
                    dark ? 0xFFFFB1C3 : 0xFFB3264C);
            primaryContainer = systemColor(context, dark ? "system_accent1_800" : "system_accent1_100",
                    dark ? 0xFF5B2335 : 0xFFF9DCE5);
            primaryText = systemColor(context, dark ? "system_accent1_100" : "system_accent1_800",
                    dark ? 0xFFFFD9E1 : 0xFF6D1730);
            text = systemColor(context, dark ? "system_neutral1_100" : "system_neutral1_900",
                    dark ? 0xFFE8E0E5 : 0xFF211A1D);
            secondaryText = systemColor(context, dark ? "system_neutral2_300" : "system_neutral2_600",
                    dark ? 0xFFCAC4CA : 0xFF645D62);
            buttonText = systemColor(context, dark ? "system_accent1_1000" : "system_accent1_0",
                    dark ? 0xFF3D071A : 0xFFFFFFFF);
            success = color(dark ? 0xFF83D6A5 : 0xFF23764B);
            error = color(dark ? 0xFFFFB4AB : 0xFFBA1A1A);
        }

        static Palette forNight(Context context, boolean dark) {
            return new Palette(context, dark);
        }

        private static int systemColor(Context context, String resourceName, int fallback) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                return color(fallback);
            }
            int resourceId = context.getResources().getIdentifier(resourceName, "color", "android");
            if (resourceId == 0) {
                return color(fallback);
            }
            try {
                return context.getResources().getColor(resourceId, context.getTheme());
            } catch (Resources.NotFoundException ignored) {
                return color(fallback);
            }
        }

        private static int color(int value) {
            return Color.rgb(Color.red(value), Color.green(value), Color.blue(value));
        }
    }
}
