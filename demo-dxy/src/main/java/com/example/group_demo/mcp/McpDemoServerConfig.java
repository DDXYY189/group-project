package com.example.group_demo.mcp;

import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpDemoServerConfig {

    @Bean
    @ConditionalOnProperty(prefix = "mcp", name = "demo-server-enabled", havingValue = "true",
        matchIfMissing = true)
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpDemoServlet(
        McpDemoServer server) {
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
            new ServletRegistrationBean<>(server.provider(), "/mcp/demo");
        registration.setLoadOnStartup(1);
        return registration;
    }
}
