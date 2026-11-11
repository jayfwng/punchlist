package com.punchlist.app;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * PUNCH LIST 양식(집계표 + 공종별 시트)을 사진 포함 .xlsx 로 직접 생성한다.
 * 안드로이드 API에 의존하지 않으므로 JVM 에서도 테스트할 수 있다.
 */
public class XlsxWriter {

    /** 사진 바이트를 공급하는 인터페이스 (안드로이드: 축소 JPEG, 테스트: 더미) */
    public interface ImageSource {
        /** @return null 이면 사진 생략 */
        Img load(String photoId) throws IOException;
    }

    public static class Img {
        public final byte[] jpeg;
        public final int width, height;
        public Img(byte[] jpeg, int width, int height) { this.jpeg = jpeg; this.width = width; this.height = height; }
    }

    public static class Item {
        public int no;
        public String floor = "", location = "", equipment = "", issue = "";
        public String action = "", company = "", actionDate = "";
        public boolean done;
        public List<String> photoIds = new ArrayList<String>();
        /** 엑셀 현장사진 1·2 로 넣을 사진 (비어 있으면 앞의 2장) */
        public List<String> excelPhotoIds = new ArrayList<String>();

        public List<String> excelPhotos() {
            List<String> src = excelPhotoIds.isEmpty() ? photoIds : excelPhotoIds;
            return src.size() <= 2 ? src : src.subList(0, 2);
        }
    }

    public static class Category {
        public String name;        // 시트명 (예: 안전(공통))
        public String title;       // 제목 (예: PUNCH LIST  (안전부문))
        public String summaryName; // 집계표 표기 (예: 안전)
        public List<Item> items = new ArrayList<Item>();
        public Category(String name, String title, String summaryName) { this.name = name; this.title = title; this.summaryName = summaryName; }
    }

    public interface Progress { void onPhoto(int done, int total); }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final long EMU_PER_PX = 9525L;

    // 열 너비(문자 단위) — 원본 양식과 동일
    private static final double[] COL_W = {4.25, 9.75, 21.5, 23.625, 40.625, 30.625, 30.625, 30.625, 10.625, 30.625, 30.625, 30.625, 2.125, 10.0};
    private static final double ROW_H_PT = 109.5; // 데이터 행 높이
    private static final String[] HEADERS = {"NO", "층", "장소", "설비(장비)명", "현황 및 문제점", "현장 사진 1", "현장 사진 2",
            "조치 내역", "업체명", "조치일자", "조치사진 1", "조치사진 2"};

    // ---- styles.xml 의 cellXfs 인덱스 ----
    private static final int S_DEFAULT = 0, S_TITLE = 1, S_HEAD = 2, S_CELL_C = 3, S_CELL_WRAP = 4, S_SUM_TITLE = 5,
            S_SUM_HEAD = 6, S_SUM_CELL = 7, S_SUM_NUM = 8, S_SUM_PCT = 9, S_DATE_R = 10, S_INFO = 11, S_DONE = 12, S_NO = 13;

    private final String buildingName;
    private final String inspectDate;   // 표시용 yyyy-MM-dd
    private final String inspector;
    private final List<Category> cats;

    public XlsxWriter(String buildingName, String inspectDate, String inspector, List<Category> cats) {
        this.buildingName = buildingName == null ? "" : buildingName;
        this.inspectDate = inspectDate == null ? "" : inspectDate;
        this.inspector = inspector == null ? "" : inspector;
        this.cats = cats;
    }

    public void write(OutputStream out, ImageSource images, Progress progress) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(out);
        zip.setLevel(6);

        int sheetCount = cats.size() + 1;
        int totalPhotos = 0;
        for (Category c : cats) for (Item it : c.items) totalPhotos += it.excelPhotos().size();

        // 사진/드로잉
        int imageNo = 0, donePhotos = 0;
        List<Integer> drawingSheets = new ArrayList<Integer>(); // sheet index(1-based) 가진 drawing
        List<String> sheetXml = new ArrayList<String>();
        sheetXml.add(summarySheet());
        for (int ci = 0; ci < cats.size(); ci++) {
            Category c = cats.get(ci);
            int sheetIdx = ci + 2;
            StringBuilder drawing = new StringBuilder();
            StringBuilder drawingRels = new StringBuilder();
            int picNo = 0;
            for (int r = 0; r < c.items.size(); r++) {
                Item it = c.items.get(r);
                List<String> xl = it.excelPhotos();
                int n = xl.size();
                for (int p = 0; p < n; p++) {
                    Img img = null;
                    try { img = images == null ? null : images.load(xl.get(p)); } catch (IOException e) { img = null; }
                    donePhotos++;
                    if (progress != null) progress.onPhoto(donePhotos, totalPhotos);
                    if (img == null) continue;
                    imageNo++;
                    picNo++;
                    String media = "image" + imageNo + ".jpeg";
                    putBytes(zip, "xl/media/" + media, img.jpeg, true);
                    drawingRels.append("<Relationship Id=\"rId").append(picNo)
                            .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"../media/")
                            .append(media).append("\"/>");
                    drawing.append(picAnchor(picNo, 5 + p, 3 + r, img.width, img.height));
                }
            }
            boolean hasDrawing = picNo > 0;
            if (hasDrawing) {
                drawingSheets.add(sheetIdx);
                putText(zip, "xl/drawings/drawing" + sheetIdx + ".xml",
                        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                        "<xdr:wsDr xmlns:xdr=\"http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">"
                        + drawing + "</xdr:wsDr>");
                putText(zip, "xl/drawings/_rels/drawing" + sheetIdx + ".xml.rels",
                        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" + drawingRels + "</Relationships>");
                putText(zip, "xl/worksheets/_rels/sheet" + sheetIdx + ".xml.rels",
                        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing\" Target=\"../drawings/drawing" + sheetIdx + ".xml\"/>" +
                        "</Relationships>");
            }
            sheetXml.add(categorySheet(c, hasDrawing));
        }

        for (int i = 0; i < sheetXml.size(); i++) putText(zip, "xl/worksheets/sheet" + (i + 1) + ".xml", sheetXml.get(i));

        // [Content_Types].xml
        StringBuilder ct = new StringBuilder();
        ct.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
          .append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
          .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
          .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
          .append("<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>")
          .append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
          .append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
          .append("<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>")
          .append("<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>");
        for (int i = 1; i <= sheetCount; i++)
            ct.append("<Override PartName=\"/xl/worksheets/sheet").append(i).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        for (int s : drawingSheets)
            ct.append("<Override PartName=\"/xl/drawings/drawing").append(s).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawing+xml\"/>");
        ct.append("</Types>");
        putText(zip, "[Content_Types].xml", ct.toString());

        putText(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
                "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>" +
                "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/>" +
                "</Relationships>");

        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        String now = iso.format(new Date());
        putText(zip, "docProps/core.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" xmlns:dcmitype=\"http://purl.org/dc/dcmitype/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" +
                "<dc:title>" + esc(buildingName + " Punch List") + "</dc:title><dc:creator>" + esc(inspector.length() > 0 ? inspector : "PunchList") + "</dc:creator>" +
                "<dcterms:created xsi:type=\"dcterms:W3CDTF\">" + now + "</dcterms:created><dcterms:modified xsi:type=\"dcterms:W3CDTF\">" + now + "</dcterms:modified>" +
                "</cp:coreProperties>");
        putText(zip, "docProps/app.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\"><Application>PunchList</Application></Properties>");

        // workbook.xml
        StringBuilder wb = new StringBuilder();
        wb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
          .append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
          .append("<workbookPr/><bookViews><workbookView activeTab=\"0\"/></bookViews><sheets>");
        wb.append("<sheet name=\"집계표\" sheetId=\"1\" r:id=\"rId1\"/>");
        for (int i = 0; i < cats.size(); i++)
            wb.append("<sheet name=\"").append(esc(cats.get(i).name)).append("\" sheetId=\"").append(i + 2).append("\" r:id=\"rId").append(i + 2).append("\"/>");
        wb.append("</sheets><definedNames>");
        for (int i = 0; i < cats.size(); i++) {
            int last = Math.max(4, 3 + cats.get(i).items.size());
            String q = sheetRef(cats.get(i).name);
            wb.append("<definedName name=\"_xlnm._FilterDatabase\" localSheetId=\"").append(i + 1).append("\" hidden=\"1\">")
              .append(esc(q)).append("!$A$3:$N$").append(last).append("</definedName>");
            wb.append("<definedName name=\"_xlnm.Print_Area\" localSheetId=\"").append(i + 1).append("\">")
              .append(esc(q)).append("!$A$1:$L$").append(last).append("</definedName>");
            wb.append("<definedName name=\"_xlnm.Print_Titles\" localSheetId=\"").append(i + 1).append("\">")
              .append(esc(q)).append("!$3:$3</definedName>");
        }
        wb.append("</definedNames><calcPr calcId=\"191029\" fullCalcOnLoad=\"1\"/></workbook>");
        putText(zip, "xl/workbook.xml", wb.toString());

        StringBuilder wr = new StringBuilder();
        wr.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 1; i <= sheetCount; i++)
            wr.append("<Relationship Id=\"rId").append(i).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet").append(i).append(".xml\"/>");
        wr.append("<Relationship Id=\"rId").append(sheetCount + 1).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");
        wr.append("</Relationships>");
        putText(zip, "xl/_rels/workbook.xml.rels", wr.toString());

        putText(zip, "xl/styles.xml", styles());
        zip.finish();
        zip.flush();
    }

    // ------------------------------------------------------------------ sheets

    private String summarySheet() {
        StringBuilder sb = sheetStart("FF0070C0", 100, 0, 0);
        sb.append("<cols><col min=\"1\" max=\"1\" width=\"1.75\" customWidth=\"1\"/><col min=\"2\" max=\"2\" width=\"11\" customWidth=\"1\"/>")
          .append("<col min=\"3\" max=\"11\" width=\"10\" customWidth=\"1\"/><col min=\"12\" max=\"12\" width=\"16\" customWidth=\"1\"/></cols><sheetData>");
        // 1행 제목
        sb.append("<row r=\"1\" ht=\"35.1\" customHeight=\"1\">").append(str("B1", "집 계 표", S_SUM_TITLE));
        for (char c = 'C'; c <= 'L'; c++) sb.append(empty(c + "1", S_SUM_TITLE));
        sb.append("</row>");
        // 2행: 건물/점검일
        String info = "건물명 : " + buildingName + (inspector.length() > 0 ? "    점검자 : " + inspector : "");
        sb.append("<row r=\"2\" ht=\"20\" customHeight=\"1\">").append(str("B2", info, S_INFO))
          .append(str("L2", inspectDate, S_DATE_R)).append("</row>");
        // 3,4행 머리글
        sb.append("<row r=\"3\" ht=\"20.1\" customHeight=\"1\">")
          .append(str("B3", "공 종", S_SUM_HEAD)).append(str("C3", "하자(건수)", S_SUM_HEAD)).append(empty("D3", S_SUM_HEAD)).append(empty("E3", S_SUM_HEAD))
          .append(str("F3", "공종별 하자처리(율%)", S_SUM_HEAD)).append(empty("G3", S_SUM_HEAD)).append(empty("H3", S_SUM_HEAD))
          .append(str("I3", "하자처리율(율%)", S_SUM_HEAD)).append(empty("J3", S_SUM_HEAD)).append(empty("K3", S_SUM_HEAD))
          .append(str("L3", "비고", S_SUM_HEAD)).append("</row>");
        sb.append("<row r=\"4\" ht=\"20.1\" customHeight=\"1\">").append(empty("B4", S_SUM_HEAD));
        String[] sub = {"계", "진행중", "조치완료"};
        char col = 'C';
        for (int g = 0; g < 3; g++) for (int k = 0; k < 3; k++) { sb.append(str(col + "4", sub[k], S_SUM_HEAD)); col++; }
        sb.append(empty("L4", S_SUM_HEAD)).append("</row>");

        int total = 0, totalDone = 0;
        int[] cnt = new int[cats.size()], done = new int[cats.size()];
        for (int i = 0; i < cats.size(); i++) {
            for (Item it : cats.get(i).items) { cnt[i]++; if (it.done) done[i]++; }
            total += cnt[i]; totalDone += done[i];
        }
        int firstRow = 5, lastRow = 4 + cats.size(), sumRow = lastRow + 1;
        for (int i = 0; i < cats.size(); i++) {
            int r = firstRow + i;
            String q = sheetRef(cats.get(i).name);
            int last = Math.max(4, 3 + cats.get(i).items.size()) + 200;
            int c = cnt[i], d = done[i], p = c - d;
            sb.append("<row r=\"").append(r).append("\" ht=\"20.1\" customHeight=\"1\">")
              .append(str("B" + r, cats.get(i).summaryName, S_SUM_CELL))
              .append(fnum("C" + r, "COUNT(" + q + "!$A$4:$A$" + last + ")", c, S_SUM_NUM))
              .append(fnum("D" + r, "C" + r + "-E" + r, p, S_SUM_NUM))
              .append(fnum("E" + r, "COUNTIF(" + q + "!$N$4:$N$" + last + ",\"완료\")", d, S_SUM_NUM))
              .append(fnum("F" + r, "IFERROR(C" + r + "/$C" + r + ",0)", c == 0 ? 0 : 1, S_SUM_PCT))
              .append(fnum("G" + r, "IFERROR(D" + r + "/$C" + r + ",0)", c == 0 ? 0 : (double) p / c, S_SUM_PCT))
              .append(fnum("H" + r, "IFERROR(E" + r + "/$C" + r + ",0)", c == 0 ? 0 : (double) d / c, S_SUM_PCT))
              .append(fnum("I" + r, "IFERROR(C" + r + "/$C$" + sumRow + ",0)", total == 0 ? 0 : (double) c / total, S_SUM_PCT))
              .append(fnum("J" + r, "IFERROR(D" + r + "/$C$" + sumRow + ",0)", total == 0 ? 0 : (double) p / total, S_SUM_PCT))
              .append(fnum("K" + r, "IFERROR(E" + r + "/$C$" + sumRow + ",0)", total == 0 ? 0 : (double) d / total, S_SUM_PCT))
              .append(empty("L" + r, S_SUM_CELL)).append("</row>");
        }
        int r = sumRow;
        int tp = total - totalDone;
        sb.append("<row r=\"").append(r).append("\" ht=\"20.1\" customHeight=\"1\">")
          .append(str("B" + r, "계", S_SUM_HEAD))
          .append(fnum("C" + r, "SUM(C" + firstRow + ":C" + lastRow + ")", total, S_SUM_NUM))
          .append(fnum("D" + r, "SUM(D" + firstRow + ":D" + lastRow + ")", tp, S_SUM_NUM))
          .append(fnum("E" + r, "SUM(E" + firstRow + ":E" + lastRow + ")", totalDone, S_SUM_NUM))
          .append(empty("F" + r, S_SUM_CELL)).append(empty("G" + r, S_SUM_CELL)).append(empty("H" + r, S_SUM_CELL))
          .append(fnum("I" + r, "IFERROR(C" + r + "/C" + r + ",0)", total == 0 ? 0 : 1, S_SUM_PCT))
          .append(fnum("J" + r, "IFERROR(D" + r + "/C" + r + ",0)", total == 0 ? 0 : (double) tp / total, S_SUM_PCT))
          .append(fnum("K" + r, "IFERROR(E" + r + "/C" + r + ",0)", total == 0 ? 0 : (double) totalDone / total, S_SUM_PCT))
          .append(empty("L" + r, S_SUM_CELL)).append("</row>");
        sb.append("</sheetData><mergeCells count=\"6\"><mergeCell ref=\"B1:L1\"/><mergeCell ref=\"B3:B4\"/><mergeCell ref=\"C3:E3\"/>")
          .append("<mergeCell ref=\"F3:H3\"/><mergeCell ref=\"I3:K3\"/><mergeCell ref=\"L3:L4\"/></mergeCells>")
          .append("<pageMargins left=\"0.5\" right=\"0.5\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/>")
          .append("<pageSetup paperSize=\"9\" fitToWidth=\"1\" fitToHeight=\"0\" orientation=\"landscape\"/>")
          .append("</worksheet>");
        return sb.toString();
    }

    private String categorySheet(Category c, boolean hasDrawing) {
        StringBuilder sb = sheetStart("FF00B050", 70, 5, 3);
        sb.append("<cols>");
        for (int i = 0; i < COL_W.length; i++) {
            sb.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1).append("\" width=\"").append(COL_W[i]).append("\" customWidth=\"1\"");
            if (i == 8 && !hasCompany(c)) sb.append(" hidden=\"1\"");
            sb.append("/>");
        }
        sb.append("</cols><sheetData>");
        // 1행 제목
        sb.append("<row r=\"1\" ht=\"31.5\" customHeight=\"1\">").append(str("A1", c.title, S_TITLE));
        for (char col = 'B'; col <= 'L'; col++) sb.append(empty(col + "1", S_TITLE));
        sb.append("</row>");
        // 2행: 건물/일자 + 완료 집계
        int n = c.items.size(), d = 0;
        for (Item it : c.items) if (it.done) d++;
        int last = Math.max(4, 3 + n);
        sb.append("<row r=\"2\" ht=\"24.95\" customHeight=\"1\">")
          .append(str("A2", "건물명 : " + buildingName + "      점검일 : " + inspectDate, S_INFO))
          .append(fstr("N2", "COUNT(A4:A" + (last + 200) + ")&\"/\"&COUNTIF(N4:N" + (last + 200) + ",\"완료\")", n + "/" + d, S_INFO))
          .append("</row>");
        // 3행 머리글
        sb.append("<row r=\"3\" ht=\"48.75\" customHeight=\"1\">");
        for (int i = 0; i < HEADERS.length; i++) sb.append(str(colName(i) + "3", HEADERS[i], S_HEAD));
        sb.append(str("N3", "조치완료", S_HEAD)).append("</row>");
        // 데이터
        for (int i = 0; i < n; i++) {
            Item it = c.items.get(i);
            int r = 4 + i;
            sb.append("<row r=\"").append(r).append("\" ht=\"").append(ROW_H_PT).append("\" customHeight=\"1\">")
              .append(num("A" + r, it.no, S_NO))
              .append(str("B" + r, it.floor, S_CELL_C))
              .append(str("C" + r, it.location, S_CELL_C))
              .append(str("D" + r, it.equipment, S_CELL_C))
              .append(str("E" + r, it.issue, S_CELL_WRAP))
              .append(empty("F" + r, S_CELL_C)).append(empty("G" + r, S_CELL_C))
              .append(str("H" + r, it.action, S_CELL_WRAP))
              .append(str("I" + r, it.company, S_CELL_C))
              .append(str("J" + r, it.actionDate, S_CELL_C))
              .append(empty("K" + r, S_CELL_C)).append(empty("L" + r, S_CELL_C))
              .append(str("N" + r, it.done ? "완료" : "", S_DONE))
              .append("</row>");
        }
        sb.append("</sheetData>");
        sb.append("<autoFilter ref=\"A3:N").append(last).append("\"/>");
        sb.append("<mergeCells count=\"1\"><mergeCell ref=\"A1:L1\"/></mergeCells>");
        sb.append("<pageMargins left=\"0.25\" right=\"0.25\" top=\"0.5\" bottom=\"0.5\" header=\"0.3\" footer=\"0.3\"/>");
        sb.append("<pageSetup paperSize=\"9\" fitToWidth=\"1\" fitToHeight=\"0\" orientation=\"landscape\"/>");
        if (hasDrawing) sb.append("<drawing r:id=\"rId1\"/>");
        sb.append("</worksheet>");
        return sb.toString();
    }

    private static boolean hasCompany(Category c) {
        for (Item it : c.items) if (it.company != null && it.company.trim().length() > 0) return true;
        return false;
    }

    private static StringBuilder sheetStart(String tab, int zoom, int xSplit, int ySplit) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
          .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
          .append("<sheetPr><tabColor rgb=\"").append(tab).append("\"/>")
          .append("<pageSetUpPr fitToPage=\"1\"/></sheetPr>");
        sb.append("<sheetViews><sheetView workbookViewId=\"0\" zoomScale=\"").append(zoom).append("\" zoomScaleNormal=\"").append(zoom).append("\">");
        if (xSplit > 0 || ySplit > 0) {
            String tl = colName(xSplit) + (ySplit + 1);
            sb.append("<pane xSplit=\"").append(xSplit).append("\" ySplit=\"").append(ySplit).append("\" topLeftCell=\"").append(tl)
              .append("\" activePane=\"bottomRight\" state=\"frozen\"/><selection pane=\"bottomRight\" activeCell=\"").append(tl).append("\" sqref=\"").append(tl).append("\"/>");
        }
        sb.append("</sheetView></sheetViews><sheetFormatPr defaultRowHeight=\"16.5\"/>");
        return sb;
    }

    /** 셀 박스 안에 비율 유지하며 가운데 배치 */
    private static String picAnchor(int id, int col, int row, int imgW, int imgH) {
        double colPx = colWidthPx(COL_W[col]);
        double rowPx = ROW_H_PT * 96.0 / 72.0;
        double pad = 4;
        double boxW = colPx - pad * 2, boxH = rowPx - pad * 2;
        double scale = Math.min(boxW / imgW, boxH / imgH);
        long w = Math.round(imgW * scale), h = Math.round(imgH * scale);
        long offX = Math.round((colPx - w) / 2.0), offY = Math.round((rowPx - h) / 2.0);
        long cx = w * EMU_PER_PX, cy = h * EMU_PER_PX;
        return "<xdr:oneCellAnchor><xdr:from><xdr:col>" + col + "</xdr:col><xdr:colOff>" + (offX * EMU_PER_PX) + "</xdr:colOff><xdr:row>" + row +
                "</xdr:row><xdr:rowOff>" + (offY * EMU_PER_PX) + "</xdr:rowOff></xdr:from><xdr:ext cx=\"" + cx + "\" cy=\"" + cy + "\"/>" +
                "<xdr:pic><xdr:nvPicPr><xdr:cNvPr id=\"" + (id + 1) + "\" name=\"그림 " + id + "\"/><xdr:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></xdr:cNvPicPr></xdr:nvPicPr>" +
                "<xdr:blipFill><a:blip xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" r:embed=\"rId" + id + "\"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill>" +
                "<xdr:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"" + cx + "\" cy=\"" + cy + "\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic><xdr:clientData/></xdr:oneCellAnchor>";
    }

    private static double colWidthPx(double chars) {
        // 맑은 고딕 11 기본 글꼴의 최대 숫자폭 7px 기준
        return Math.floor((256 * chars + Math.floor(128.0 / 7)) / 256 * 7);
    }

    // ------------------------------------------------------------------ styles

    private static String styles() {
        String f = "맑은 고딕";
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
          .append("<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
          .append("<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"0.0%\"/></numFmts>")
          .append("<fonts count=\"6\">")
          .append("<font><sz val=\"11\"/><name val=\"").append(f).append("\"/><family val=\"3\"/><charset val=\"129\"/></font>")   // 0
          .append("<font><b/><sz val=\"20\"/><name val=\"").append(f).append("\"/><family val=\"3\"/><charset val=\"129\"/></font>") // 1 제목
          .append("<font><b/><sz val=\"10\"/><name val=\"").append(f).append("\"/><family val=\"3\"/><charset val=\"129\"/></font>") // 2 머리글
          .append("<font><sz val=\"10\"/><name val=\"").append(f).append("\"/><family val=\"3\"/><charset val=\"129\"/></font>")     // 3 본문
          .append("<font><b/><sz val=\"16\"/><name val=\"").append(f).append("\"/><family val=\"3\"/><charset val=\"129\"/></font>") // 4 집계 제목
          .append("<font><b/><sz val=\"10\"/><color rgb=\"FFC00000\"/><name val=\"").append(f).append("\"/><family val=\"3\"/><charset val=\"129\"/></font>") // 5 완료
          .append("</fonts>")
          .append("<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>")
          .append("<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFD9D9D9\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>")
          .append("<borders count=\"3\">")
          .append("<border><left/><right/><top/><bottom/><diagonal/></border>")
          .append("<border><left style=\"thin\"><color auto=\"1\"/></left><right style=\"thin\"><color auto=\"1\"/></right><top style=\"thin\"><color auto=\"1\"/></top><bottom style=\"thin\"><color auto=\"1\"/></bottom><diagonal/></border>")
          .append("<border><left style=\"hair\"><color auto=\"1\"/></left><right style=\"hair\"><color auto=\"1\"/></right><top style=\"hair\"><color auto=\"1\"/></top><bottom style=\"hair\"><color auto=\"1\"/></bottom><diagonal/></border>")
          .append("</borders>")
          .append("<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>")
          .append("<cellXfs count=\"14\">")
          .append("<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>") // 0
          .append("<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\"/></xf>") // 1 제목
          .append("<xf numFmtId=\"0\" fontId=\"2\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\"/></xf>") // 2 머리글
          .append("<xf numFmtId=\"0\" fontId=\"3\" fillId=\"0\" borderId=\"2\" xfId=\"0\" applyFont=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\"/></xf>") // 3 본문 가운데
          .append("<xf numFmtId=\"0\" fontId=\"3\" fillId=\"0\" borderId=\"2\" xfId=\"0\" applyFont=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\"/></xf>") // 4 본문 줄바꿈
          .append("<xf numFmtId=\"0\" fontId=\"4\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\"/></xf>") // 5 집계 제목
          .append("<xf numFmtId=\"0\" fontId=\"2\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\"/></xf>") // 6 집계 머리글
          .append("<xf numFmtId=\"0\" fontId=\"3\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\"/></xf>") // 7 집계 셀
          .append("<xf numFmtId=\"0\" fontId=\"3\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\"/></xf>") // 8 집계 숫자
          .append("<xf numFmtId=\"164\" fontId=\"3\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\"/></xf>") // 9 집계 %
          .append("<xf numFmtId=\"0\" fontId=\"3\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyAlignment=\"1\"><alignment horizontal=\"right\" vertical=\"center\"/></xf>") // 10 날짜 우측
          .append("<xf numFmtId=\"0\" fontId=\"3\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyAlignment=\"1\"><alignment horizontal=\"left\" vertical=\"center\"/></xf>") // 11 정보
          .append("<xf numFmtId=\"0\" fontId=\"5\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\"/></xf>") // 12 완료
          .append("<xf numFmtId=\"0\" fontId=\"3\" fillId=\"0\" borderId=\"2\" xfId=\"0\" applyFont=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\"/></xf>") // 13 NO
          .append("</cellXfs>")
          .append("<cellStyles count=\"1\"><cellStyle name=\"표준\" xfId=\"0\" builtinId=\"0\"/></cellStyles>")
          .append("</styleSheet>");
        return sb.toString();
    }

    // ------------------------------------------------------------------ helpers

    private static String colName(int idx) {
        StringBuilder s = new StringBuilder();
        idx++;
        while (idx > 0) { int m = (idx - 1) % 26; s.insert(0, (char) ('A' + m)); idx = (idx - 1) / 26; }
        return s.toString();
    }

    static String sheetRef(String name) { return "'" + name.replace("'", "''") + "'"; }

    private static String str(String ref, String v, int s) {
        if (v == null || v.length() == 0) return empty(ref, s);
        return "<c r=\"" + ref + "\" s=\"" + s + "\" t=\"inlineStr\"><is><t xml:space=\"preserve\">" + esc(v) + "</t></is></c>";
    }
    private static String empty(String ref, int s) { return "<c r=\"" + ref + "\" s=\"" + s + "\"/>"; }
    private static String num(String ref, double v, int s) { return "<c r=\"" + ref + "\" s=\"" + s + "\"><v>" + fmt(v) + "</v></c>"; }
    private static String fnum(String ref, String formula, double cached, int s) {
        return "<c r=\"" + ref + "\" s=\"" + s + "\"><f>" + esc(formula) + "</f><v>" + fmt(cached) + "</v></c>";
    }
    private static String fstr(String ref, String formula, String cached, int s) {
        return "<c r=\"" + ref + "\" s=\"" + s + "\" t=\"str\"><f>" + esc(formula) + "</f><v>" + esc(cached) + "</v></c>";
    }
    private static String fmt(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
        return Double.toString(v);
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                case '\r': break;
                default:
                    if (c < 0x20 && c != '\n' && c != '\t') break; // XML 금지 제어문자 제거
                    b.append(c);
            }
        }
        return b.toString();
    }

    private static void putText(ZipOutputStream zip, String name, String text) throws IOException {
        putBytes(zip, name, text.getBytes(UTF8), false);
    }

    private static void putBytes(ZipOutputStream zip, String name, byte[] data, boolean stored) throws IOException {
        ZipEntry e = new ZipEntry(name);
        if (stored) {
            CRC32 crc = new CRC32();
            crc.update(data);
            e.setMethod(ZipEntry.STORED);
            e.setSize(data.length);
            e.setCompressedSize(data.length);
            e.setCrc(crc.getValue());
        }
        zip.putNextEntry(e);
        zip.write(data);
        zip.closeEntry();
    }
}
