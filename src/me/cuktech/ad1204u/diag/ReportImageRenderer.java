package me.cuktech.ad1204u.diag;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 独立渲染 1080px 宽高清证据报告长图（完整包含结论、各口指标与 4 条 0xAF08 原始十六进制回包帧），
 * 写入系统相册 Pictures/CUKTECH_Diag，不依赖屏幕截图裁切。
 */
public final class ReportImageRenderer {

    private ReportImageRenderer() {}

    private static final class KvRow {
        final String label;
        final String value;
        final boolean highlightValue;

        KvRow(String label, String value, boolean highlightValue) {
            this.label = label;
            this.value = value;
            this.highlightValue = highlightValue;
        }
    }

    public static Bitmap renderReportBitmap(DiagProtocol.DiagReport r) {
        final int width = 1080;
        final int outerPad = 48;
        final int cardWidth = width - outerPad * 2;
        final int innerPad = 40;
        final int contentWidth = cardWidth - innerPad * 2;

        TextPaint titlePaint = newPaint(42f, true, 0xFF0F172A, false);
        TextPaint subPaint = newPaint(25f, false, 0xFF475569, false);
        TextPaint verdictTitlePaint = newPaint(38f, true, 0xFF0F172A, false);
        TextPaint bigMetricLabelPaint = newPaint(24f, false, 0xFF475569, false);
        TextPaint bigMetricNumPaint = newPaint(56f, true, 0xFF0F172A, false);
        TextPaint notePaint = newPaint(24f, false, 0xFF334155, false);
        TextPaint secHeaderPaint = newPaint(28f, true, 0xFF0F172A, false);
        TextPaint kvKeyPaint = newPaint(25f, false, 0xFF64748B, false);
        TextPaint kvValPaint = newPaint(25f, true, 0xFF0F172A, false);
        TextPaint kvWarnValPaint = newPaint(25f, true, 0xFFB91C1C, false);
        TextPaint monoPaint = newPaint(21f, false, 0xFF1E293B, true);
        TextPaint footerPaint = newPaint(22f, false, 0xFF64748B, false);

        boolean legacy = r.isLegacyFirmwareWithout0079();
        boolean alert = r.hasFreezeEvidence();

        int verdictBg;
        int verdictBorder;
        int verdictTitleColor;
        String verdictHeadline;
        String verdictNote;

        if (legacy) {
            verdictBg = 0xFFEFF6FF;
            verdictBorder = 0xFF93C5FD;
            verdictTitleColor = 0xFF1D4ED8;
            verdictHeadline = "当前固件尚未升级至 2.1.2_0079";
            verdictNote = "已成功连上充电头并读出旧版固件号，旧版固件不含 0079 新增的冻死断电保护与 Flash 计数记录项。";
        } else if (alert) {
            verdictBg = 0xFFFEF2F2;
            verdictBorder = 0xFFFCA5A5;
            verdictTitleColor = 0xFFB91C1C;
            verdictHeadline = "检出协议芯片冻死拉闸记录";
            verdictNote = "官方 0079 固件写入 Flash 记录 #5 (跨断电永久保存) 或当次通电快照非零，证实该充电头曾触发读数冻结满约 18 秒的关断保护。";
        } else {
            verdictBg = 0xFFECFDF5;
            verdictBorder = 0xFF6EE7B7;
            verdictTitleColor = 0xFF047857;
            verdictHeadline = "暂未检出冻死拉闸记录";
            verdictNote = "Flash 历史累计冻死拉闸次数为 0。注：0079 仅在端口电压与电流均非零、且读数连续不变满 301 次 (18001 tick) 时计入一次拉闸。";
        }
        verdictTitlePaint.setColor(verdictTitleColor);
        bigMetricNumPaint.setColor(verdictTitleColor);

        StaticLayout verdictNoteLayout = buildLayout(verdictNote, notePaint, contentWidth, Layout.Alignment.ALIGN_NORMAL);

        List<KvRow> detailRows = new ArrayList<>();
        detailRows.add(new KvRow("检测时间", r.timestampText, false));
        detailRows.add(new KvRow("设备标识", r.deviceName + " (" + r.maskedMac + ")", false));
        if (r.versionInfo != null) {
            detailRows.add(new KvRow("MCU 固件版本",
                    String.format(Locale.US, "%s (CRC 0x%04X)",
                            r.versionInfo.formatMcuVersion(), r.versionInfo.mbrCrc16), false));
            detailRows.add(new KvRow("协议芯片版本",
                    String.format(Locale.US, "C1 0x%08X · C2 0x%08X\nC3/A 0x%04X",
                            r.versionInfo.husbC1Version, r.versionInfo.husbC2Version, r.versionInfo.sc2021Version), false));
        } else {
            detailRows.add(new KvRow("MCU 固件版本", "未返回版本帧", false));
        }

        if (r.snapshot != null) {
            if (r.snapshot.triggered) {
                detailRows.add(new KvRow("本次通电首次快照",
                        String.format(Locale.US, "已触发 · %s\n连续停滞 %d 次 / %d tick · 原始采样 V=%d, I=%d",
                                r.snapshot.portLabel(), r.snapshot.frozenPollCount,
                                r.snapshot.frozenDurationTicks, r.snapshot.rawVoltage, r.snapshot.rawCurrent),
                        true));
            } else {
                detailRows.add(new KvRow("本次通电首次快照", "未触发 (拔插头断电清空)", false));
            }
        }
        if (r.peakPolls != null) {
            detailRows.add(new KvRow("本次通电停滞峰值",
                    String.format(Locale.US, "C1 峰值 %d 次 · C2 峰值 %d 次 (阈值 301 次)",
                            r.peakPolls.c1PeakPolls, r.peakPolls.c2PeakPolls),
                    r.peakPolls.c1PeakPolls >= 100 || r.peakPolls.c2PeakPolls >= 100));
        }

        final int keyColWidth = 280;
        final int valColWidth = contentWidth - keyColWidth - 20;
        List<StaticLayout> valLayouts = new ArrayList<>();
        int detailsContentHeight = 0;
        for (int i = 0; i < detailRows.size(); i++) {
            KvRow row = detailRows.get(i);
            StaticLayout vl = buildLayout(row.value, row.highlightValue ? kvWarnValPaint : kvValPaint,
                    valColWidth, Layout.Alignment.ALIGN_OPPOSITE);
            valLayouts.add(vl);
            int rowH = Math.max(38, vl.getHeight()) + 28;
            detailsContentHeight += rowH;
        }

        String hexBlockText =
                "01(版本): " + (r.versionInfo != null ? r.versionInfo.rawHex : "无响应") + "\n" +
                "0C(累计): " + (r.flashCounts != null ? r.flashCounts.rawHex : "无响应") + "\n" +
                "0A(快照): " + (r.snapshot != null ? r.snapshot.rawHex : "无响应") + "\n" +
                "0B(峰值): " + (r.peakPolls != null ? r.peakPolls.rawHex : "无响应");
        StaticLayout hexLayout = buildLayout(hexBlockText, monoPaint, contentWidth - 32, Layout.Alignment.ALIGN_NORMAL);

        // 计算总高度
        int y = outerPad;
        int headerHeight = 112;
        y += headerHeight + 24;

        int verdictCardHeight = innerPad + 54 + (r.flashCounts != null ? 156 : 16) + verdictNoteLayout.getHeight() + innerPad;
        y += verdictCardHeight + 28;

        int detailCardHeight = innerPad + 48 + detailsContentHeight + innerPad - 12;
        y += detailCardHeight + 28;

        int hexCardHeight = innerPad + 50 + hexLayout.getHeight() + 32 + innerPad;
        y += hexCardHeight + 28;

        int footerHeight = 44;
        int totalHeight = y + footerHeight + outerPad;

        Bitmap bmp = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(0xFFF1F5F9);

        Paint boxFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint boxStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        boxStroke.setStyle(Paint.Style.STROKE);
        boxStroke.setStrokeWidth(2f);

        int curY = outerPad;

        // 1. 页眉标题
        canvas.drawText("酷态科 10 号 Ultra (AD1204U) 固件冻死诊断报告", outerPad, curY + 44, titlePaint);
        canvas.drawText("GATT 0xAF00 离线蓝牙直读  ·  App 零联网权限本地生成", outerPad, curY + 86, subPaint);
        curY += headerHeight + 24;

        // 2. 核心结论卡片
        RectF verdictRect = new RectF(outerPad, curY, outerPad + cardWidth, curY + verdictCardHeight);
        boxFill.setColor(verdictBg);
        boxStroke.setColor(verdictBorder);
        canvas.drawRoundRect(verdictRect, 24f, 24f, boxFill);
        canvas.drawRoundRect(verdictRect, 24f, 24f, boxStroke);

        int vy = curY + innerPad;
        canvas.drawText(verdictHeadline, outerPad + innerPad, vy + 38, verdictTitlePaint);
        vy += 54;

        if (r.flashCounts != null) {
            int halfW = (contentWidth - 24) / 2;
            Paint metricBoxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            metricBoxPaint.setColor(0xD9FFFFFF);
            Paint metricBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            metricBorderPaint.setStyle(Paint.Style.STROKE);
            metricBorderPaint.setStrokeWidth(2f);
            metricBorderPaint.setColor(0x26000000);

            RectF c1Box = new RectF(outerPad + innerPad, vy + 8, outerPad + innerPad + halfW, vy + 136);
            RectF c2Box = new RectF(outerPad + innerPad + halfW + 24, vy + 8, outerPad + innerPad + contentWidth, vy + 136);
            canvas.drawRoundRect(c1Box, 16f, 16f, metricBoxPaint);
            canvas.drawRoundRect(c1Box, 16f, 16f, metricBorderPaint);
            canvas.drawRoundRect(c2Box, 16f, 16f, metricBoxPaint);
            canvas.drawRoundRect(c2Box, 16f, 16f, metricBorderPaint);

            canvas.drawText("C1 口累计拉闸", c1Box.left + 24, c1Box.top + 40, bigMetricLabelPaint);
            canvas.drawText(r.flashCounts.c1TotalCount + " 次", c1Box.left + 24, c1Box.top + 106, bigMetricNumPaint);

            canvas.drawText("C2 口累计拉闸", c2Box.left + 24, c2Box.top + 40, bigMetricLabelPaint);
            canvas.drawText(r.flashCounts.c2TotalCount + " 次", c2Box.left + 24, c2Box.top + 106, bigMetricNumPaint);
            vy += 156;
        } else {
            vy += 16;
        }

        canvas.save();
        canvas.translate(outerPad + innerPad, vy);
        verdictNoteLayout.draw(canvas);
        canvas.restore();

        curY += verdictCardHeight + 28;

        // 3. 诊断指标明细卡片
        RectF detailRect = new RectF(outerPad, curY, outerPad + cardWidth, curY + detailCardHeight);
        boxFill.setColor(Color.WHITE);
        boxStroke.setColor(0xFFE2E8F0);
        canvas.drawRoundRect(detailRect, 24f, 24f, boxFill);
        canvas.drawRoundRect(detailRect, 24f, 24f, boxStroke);

        int dy = curY + innerPad;
        canvas.drawText("设备与运行明细", outerPad + innerPad, dy + 30, secHeaderPaint);
        dy += 48;

        Paint dividerPaint = new Paint();
        dividerPaint.setColor(0xFFF1F5F9);
        dividerPaint.setStrokeWidth(2f);

        for (int i = 0; i < detailRows.size(); i++) {
            KvRow row = detailRows.get(i);
            StaticLayout vl = valLayouts.get(i);
            if (i > 0) {
                canvas.drawLine(outerPad + innerPad, dy, outerPad + innerPad + contentWidth, dy, dividerPaint);
            }
            canvas.drawText(row.label, outerPad + innerPad, dy + 38, kvKeyPaint);
            canvas.save();
            canvas.translate(outerPad + innerPad + keyColWidth + 20, dy + 12);
            vl.draw(canvas);
            canvas.restore();
            dy += Math.max(38, vl.getHeight()) + 28;
        }

        curY += detailCardHeight + 28;

        // 4. 原始十六进制回包证据卡片
        RectF hexRect = new RectF(outerPad, curY, outerPad + cardWidth, curY + hexCardHeight);
        boxFill.setColor(Color.WHITE);
        boxStroke.setColor(0xFFE2E8F0);
        canvas.drawRoundRect(hexRect, 24f, 24f, boxFill);
        boxStroke.setColor(0xFFE2E8F0);
        canvas.drawRoundRect(hexRect, 24f, 24f, boxStroke);

        int hy = curY + innerPad;
        canvas.drawText("0xAF08 原始蓝牙回包帧 (Hex 取证核验)", outerPad + innerPad, hy + 30, secHeaderPaint);
        hy += 50;

        RectF codeBg = new RectF(outerPad + innerPad, hy, outerPad + innerPad + contentWidth, hy + hexLayout.getHeight() + 32);
        boxFill.setColor(0xFFF8FAFC);
        canvas.drawRoundRect(codeBg, 14f, 14f, boxFill);

        canvas.save();
        canvas.translate(codeBg.left + 16, codeBg.top + 16);
        hexLayout.draw(canvas);
        canvas.restore();

        curY += hexCardHeight + 28;

        // 5. 页脚说明
        canvas.drawText("说明：本工具仅发送 0xF1 只读查询指令 (01 / 0C / 0A / 0B)，不修改充电头任何配置。",
                outerPad, curY + 28, footerPaint);

        return bmp;
    }

    public static String saveReportPngToGallery(Context context, DiagProtocol.DiagReport report) throws IOException {
        Bitmap bmp = renderReportBitmap(report);
        String safeTs = report.timestampText.replace(':', '-').replace(' ', '_');
        String fileName = "CUKTECH_10Ultra_Diag_" + safeTs + ".png";

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentResolver resolver = context.getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CUKTECH_Diag");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);

                Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    throw new IOException("无法在系统相册创建图片记录");
                }
                try (OutputStream out = resolver.openOutputStream(uri)) {
                    if (out == null || !bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        throw new IOException("PNG 编码写入失败");
                    }
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(uri, done, null, null);
                return "系统相册 Pictures/CUKTECH_Diag/" + fileName;
            } else {
                @SuppressWarnings("deprecation")
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES), "CUKTECH_Diag");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IOException("无法创建相册目录 " + dir.getAbsolutePath());
                }
                File outFile = new File(dir, fileName);
                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
                        throw new IOException("PNG 编码写入失败");
                    }
                    fos.flush();
                }
                MediaScannerConnection.scanFile(context, new String[]{outFile.getAbsolutePath()},
                        new String[]{"image/png"}, null);
                return outFile.getAbsolutePath();
            }
        } finally {
            bmp.recycle();
        }
    }

    private static TextPaint newPaint(float sizePx, boolean bold, int color, boolean mono) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTextSize(sizePx);
        p.setColor(color);
        if (mono) {
            p.setTypeface(bold ? Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) : Typeface.MONOSPACE);
        } else {
            p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        return p;
    }

    @SuppressWarnings("deprecation")
    private static StaticLayout buildLayout(CharSequence text, TextPaint paint, int width, Layout.Alignment align) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return StaticLayout.Builder.obtain(text, 0, text.length(), paint, Math.max(1, width))
                    .setAlignment(align)
                    .setLineSpacing(6f, 1.08f)
                    .setIncludePad(false)
                    .build();
        } else {
            return new StaticLayout(text, paint, Math.max(1, width),
                    align, 1.08f, 6f, false);
        }
    }
}
