package bdsm.common.text;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Tokenizer {

    private static final int MIN_TOKEN_LENGTH = 2;
    private static final int MAX_TOKEN_LENGTH = 50;
    private static final String STOPWORDS_RESOURCE = "/stopwords.txt";
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");

    private final Set<String> stopwords;

    public Tokenizer() {
        this(loadDefaultStopwords());
    }

    public Tokenizer(Set<String> stopwords) {
        this.stopwords = Set.copyOf(stopwords);
    }

    public List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null) return tokens;
        Matcher matcher = TOKEN.matcher(normalize(text));
        while (matcher.find()) {
            String token = matcher.group();
            if (isValidToken(token)) tokens.add(token);
        }
        return tokens;
    }

    private boolean isValidToken(String token) {
        int length = token.codePointCount(0, token.length());
        return length >= MIN_TOKEN_LENGTH
                && length <= MAX_TOKEN_LENGTH
                && !stopwords.contains(token);
    }

    private static String normalize(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        return COMBINING_MARKS.matcher(decomposed).replaceAll("").toLowerCase(Locale.ROOT).replace('ς', 'σ');
    }

    private static Set<String> loadDefaultStopwords() {
        try (InputStream in = Tokenizer.class.getResourceAsStream(STOPWORDS_RESOURCE)) {
            if (in == null) throw new IllegalStateException("Resource not found: " + STOPWORDS_RESOURCE);
            return readStopwords(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Set<String> readStopwords(InputStream in) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        return reader.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .collect(Collectors.toSet());
    }
}
