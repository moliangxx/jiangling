// 使用已安装的 Noto Sans SC 字体生成适用于 SPIFFS 的字库子集。
// 参数依次为字体源文件和输出的 VLW 字库路径。示例：
//   java scripts\GenerateChineseFont.java "C:\Windows\Fonts\Noto Sans SC (TrueType).otf" data\JiangLingCJK16.vlw
// 这是在电脑上运行的 Java 工具，不是烧录到 ESP32 的固件代码。
// Java 和 C++ 都需要声明类型，但 Java 的包/类语法不同，不能当作 C++ 编译。

import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.Color;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.font.LineMetrics;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

// Java 的 public 类名必须与文件名 GenerateChineseFont.java 一致。
public class GenerateChineseFont {
  // 字号和字体名称会写入输出文件，并供屏幕端加载字库时使用。
  private static final int FONT_SIZE = 16;
  private static final String FONT_NAME = "JiangLing UI CJK";
  private static final String POSTSCRIPT_NAME = "JiangLingUICJK";

  // static 表示属于类本身；final 表示字段初始化后不能再赋成其他值。
  private static final class Glyph {
    // 单个字形的 Unicode 编码、显示尺寸/偏移，以及灰度位图。
    final int codePoint;
    final int width;
    final int height;
    final int advance;
    final int deltaY;
    final int deltaX;
    final byte[] bitmap;

    // 构造函数名与类名相同且没有返回类型；new Glyph(...) 时会调用它。
    Glyph(int codePoint, int width, int height, int advance,
        int deltaY, int deltaX, byte[] bitmap) {
      this.codePoint = codePoint;
      this.width = width;
      this.height = height;
      this.advance = advance;
      this.deltaY = deltaY;
      this.deltaX = deltaX;
      this.bitmap = bitmap;
    }
  }

  public static void main(String[] args) throws IOException, FontFormatException {
    // main 是 Java 程序入口；args 保存命令行传入的字体文件和输出路径。
    if (args.length != 2) {
      throw new IllegalArgumentException(
          "Usage: java scripts\\GenerateChineseFont.java <NotoSansSC.otf> <output.vlw>");
    }

    // new 创建 File 对象；deriveFont() 基于源字体生成指定字号的新字体。
    Font sourceFont = Font.createFont(Font.TRUETYPE_FONT, new File(args[0]))
        .deriveFont((float) FONT_SIZE);
    FontRenderContext context = new FontRenderContext(null, true, true);
    LineMetrics lineMetrics = sourceFont.getLineMetrics("Ag", context);
    int ascent = Math.round(lineMetrics.getAscent());
    int descent = Math.round(lineMetrics.getDescent());

    List<Glyph> glyphs = new ArrayList<>();
    CharsetEncoder gb2312 = Charset.forName("GB2312").newEncoder();

    // 只收录 ASCII 和 GB2312 可编码字符，控制字库体积。
    // 逐一检查 Unicode 编码；UTF-16 代理区不是独立字符，因此跳过。
    for (int codePoint = 0x20; codePoint <= 0xFFFF; codePoint++) {
      if (codePoint >= 0xD800 && codePoint <= 0xDFFF) continue;

      String character = new String(Character.toChars(codePoint));
      boolean included = codePoint <= 0x7E
          || (codePoint >= 0x80 && gb2312.canEncode(character));
      // 只收录选定字符集内且源字体确实支持的字符。
      if (!included || !sourceFont.canDisplay(codePoint)) continue;

      GlyphVector vector = sourceFont.createGlyphVector(context, character);
      Rectangle bounds = vector.getGlyphPixelBounds(0, context, 0, 0);
      int width = Math.max(0, bounds.width);
      int height = Math.max(0, bounds.height);
      int advance = Math.round(vector.getGlyphMetrics(0).getAdvanceX());
      int deltaX = (int) bounds.x;
      int deltaY = -(int) bounds.y;

      // VLW 格式的字形字段容量有限；超出时明确报错，避免生成损坏文件。
      if (width > 255 || height > 255 || advance > 255
          || deltaX < -128 || deltaX > 127
          || deltaY < Short.MIN_VALUE || deltaY > Short.MAX_VALUE) {
        throw new IllegalStateException(
            "Glyph metrics exceed the TFT_eSPI VLW limits: U+"
                + Integer.toHexString(codePoint));
      }

      // Java AWT 将字形栅格化为灰度图，后续按 VLW 格式写出。
      byte[] bitmap = new byte[width * height];
      if (width > 0 && height > 0) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(
            RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(
            RenderingHints.KEY_FRACTIONALMETRICS,
            RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        graphics.setColor(Color.WHITE);
        graphics.drawGlyphVector(vector, -bounds.x, -bounds.y);
        graphics.dispose();

        for (int y = 0; y < height; y++) {
          for (int x = 0; x < width; x++) {
            bitmap[y * width + x] = (byte) image.getRaster().getSample(x, y, 0);
          }
        }
      }

      glyphs.add(new Glyph(
          codePoint, width, height, advance, deltaY, deltaX, bitmap));
    }

    // 确保输出路径的上级目录存在，然后创建/覆盖 VLW 输出文件。
    File outputFile = new File(args[1]);
    File parent = outputFile.getAbsoluteFile().getParentFile();
    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
      throw new IOException("Cannot create output directory: " + parent);
    }

    // VLW 文件先写字体/字形元数据，再连续写入所有字形位图。
    try (DataOutputStream output = new DataOutputStream(
        new BufferedOutputStream(new FileOutputStream(outputFile)))) {
      output.writeInt(glyphs.size());
      output.writeInt(11);
      output.writeInt(FONT_SIZE);
      output.writeInt(0);
      output.writeInt(ascent);
      output.writeInt(descent);

      for (Glyph glyph : glyphs) {
        output.writeInt(glyph.codePoint);
        output.writeInt(glyph.height);
        output.writeInt(glyph.width);
        output.writeInt(glyph.advance);
        output.writeInt(glyph.deltaY);
        output.writeInt(glyph.deltaX);
        output.writeInt(0);
      }

      for (Glyph glyph : glyphs) {
        output.write(glyph.bitmap);
      }

      writeName(output, FONT_NAME);
      writeName(output, POSTSCRIPT_NAME);
      output.writeByte(1);
    }

    System.out.printf(
        "Generated %d glyphs at %d px: %s (%d bytes)%n",
        glyphs.size(), FONT_SIZE, outputFile, outputFile.length());
  }

  // VLW 名称字段按照“长度 + UTF-8 字节 + 结束标记”的格式写入。
  private static void writeName(DataOutputStream output, String name) throws IOException {
    byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > 255) {
      throw new IllegalArgumentException("Font name is too long: " + name);
    }
    output.writeByte(bytes.length);
    output.write(bytes);
    output.writeByte(0);
  }
}
