package me.cuktech.ad1204u.diag;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 单页直达主界面：
 * 1. 起始态：检测前说明 + 唯一主操作「开始一键检测」（无置灰空壳按钮）
 * 2. 进行态：实时连接与读取步骤 +「取消检测」
 * 3. 选择/未发现态：重扫入口 + 可展开的信号强度排序手动设备列表
 * 4. 结果态：核心结论卡片 + 指标明细 + 可折叠原始 Hex 帧 + 底部「保存证据长图 / 复制文字报告」
 */
public final class MainActivity extends Activity implements BleDiagClient.Listener {

    private static final int REQ_PERMS_BLE     = 1001;
    private static final int REQ_ENABLE_BT     = 1002;
    private static final int REQ_PERM_STORAGE  = 1003;

    private BleDiagClient bleClient;
    private DiagProtocol.DiagReport currentReport;
    private boolean hexExpanded = false;
    private boolean nearbyExpanded = false;

    private LinearLayout rootContent;
    private LinearLayout bodyContainer;
    private TextView headerRetestAction;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindowAppearance();

        bleClient = new BleDiagClient(this, this);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(0xFFF3F6FA);

        rootContent = new LinearLayout(this);
        rootContent.setOrientation(LinearLayout.VERTICAL);
        final int baseHPad = dp(18);
        final int baseTopPad = dp(16);
        final int baseBotPad = dp(24);
        rootContent.setPadding(baseHPad, baseTopPad, baseHPad, baseBotPad);

        scrollView.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int topInset = insets.getSystemWindowInsetTop();
                int botInset = insets.getSystemWindowInsetBottom();
                rootContent.setPadding(baseHPad, baseTopPad + topInset, baseHPad, baseBotPad + botInset);
                return insets.consumeSystemWindowInsets();
            }
        });

        rootContent.addView(buildPersistentHeader());

        bodyContainer = new LinearLayout(this);
        bodyContainer.setOrientation(LinearLayout.VERTICAL);
        rootContent.addView(bodyContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        scrollView.addView(rootContent, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scrollView);

        renderIdleState();
    }

    @Override
    protected void onDestroy() {
        if (bleClient != null) {
            bleClient.cancel();
        }
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    private void configureWindowAppearance() {
        Window w = getWindow();
        if (w == null) return;
        w.setStatusBarColor(0xFFF3F6FA);
        w.setNavigationBarColor(0xFFF3F6FA);
        int flags = w.getDecorView().getSystemUiVisibility();
        flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        w.getDecorView().setSystemUiVisibility(flags);
    }

    private View buildPersistentHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(16);
        header.setLayoutParams(lp);

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView badge = new TextView(this);
        badge.setText("离线直读 · 无联网权限");
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextColor(0xFF1D4ED8);
        badge.setPadding(dp(10), dp(4), dp(10), dp(4));
        badge.setBackground(roundRect(0xFFDBEAFE, 0xFFBFDBFE, dp(999)));
        topRow.addView(badge, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View spacer = new View(this);
        topRow.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        headerRetestAction = new TextView(this);
        headerRetestAction.setText("重新检测");
        headerRetestAction.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        headerRetestAction.setTypeface(Typeface.DEFAULT_BOLD);
        headerRetestAction.setTextColor(0xFF1D4ED8);
        headerRetestAction.setPadding(dp(12), dp(8), dp(4), dp(8));
        headerRetestAction.setVisibility(View.GONE);
        headerRetestAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkPreconditionsAndStart();
            }
        });
        topRow.addView(headerRetestAction);
        header.addView(topRow);

        TextView title = new TextView(this);
        title.setText("酷态科 10 号 Ultra 固件冻死诊断");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF0F172A);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(10);
        header.addView(title, titleLp);

        TextView subtitle = new TextView(this);
        subtitle.setText("通过 0xAF00 服务只读查询 2.1.2_0079 内置的 C1/C2 冻死拉闸累计次数与现场快照");
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        subtitle.setLineSpacing(dp(2), 1.05f);
        subtitle.setTextColor(0xFF475569);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(5);
        header.addView(subtitle, subLp);

        return header;
    }

    // ==================== 状态 1：待检测起始态 ====================

    private void renderIdleState() {
        currentReport = null;
        headerRetestAction.setVisibility(View.GONE);
        bodyContainer.removeAllViews();

        LinearLayout prepCard = createCard(0xFFFFFFFF, 0xFFE2E8F0);
        prepCard.addView(sectionTitle("检测前准备"));

        prepCard.addView(stepItem("1", "将酷态科 10 号 Ultra (AD1204U) 插在插座保持通电，确认充电头屏幕菜单中的蓝牙处于开启状态。"));
        prepCard.addView(stepItem("2", "请先从手机后台彻底划掉「米家」App。低功耗蓝牙同一时刻仅允许单个连接，米家占线时其他应用无法搜到充电头。"));
        prepCard.addView(stepItem("3", "本应用仅发送 4 条只读查询指令，读取完成后立即释放蓝牙，不修改充电头任何设置。"));

        bodyContainer.addView(prepCard);

        Button startBtn = createPrimaryButton("开始一键检测");
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        btnLp.topMargin = dp(8);
        startBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkPreconditionsAndStart();
            }
        });
        bodyContainer.addView(startBtn, btnLp);
    }

    // ==================== 状态 2：连接与读取进行态 ====================

    private void renderWorkingState(String stepTitle, String stepDetail) {
        headerRetestAction.setVisibility(View.GONE);
        bodyContainer.removeAllViews();

        LinearLayout card = createCard(0xFFFFFFFF, 0xFFE2E8F0);
        card.setGravity(Gravity.CENTER_HORIZONTAL);

        ProgressBar pb = new ProgressBar(this);
        pb.setIndeterminateTintList(ColorStateList.valueOf(0xFF1D4ED8));
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        pbLp.topMargin = dp(12);
        pbLp.bottomMargin = dp(16);
        card.addView(pb, pbLp);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(stepTitle);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setTextColor(0xFF0F172A);
        tvTitle.setGravity(Gravity.CENTER);
        card.addView(tvTitle);

        TextView tvDetail = new TextView(this);
        tvDetail.setText(stepDetail);
        tvDetail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        tvDetail.setTextColor(0xFF64748B);
        tvDetail.setGravity(Gravity.CENTER);
        tvDetail.setLineSpacing(dp(2), 1.05f);
        LinearLayout.LayoutParams detLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detLp.topMargin = dp(8);
        detLp.bottomMargin = dp(8);
        card.addView(tvDetail, detLp);

        bodyContainer.addView(card);

        Button cancelBtn = createSecondaryButton("取消检测");
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        btnLp.topMargin = dp(6);
        cancelBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                bleClient.cancel();
                renderIdleState();
            }
        });
        bodyContainer.addView(cancelBtn, btnLp);
    }

    // ==================== 状态 3：多设备选择 / 未自动发现态 ====================

    private void renderPickerState(final boolean hasAutoMatches,
                                   final List<BleDiagClient.ScannedCandidate> primaryList,
                                   final List<BleDiagClient.ScannedCandidate> nearbyList) {
        headerRetestAction.setVisibility(View.GONE);
        bodyContainer.removeAllViews();

        if (hasAutoMatches) {
            LinearLayout card = createCard(0xFFFFFFFF, 0xFFE2E8F0);
            card.addView(sectionTitle("发现多台酷态科充电头，请点击要检测的设备"));
            for (int i = 0; i < primaryList.size(); i++) {
                card.addView(buildCandidateRow(primaryList.get(i), i > 0));
            }
            bodyContainer.addView(card);
        } else {
            LinearLayout tipCard = createCard(0xFFFFFBEB, 0xFFFDE68A);
            TextView t = new TextView(this);
            t.setText("未自动搜到酷态科 10 号 Ultra 广播");
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextColor(0xFF92400E);
            tipCard.addView(t);

            TextView hint = new TextView(this);
            hint.setText("1. 请确认手机后台的「米家」App 已彻底划掉关闭（米家连着时充电头不会对外广播）；\n"
                    + "2. 请确认充电头已通电，且充电头设置里的蓝牙处于开启状态；\n"
                    + "3. 将手机贴近充电头 20 厘米内点击下方重新搜索。");
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            hint.setTextColor(0xFF78350F);
            hint.setLineSpacing(dp(3), 1.08f);
            LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hLp.topMargin = dp(8);
            tipCard.addView(hint, hLp);
            bodyContainer.addView(tipCard);
        }

        Button rescanBtn = createPrimaryButton("重新搜索充电头");
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        btnLp.topMargin = dp(6);
        btnLp.bottomMargin = dp(12);
        rescanBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkPreconditionsAndStart();
            }
        });
        bodyContainer.addView(rescanBtn, btnLp);

        if (nearbyList != null && !nearbyList.isEmpty()) {
            LinearLayout manualCard = createCard(0xFFFFFFFF, 0xFFE2E8F0);
            final LinearLayout listWrap = new LinearLayout(this);
            listWrap.setOrientation(LinearLayout.VERTICAL);
            listWrap.setVisibility(nearbyExpanded ? View.VISIBLE : View.GONE);

            final TextView toggleRow = new TextView(this);
            updateNearbyToggleText(toggleRow, nearbyList.size());
            toggleRow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            toggleRow.setTypeface(Typeface.DEFAULT_BOLD);
            toggleRow.setTextColor(0xFF334155);
            toggleRow.setPadding(0, dp(4), 0, dp(4));
            toggleRow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    nearbyExpanded = !nearbyExpanded;
                    listWrap.setVisibility(nearbyExpanded ? View.VISIBLE : View.GONE);
                    updateNearbyToggleText(toggleRow, nearbyList.size());
                }
            });
            manualCard.addView(toggleRow);

            int maxShow = Math.min(nearbyList.size(), 12);
            for (int i = 0; i < maxShow; i++) {
                listWrap.addView(buildCandidateRow(nearbyList.get(i), true));
            }
            manualCard.addView(listWrap);
            bodyContainer.addView(manualCard);
        }
    }

    private void updateNearbyToggleText(TextView tv, int count) {
        String arrow = nearbyExpanded ? "▾ " : "▸ ";
        tv.setText(arrow + "高级：从附近其他蓝牙设备手动选择 (" + count + ")");
    }

    private View buildCandidateRow(final BleDiagClient.ScannedCandidate c, boolean topDivider) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        if (topDivider) {
            wrap.addView(dividerView());
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(12), dp(4), dp(12));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);

        TextView nameTv = new TextView(this);
        nameTv.setText(c.displayTitle());
        nameTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        nameTv.setTypeface(Typeface.DEFAULT_BOLD);
        nameTv.setTextColor(0xFF0F172A);
        left.addView(nameTv);

        TextView subTv = new TextView(this);
        subTv.setText(DiagProtocol.maskMac(c.address) + "  ·  " + c.rssi + " dBm  ·  " + c.matchReason);
        subTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        subTv.setTextColor(0xFF64748B);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp(2);
        left.addView(subTv, sLp);

        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView actionTv = new TextView(this);
        actionTv.setText("连接 ›");
        actionTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        actionTv.setTypeface(Typeface.DEFAULT_BOLD);
        actionTv.setTextColor(0xFF1D4ED8);
        actionTv.setPadding(dp(12), dp(6), dp(4), dp(6));
        row.addView(actionTv);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                bleClient.connectToCandidate(c);
            }
        });
        wrap.addView(row);
        return wrap;
    }

    // ==================== 状态 4：错误提示态 ====================

    private void renderErrorState(String errTitle, String errHint, final boolean offerLocationSettings) {
        headerRetestAction.setVisibility(View.GONE);
        bodyContainer.removeAllViews();

        LinearLayout card = createCard(0xFFFEF2F2, 0xFFFECACA);
        TextView title = new TextView(this);
        title.setText(errTitle);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFFB91C1C);
        card.addView(title);

        TextView hint = new TextView(this);
        hint.setText(errHint);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        hint.setTextColor(0xFF7F1D1D);
        hint.setLineSpacing(dp(2), 1.06f);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.topMargin = dp(8);
        card.addView(hint, hLp);
        bodyContainer.addView(card);

        if (offerLocationSettings) {
            Button locBtn = createPrimaryButton("打开系统定位开关");
            LinearLayout.LayoutParams locLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
            locLp.topMargin = dp(6);
            locLp.bottomMargin = dp(10);
            locBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                    } catch (Exception ignored) {
                    }
                }
            });
            bodyContainer.addView(locBtn, locLp);
        }

        Button retryBtn = offerLocationSettings
                ? createSecondaryButton("开启后重新检测")
                : createPrimaryButton("重新检测");
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        btnLp.topMargin = dp(6);
        retryBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkPreconditionsAndStart();
            }
        });
        bodyContainer.addView(retryBtn, btnLp);
    }

    // ==================== 状态 5：诊断完成结果态 ====================

    private void renderResultState(final DiagProtocol.DiagReport r) {
        currentReport = r;
        hexExpanded = false;
        headerRetestAction.setVisibility(View.VISIBLE);
        bodyContainer.removeAllViews();

        boolean legacy = r.isLegacyFirmwareWithout0079();
        boolean alert = r.hasFreezeEvidence();

        int cardBg;
        int cardBorder;
        int accentColor;
        String headline;
        String explain;

        if (legacy) {
            cardBg = 0xFFEFF6FF;
            cardBorder = 0xFF93C5FD;
            accentColor = 0xFF1D4ED8;
            headline = "当前固件尚未升级至 2.1.2_0079";
            explain = "已成功连上充电头并读出旧版固件号。旧版固件不含 0079 新增的按口冻死关断保护与 Flash 诊断记录项。";
        } else if (alert) {
            cardBg = 0xFFFEF2F2;
            cardBorder = 0xFFFCA5A5;
            accentColor = 0xFFB91C1C;
            headline = "检出协议芯片冻死拉闸记录";
            explain = "官方 0079 固件写入 Flash 记录 #5（跨断电永久保存）或当次通电快照非零，证实该充电头曾触发读数冻结满约 18 秒的关断保护。";
        } else {
            cardBg = 0xFFECFDF5;
            cardBorder = 0xFF6EE7B7;
            accentColor = 0xFF047857;
            headline = "暂未检出冻死拉闸记录";
            explain = "Flash 历史累计冻死拉闸次数为 0。注：0079 仅在端口电压与电流均非零、且读数连续不变满 301 次 (18001 tick) 时计入一次拉闸。";
        }

        // 1. 核心结论卡片
        LinearLayout verdictCard = createCard(cardBg, cardBorder);
        TextView tvVerdict = new TextView(this);
        tvVerdict.setText(headline);
        tvVerdict.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
        tvVerdict.setTypeface(Typeface.DEFAULT_BOLD);
        tvVerdict.setTextColor(accentColor);
        verdictCard.addView(tvVerdict);

        if (r.flashCounts != null) {
            LinearLayout metricRow = new LinearLayout(this);
            metricRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams mRowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mRowLp.topMargin = dp(12);
            mRowLp.bottomMargin = dp(10);
            metricRow.setLayoutParams(mRowLp);

            LinearLayout c1Box = buildBigCountBox("C1 口累计拉闸", r.flashCounts.c1TotalCount + " 次", accentColor);
            LinearLayout.LayoutParams c1Lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            c1Lp.rightMargin = dp(6);
            metricRow.addView(c1Box, c1Lp);

            LinearLayout c2Box = buildBigCountBox("C2 口累计拉闸", r.flashCounts.c2TotalCount + " 次", accentColor);
            LinearLayout.LayoutParams c2Lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            c2Lp.leftMargin = dp(6);
            metricRow.addView(c2Box, c2Lp);

            verdictCard.addView(metricRow);
        }

        TextView tvExplain = new TextView(this);
        tvExplain.setText(explain);
        tvExplain.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tvExplain.setTextColor(0xFF334155);
        tvExplain.setLineSpacing(dp(2), 1.06f);
        LinearLayout.LayoutParams exLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (r.flashCounts == null) exLp.topMargin = dp(8);
        verdictCard.addView(tvExplain, exLp);
        bodyContainer.addView(verdictCard);

        // 2. 诊断明细卡片
        LinearLayout detailCard = createCard(0xFFFFFFFF, 0xFFE2E8F0);
        detailCard.addView(sectionTitle("设备与运行明细"));

        detailCard.addView(kvRow("检测时间", r.timestampText, false, false));
        detailCard.addView(kvRow("设备标识", r.deviceName + " (" + r.maskedMac + ")", true, false));

        if (r.versionInfo != null) {
            detailCard.addView(kvRow("MCU 固件版本",
                    String.format(Locale.US, "%s (CRC 0x%04X)",
                            r.versionInfo.formatMcuVersion(), r.versionInfo.mbrCrc16), true, false));
            detailCard.addView(kvRow("协议芯片版本",
                    String.format(Locale.US, "C1 0x%08X · C2 0x%08X\nC3/A 0x%04X",
                            r.versionInfo.husbC1Version, r.versionInfo.husbC2Version, r.versionInfo.sc2021Version),
                    true, false));
        } else {
            detailCard.addView(kvRow("MCU 固件版本", "未返回版本帧", true, false));
        }

        if (r.snapshot != null) {
            if (r.snapshot.triggered) {
                String snapStr = String.format(Locale.US,
                        "已触发 · %s\n连续停滞 %d 次 / %d tick · 原始采样 V=%d, I=%d",
                        r.snapshot.portLabel(), r.snapshot.frozenPollCount,
                        r.snapshot.frozenDurationTicks, r.snapshot.rawVoltage, r.snapshot.rawCurrent);
                detailCard.addView(kvRow("本次通电首次快照", snapStr, true, true));
            } else {
                detailCard.addView(kvRow("本次通电首次快照", "未触发 (拔插头断电清空)", true, false));
            }
        }

        if (r.peakPolls != null) {
            boolean highPeak = r.peakPolls.c1PeakPolls >= 100 || r.peakPolls.c2PeakPolls >= 100;
            detailCard.addView(kvRow("本次通电停滞峰值",
                    String.format(Locale.US, "C1 峰值 %d 次 · C2 峰值 %d 次 (阈值 301 次)",
                            r.peakPolls.c1PeakPolls, r.peakPolls.c2PeakPolls),
                    true, highPeak));
        }
        bodyContainer.addView(detailCard);

        // 3. 折叠式原始 Hex 证据卡片
        LinearLayout hexCard = createCard(0xFFFFFFFF, 0xFFE2E8F0);
        final TextView hexHeader = new TextView(this);
        hexHeader.setText("▸ 查看原始蓝牙回包帧 (0xAF08 Hex)");
        hexHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        hexHeader.setTypeface(Typeface.DEFAULT_BOLD);
        hexHeader.setTextColor(0xFF334155);

        final TextView hexBody = new TextView(this);
        hexBody.setTypeface(Typeface.MONOSPACE);
        hexBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        hexBody.setTextColor(0xFF1E293B);
        hexBody.setLineSpacing(dp(3), 1.08f);
        hexBody.setPadding(dp(12), dp(10), dp(12), dp(10));
        hexBody.setBackground(roundRect(0xFFF8FAFC, 0xFFE2E8F0, dp(10)));
        hexBody.setText(
                "01(版本): " + (r.versionInfo != null ? r.versionInfo.rawHex : "无响应") + "\n" +
                "0C(累计): " + (r.flashCounts != null ? r.flashCounts.rawHex : "无响应") + "\n" +
                "0A(快照): " + (r.snapshot != null ? r.snapshot.rawHex : "无响应") + "\n" +
                "0B(峰值): " + (r.peakPolls != null ? r.peakPolls.rawHex : "无响应"));
        LinearLayout.LayoutParams hbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hbLp.topMargin = dp(10);
        hexBody.setLayoutParams(hbLp);
        hexBody.setVisibility(View.GONE);

        hexHeader.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hexExpanded = !hexExpanded;
                hexBody.setVisibility(hexExpanded ? View.VISIBLE : View.GONE);
                hexHeader.setText((hexExpanded ? "▾ " : "▸ ") + "查看原始蓝牙回包帧 (0xAF08 Hex)");
            }
        });
        hexCard.addView(hexHeader);
        hexCard.addView(hexBody);
        bodyContainer.addView(hexCard);

        // 4. 底部结果导出栏（主操作：保存证据长图；次操作：复制文字报告）
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actLp.topMargin = dp(4);
        actionRow.setLayoutParams(actLp);

        Button saveImgBtn = createPrimaryButton("保存证据长图");
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(0, dp(50), 1f);
        sLp.rightMargin = dp(6);
        saveImgBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handleSaveReportImage();
            }
        });
        actionRow.addView(saveImgBtn, sLp);

        Button copyBtn = createSecondaryButton("复制文字报告");
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, dp(50), 1f);
        cLp.leftMargin = dp(6);
        copyBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyPlainReport(r);
            }
        });
        actionRow.addView(copyBtn, cLp);

        bodyContainer.addView(actionRow);
    }

    // ==================== 权限检查与导出动作 ====================

    private void checkPreconditionsAndStart() {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQ_PERMS_BLE);
            return;
        }

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
        if (adapter == null) {
            renderErrorState("当前设备不支持低功耗蓝牙", "请使用带蓝牙功能的安卓手机进行测试。", false);
            return;
        }
        if (!adapter.isEnabled()) {
            try {
                @SuppressWarnings("deprecation")
                Intent enableBt = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBt, REQ_ENABLE_BT);
            } catch (Exception e) {
                renderErrorState("请先开启手机蓝牙", "无法调起蓝牙开关弹窗，请从下拉状态栏手动打开系统蓝牙。", false);
            }
            return;
        }

        // Android 6–11 系统层面要求开启定位总开关，否则 BLE 扫描会静默返回空列表
        if (Build.VERSION.SDK_INT < 31 && !isLocationEnabled()) {
            renderErrorState("Android 11 及以下系统需开启系统定位开关",
                    "旧版安卓系统规定低功耗蓝牙扫描依赖定位总开关（本应用无联网权限，不会采集任何位置数据）。开启定位后即可正常搜索充电头。",
                    true);
            return;
        }

        nearbyExpanded = false;
        bleClient.startAutoDetect();
    }

    @SuppressWarnings("deprecation")
    private boolean isLocationEnabled() {
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return lm.isLocationEnabled();
        }
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    private void handleSaveReportImage() {
        if (currentReport == null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM_STORAGE);
            return;
        }
        try {
            String savedPath = ReportImageRenderer.saveReportPngToGallery(this, currentReport);
            Toast.makeText(this, "已保存证据长图至：" + savedPath, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "保存图片失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void copyPlainReport(DiagProtocol.DiagReport r) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("CUKTECH_10Ultra_Diag", r.buildPlainTextReport()));
            Toast.makeText(this, "已复制完整诊断报告（含原始 Hex 证据帧）", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS_BLE) {
            boolean allOk = grantResults.length > 0;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    allOk = false;
                    break;
                }
            }
            if (allOk) {
                checkPreconditionsAndStart();
            } else {
                renderErrorState("需要蓝牙扫描/连接权限才能连接充电头",
                        "请在系统权限弹窗中选择「允许」后重新点击检测。本应用无任何联网权限。",
                        false);
            }
        } else if (requestCode == REQ_PERM_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                handleSaveReportImage();
            } else {
                Toast.makeText(this, "未授予相册存储权限，您仍可使用右侧「复制文字报告」", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ENABLE_BT) {
            if (resultCode == Activity.RESULT_OK) {
                checkPreconditionsAndStart();
            } else {
                renderErrorState("手机蓝牙仍处于关闭状态", "开启蓝牙后点击下方按钮即可开始检测。", false);
            }
        }
    }

    // ==================== BleDiagClient.Listener ====================

    @Override
    public void onProgress(String stepTitle, String stepDetail) {
        renderWorkingState(stepTitle, stepDetail);
    }

    @Override
    public void onMultipleTargetsFound(List<BleDiagClient.ScannedCandidate> targets,
                                       List<BleDiagClient.ScannedCandidate> nearbyOthers) {
        renderPickerState(true, targets, nearbyOthers);
    }

    @Override
    public void onNoTargetAutoFound(List<BleDiagClient.ScannedCandidate> nearbyOthers) {
        renderPickerState(false, null, nearbyOthers);
    }

    @Override
    public void onSuccess(DiagProtocol.DiagReport report) {
        renderResultState(report);
    }

    @Override
    public void onError(String errorTitle, String errorHint) {
        renderErrorState(errorTitle, errorHint, false);
    }

    // ==================== 通用轻量 UI 构造工具 ====================

    private LinearLayout createCard(int bgColor, int strokeColor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(roundRect(bgColor, strokeColor, dp(14)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        card.setLayoutParams(lp);
        return card;
    }

    private TextView sectionTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(0xFF0F172A);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        tv.setLayoutParams(lp);
        return tv;
    }

    private View stepItem(String num, String body) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);

        TextView badge = new TextView(this);
        badge.setText(num);
        badge.setGravity(Gravity.CENTER);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextColor(0xFF1D4ED8);
        badge.setBackground(roundRect(0xFFEFF6FF, 0xFFBFDBFE, dp(999)));
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(dp(24), dp(24));
        bLp.rightMargin = dp(10);
        bLp.topMargin = dp(1);
        row.addView(badge, bLp);

        TextView tv = new TextView(this);
        tv.setText(body);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        tv.setTextColor(0xFF334155);
        tv.setLineSpacing(dp(2), 1.08f);
        row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private LinearLayout buildBigCountBox(String label, String countText, int accentColor) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.setBackground(roundRect(0xCCFFFFFF, 0x33000000, dp(10)));

        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        lbl.setTextColor(0xFF475569);
        box.addView(lbl);

        TextView val = new TextView(this);
        val.setText(countText);
        val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f);
        val.setTypeface(Typeface.DEFAULT_BOLD);
        val.setTextColor(accentColor);
        LinearLayout.LayoutParams vLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vLp.topMargin = dp(3);
        box.addView(val, vLp);
        return box;
    }

    private View kvRow(String key, String value, boolean withTopDivider, boolean highlightDanger) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        if (withTopDivider) {
            wrap.addView(dividerView());
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, dp(10));

        TextView kTv = new TextView(this);
        kTv.setText(key);
        kTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        kTv.setTextColor(0xFF64748B);
        row.addView(kTv, new LinearLayout.LayoutParams(dp(124), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView vTv = new TextView(this);
        vTv.setText(value);
        vTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        vTv.setTypeface(Typeface.DEFAULT_BOLD);
        vTv.setTextColor(highlightDanger ? 0xFFB91C1C : 0xFF0F172A);
        vTv.setLineSpacing(dp(2), 1.06f);
        vTv.setGravity(Gravity.END);
        row.addView(vTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        wrap.addView(row);
        return wrap;
    }

    private View dividerView() {
        View v = new View(this);
        v.setBackgroundColor(0xFFF1F5F9);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return v;
    }

    private Button createPrimaryButton(String text) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(Color.WHITE);
        b.setStateListAnimator(null);
        b.setBackground(roundRect(0xFF1D4ED8, 0xFF1E40AF, dp(12)));
        return b;
    }

    private Button createSecondaryButton(String text) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(0xFF1E293B);
        b.setStateListAnimator(null);
        b.setBackground(roundRect(0xFFFFFFFF, 0xFFCBD5E1, dp(12)));
        return b;
    }

    private GradientDrawable roundRect(int fillColor, int strokeColor, int radiusPx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(radiusPx);
        gd.setColor(fillColor);
        gd.setStroke(dp(1), strokeColor);
        return gd;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
