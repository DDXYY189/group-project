package com.example.demo.wechat;

import io.nayuki.qrcodegen.QrCode;

/**
 * 将扫码登录内容渲染为终端可扫的 ASCII 二维码，或浏览器可显示的 SVG 二维码。
 */
public final class QrCodeUtil {

    private QrCodeUtil() {
    }

    public static String toAscii(String content) {
        QrCode qr = QrCode.encodeText(content, QrCode.Ecc.LOW);
        StringBuilder sb = new StringBuilder();
        for (int y = 0; y < qr.size; y += 2) {
            for (int x = 0; x < qr.size; x++) {
                boolean top = qr.getModule(x, y);
                boolean bottom = y + 1 < qr.size && qr.getModule(x, y + 1);
                if (top && bottom) {
                    sb.append('█');
                } else if (top) {
                    sb.append('▀');
                } else if (bottom) {
                    sb.append('▄');
                } else {
                    sb.append(' ');
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * 将内容渲染为 SVG 格式二维码，可直接嵌入 HTML 页面。
     *
     * @param content 二维码内容
     * @param scale   每个模块的像素大小（推荐 10）
     * @return SVG 字符串
     */
    public static String toSvg(String content, int scale) {
        QrCode qr = QrCode.encodeText(content, QrCode.Ecc.LOW);
        int dim = qr.size * scale;
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\"")
                .append(" width=\"").append(dim).append("\"")
                .append(" height=\"").append(dim).append("\"")
                .append(" viewBox=\"0 0 ").append(dim).append(' ').append(dim).append("\">");
        sb.append("<rect width=\"").append(dim).append("\" height=\"").append(dim)
                .append("\" fill=\"#ffffff\"/>");
        for (int y = 0; y < qr.size; y++) {
            for (int x = 0; x < qr.size; x++) {
                if (qr.getModule(x, y)) {
                    sb.append("<rect x=\"").append(x * scale)
                            .append("\" y=\"").append(y * scale)
                            .append("\" width=\"").append(scale)
                            .append("\" height=\"").append(scale)
                            .append("\" fill=\"#000000\"/>");
                }
            }
        }
        sb.append("</svg>");
        return sb.toString();
    }
}
