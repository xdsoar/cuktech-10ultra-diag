package me.cuktech.ad1204u.diag;

import java.util.Locale;
import java.util.UUID;

/**
 * 酷态科 10 号超级电能充 Ultra (AD1204U / njcuk.fitting.ad1204)
 * 私有 GATT 0xAF00 只读诊断协议编解码与广播特征识别。
 *
 * 严格安全边界：本类仅包含 0xF1 族下的 4 条纯只读查询指令（01 / 0C / 0A / 0B），
 * 不包含任何写配置、清零计数、恢复出厂或固件升级指令。
 */
public final class DiagProtocol {

    private DiagProtocol() {}

    public static final UUID UUID_SERVICE_DIAG = UUID.fromString("0000af00-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHAR_WRITE   = UUID.fromString("0000af07-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_CHAR_NOTIFY  = UUID.fromString("0000af08-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_DESC_CCCD    = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    public static final UUID UUID_SERVICE_MIOT = UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb");

    /** 米家 BLE 广播 Service Data (0xFE95) 偏移 2..3 的 Product ID：26126 (0x660E) */
    public static final int MIOT_PID_AD1204U = 0x660E;

    public static final byte OPCODE_VERSION      = 0x01;
    public static final byte OPCODE_SNAPSHOT     = 0x0A;
    public static final byte OPCODE_PEAK_POLLS   = 0x0B;
    public static final byte OPCODE_FLASH_COUNTS = 0x0C;

    /** 构造 9 字节标准只读查询帧：FE DC BA 00 F1 02 01 <opcode> EF */
    public static byte[] buildReadCommand(byte opcode) {
        if (opcode != OPCODE_VERSION
                && opcode != OPCODE_SNAPSHOT
                && opcode != OPCODE_PEAK_POLLS
                && opcode != OPCODE_FLASH_COUNTS) {
            throw new IllegalArgumentException("Unsupported read-only opcode: " + opcode);
        }
        return new byte[] {
                (byte) 0xFE, (byte) 0xDC, (byte) 0xBA,
                (byte) 0x00, (byte) 0xF1, (byte) 0x02,
                (byte) 0x01, opcode, (byte) 0xEF
        };
    }

    /** 检查米家 0xFE95 Service Data 是否匹配酷态科 10 号 Ultra (PID 0x660E) */
    public static boolean isMiotAd1204uServiceData(byte[] fe95Data) {
        if (fe95Data == null || fe95Data.length < 4) {
            return false;
        }
        int pid = u16le(fe95Data, 2);
        return pid == MIOT_PID_AD1204U;
    }

    /** 提取米家 0xFE95 Service Data 中的 Product ID（无则返回 -1） */
    public static int extractMiotPid(byte[] fe95Data) {
        if (fe95Data == null || fe95Data.length < 4) {
            return -1;
        }
        return u16le(fe95Data, 2);
    }

    /** 判断广播名称是否命中酷态科充电头特征 */
    public static boolean isLikelyCuktechName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String upper = name.toUpperCase(Locale.US);
        return upper.contains("CUKTECH") || upper.contains("AD1204") || upper.contains("10 ULTRA");
    }

    /** 校验通知帧包头 FE DC BA 01 F1 与包尾 EF，返回内部 opcode（frame[5]），无效返回 -1 */
    public static int extractResponseOpcode(byte[] frame) {
        if (frame == null || frame.length < 8) {
            return -1;
        }
        if ((frame[0] & 0xFF) != 0xFE
                || (frame[1] & 0xFF) != 0xDC
                || (frame[2] & 0xFF) != 0xBA
                || (frame[3] & 0xFF) != 0x01
                || (frame[4] & 0xFF) != 0xF1
                || (frame[frame.length - 1] & 0xFF) != 0xEF) {
            return -1;
        }
        return frame[5] & 0xFF;
    }

    public static final class VersionInfo {
        public final int mcuVersionRaw;
        public final int mbrCrc16;
        public final int sc2021Version;
        public final long husbC1Version;
        public final long husbC2Version;
        public final String rawHex;

        public VersionInfo(int mcuVersionRaw, int mbrCrc16, int sc2021Version,
                           long husbC1Version, long husbC2Version, String rawHex) {
            this.mcuVersionRaw = mcuVersionRaw;
            this.mbrCrc16 = mbrCrc16;
            this.sc2021Version = sc2021Version;
            this.husbC1Version = husbC1Version;
            this.husbC2Version = husbC2Version;
            this.rawHex = rawHex;
        }

        public String formatMcuVersion() {
            if (isBcd16(mcuVersionRaw)) {
                return String.format(Locale.US, "2.1.2_%04X", mcuVersionRaw);
            }
            return String.format(Locale.US, "0x%04X", mcuVersionRaw);
        }

        public boolean supports0079Diag() {
            return mcuVersionRaw >= 0x0079;
        }
    }

    public static final class FlashFreezeCounts {
        public final int c1TotalCount;
        public final int c2TotalCount;
        public final String rawHex;

        public FlashFreezeCounts(int c1TotalCount, int c2TotalCount, String rawHex) {
            this.c1TotalCount = c1TotalCount;
            this.c2TotalCount = c2TotalCount;
            this.rawHex = rawHex;
        }

        public boolean hasAnyFreeze() {
            return c1TotalCount > 0 || c2TotalCount > 0;
        }
    }

    public static final class FreezeSnapshot {
        public final boolean triggered;
        public final int port; // 1 = C1, 2 = C2, 0 = none
        public final int rawVoltage;
        public final int rawCurrent;
        public final int frozenPollCount;
        public final long frozenDurationTicks;
        public final String rawHex;

        public FreezeSnapshot(boolean triggered, int port, int rawVoltage, int rawCurrent,
                              int frozenPollCount, long frozenDurationTicks, String rawHex) {
            this.triggered = triggered;
            this.port = port;
            this.rawVoltage = rawVoltage;
            this.rawCurrent = rawCurrent;
            this.frozenPollCount = frozenPollCount;
            this.frozenDurationTicks = frozenDurationTicks;
            this.rawHex = rawHex;
        }

        public String portLabel() {
            if (port == 1) return "C1 口";
            if (port == 2) return "C2 口";
            return port == 0 ? "无" : ("端口 #" + port);
        }
    }

    public static final class PeakFreezePolls {
        public final int c1PeakPolls;
        public final int c2PeakPolls;
        public final String rawHex;

        public PeakFreezePolls(int c1PeakPolls, int c2PeakPolls, String rawHex) {
            this.c1PeakPolls = c1PeakPolls;
            this.c2PeakPolls = c2PeakPolls;
            this.rawHex = rawHex;
        }
    }

    /**
     * 解析 Opcode 0x01（固件与各口协议芯片版本，共 21 字节）：
     * FE DC BA 01 F1 01 <mcu:2B> <crc:2B> <sc2021:2B> <c1:4B> <c2:4B> EF
     */
    public static VersionInfo parseVersionResponse(byte[] frame) {
        if (extractResponseOpcode(frame) != (OPCODE_VERSION & 0xFF) || frame.length < 21) {
            return null;
        }
        int mcu = u16le(frame, 6);
        int crc = u16le(frame, 8);
        int sc2021 = u16le(frame, 10);
        long c1 = u32le(frame, 12);
        long c2 = u32le(frame, 16);
        return new VersionInfo(mcu, crc, sc2021, c1, c2, toHex(frame));
    }

    /**
     * 解析 Opcode 0x0C（Flash NVS 记录 #5 历史累计 C1/C2 冻死拉闸次数，共 12 字节）：
     * FE DC BA 01 F1 0C 04 <c1:2B LE> <c2:2B LE> EF
     */
    public static FlashFreezeCounts parseFlashCountsResponse(byte[] frame) {
        if (extractResponseOpcode(frame) != (OPCODE_FLASH_COUNTS & 0xFF) || frame.length < 12) {
            return null;
        }
        if ((frame[6] & 0xFF) != 0x04) {
            return null;
        }
        int c1 = u16le(frame, 7);
        int c2 = u16le(frame, 9);
        return new FlashFreezeCounts(c1, c2, toHex(frame));
    }

    /**
     * 解析 Opcode 0x0A（本次不断电首次冻死拉闸黑匣子快照，共 20 字节）：
     * FE DC BA 01 F1 0A 0C <trig:1B> <port:1B> <V:2B LE> <I:2B LE> <cnt:2B LE> <ticks:4B LE> EF
     */
    public static FreezeSnapshot parseSnapshotResponse(byte[] frame) {
        if (extractResponseOpcode(frame) != (OPCODE_SNAPSHOT & 0xFF) || frame.length < 20) {
            return null;
        }
        if ((frame[6] & 0xFF) != 0x0C) {
            return null;
        }
        boolean triggered = (frame[7] & 0xFF) != 0;
        int port = frame[8] & 0xFF;
        int volt = u16le(frame, 9);
        int curr = u16le(frame, 11);
        int cnt = u16le(frame, 13);
        long ticks = u32le(frame, 15);
        return new FreezeSnapshot(triggered, port, volt, curr, cnt, ticks, toHex(frame));
    }

    /**
     * 解析 Opcode 0x0B（本次不断电连续采样不变峰值次数，共 12 字节）：
     * FE DC BA 01 F1 0B 04 <c1_peak:2B LE> <c2_peak:2B LE> EF
     */
    public static PeakFreezePolls parsePeakPollsResponse(byte[] frame) {
        if (extractResponseOpcode(frame) != (OPCODE_PEAK_POLLS & 0xFF) || frame.length < 12) {
            return null;
        }
        if ((frame[6] & 0xFF) != 0x04) {
            return null;
        }
        int c1Peak = u16le(frame, 7);
        int c2Peak = u16le(frame, 9);
        return new PeakFreezePolls(c1Peak, c2Peak, toHex(frame));
    }

    public static final class DiagReport {
        public final String timestampText;
        public final String deviceName;
        public final String maskedMac;
        public final int rssi;
        public final VersionInfo versionInfo;
        public final FlashFreezeCounts flashCounts;
        public final FreezeSnapshot snapshot;
        public final PeakFreezePolls peakPolls;

        public DiagReport(String timestampText, String deviceName, String macAddress, int rssi,
                          VersionInfo versionInfo, FlashFreezeCounts flashCounts,
                          FreezeSnapshot snapshot, PeakFreezePolls peakPolls) {
            this.timestampText = timestampText;
            this.deviceName = (deviceName == null || deviceName.trim().isEmpty()) ? "CUKTECH 10 Ultra" : deviceName.trim();
            this.maskedMac = maskMac(macAddress);
            this.rssi = rssi;
            this.versionInfo = versionInfo;
            this.flashCounts = flashCounts;
            this.snapshot = snapshot;
            this.peakPolls = peakPolls;
        }

        public boolean isLegacyFirmwareWithout0079() {
            return flashCounts == null && versionInfo != null && !versionInfo.supports0079Diag();
        }

        public boolean hasFreezeEvidence() {
            if (flashCounts != null && flashCounts.hasAnyFreeze()) {
                return true;
            }
            return snapshot != null && snapshot.triggered;
        }

        public String buildPlainTextReport() {
            StringBuilder sb = new StringBuilder();
            sb.append("【酷态科 10 号 Ultra (AD1204U) 固件冻死诊断报告】\n");
            sb.append("检测时间：").append(timestampText).append('\n');
            sb.append("设备标识：").append(deviceName).append(" (").append(maskedMac).append(")\n");
            if (versionInfo != null) {
                sb.append("MCU 固件：").append(versionInfo.formatMcuVersion())
                  .append(String.format(Locale.US, " (CRC 0x%04X)\n", versionInfo.mbrCrc16));
                sb.append(String.format(Locale.US,
                        "协议芯片：C1=0x%08X | C2=0x%08X | C3/A=0x%04X\n",
                        versionInfo.husbC1Version, versionInfo.husbC2Version, versionInfo.sc2021Version));
            } else {
                sb.append("MCU 固件：未返回版本帧\n");
            }
            sb.append('\n');

            if (isLegacyFirmwareWithout0079()) {
                sb.append("▶ 诊断结论：当前固件尚未升级至 2.1.2_0079\n");
                sb.append("说明：旧版固件不含 0079 新增的 18 秒冻死断电保护及 Flash 诊断记录项。\n");
            } else if (flashCounts != null) {
                if (hasFreezeEvidence()) {
                    sb.append("▶ 诊断结论：【检出协议芯片冻死拉闸记录】\n");
                } else {
                    sb.append("▶ 诊断结论：暂未检出冻死拉闸记录\n");
                }
                sb.append("• Flash 历史累计拉闸（断电保留）：C1 口 ")
                  .append(flashCounts.c1TotalCount).append(" 次 | C2 口 ")
                  .append(flashCounts.c2TotalCount).append(" 次\n");

                if (snapshot != null) {
                    if (snapshot.triggered) {
                        sb.append(String.format(Locale.US,
                                "• 本次通电首次拉闸快照：已触发（%s，连续停滞 %d 次 / %d tick，原始采样 V=%d, I=%d）\n",
                                snapshot.portLabel(), snapshot.frozenPollCount,
                                snapshot.frozenDurationTicks, snapshot.rawVoltage, snapshot.rawCurrent));
                    } else {
                        sb.append("• 本次通电首次拉闸快照：未触发（拔插头断电会清空此项）\n");
                    }
                }
                if (peakPolls != null) {
                    sb.append("• 本次通电连续停滞峰值：C1 峰值 ")
                      .append(peakPolls.c1PeakPolls).append(" 次 | C2 峰值 ")
                      .append(peakPolls.c2PeakPolls).append(" 次（满 301 次且满 18001 tick 触发拉闸）\n");
                }
            }

            sb.append("\n--- 原始蓝牙回包帧（0xAF08 Hex）---\n");
            sb.append("01(版本): ").append(versionInfo != null ? versionInfo.rawHex : "无响应").append('\n');
            sb.append("0C(累计): ").append(flashCounts != null ? flashCounts.rawHex : "无响应").append('\n');
            sb.append("0A(快照): ").append(snapshot != null ? snapshot.rawHex : "无响应").append('\n');
            sb.append("0B(峰值): ").append(peakPolls != null ? peakPolls.rawHex : "无响应");
            return sb.toString();
        }
    }

    public static String maskMac(String mac) {
        if (mac == null || mac.length() < 17) {
            return mac == null ? "未知地址" : mac;
        }
        String[] parts = mac.split(":");
        if (parts.length == 6) {
            return parts[0] + ":" + parts[1] + ":**:**:" + parts[4] + ":" + parts[5];
        }
        return mac;
    }

    public static int u16le(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    public static long u32le(byte[] b, int off) {
        return ((long) (b[off] & 0xFF))
                | (((long) (b[off + 1] & 0xFF)) << 8)
                | (((long) (b[off + 2] & 0xFF)) << 16)
                | (((long) (b[off + 3] & 0xFF)) << 24);
    }

    private static boolean isBcd16(int v) {
        for (int shift = 0; shift < 16; shift += 4) {
            if (((v >> shift) & 0xF) > 9) {
                return false;
            }
        }
        return v > 0;
    }

    public static String toHex(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(data.length * 3 - 1);
        for (int i = 0; i < data.length; i++) {
            if (i > 0) sb.append('-');
            sb.append(String.format(Locale.US, "%02X", data[i] & 0xFF));
        }
        return sb.toString();
    }
}
