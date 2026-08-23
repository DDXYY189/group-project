package com.example.group_demo.session;

import com.github.wechat.ilink.sdk.core.context.ConversationContext;
import com.github.wechat.ilink.sdk.core.context.ContextKey;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionStoreTest {

    @Test
    void savesAndRestoresResumeContext() {
        DataSource dataSource = new EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .build();
        SessionStore store = new SessionStore(new JdbcTemplate(dataSource));

        LoginContext login = new LoginContext("bot-token", "wechat-user", "bot-1", "https://base");
        ConversationContext conversation = new ConversationContext(new ContextKey("bot-1", "friend-1"));
        conversation.setLatestContextToken("context-token-1");
        Map<String, ConversationContext> contexts = new LinkedHashMap<>();
        contexts.put("friend-1", conversation);
        ResumeContext resume = ResumeContext.builder(login)
            .updatesCursor("cursor-1")
            .conversationContexts(contexts)
            .build();

        store.save("session-1", resume);

        SessionStore.StoredSession stored = store.loadAll().get(0);
        ResumeContext restored = store.toResumeContext(stored);

        assertEquals("session-1", stored.sessionId());
        assertEquals("bot-token", restored.getLoginContext().getBotToken());
        assertEquals("cursor-1", restored.getUpdatesCursor());
        assertEquals("context-token-1",
            restored.getConversationContextMap().get("friend-1").getLatestContextToken());
    }
}
