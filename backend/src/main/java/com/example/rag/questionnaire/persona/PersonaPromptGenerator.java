package com.example.rag.questionnaire.persona;

import com.example.rag.common.PromptLoader;
import com.example.rag.model.ModelClientProvider;
import com.example.rag.model.ModelPurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Component
public class PersonaPromptGenerator {

    private static final Logger log = LoggerFactory.getLogger(PersonaPromptGenerator.class);

    private final ModelClientProvider modelClientProvider;
    private final PromptLoader promptLoader;

    public PersonaPromptGenerator(ModelClientProvider modelClientProvider, PromptLoader promptLoader) {
        this.modelClientProvider = modelClientProvider;
        this.promptLoader = promptLoader;
    }

    public String generate(String name, String role, String focusAreas) {
        ChatClient client = modelClientProvider.getChatClient(ModelPurpose.QUESTIONNAIRE);
        String userPrompt = promptLoader.load("persona-prompt-gen.txt");

        String content = client.prompt()
                .user(u -> u.text(userPrompt)
                        .param("name", name)
                        .param("role", role)
                        .param("focusAreas", focusAreas != null ? focusAreas : ""))
                .call()
                .content();

        log.info("Generated prompt for persona '{}': {} chars", name, content != null ? content.length() : 0);
        return content != null ? content.trim() : "";
    }
}
