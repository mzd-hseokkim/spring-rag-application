package com.example.rag.common;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class PromptLoader {

    public String load(String name) {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/" + name);
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            // 첫 줄이 주석(# v1.0 ...)이면 제거
            if (content.startsWith("#")) {
                int newline = content.indexOf('\n');
                if (newline > 0) {
                    content = content.substring(newline + 1);
                }
            }
            return content.trim();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load prompt: " + name, e);
        }
    }
}
