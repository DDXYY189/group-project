package com.example.demo.wechat;

import io.nayuki.qrcodegen.QrCode;

/**
 * 将扫码登录内容渲染为终端可扫的 ASCII 二维码。
 */
public final class QrCodeUtil {

    private QrCodeUtil() {
    }

    public static String toAscii(String content) {
        QrCode qr = QrCode.encodeText(content, QrCode.Ecc.LOW);
        StringBuilder sb = new StringBuilder();
        // 用 Unicode 半高方块字符，每行表示两行模块，提升终端可读性
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
}
