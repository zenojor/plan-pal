package com.weekendplanner.config;

import com.weekendplanner.engine.tooling.ToolCallbackFactory;
import com.weekendplanner.engine.tooling.ToolEffect;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Set;

@Configuration
public class PlanPalAiRuntimeConfig {

    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.create(chatModel);
    }

    @Bean
    public ToolCallbackProvider readOnlyToolCallbacks(ToolCallbackFactory callbackFactory) {
        return ToolCallbackProvider.from(callbackFactory.callbacksFor(Set.of(ToolEffect.READ_ONLY), "llm-read-only"));
    }

    @Bean
    public ToolCallbackProvider externalWriteToolCallbacks(ToolCallbackFactory callbackFactory) {
        return ToolCallbackProvider.from(callbackFactory.callbacksFor(Set.of(ToolEffect.EXTERNAL_WRITE), "confirmPlan"));
    }
}

