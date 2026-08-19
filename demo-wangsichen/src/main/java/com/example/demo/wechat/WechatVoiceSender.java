package com.example.demo.wechat;

import com.github.wechat.ilink.sdk.core.login.LoginContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 微信 iLink 原生语音发送。
 *
 * <p>SDK 自带的 {@code sendVoice} 在当前 CDN 协议下字段不兼容，会导致微信客户端静默丢弃语音气泡。
 * 这里直接按 iLink HTTP 协议发送，并在发送前通过“下载回读 + AES 解密”校验下载凭证，避免客户端
 * 因拿不到音频而吞掉气泡。
 */
@Component
public class WechatVoiceSender {

    private static final Logger log = LoggerFactory.getLogger(WechatVoiceSender.class);

    private static final String CDN_BASE = "https://novac2c.cdn.weixin.qq.com/c2c";
    private static final int VOICE_MEDIA_TYPE = 4;
    private static final int VOICE_ENCODE_TYPE = 4;
    private static final int VOICE_SAMPLE_RATE = 16000;
    private static final int VOICE_BITS_PER_SAMPLE = 16;
    private static final String CHANNEL_VERSION = "1.0.0";

    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    public void send(
            LoginContext login,
            String userId,
            String contextToken,
            byte[] silkBytes,
            int durationMs)
            throws Exception {

        String aesKeyHex = randomHex(16);
        byte[] aesKey = hexToBytes(aesKeyHex);
        String filekey = randomHex(16);

        int rawSize = silkBytes.length;
        String rawMd5 = md5Hex(silkBytes);
        byte[] ciphertext = aesEcbPkcs7Encrypt(silkBytes, aesKey);

        String uploadParam =
                getUploadParam(
                        login, userId, filekey, aesKeyHex, rawSize, rawMd5, ciphertext.length);

        Map<String, String> cdnHeaders = uploadToCdn(uploadParam, filekey, ciphertext);
        String downloadKey = pickDownloadToken(uploadParam, cdnHeaders, silkBytes, aesKey);

        sendVoiceMessage(
                login,
                userId,
                contextToken,
                downloadKey,
                aesKeyHex,
                durationMs);
    }

    private String getUploadParam(
            LoginContext login,
            String userId,
            String filekey,
            String aesKeyHex,
            int rawSize,
            String rawMd5,
            int fileSize)
            throws Exception {

        ObjectNode req = mapper.createObjectNode();
        req.put("filekey", filekey);
        req.put("media_type", VOICE_MEDIA_TYPE);
        req.put("to_user_id", userId);
        req.put("rawsize", rawSize);
        req.put("rawfilemd5", rawMd5);
        req.put("filesize", fileSize);
        req.put("no_need_thumb", true);
        req.put("aeskey", aesKeyHex);
        req.putObject("base_info").put("channel_version", CHANNEL_VERSION);

        String body = postJson(login, "/ilink/bot/getuploadurl", mapper.writeValueAsBytes(req));
        JsonNode json = mapper.readTree(body);

        String uploadParam = json.path("upload_param").asText(null);
        if (uploadParam == null || uploadParam.isBlank()) {
            throw new IllegalStateException("getuploadurl 未返回 upload_param: " + body);
        }

        int ret = json.path("ret").asInt(0);
        int errcode = json.path("errcode").asInt(0);
        if (ret != 0 || errcode != 0) {
            log.warn("getuploadurl 返回 ret={}, errcode={}，但仍拿到 upload_param，继续上传", ret, errcode);
        }
        return uploadParam;
    }

    private Map<String, String> uploadToCdn(
            String uploadParam, String filekey, byte[] ciphertext) throws Exception {
        String url =
                CDN_BASE
                        + "/upload?encrypted_query_param="
                        + URLEncoder.encode(uploadParam, StandardCharsets.UTF_8)
                        + "&filekey="
                        + URLEncoder.encode(filekey, StandardCharsets.UTF_8);

        HttpRequest req =
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/octet-stream")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(ciphertext))
                        .build();

        HttpResponse<String> resp =
                httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("CDN 上传失败 HTTP " + resp.statusCode() + ": " + resp.body());
        }

        Map<String, String> headers = new LinkedHashMap<>();
        resp.headers().map().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name.toLowerCase(Locale.ROOT), values.get(0));
            }
        });

        log.info("语音文件已上传 CDN，密文 {} 字节", ciphertext.length);
        return headers;
    }

    /**
     * 从候选凭证里挑出真正能下载并解密的那个。微信语音的下载凭证与图片/视频不同，
     * 单纯用 x-encrypted-param 可能无效，因此这里逐个回读校验。
     */
    private String pickDownloadToken(
            String uploadParam, Map<String, String> headers, byte[] plaintext, byte[] aesKey) {
        Set<String> candidates = new LinkedHashSet<>();
        if (uploadParam != null && !uploadParam.isBlank()) {
            candidates.add(uploadParam);
        }
        String shortParam = headers.get("x-encrypted-param");
        String queryParam = headers.get("x-encrypted-query-param");
        if (shortParam != null && !shortParam.isBlank()) {
            candidates.add(shortParam);
        }
        if (queryParam != null && !queryParam.isBlank()) {
            candidates.add(queryParam);
        }

        log.info(
                "语音下载凭证候选：uploadParam={}, x-encrypted-param={}, x-encrypted-query-param={}",
                len(uploadParam),
                len(shortParam),
                len(queryParam));

        for (String token : candidates) {
            try {
                byte[] downloaded = downloadCiphertext(token);
                byte[] decrypted = aesEcbPkcs7Decrypt(downloaded, aesKey);
                if (Arrays.equals(decrypted, plaintext)) {
                    log.info("语音下载凭证校验通过，采用 {} 字符凭证", token.length());
                    return token;
                }
                log.warn("语音下载凭证（{} 字符）解密后内容不一致", token.length());
            } catch (Exception e) {
                log.warn("语音下载凭证（{} 字符）校验失败：{}", token.length(), e.getMessage());
            }
        }

        log.warn("所有下载凭证校验均失败，回退到 uploadParam");
        return uploadParam;
    }

    private int len(String s) {
        return s == null ? 0 : s.length();
    }

    private byte[] downloadCiphertext(String token) throws Exception {
        String url =
                CDN_BASE
                        + "/download?encrypted_query_param="
                        + URLEncoder.encode(token, StandardCharsets.UTF_8);
        HttpRequest req =
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(20))
                        .GET()
                        .build();
        HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + resp.statusCode());
        }
        return resp.body();
    }

    private void sendVoiceMessage(
            LoginContext login,
            String userId,
            String contextToken,
            String downloadKey,
            String aesKeyHex,
            int durationMs)
            throws Exception {

        ObjectNode voiceItem = mapper.createObjectNode();
        voiceItem.put("type", 3);
        ObjectNode vi = voiceItem.putObject("voice_item");
        vi.put("encode_type", VOICE_ENCODE_TYPE);
        vi.put("bits_per_sample", VOICE_BITS_PER_SAMPLE);
        vi.put("sample_rate", VOICE_SAMPLE_RATE);
        vi.put("playtime", durationMs);
        ObjectNode media = vi.putObject("media");
        media.put("encrypt_query_param", downloadKey);
        media.put(
                "aes_key",
                Base64.getEncoder().encodeToString(aesKeyHex.getBytes(StandardCharsets.UTF_8)));
        media.put("encrypt_type", 1);

        ObjectNode msg = mapper.createObjectNode();
        msg.put("from_user_id", "");
        msg.put("to_user_id", userId);
        msg.put("client_id", "ilink-sdk:" + System.currentTimeMillis() + "-" + randomHex(4));
        msg.put("message_type", 2);
        msg.put("message_state", 2);
        msg.put("context_token", contextToken);
        ArrayNode items = msg.putArray("item_list");
        items.add(voiceItem);

        ObjectNode req = mapper.createObjectNode();
        req.set("msg", msg);
        req.putObject("base_info").put("channel_version", CHANNEL_VERSION);

        String body = mapper.writeValueAsString(req);
        log.info("语音 sendmessage 请求：{}", body);

        String respBody = postJson(login, "/ilink/bot/sendmessage", mapper.writeValueAsBytes(req));
        JsonNode json = mapper.readTree(respBody);
        log.info("语音 sendmessage 响应：{}", respBody);
        checkRet(json, "/ilink/bot/sendmessage");
    }

    private String postJson(LoginContext login, String path, byte[] bodyBytes) throws Exception {
        HttpRequest req =
                HttpRequest.newBuilder()
                        .uri(URI.create(normalizeBase(login.getBaseUrl()) + path))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .header("AuthorizationType", "ilink_bot_token")
                        .header("Authorization", "Bearer " + login.getBotToken())
                        .header("X-WECHAT-UIN", randomWechatUin())
                        .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                        .build();

        HttpResponse<String> resp =
                httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IllegalStateException(path + " 返回 HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }

    private void checkRet(JsonNode json, String path) {
        int ret = json.path("ret").asInt(0);
        int errcode = json.path("errcode").asInt(0);
        if (ret != 0 || errcode != 0) {
            throw new IllegalStateException(
                    path
                            + " 返回 ret="
                            + ret
                            + ", errcode="
                            + errcode
                            + ", errmsg="
                            + json.path("errmsg").asText()
                            + ": "
                            + json);
        }
    }

    private String normalizeBase(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "https://ilinkai.weixin.qq.com";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private String randomWechatUin() {
        long v = random.nextInt() & 0xffffffffL;
        return Base64.getEncoder().encodeToString(String.valueOf(v).getBytes(StandardCharsets.UTF_8));
    }

    private String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        random.nextBytes(buf);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte b : buf) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private String md5Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(data);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private byte[] aesEcbPkcs7Encrypt(byte[] plain, byte[] key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        return cipher.doFinal(plain);
    }

    private byte[] aesEcbPkcs7Decrypt(byte[] ciphertext, byte[] key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
        return cipher.doFinal(ciphertext);
    }
}
