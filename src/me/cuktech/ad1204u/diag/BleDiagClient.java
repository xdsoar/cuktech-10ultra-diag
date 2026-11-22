package me.cuktech.ad1204u.diag;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 串行化 BLE 扫描、连接、0xAF08 通知订阅与 4 条只读指令顺序读取状态机。
 */
@SuppressLint("MissingPermission")
public final class BleDiagClient {

    public static final class ScannedCandidate {
        public final BluetoothDevice device;
        public final String address;
        public String name;
        public int rssi;
        public int matchTier; // 3 = MiOT PID 0x660E, 2 = 0xAF00 / CUKTECH 名称, 1 = 其他附近 BLE 设备
        public String matchReason;

        ScannedCandidate(BluetoothDevice device, String address, String name, int rssi,
                         int matchTier, String matchReason) {
            this.device = device;
            this.address = address;
            this.name = name;
            this.rssi = rssi;
            this.matchTier = matchTier;
            this.matchReason = matchReason;
        }

        public String displayTitle() {
            if (name != null && !name.trim().isEmpty()) {
                return name.trim();
            }
            if (matchTier == 3) {
                return "CUKTECH 10 号 Ultra";
            }
            return "未命名蓝牙设备";
        }
    }

    public interface Listener {
        void onProgress(String stepTitle, String stepDetail);
        void onMultipleTargetsFound(List<ScannedCandidate> targets, List<ScannedCandidate> nearbyOthers);
        void onNoTargetAutoFound(List<ScannedCandidate> nearbyOthers);
        void onSuccess(DiagProtocol.DiagReport report);
        void onError(String errorTitle, String errorHint);
    }

    private static final long SCAN_SETTLE_AFTER_HIT_MS = 1600L;
    private static final long SCAN_MAX_TIMEOUT_MS      = 6000L;
    private static final long CONNECT_TIMEOUT_MS       = 8000L;
    private static final long CMD_TIMEOUT_MS           = 1800L;

    private static final byte[] QUERY_SEQUENCE = new byte[] {
            DiagProtocol.OPCODE_VERSION,
            DiagProtocol.OPCODE_FLASH_COUNTS,
            DiagProtocol.OPCODE_SNAPSHOT,
            DiagProtocol.OPCODE_PEAK_POLLS
    };

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Listener listener;

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private boolean scanning = false;
    private boolean cancelled = false;
    private boolean settleScheduled = false;

    private final Map<String, ScannedCandidate> candidates = new LinkedHashMap<>();

    private BluetoothGatt activeGatt;
    private BluetoothGattCharacteristic writeChar;
    private ScannedCandidate targetCandidate;
    private int gattRetryCount = 0;

    private int queryIndex = 0;
    private int cmdRetryCount = 0;
    private DiagProtocol.VersionInfo gotVersion;
    private DiagProtocol.FlashFreezeCounts gotFlashCounts;
    private DiagProtocol.FreezeSnapshot gotSnapshot;
    private DiagProtocol.PeakFreezePolls gotPeakPolls;

    public BleDiagClient(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
        BluetoothManager bm = (BluetoothManager) appContext.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) {
            this.adapter = bm.getAdapter();
        }
    }

    public void startAutoDetect() {
        cancelCleanupOnly();
        cancelled = false;
        settleScheduled = false;
        candidates.clear();

        if (adapter == null || !adapter.isEnabled()) {
            listener.onError("手机蓝牙未开启", "请先开启手机系统蓝牙后再试。");
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            listener.onError("无法启动低功耗蓝牙扫描", "请开关一次手机蓝牙后重试。");
            return;
        }

        listener.onProgress("正在搜索充电头…", "请将手机靠近已通电的酷态科 10 号 Ultra，并确保已从后台划掉米家 App");
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        try {
            scanning = true;
            scanner.startScan(null, settings, scanCallback);
        } catch (Exception e) {
            scanning = false;
            listener.onError("蓝牙扫描启动失败", e.getMessage() != null ? e.getMessage() : "请检查附近设备/定位权限是否已允许。");
            return;
        }
        mainHandler.postDelayed(scanTimeoutRunnable, SCAN_MAX_TIMEOUT_MS);
    }

    public void connectToCandidate(ScannedCandidate candidate) {
        stopScanInternal();
        mainHandler.removeCallbacksAndMessages(null);
        cancelled = false;
        targetCandidate = candidate;
        gattRetryCount = 0;
        gotVersion = null;
        gotFlashCounts = null;
        gotSnapshot = null;
        gotPeakPolls = null;
        openGattConnection();
    }

    public void cancel() {
        cancelled = true;
        cancelCleanupOnly();
    }

    private void cancelCleanupOnly() {
        stopScanInternal();
        mainHandler.removeCallbacksAndMessages(null);
        closeGattInternal();
    }

    private void stopScanInternal() {
        if (scanning && scanner != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (Exception ignored) {
            }
        }
        scanning = false;
    }

    private void closeGattInternal() {
        if (activeGatt != null) {
            try {
                activeGatt.disconnect();
            } catch (Exception ignored) {
            }
            try {
                activeGatt.close();
            } catch (Exception ignored) {
            }
            activeGatt = null;
        }
        writeChar = null;
    }

    private final Runnable scanSettleRunnable = new Runnable() {
        @Override
        public void run() {
            if (cancelled || !scanning) return;
            finishScanAndDecide();
        }
    };

    private final Runnable scanTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (cancelled || !scanning) return;
            finishScanAndDecide();
        }
    };

    private void finishScanAndDecide() {
        stopScanInternal();
        mainHandler.removeCallbacks(scanSettleRunnable);
        mainHandler.removeCallbacks(scanTimeoutRunnable);

        List<ScannedCandidate> matched = new ArrayList<>();
        List<ScannedCandidate> others = new ArrayList<>();
        for (ScannedCandidate c : candidates.values()) {
            if (c.matchTier >= 2) {
                matched.add(c);
            } else if (c.rssi >= -88) {
                others.add(c);
            }
        }
        Comparator<ScannedCandidate> sorter = new Comparator<ScannedCandidate>() {
            @Override
            public int compare(ScannedCandidate a, ScannedCandidate b) {
                if (a.matchTier != b.matchTier) {
                    return Integer.compare(b.matchTier, a.matchTier);
                }
                return Integer.compare(b.rssi, a.rssi);
            }
        };
        Collections.sort(matched, sorter);
        Collections.sort(others, sorter);

        if (matched.size() == 1) {
            connectToCandidate(matched.get(0));
        } else if (matched.size() > 1) {
            listener.onMultipleTargetsFound(matched, others);
        } else {
            listener.onNoTargetAutoFound(others);
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            handleScanResult(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            if (results == null) return;
            for (ScanResult r : results) {
                handleScanResult(r);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (cancelled) return;
                    stopScanInternal();
                    listener.onError("系统蓝牙扫描失败 (错误码 " + errorCode + ")",
                            "请关闭手机蓝牙等待 2 秒后重新打开再试。");
                }
            });
        }
    };

    private void handleScanResult(final ScanResult result) {
        if (result == null || result.getDevice() == null) return;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (cancelled || !scanning) return;
                BluetoothDevice dev = result.getDevice();
                String addr = dev.getAddress();
                if (addr == null) return;

                ScanRecord rec = result.getScanRecord();
                String name = rec != null ? rec.getDeviceName() : null;
                if (name == null || name.isEmpty()) {
                    try {
                        name = dev.getName();
                    } catch (Exception ignored) {
                    }
                }

                int tier = 1;
                String reason = "附近蓝牙设备";
                if (rec != null) {
                    byte[] fe95 = rec.getServiceData(new ParcelUuid(DiagProtocol.UUID_SERVICE_MIOT));
                    if (DiagProtocol.isMiotAd1204uServiceData(fe95)) {
                        tier = 3;
                        reason = "米家广播识别 · 10 号 Ultra (0x660E)";
                    } else {
                        List<ParcelUuid> uuids = rec.getServiceUuids();
                        boolean hasAf00 = uuids != null && uuids.contains(new ParcelUuid(DiagProtocol.UUID_SERVICE_DIAG));
                        if (hasAf00) {
                            tier = 2;
                            reason = "包含 0xAF00 诊断服务";
                        } else if (DiagProtocol.isLikelyCuktechName(name)) {
                            tier = 2;
                            reason = "设备名称匹配";
                        } else if (fe95 != null) {
                            int pid = DiagProtocol.extractMiotPid(fe95);
                            if (pid > 0 && DiagProtocol.isLikelyCuktechName(name)) {
                                tier = 2;
                                reason = "米家广播 · CUKTECH";
                            }
                        }
                    }
                } else if (DiagProtocol.isLikelyCuktechName(name)) {
                    tier = 2;
                    reason = "设备名称匹配";
                }

                ScannedCandidate existing = candidates.get(addr);
                if (existing == null) {
                    existing = new ScannedCandidate(dev, addr, name, result.getRssi(), tier, reason);
                    candidates.put(addr, existing);
                } else {
                    existing.rssi = result.getRssi();
                    if ((existing.name == null || existing.name.isEmpty()) && name != null && !name.isEmpty()) {
                        existing.name = name;
                    }
                    if (tier > existing.matchTier) {
                        existing.matchTier = tier;
                        existing.matchReason = reason;
                    }
                }

                if (tier >= 2 && !settleScheduled) {
                    settleScheduled = true;
                    listener.onProgress("已发现酷态科充电头，准备连接…",
                            existing.displayTitle() + " (" + DiagProtocol.maskMac(addr) + " · " + result.getRssi() + " dBm)");
                    mainHandler.removeCallbacks(scanTimeoutRunnable);
                    mainHandler.postDelayed(scanSettleRunnable, SCAN_SETTLE_AFTER_HIT_MS);
                }
            }
        });
    }

    private void openGattConnection() {
        if (cancelled || targetCandidate == null) return;
        closeGattInternal();

        listener.onProgress("正在建立蓝牙连接…",
                targetCandidate.displayTitle() + " (" + DiagProtocol.maskMac(targetCandidate.address) + ")");

        mainHandler.removeCallbacks(connectTimeoutRunnable);
        mainHandler.postDelayed(connectTimeoutRunnable, CONNECT_TIMEOUT_MS);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                activeGatt = targetCandidate.device.connectGatt(
                        appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            } else {
                activeGatt = targetCandidate.device.connectGatt(appContext, false, gattCallback);
            }
        } catch (Exception e) {
            mainHandler.removeCallbacks(connectTimeoutRunnable);
            listener.onError("建立蓝牙连接失败", e.getMessage() != null ? e.getMessage() : "系统蓝牙连接异常");
        }
    }

    private final Runnable connectTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (cancelled) return;
            closeGattInternal();
            listener.onError("连接充电头超时",
                    "请确认米家 App 已彻底从后台关闭，且手机紧贴已通电的充电头后重试。");
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(final BluetoothGatt gatt, final int status, final int newState) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (cancelled || gatt != activeGatt) return;
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        mainHandler.removeCallbacks(connectTimeoutRunnable);
                        closeGattInternal();
                        if (gattRetryCount < 1) {
                            gattRetryCount++;
                            listener.onProgress("连接受阻，正在自动重连…", "GATT 状态码 " + status);
                            mainHandler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    openGattConnection();
                                }
                            }, 450L);
                        } else {
                            listener.onError("蓝牙连接中断 (状态码 " + status + ")",
                                    "请确认没有其他手机或米家后台占用该充电头蓝牙，靠近后重试。");
                        }
                        return;
                    }

                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        listener.onProgress("已连接，正在发现 0xAF00 诊断服务…",
                                targetCandidate.displayTitle());
                        mainHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (!cancelled && activeGatt == gatt) {
                                    gatt.discoverServices();
                                }
                            }
                        }, 200L);
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        mainHandler.removeCallbacks(connectTimeoutRunnable);
                        closeGattInternal();
                        listener.onError("充电头断开了蓝牙连接", "请重新点击开始检测。");
                    }
                }
            });
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt gatt, final int status) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (cancelled || gatt != activeGatt) return;
                    mainHandler.removeCallbacks(connectTimeoutRunnable);

                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        closeGattInternal();
                        listener.onError("读取蓝牙服务列表失败", "GATT 状态码 " + status);
                        return;
                    }

                    BluetoothGattService svc = gatt.getService(DiagProtocol.UUID_SERVICE_DIAG);
                    if (svc == null) {
                        closeGattInternal();
                        listener.onError("该设备不含 0xAF00 诊断服务",
                                "所连设备不是酷态科 10 号 Ultra，或固件不支持私有诊断通道。");
                        return;
                    }

                    BluetoothGattCharacteristic chWrite = svc.getCharacteristic(DiagProtocol.UUID_CHAR_WRITE);
                    BluetoothGattCharacteristic chNotify = svc.getCharacteristic(DiagProtocol.UUID_CHAR_NOTIFY);
                    if (chWrite == null || chNotify == null) {
                        closeGattInternal();
                        listener.onError("未找到完整的读写特征 (0xAF07 / 0xAF08)",
                                "请确认所连设备为酷态科 10 号 Ultra。");
                        return;
                    }
                    writeChar = chWrite;

                    listener.onProgress("正在开启 0xAF08 通知通道…", "订阅诊断回包通知");
                    boolean localOk = gatt.setCharacteristicNotification(chNotify, true);
                    BluetoothGattDescriptor cccd = chNotify.getDescriptor(DiagProtocol.UUID_DESC_CCCD);
                    if (!localOk || cccd == null) {
                        closeGattInternal();
                        listener.onError("无法订阅 0xAF08 通知", "未找到 CCCD 描述符 (0x2902)。");
                        return;
                    }

                    mainHandler.postDelayed(cccdTimeoutRunnable, 2500L);
                    boolean writeStarted = writeCccdEnable(gatt, cccd);
                    if (!writeStarted) {
                        mainHandler.removeCallbacks(cccdTimeoutRunnable);
                        closeGattInternal();
                        listener.onError("开启 0xAF08 通知失败", "系统拒绝写入 CCCD 描述符，请重试。");
                    }
                }
            });
        }

        @Override
        public void onDescriptorWrite(final BluetoothGatt gatt, final BluetoothGattDescriptor descriptor, final int status) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (cancelled || gatt != activeGatt) return;
                    mainHandler.removeCallbacks(cccdTimeoutRunnable);
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        closeGattInternal();
                        listener.onError("通知通道启用失败 (状态码 " + status + ")", "请重试一次检测。");
                        return;
                    }
                    queryIndex = 0;
                    cmdRetryCount = 0;
                    mainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            sendCurrentQuery();
                        }
                    }, 80L);
                }
            });
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                byte[] val = characteristic.getValue();
                final byte[] copy = val != null ? val.clone() : null;
                handleNotifyOnMain(gatt, characteristic, copy);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
            final byte[] copy = value != null ? value.clone() : null;
            handleNotifyOnMain(gatt, characteristic, copy);
        }
    };

    private final Runnable cccdTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (cancelled || activeGatt == null) return;
            // 极少数 ROM 不回调 onDescriptorWrite，兜底直接进入只读查询
            queryIndex = 0;
            cmdRetryCount = 0;
            sendCurrentQuery();
        }
    };

    private void handleNotifyOnMain(final BluetoothGatt gatt,
                                    final BluetoothGattCharacteristic ch,
                                    final byte[] frame) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (cancelled || gatt != activeGatt || frame == null) return;
                if (!DiagProtocol.UUID_CHAR_NOTIFY.equals(ch.getUuid())) return;

                int rxOp = DiagProtocol.extractResponseOpcode(frame);
                if (rxOp < 0) return;

                if (rxOp == (DiagProtocol.OPCODE_VERSION & 0xFF)) {
                    DiagProtocol.VersionInfo v = DiagProtocol.parseVersionResponse(frame);
                    if (v != null) {
                        gotVersion = v;
                        advanceIfWaitingFor(DiagProtocol.OPCODE_VERSION);
                    }
                } else if (rxOp == (DiagProtocol.OPCODE_FLASH_COUNTS & 0xFF)) {
                    DiagProtocol.FlashFreezeCounts c = DiagProtocol.parseFlashCountsResponse(frame);
                    if (c != null) {
                        gotFlashCounts = c;
                        advanceIfWaitingFor(DiagProtocol.OPCODE_FLASH_COUNTS);
                    }
                } else if (rxOp == (DiagProtocol.OPCODE_SNAPSHOT & 0xFF)) {
                    DiagProtocol.FreezeSnapshot s = DiagProtocol.parseSnapshotResponse(frame);
                    if (s != null) {
                        gotSnapshot = s;
                        advanceIfWaitingFor(DiagProtocol.OPCODE_SNAPSHOT);
                    }
                } else if (rxOp == (DiagProtocol.OPCODE_PEAK_POLLS & 0xFF)) {
                    DiagProtocol.PeakFreezePolls p = DiagProtocol.parsePeakPollsResponse(frame);
                    if (p != null) {
                        gotPeakPolls = p;
                        advanceIfWaitingFor(DiagProtocol.OPCODE_PEAK_POLLS);
                    }
                }
            }
        });
    }

    private void advanceIfWaitingFor(byte expectedOpcode) {
        if (queryIndex < QUERY_SEQUENCE.length && QUERY_SEQUENCE[queryIndex] == expectedOpcode) {
            mainHandler.removeCallbacks(cmdTimeoutRunnable);
            queryIndex++;
            cmdRetryCount = 0;
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    sendCurrentQuery();
                }
            }, 60L);
        }
    }

    private void sendCurrentQuery() {
        if (cancelled || activeGatt == null || writeChar == null) return;
        if (queryIndex >= QUERY_SEQUENCE.length) {
            finishAndDeliverReport();
            return;
        }

        byte op = QUERY_SEQUENCE[queryIndex];
        String desc;
        switch (op) {
            case DiagProtocol.OPCODE_VERSION:
                desc = "1/4 读取 MCU 固件与协议芯片版本 (0x01)…";
                break;
            case DiagProtocol.OPCODE_FLASH_COUNTS:
                desc = "2/4 读取 Flash 历史累计冻死拉闸次数 (0x0C)…";
                break;
            case DiagProtocol.OPCODE_SNAPSHOT:
                desc = "3/4 读取本次通电首次拉闸现场快照 (0x0A)…";
                break;
            default:
                desc = "4/4 读取本次通电连续停滞峰值 (0x0B)…";
                break;
        }
        listener.onProgress("正在读取诊断数据…", desc);

        byte[] cmd = DiagProtocol.buildReadCommand(op);
        mainHandler.removeCallbacks(cmdTimeoutRunnable);
        mainHandler.postDelayed(cmdTimeoutRunnable, CMD_TIMEOUT_MS);

        boolean ok = writeCommandBytes(activeGatt, writeChar, cmd);
        if (!ok) {
            mainHandler.removeCallbacks(cmdTimeoutRunnable);
            onCurrentCommandTimeoutOrFail();
        }
    }

    private final Runnable cmdTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (cancelled || activeGatt == null) return;
            onCurrentCommandTimeoutOrFail();
        }
    };

    private void onCurrentCommandTimeoutOrFail() {
        if (queryIndex >= QUERY_SEQUENCE.length) {
            finishAndDeliverReport();
            return;
        }
        byte op = QUERY_SEQUENCE[queryIndex];

        // 若 0x01 已明确读出固件低于 0079（例如 2.1.2_0073），则 0x0C 无响应属于旧固件正常现象，无需苦等 0A/0B
        if (op == DiagProtocol.OPCODE_FLASH_COUNTS
                && gotVersion != null
                && !gotVersion.supports0079Diag()) {
            finishAndDeliverReport();
            return;
        }

        if (cmdRetryCount < 1) {
            cmdRetryCount++;
            sendCurrentQuery();
            return;
        }

        queryIndex++;
        cmdRetryCount = 0;
        sendCurrentQuery();
    }

    private void finishAndDeliverReport() {
        mainHandler.removeCallbacksAndMessages(null);
        closeGattInternal();

        if (gotVersion == null && gotFlashCounts == null && gotSnapshot == null && gotPeakPolls == null) {
            listener.onError("未收到充电头诊断响应",
                    "虽然连上了 0xAF00 服务，但四条只读查询均未回包。请确认设备为酷态科 10 号 Ultra 且未被其他连接干扰后重试。");
            return;
        }

        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        DiagProtocol.DiagReport report = new DiagProtocol.DiagReport(
                ts,
                targetCandidate.displayTitle(),
                targetCandidate.address,
                targetCandidate.rssi,
                gotVersion,
                gotFlashCounts,
                gotSnapshot,
                gotPeakPolls
        );
        listener.onSuccess(report);
    }

    @SuppressWarnings("deprecation")
    private static boolean writeCccdEnable(BluetoothGatt gatt, BluetoothGattDescriptor cccd) {
        if (Build.VERSION.SDK_INT >= 33) {
            return gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == 0;
        } else {
            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            return gatt.writeDescriptor(cccd);
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean writeCommandBytes(BluetoothGatt gatt, BluetoothGattCharacteristic ch, byte[] payload) {
        int props = ch.getProperties();
        int writeType = ((props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
                ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        if (Build.VERSION.SDK_INT >= 33) {
            return gatt.writeCharacteristic(ch, payload, writeType) == 0;
        } else {
            ch.setWriteType(writeType);
            ch.setValue(payload);
            return gatt.writeCharacteristic(ch);
        }
    }
}
