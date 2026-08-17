package com.example.demo.wechat;

import com.example.demo.config.WechatBotProperties;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 微信 iLink 客户端配置。关闭内置心跳，由 WechatBotRunner 自行轮询，避免消息被心跳线程消费。
 */
@Configuration
public class WechatConfig {

    @Bean(destroyMethod = "close")
    public ILinkClient iLinkClient(WechatBotProperties props) {
        ILinkConfig config = ILinkConfig.builder()
                .heartbeatEnabled(false)
                .heartbeatIntervalMs(props.getHeartbeatIntervalMs())
                .build();
        return ILinkClient.builder()
                .config(config)
                .build();
    }
}
