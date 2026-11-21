package ru.slie.luna.rest.client.generator.data;

import net.datafaker.Faker;

import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

/**
 * Pre-generated texts: datafaker is too slow to call for every one of a million issues.
 */
public class TextPool {
    private final List<String> words;
    private final List<String> sentences;
    private final List<String> paragraphs;

    public TextPool(long seed) {
        Faker faker = new Faker(Locale.of("ru"), new Random(seed));
        this.words = IntStream.range(0, 3000).mapToObj(i -> faker.lorem().word()).distinct().toList();
        this.sentences = IntStream.range(0, 2000).mapToObj(i -> faker.lorem().sentence(faker.random().nextInt(3, 9))).toList();
        this.paragraphs = IntStream.range(0, 500).mapToObj(i -> faker.lorem().paragraph(faker.random().nextInt(2, 6))).toList();
    }

    private static <T> T any(List<T> list) {
        return list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }

    public String word() {
        return any(words);
    }

    public String word(int index) {
        return words.get(Math.floorMod(index, words.size()));
    }

    public String words(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(word());
        }
        return sb.toString();
    }

    public String sentence() {
        String s = any(sentences);
        return s.length() > 250 ? s.substring(0, 250) : s;
    }

    public String paragraphs(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append("\n\n");
            }
            sb.append(any(paragraphs));
        }
        return sb.toString();
    }

    public String markdown() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(sentence()).append("\n\n");
        sb.append(any(paragraphs)).append(" **").append(word()).append("**.\n\n");
        sb.append("## Детали:\n");
        for (int i = 0, n = random.nextInt(3, 6); i < n; i++) {
            sb.append("* ").append(sentence()).append('\n');
        }
        sb.append("\n```json\n{\n  \"status\": \"error\",\n  \"code\": ").append(random.nextInt(400, 600))
          .append(",\n  \"message\": \"").append(word()).append(" exception\"\n}\n```\n");
        return sb.toString();
    }
}
