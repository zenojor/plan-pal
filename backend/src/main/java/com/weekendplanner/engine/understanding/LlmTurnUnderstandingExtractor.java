package com.weekendplanner.engine.understanding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class LlmTurnUnderstandingExtractor {

    private static final Logger log = LoggerFactory.getLogger(LlmTurnUnderstandingExtractor.class);

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final UnderstandingPromptFactory promptFactory;
    private final SlotNormalizer normalizer;

    @Autowired
    public LlmTurnUnderstandingExtractor(ObjectProvider<ChatModel> chatModelProvider,
                                         ObjectMapper objectMapper,
                                         UnderstandingPromptFactory promptFactory,
                                         SlotNormalizer normalizer) {
        this(chatModelProvider.getIfAvailable(), objectMapper, promptFactory, normalizer);
    }

    public LlmTurnUnderstandingExtractor(ChatModel chatModel,
                                         ObjectMapper objectMapper,
                                         UnderstandingPromptFactory promptFactory,
                                         SlotNormalizer normalizer) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.promptFactory = promptFactory == null ? new UnderstandingPromptFactory(this.objectMapper) : promptFactory;
        this.normalizer = normalizer == null ? new SlotNormalizer() : normalizer;
    }

    public Optional<TurnUnderstanding> extract(UnderstandingRequest request) {
        if (chatModel == null) return Optional.empty();
        try {
            String content = chatModel.call(new Prompt(List.of(
                    new SystemMessage(promptFactory.systemPrompt()),
                    new UserMessage(promptFactory.userPayload(request))
            ))).getResult().getOutput().getText();
            JsonNode node = objectMapper.readTree(extractJson(content));
            return Optional.of(normalizer.fromJson(node));
        } catch (Exception e) {
            log.warn("[Understanding] LLM extraction failed: {}", e.toString());
            return Optional.empty();
        }
    }

    private String extractJson(String text) {
        if (text == null) return "{}";
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : text;
    }
}
