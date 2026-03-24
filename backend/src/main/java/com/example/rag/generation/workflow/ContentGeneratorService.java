package com.example.rag.generation.workflow;

import com.example.rag.common.PromptLoader;
import com.example.rag.model.ModelClientProvider;
import com.example.rag.model.ModelPurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ContentGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(ContentGeneratorService.class);

    private static final String OUTLINE_FORMAT =
            "{\"title\":\"문서 제목\",\"summary\":\"문서 요약\",\"sections\":[{\"key\":\"section_key\",\"heading\":\"섹션 제목\",\"purpose\":\"섹션 목적\",\"keyPoints\":[\"포인트1\"],\"estimatedLength\":500}]}";

    private static final String SECTION_FORMAT =
            "{\"key\":\"section_key\",\"title\":\"섹션 제목\",\"content\":\"마크다운 본문\",\"highlights\":[\"포인트1\"],\"tables\":[],\"references\":[]}";

    private final ModelClientProvider modelClientProvider;
    private final PromptLoader promptLoader;
    private final AiResponseParser parser;

    public ContentGeneratorService(ModelClientProvider modelClientProvider,
                                   PromptLoader promptLoader,
                                   AiResponseParser parser) {
        this.modelClientProvider = modelClientProvider;
        this.promptLoader = promptLoader;
        this.parser = parser;
    }

    public DocumentOutline generateOutline(String userInput, String systemPrompt,
                                           List<String> ragContext) {
        ChatClient client = modelClientProvider.getChatClient(ModelPurpose.CHAT);
        String userPrompt = promptLoader.load("generation-outline.txt");
        String contextText = ragContext.isEmpty() ? "없음" : String.join("\n---\n", ragContext);

        String content = client.prompt()
                .system(systemPrompt)
                .user(u -> u.text(userPrompt)
                        .param("format", OUTLINE_FORMAT)
                        .param("input", userInput)
                        .param("context", contextText))
                .call()
                .content();

        log.debug("Outline raw response length: {}", content != null ? content.length() : 0);
        return parser.parseOutline(content);
    }

    public SectionContent generateSection(SectionPlan plan, String systemPrompt,
                                          List<String> ragContext,
                                          List<SectionContent> previousSections) {
        ChatClient client = modelClientProvider.getChatClient(ModelPurpose.CHAT);
        String userPrompt = promptLoader.load("generation-section.txt");

        String joined = previousSections.stream()
                .map(s -> "## " + s.title() + "\n" + s.content())
                .collect(Collectors.joining("\n\n"));
        String previousContext = joined.isBlank() ? "없음 (첫 번째 섹션)" : joined;
        String contextText = ragContext.isEmpty() ? "없음" : String.join("\n---\n", ragContext);

        String content = client.prompt()
                .system(systemPrompt)
                .user(u -> u.text(userPrompt)
                        .param("format", SECTION_FORMAT)
                        .param("heading", plan.heading())
                        .param("purpose", plan.purpose())
                        .param("keyPoints", String.join(", ", plan.keyPoints()))
                        .param("previous", previousContext)
                        .param("context", contextText))
                .call()
                .content();

        if (log.isDebugEnabled()) {
            log.debug("Section '{}' raw response length: {}", plan.heading(), content != null ? content.length() : 0);
        }
        return parser.parseSection(content);
    }
}
