package io.devflow.infrastructure.ticketing.jira;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarkdownToAdfConverterTest {

    @Test
    @DisplayName("Given an empty Jira comment when it is converted then DevFlow returns an empty paragraph instead of invalid ADF")
    void givenAnEmptyJiraComment_whenItIsConverted_thenDevFlowReturnsAnEmptyParagraphInsteadOfInvalidAdf() {
        List<Map<String, Object>> document = MarkdownToAdfConverter.convert("   ");

        assertEquals(1, document.size());
        assertEquals("paragraph", document.getFirst().get("type"));
        assertFalse(document.getFirst().containsKey("content"));
    }

    @Test
    @DisplayName("Given rich business feedback when it is converted then DevFlow preserves headings, lists, rules, links and code blocks for Jira")
    void givenRichBusinessFeedback_whenItIsConverted_thenDevFlowPreservesHeadingsListsRulesLinksAndCodeBlocksForJira() {
        List<Map<String, Object>> document = MarkdownToAdfConverter.convert("""
            # Delivery plan
            Ship **value** with `code`, **`snippet`** and https://example.com/roadmap

            - first outcome
            - second outcome

            1. first review step
            2. second review step

            ---

            ```java
            line1
            line2
            ```
            """);

        assertEquals("heading", document.get(0).get("type"));
        assertEquals(Map.of("level", 1), document.get(0).get("attrs"));
        assertEquals("paragraph", document.get(1).get("type"));
        assertEquals("bulletList", document.get(2).get("type"));
        assertEquals("orderedList", document.get(3).get("type"));
        assertEquals("rule", document.get(4).get("type"));
        assertEquals("codeBlock", document.get(5).get("type"));
        assertEquals(Map.of("language", "java"), document.get(5).get("attrs"));
        assertEquals("line1\nline2", textNode(document.get(5)).get("text"));

        List<Map<String, Object>> paragraph = content(document.get(1));
        assertTrue(paragraph.stream().anyMatch(node -> "value".equals(node.get("text"))));
        assertTrue(paragraph.stream().anyMatch(node -> "snippet".equals(node.get("text"))));
        assertTrue(paragraph.stream().anyMatch(node -> "https://example.com/roadmap".equals(node.get("text"))));
    }

    @Test
    @DisplayName("Given inline feedback only when it is converted then DevFlow preserves plain text, emphasis and links in order")
    void givenInlineFeedbackOnly_whenItIsConverted_thenDevFlowPreservesPlainTextEmphasisAndLinksInOrder() {
        List<Map<String, Object>> content = MarkdownToAdfConverter.parseInline("Keep **business value** and `example` after https://example.com");

        assertEquals(6, content.size());
        assertEquals("Keep ", content.get(0).get("text"));
        assertEquals("business value", content.get(1).get("text"));
        assertEquals("example", content.get(3).get("text"));
        assertEquals("https://example.com", content.get(5).get("text"));
        assertEquals(List.of(Map.of("type", "strong")), content.get(1).get("marks"));
        assertEquals(List.of(Map.of("type", "code")), content.get(3).get("marks"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> content(Map<String, Object> node) {
        return (List<Map<String, Object>>) node.get("content");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> textNode(Map<String, Object> node) {
        return ((List<Map<String, Object>>) node.get("content")).getFirst();
    }
}
