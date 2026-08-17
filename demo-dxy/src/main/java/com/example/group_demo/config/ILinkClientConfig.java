package com.example.group_demo.config;

import com.example.group_demo.bot.BotService;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ILinkClientConfig {

    @Bean(destroyMethod = "close")
    public ILinkClient iLinkClient(BotService botService) {
        return ILinkClient.builder()
            .onLogin(new OnLoginListener() {
                @Override
                public void onLoginSuccess(LoginContext context) {
                    botService.onLoginSuccess(context);
                }

                @Override
                public void onLoginFailure(Throwable throwable) {
                    botService.onLoginFailure(throwable);
                }
            })
            .onMessage(botService::onMessages)
            .build();
    }
}
