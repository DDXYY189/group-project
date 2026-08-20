package com.example.group_demo.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多轮对话上下文记忆服务
 *
 * <p>采用滑动窗口机制保存多轮对话历史，不做摘要压缩。
 * 按 sessionId 区分不同用户会话，每个 session 独立一套对话历史，内存存储（ConcurrentHashMap），不需要数据库。
 *
 * <h3>核心机制</h3>
 * <ul>
 *   <li>滑动窗口：最多保存最近 {@value #MAX_ROUNDS} 轮对话（1轮 = 1条user消息 + 1条assistant消息），
 *       超过时丢弃最早的对话记录，只保留最新的会话。</li>
 *   <li>会话隔离：不同 sessionId 拥有独立的对话历史，互不干扰。</li>
 *   <li>闲置清理：会话超过 {@value #MAX_IDLE_MILLIS} 毫秒未活跃时，自动清理释放内存。</li>
 * </ul>
 *
 * <h3>消息结构</h3>
 * <p>每条消息只保存 role（user / assistant）和 content 内容。
 * 系统消息（system）不存入历史列表，由 FunctionCallService 每次调用时单独添加。
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * // 1. 确保会话存在（sessionId为空时自动新建）
 * String sessionId = contextService.ensureSession(sessionId);
 * // 2. 获取历史消息（已按滑动窗口过滤）
 * List<ChatMessage> history = contextService.getHistory(sessionId);
 * // 3. 调用大模型，获取回复
 * // 4. 本轮结束后，追加user和assistant消息
 * contextService.addMessage(sessionId, "user", userMessage);
 * contextService.addMessage(sessionId, "assistant", reply);
 * }</pre>
 */
@Service
public class ConversationContextService {

    private static final Logger log = LoggerFactory.getLogger(ConversationContextService.class);

    /** 滑动窗口最大保存轮数（1轮 = 1条user + 1条assistant = 2条消息） */
    private static final int MAX_ROUNDS = 8;

    /** 最大保存消息条数 = 最大轮数 × 2 */
    private static final int MAX_MESSAGES = MAX_ROUNDS * 2;

    /** 会话最大闲置时间（毫秒），默认30分钟，超过后自动清理 */
    private static final long MAX_IDLE_MILLIS = 30 * 60 * 1000;

    /** 闲置清理的最小间隔（毫秒），避免每次调用都执行清理 */
    private static final long CLEANUP_INTERVAL_MILLIS = 5 * 60 * 1000;

    /**
     * 单条对话消息结构
     * <p>只保存 role（user / assistant）和 content，不保存工具调用等中间过程消息。
     */
    public static class ChatMessage {
        /** 消息角色：user 或 assistant */
        private final String role;
        /** 消息内容 */
        private final String content;

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public String getRole() {
            return role;
        }

        public String getContent() {
            return content;
        }

        @Override
        public String toString() {
            String preview = content == null ? "" :
                    (content.length() > 60 ? content.substring(0, 60) + "..." : content);
            return "ChatMessage{role='" + role + "', content='" + preview + "'}";
        }
    }

    /**
     * 会话上下文：每个 sessionId 对应一个独立的上下文
     * <p>包含消息列表和最后活跃时间戳，消息列表使用 synchronized 保护并发读写。
     */
    private static class SessionContext {
        /** 对话消息列表（按时间顺序，最早的在头部） */
        final List<ChatMessage> messages = new ArrayList<>();
        /** 最后活跃时间（毫秒时间戳） */
        volatile long lastActiveTime;

        SessionContext() {
            this.lastActiveTime = System.currentTimeMillis();
        }
    }

    /** 会话存储：sessionId → 会话上下文，线程安全 */
    private final Map<String, SessionContext> sessionMap = new ConcurrentHashMap<>();

    /** 上次执行闲置清理的时间戳，用于控制清理频率 */
    private volatile long lastCleanupTime = System.currentTimeMillis();

    // ======================== 会话管理 ========================

    /**
     * 获取或创建会话
     * <p>边界处理①：sessionId 为空时，自动新建会话并返回新的 sessionId。
     *
     * @param sessionId 会话ID，可为 null 或空字符串
     * @return 有效的 sessionId（若传入为空则返回新生成的 UUID）
     */
    public String ensureSession(String sessionId) {
        // 边界处理①：sessionId为空，新建会话
        if (sessionId == null || sessionId.trim().isEmpty()) {
            sessionId = UUID.randomUUID().toString().replace("-", "");
            log.info("sessionId为空, 新建会话: sessionId={}", sessionId);
        }

        // 使用 computeIfAbsent 保证只创建一次
        String finalSessionId = sessionId;
        sessionMap.computeIfAbsent(finalSessionId, k -> {
            log.info("创建新会话上下文: sessionId={}", k);
            return new SessionContext();
        });

        // 顺便执行一次节流的闲置清理
        lazyCleanIdleSessions();

        return sessionId;
    }

    /**
     * 获取滑动窗口过滤后的历史消息列表
     * <p>返回当前 session 的历史消息副本（不含系统消息），调用方可安全修改。
     * 同时更新会话的最后活跃时间。
     *
     * @param sessionId 会话ID
     * @return 历史消息列表（只读副本），会话不存在时返回空列表
     */
    public List<ChatMessage> getHistory(String sessionId) {
        SessionContext ctx = sessionMap.get(sessionId);
        if (ctx == null) {
            return new ArrayList<>();
        }
        // 更新最后活跃时间
        ctx.lastActiveTime = System.currentTimeMillis();
        // 返回副本，避免外部修改内部列表
        synchronized (ctx.messages) {
            return new ArrayList<>(ctx.messages);
        }
    }

    /**
     * 向会话追加一条消息（user 或 assistant）
     * <p>追加后自动执行滑动窗口逻辑：消息总数超过最大窗口数量时，删除最旧的条目。
     *
     * <p>边界处理③：滑动窗口 —— 对话总数超过 {@value #MAX_MESSAGES} 条时，
     * 从列表头部（最旧）开始删除，直到不超过上限。
     *
     * @param sessionId 会话ID
     * @param role      消息角色（user / assistant）
     * @param content   消息内容
     */
    public void addMessage(String sessionId, String role, String content) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            log.warn("addMessage: sessionId为空, 忽略此消息");
            return;
        }
        if (role == null || role.trim().isEmpty()) {
            log.warn("addMessage: role为空, 忽略此消息");
            return;
        }

        SessionContext ctx = sessionMap.computeIfAbsent(sessionId, k -> new SessionContext());

        synchronized (ctx.messages) {
            ctx.messages.add(new ChatMessage(role, content));

            // 滑动窗口逻辑：消息总数超过最大窗口数量，删除最旧的条目
            while (ctx.messages.size() > MAX_MESSAGES) {
                ChatMessage removed = ctx.messages.remove(0);
                log.debug("滑动窗口淘汰旧消息: sessionId={}, removed={}", sessionId, removed);
            }
        }

        // 更新最后活跃时间
        ctx.lastActiveTime = System.currentTimeMillis();
    }

    // ======================== 闲置清理 ========================

    /**
     * 清理闲置过久的会话
     * <p>边界处理②：会话太久闲置时，删除该会话释放内存。
     * 遍历所有会话，最后活跃时间超过 {@value #MAX_IDLE_MILLIS} 毫秒的会话将被移除。
     *
     * @return 本次清理的会话数量
     */
    public int cleanIdleSessions() {
        long now = System.currentTimeMillis();
        int cleaned = 0;

        Iterator<Map.Entry<String, SessionContext>> it = sessionMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, SessionContext> entry = it.next();
            SessionContext ctx = entry.getValue();
            long idleDuration = now - ctx.lastActiveTime;
            if (idleDuration > MAX_IDLE_MILLIS) {
                it.remove();
                cleaned++;
                log.info("清理闲置会话: sessionId={}, 闲置时长={}ms", entry.getKey(), idleDuration);
            }
        }

        if (cleaned > 0) {
            log.info("共清理 {} 个闲置会话, 剩余活跃会话: {}", cleaned, sessionMap.size());
        }

        lastCleanupTime = now;
        return cleaned;
    }

    /**
     * 节流的闲置清理：距离上次清理超过 {@value #CLEANUP_INTERVAL_MILLIS} 毫秒才执行
     * <p>在 ensureSession 等高频方法中调用，避免每次都扫描全部会话。
     */
    private void lazyCleanIdleSessions() {
        long now = System.currentTimeMillis();
        if (now - lastCleanupTime > CLEANUP_INTERVAL_MILLIS) {
            cleanIdleSessions();
        }
    }

    // ======================== 辅助方法（调试/测试用） ========================

    /**
     * 获取当前会话的消息数量（主要用于调试和测试）
     *
     * @param sessionId 会话ID
     * @return 消息条数，会话不存在时返回0
     */
    public int getMessageCount(String sessionId) {
        SessionContext ctx = sessionMap.get(sessionId);
        if (ctx == null) {
            return 0;
        }
        synchronized (ctx.messages) {
            return ctx.messages.size();
        }
    }

    /**
     * 清除指定会话的全部历史记录
     *
     * @param sessionId 会话ID
     */
    public void clearSession(String sessionId) {
        SessionContext ctx = sessionMap.remove(sessionId);
        if (ctx != null) {
            log.info("清除会话历史: sessionId={}, 消息数={}", sessionId, ctx.messages.size());
        }
    }

    /**
     * 获取当前活跃会话总数（主要用于调试和测试）
     *
     * @return 活跃会话数量
     */
    public int getActiveSessionCount() {
        return sessionMap.size();
    }

    /**
     * 打印指定会话的全部历史消息（调试用）
     *
     * @param sessionId 会话ID
     * @return 历史消息的格式化字符串
     */
    public String dumpHistory(String sessionId) {
        SessionContext ctx = sessionMap.get(sessionId);
        if (ctx == null) {
            return "会话不存在: " + sessionId;
        }
        synchronized (ctx.messages) {
            StringBuilder sb = new StringBuilder();
            sb.append("会话 ").append(sessionId).append(" 历史(").append(ctx.messages.size()).append("条):\n");
            for (int i = 0; i < ctx.messages.size(); i++) {
                ChatMessage msg = ctx.messages.get(i);
                sb.append("  [").append(i).append("] ").append(msg.getRole()).append(": ")
                  .append(msg.getContent() != null && msg.getContent().length() > 80
                          ? msg.getContent().substring(0, 80) + "..."
                          : msg.getContent())
                  .append("\n");
            }
            return sb.toString();
        }
    }

    // ======================== 测试入口 ========================
    // 测试命令：mvn compile exec:java -Dexec.mainClass="com.example.wechatbot.service.ConversationContextService"
    public static void main(String[] args) {
        ConversationContextService service = new ConversationContextService();

        System.out.println("====== 多轮对话上下文记忆测试 ======\n");

        // 测试1：基本多轮对话存储
        System.out.println("--- 测试1: 基本多轮对话存储 ---");
        String sessionId = service.ensureSession("user-001");
        System.out.println("sessionId: " + sessionId);

        service.addMessage(sessionId, "user", "你好，我叫小明");
        service.addMessage(sessionId, "assistant", "你好小明！很高兴认识你");
        System.out.println("第1轮后消息数: " + service.getMessageCount(sessionId));

        service.addMessage(sessionId, "user", "今天天气怎么样");
        service.addMessage(sessionId, "assistant", "今天天气晴朗，气温25度");
        System.out.println("第2轮后消息数: " + service.getMessageCount(sessionId));

        System.out.println(service.dumpHistory(sessionId));

        // 测试2：滑动窗口淘汰
        System.out.println("--- 测试2: 滑动窗口淘汰（MAX_ROUNDS=" + MAX_ROUNDS + "） ---");
        // 继续添加消息直到超过窗口
        for (int i = 3; i <= 12; i++) {
            service.addMessage(sessionId, "user", "第" + i + "轮提问");
            service.addMessage(sessionId, "assistant", "第" + i + "轮回复");
        }
        System.out.println("添加12轮后消息数: " + service.getMessageCount(sessionId)
                + " (应=" + MAX_MESSAGES + ")");
        System.out.println(service.dumpHistory(sessionId));

        // 测试3：sessionId为空时新建会话
        System.out.println("--- 测试3: sessionId为空时新建会话 ---");
        String newSession = service.ensureSession(null);
        System.out.println("传入null, 新建sessionId: " + newSession);
        String newSession2 = service.ensureSession("");
        System.out.println("传入空串, 新建sessionId: " + newSession2);
        System.out.println("两个sessionId不同: " + !newSession.equals(newSession2));

        // 测试4：不同会话隔离
        System.out.println("\n--- 测试4: 不同会话隔离 ---");
        service.addMessage("session-A", "user", "A的提问");
        service.addMessage("session-A", "assistant", "A的回复");
        service.addMessage("session-B", "user", "B的提问");
        service.addMessage("session-B", "assistant", "B的回复");
        System.out.println("session-A消息数: " + service.getMessageCount("session-A") + " (应=2)");
        System.out.println("session-B消息数: " + service.getMessageCount("session-B") + " (应=2)");
        System.out.println("活跃会话数: " + service.getActiveSessionCount());

        // 测试5：清除会话
        System.out.println("\n--- 测试5: 清除会话 ---");
        service.clearSession("session-A");
        System.out.println("清除session-A后消息数: " + service.getMessageCount("session-A") + " (应=0)");

        System.out.println("\n====== 测试结束 ======");
    }
}
