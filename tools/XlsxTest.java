import com.punchlist.app.XlsxWriter;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;

/** JVM 에서 XlsxWriter 출력 검증용: java tools/XlsxTest.java out.xlsx */
public class XlsxTest {
    public static void main(String[] a) throws Exception {
        String[][] defs = {{"건축","PUNCH LIST  (건축부문)","건축"},{"기계","PUNCH LIST  (기계부문)","기계"},{"전기","PUNCH LIST  (전기부문)","전기"},
                {"소방","PUNCH LIST  (소방부문)","소방"},{"승강기","PUNCH LIST  (승강기부문)","승강기"},{"안전(공통)","PUNCH LIST  (안전부문)","안전"}};
        java.util.List<XlsxWriter.Category> cats = new ArrayList<>();
        for (String[] d : defs) cats.add(new XlsxWriter.Category(d[0], d[1], d[2]));
        String[] fl = {"지하1층","9층","옥탑1층","1층"};
        for (int i = 0; i < 5; i++) {
            XlsxWriter.Item it = new XlsxWriter.Item();
            it.no = i + 1; it.floor = fl[i % 4]; it.location = "계단실 " + i; it.equipment = "벽";
            it.issue = "얼굴인식 자리 구멍 & <테스트>\n두번째 줄"; it.done = i == 2; it.company = i == 1 ? "영강" : "";
            for (int p = 0; p < (i % 3); p++) it.photoIds.add((i % 2 == 0 ? "L" : "P") + i + "_" + p);
            cats.get(0).items.add(it);
        }
        XlsxWriter.Item m = new XlsxWriter.Item(); m.no = 1; m.floor = "6호기"; m.location = "PIT"; m.issue = "누수"; m.photoIds.add("L9");
        cats.get(4).items.add(m);
        // 사진 4장 중 3번째, 1번째를 엑셀 사진으로 선택
        XlsxWriter.Item sel = new XlsxWriter.Item(); sel.no = 1; sel.floor = "2층"; sel.location = "복도"; sel.issue = "선택 테스트";
        for (int p = 0; p < 4; p++) sel.photoIds.add("Lsel_" + p);
        sel.excelPhotoIds.add("Lsel_2"); sel.excelPhotoIds.add("Lsel_0");
        cats.get(1).items.add(sel);
        final java.util.List<String> loaded = new ArrayList<>();
        XlsxWriter w = new XlsxWriter("테스트빌딩 A동", "2026-11-10", "홍길동", cats);
        try (OutputStream o = new FileOutputStream(a[0])) {
            w.write(o, id -> {
                loaded.add(id);
                boolean land = id.startsWith("L");
                int W = land ? 960 : 720, H = land ? 720 : 960;
                BufferedImage bi = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = bi.createGraphics();
                g.setColor(new Color(60, 120, 200)); g.fillRect(0, 0, W, H);
                g.setColor(Color.ORANGE); g.fillOval(W / 4, H / 4, W / 2, H / 2);
                g.setColor(Color.WHITE); g.setFont(new Font("SansSerif", Font.BOLD, 80)); g.drawString(id, 40, 120);
                ByteArrayOutputStream bo = new ByteArrayOutputStream(); ImageIO.write(bi, "jpg", bo);
                return new XlsxWriter.Img(bo.toByteArray(), W, H);
            }, (d, t) -> {});
        }
        System.out.println("loaded: " + loaded);
        System.out.println("ok");
    }
}
