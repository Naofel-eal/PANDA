package io.devflow.infrastructure.ticketing.jira;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MarkdownToAdfConverter {

  private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$");
  private static final Pattern BULLET_PATTERN = Pattern.compile("^\\s*[-*]\\s+(.+)$");
  private static final Pattern ORDERED_PATTERN = Pattern.compile("^\\s*\\d+\\.\\s+(.+)$");
  private static final Pattern RULE_PATTERN = Pattern.compile("^(---+|\\*\\*\\*+|___+)$");
  private static final Pattern CODE_FENCE_PATTERN = Pattern.compile("^```(.*)$");

  private static final Pattern INLINE_PATTERN = Pattern.compile(
      "\\*\\*`([^`]+)`\\*\\*"
          + "|\\*\\*(.+?)\\*\\*"
          + "|`([^`]+)`"
          + "|(https?://\\S+)"
  );

  private MarkdownToAdfConverter() {
  }

  static List<Map<String, Object>> convert(String markdown) {
    if (markdown == null || markdown.isBlank()) {
      return List.of(paragraph(List.of()));
    }

    List<Map<String, Object>> blocks = new ArrayList<>();
    String[] lines = markdown.split("\\R", -1);
    int i = 0;

    while (i < lines.length) {
      String line = lines[i];

      if (line.isBlank()) {
        i++;
        continue;
      }

      Matcher codeFence = CODE_FENCE_PATTERN.matcher(line);
      if (codeFence.matches()) {
        String language = codeFence.group(1).trim();
        StringBuilder code = new StringBuilder();
        i++;
        while (i < lines.length && !CODE_FENCE_PATTERN.matcher(lines[i]).matches()) {
          if (!code.isEmpty()) {
            code.append("\n");
          }
          code.append(lines[i]);
          i++;
        }
        if (i < lines.length) {
          i++;
        }
        blocks.add(codeBlock(code.toString(), language));
        continue;
      }

      Matcher heading = HEADING_PATTERN.matcher(line);
      if (heading.matches()) {
        int level = heading.group(1).length();
        blocks.add(heading(level, heading.group(2).trim()));
        i++;
        continue;
      }

      if (RULE_PATTERN.matcher(line).matches()) {
        blocks.add(Map.of("type", "rule"));
        i++;
        continue;
      }

      Matcher bullet = BULLET_PATTERN.matcher(line);
      if (bullet.matches()) {
        List<Map<String, Object>> items = new ArrayList<>();
        while (i < lines.length) {
          Matcher m = BULLET_PATTERN.matcher(lines[i]);
          if (!m.matches()) {
            break;
          }
          items.add(listItem(m.group(1)));
          i++;
        }
        blocks.add(list("bulletList", items));
        continue;
      }

      Matcher ordered = ORDERED_PATTERN.matcher(line);
      if (ordered.matches()) {
        List<Map<String, Object>> items = new ArrayList<>();
        while (i < lines.length) {
          Matcher m = ORDERED_PATTERN.matcher(lines[i]);
          if (!m.matches()) {
            break;
          }
          items.add(listItem(m.group(1)));
          i++;
        }
        blocks.add(list("orderedList", items));
        continue;
      }

      blocks.add(paragraph(parseInline(line)));
      i++;
    }

    if (blocks.isEmpty()) {
      blocks.add(paragraph(List.of()));
    }
    return blocks;
  }

  private static Map<String, Object> heading(int level, String text) {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("type", "heading");
    node.put("attrs", Map.of("level", level));
    List<Map<String, Object>> content = parseInline(text);
    if (!content.isEmpty()) {
      node.put("content", content);
    }
    return node;
  }

  private static Map<String, Object> paragraph(List<Map<String, Object>> content) {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("type", "paragraph");
    if (!content.isEmpty()) {
      node.put("content", content);
    }
    return node;
  }

  private static Map<String, Object> list(String type, List<Map<String, Object>> items) {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("type", type);
    node.put("content", items);
    return node;
  }

  private static Map<String, Object> listItem(String text) {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("type", "listItem");
    node.put("content", List.of(paragraph(parseInline(text))));
    return node;
  }

  private static Map<String, Object> codeBlock(String code, String language) {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("type", "codeBlock");
    if (!language.isEmpty()) {
      node.put("attrs", Map.of("language", language));
    }
    if (!code.isEmpty()) {
      node.put("content", List.of(Map.of("type", "text", "text", code)));
    }
    return node;
  }

  static List<Map<String, Object>> parseInline(String text) {
    if (text == null || text.isEmpty()) {
      return List.of();
    }

    List<Map<String, Object>> nodes = new ArrayList<>();
    Matcher matcher = INLINE_PATTERN.matcher(text);
    int lastEnd = 0;

    while (matcher.find()) {
      if (matcher.start() > lastEnd) {
        nodes.add(textNode(text.substring(lastEnd, matcher.start())));
      }

      if (matcher.group(1) != null) {
        // **`code`** — Jira ADF does not support combining strong+code on the same node; use code only
        nodes.add(markedTextNode(matcher.group(1),
            List.of(Map.of("type", "code"))));
      } else if (matcher.group(2) != null) {
        nodes.add(markedTextNode(matcher.group(2),
            List.of(Map.of("type", "strong"))));
      } else if (matcher.group(3) != null) {
        nodes.add(markedTextNode(matcher.group(3),
            List.of(Map.of("type", "code"))));
      } else if (matcher.group(4) != null) {
        String url = matcher.group(4);
        nodes.add(markedTextNode(url,
            List.of(Map.of("type", "link", "attrs", Map.of("href", url)))));
      }

      lastEnd = matcher.end();
    }

    if (lastEnd < text.length()) {
      nodes.add(textNode(text.substring(lastEnd)));
    }

    return nodes;
  }

  private static Map<String, Object> textNode(String text) {
    return Map.of("type", "text", "text", text);
  }

  private static Map<String, Object> markedTextNode(String text, List<Map<String, Object>> marks) {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("type", "text");
    node.put("text", text);
    node.put("marks", marks);
    return node;
  }
}
