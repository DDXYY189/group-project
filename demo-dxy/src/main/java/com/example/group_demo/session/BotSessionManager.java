package com.example.group_demo.session;

import com.example.group_demo.bot.BotService;
import com.example.group_demo.bot.MessageDispatcher;
import com.example.group_demo.image.ImageService;
import com.example.group_demo.intent.ImageTextMerger;
import com.example.group_demo.intent.IntentService;
import com.example.group_demo.llm.LlmService;
import com.example.group_demo.router.MessageRouter;
import com.example.group_demo.tool.ToolRegistry;
import com.example.group_demo.voice.VoiceService;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.ILinkClientBuilder;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class BotSessionManager {

    private static final Logger log = LoggerFactory.getLogger(BotSessionManager.class);

    private final ConcurrentHashMap<String, BotService> sessions = new ConcurrentHashMap<>();
    private final ExecutorService loginPool = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "bot-login");
        thread.setDaemon(true);
        return thread;
    });

    private final SessionStore sessionStore;
    private final LlmService llmService;
    private final VoiceService voiceService;
    private final IntentService intentService;
    private final ImageService imageService;
    private final ToolRegistry toolRegistry;
    private final ImageTextMerger imageTextMerger;
    private final MessageDispatcher messageDispatcher;
    private final MessageRouter messageRouter;

    public BotSessionManager(SessionStore sessionStore, LlmService llmService,
                             VoiceService voiceService, IntentService intentService,
                             ImageService imageService, ToolRegistry toolRegistry,
                             ImageTextMerger imageTextMerger, MessageDispatcher messageDispatcher,
                             MessageRouter messageRouter) {
        this.sessionStore = sessionStore;
        this.llmService = llmService;
        this.voiceService = voiceService;
        this.intentService = intentService;
        this.imageService = imageService;
        this.toolRegistry = toolRegistry;
        this.imageTextMerger = imageTextMerger;
        this.messageDispatcher = messageDispatcher;
        this.messageRouter = messageRouter;
    }

    @PostConstruct
    public void restoreSessions() {
        List<SessionStore.StoredSession> stored = sessionStore.loadAll();
        for (SessionStore.StoredSession item : stored) {
            try {
                ResumeContext resume = sessionStore.toResumeContext(item);
                if (resume == null) {
                    sessionStore.delete(item.sessionId());
                    continue;
                }
                BotService bot = createBot(item.sessionId(), resume);
                sessions.put(item.sessionId(), bot);
                log.info("已恢复登录会话 sessionId={} botId={}",
                    item.sessionId(), bot.getLoginContext() == null ? "?" : bot.getLoginContext().getBotId());
            } catch (Exception e) {
                log.warn("恢复会话失败，需重新扫码 sessionId={}", item.sessionId(), e);
                sessionStore.delete(item.sessionId());
            }
        }
    }

    public BotService createSession() {
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        BotService bot = createBot(sessionId, null);
        sessions.put(sessionId, bot);
        loginPool.submit(bot::startLogin);
        log.info("已创建会话 sessionId={}", sessionId);
        return bot;
    }

    public BotService get(String sessionId) {
        return sessions.get(sessionId);
    }

    public Collection<BotService> all() {
        return sessions.values();
    }

    public boolean relogin(String sessionId) {
        BotService bot = sessions.get(sessionId);
        if (bot == null) {
            return false;
        }
        loginPool.submit(bot::startLogin);
        return true;
    }

    public boolean remove(String sessionId) {
        BotService bot = sessions.remove(sessionId);
        if (bot == null) {
            return false;
        }
        bot.getClient().close();
        sessionStore.delete(sessionId);
        log.info("已关闭会话 sessionId={}", sessionId);
        return true;
    }

    private BotService createBot(String sessionId, ResumeContext resumeContext) {
        AtomicReference<BotService> botRef = new AtomicReference<>();
        ILinkClientBuilder builder = ILinkClient.builder()
            .config(ILinkConfig.builder()
                .ioCoreThreads(2)
                .ioMaxThreads(4)
                .schedulerThreads(1)
                .build())
            .onLogin(new OnLoginListener() {
                @Override
                public void onLoginSuccess(LoginContext context) {
                    BotService bot = botRef.get();
                    if (bot == null) {
                        return;
                    }
                    bot.onLoginSuccess(context);
                    sessionStore.save(sessionId, bot.getClient().exportResumeContext());
                }

                @Override
                public void onLoginFailure(Throwable throwable) {
                    BotService bot = botRef.get();
                    if (bot != null) {
                        bot.onLoginFailure(throwable);
                    }
                }
            })
            .onMessage(messages -> {
                BotService bot = botRef.get();
                if (bot != null) {
                    bot.onMessages(messages);
                }
            });
        if (resumeContext != null) {
            builder.resumeContext(resumeContext);
        }
        ILinkClient client = builder.build();
        BotService bot = new BotService(sessionId, client, llmService, voiceService,
            intentService, imageService, toolRegistry, imageTextMerger, messageDispatcher, messageRouter);
        botRef.set(bot);
        return bot;
    }

    @PreDestroy
    public void closeAll() {
        sessions.values().forEach(bot -> bot.getClient().close());
        sessions.clear();
        loginPool.shutdownNow();
    }
}
