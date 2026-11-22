package me.cuktech.ad1204u.diag;

/**
 * 离线协议编解码与报告生成回归测试（纯 JVM 直接执行）。
 */
public final class DiagProtocolTest {

    public static void main(String[] args) {
        // 1. 核对 4 条只读请求帧与固件 0x4203b4 字节偏移完全一致
        assertHex("FE-DC-BA-00-F1-02-01-01-EF", DiagProtocol.buildReadCommand(DiagProtocol.OPCODE_VERSION));
        assertHex("FE-DC-BA-00-F1-02-01-0C-EF", DiagProtocol.buildReadCommand(DiagProtocol.OPCODE_FLASH_COUNTS));
        assertHex("FE-DC-BA-00-F1-02-01-0A-EF", DiagProtocol.buildReadCommand(DiagProtocol.OPCODE_SNAPSHOT));
        assertHex("FE-DC-BA-00-F1-02-01-0B-EF", DiagProtocol.buildReadCommand(DiagProtocol.OPCODE_PEAK_POLLS));

        // 2. 核对米家 0xFE95 广播 Service Data PID 0x660E (26126) 识别
        byte[] miotAd1204 = new byte[]{0x30, 0x58, 0x0E, 0x66, 0x01, 0x02, 0x03};
        assertTrue(DiagProtocol.isMiotAd1204uServiceData(miotAd1204), "Should match AD1204U PID 0x660E");
        byte[] miotOther = new byte[]{0x30, 0x58, 0x0F, 0x66, 0x01};
        assertTrue(!DiagProtocol.isMiotAd1204uServiceData(miotOther), "Should reject other MiOT PID");

        // 3. 核对 0x01 版本响应解析 (21 字节)
        byte[] ver0079 = new byte[]{
                (byte) 0xFE, (byte) 0xDC, (byte) 0xBA,
                0x01, (byte) 0xF1, 0x01,
                0x79, 0x00,             // MCU 2.1.2_0079
                0x34, 0x12,             // CRC 0x1234
                0x05, 0x01,             // SC2021 0x0105
                0x11, 0x22, 0x33, 0x44, // C1 HUSB362 0x44332211
                0x55, 0x66, 0x77, (byte) 0x88, // C2 HUSB362 0x88776655
                (byte) 0xEF
        };
        DiagProtocol.VersionInfo vi = DiagProtocol.parseVersionResponse(ver0079);
        assertTrue(vi != null, "VersionInfo should parse");
        assertEquals("2.1.2_0079", vi.formatMcuVersion());
        assertTrue(vi.supports0079Diag(), "0079 supports diag");
        assertEquals(0x1234, vi.mbrCrc16);
        assertEquals(0x0105, vi.sc2021Version);
        assertEquals(0x44332211L, vi.husbC1Version);
        assertEquals(0x88776655L, vi.husbC2Version);

        // 4. 核对 0x0C Flash 累计冻死次数解析 (12 字节)
        byte[] flashCounts = new byte[]{
                (byte) 0xFE, (byte) 0xDC, (byte) 0xBA,
                0x01, (byte) 0xF1, 0x0C, 0x04,
                0x03, 0x00, // C1 = 3
                0x01, 0x00, // C2 = 1
                (byte) 0xEF
        };
        DiagProtocol.FlashFreezeCounts fc = DiagProtocol.parseFlashCountsResponse(flashCounts);
        assertTrue(fc != null && fc.hasAnyFreeze(), "FlashFreezeCounts should flag freeze");
        assertEquals(3, fc.c1TotalCount);
        assertEquals(1, fc.c2TotalCount);

        // 5. 核对 0x0A 快照解析 (20 字节)
        byte[] snap = new byte[]{
                (byte) 0xFE, (byte) 0xDC, (byte) 0xBA,
                0x01, (byte) 0xF1, 0x0A, 0x0C,
                0x01,       // triggered = true
                0x02,       // port = C2
                0x20, 0x4E, // raw V = 20000
                (byte) 0xDC, 0x05, // raw I = 1500
                0x2D, 0x01, // 301 polls
                (byte) 0x8C, 0x46, 0x00, 0x00, // 18060 ticks
                (byte) 0xEF
        };
        DiagProtocol.FreezeSnapshot fs = DiagProtocol.parseSnapshotResponse(snap);
        assertTrue(fs != null && fs.triggered, "Snapshot should be triggered");
        assertEquals("C2 口", fs.portLabel());
        assertEquals(20000, fs.rawVoltage);
        assertEquals(1500, fs.rawCurrent);
        assertEquals(301, fs.frozenPollCount);
        assertEquals(18060L, fs.frozenDurationTicks);

        // 6. 核对 0x0B 连续采样峰值解析 (12 字节)
        byte[] peak = new byte[]{
                (byte) 0xFE, (byte) 0xDC, (byte) 0xBA,
                0x01, (byte) 0xF1, 0x0B, 0x04,
                0x2D, 0x01, // C1 peak = 301
                0x12, 0x00, // C2 peak = 18
                (byte) 0xEF
        };
        DiagProtocol.PeakFreezePolls pp = DiagProtocol.parsePeakPollsResponse(peak);
        assertTrue(pp != null, "PeakPolls should parse");
        assertEquals(301, pp.c1PeakPolls);
        assertEquals(18, pp.c2PeakPolls);

        // 7. 核对 MAC 脱敏与纯文本报告输出
        DiagProtocol.DiagReport rep = new DiagProtocol.DiagReport(
                "2026-11-22 07:30:00", "CUKTECH", "A4:C1:38:99:88:77", -45, vi, fc, fs, pp);
        assertTrue(rep.hasFreezeEvidence(), "Report should detect freeze evidence");
        String txt = rep.buildPlainTextReport();
        assertTrue(txt.contains("A4:C1:**:**:88:77"), "MAC should be masked");
        assertTrue(txt.contains("C1 口 3 次 | C2 口 1 次"), "Report should include C1/C2 counts");
        assertTrue(txt.contains("FE-DC-BA-01-F1-0C-04-03-00-01-00-EF"), "Report should embed raw hex proof");

        System.out.println("ALL_PROTOCOL_TESTS_PASSED");
    }

    private static void assertHex(String expected, byte[] actual) {
        String got = DiagProtocol.toHex(actual);
        if (!expected.equals(got)) {
            throw new AssertionError("Expected " + expected + " but got " + got);
        }
    }

    private static void assertEquals(Object exp, Object got) {
        if (!exp.equals(got)) {
            throw new AssertionError("Expected " + exp + " but got " + got);
        }
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }
}
