package com.loanlens.ai;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Removes personal identifiers from the manager's free-text question before it is placed in a prompt: the customer's
 * own name, code, e-mail and phone number (known to the backend), plus anything that merely looks like an e-mail
 * address or a phone/account number. The financial question itself is preserved.
 */
public final class QuestionSanitizer {

    static final String PERSON = "the customer";
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    /** 8 or more digits, optionally separated by spaces, dashes or dots, with an optional leading +. */
    private static final Pattern LONG_NUMBER = Pattern.compile("(?<![\\d.,])\\+?\\d(?:[\\s().-]?\\d){7,}(?![\\d])");

    private QuestionSanitizer() {
    }

    public static String scrub(String question, List<String> sensitiveTerms) {
        String text = question == null ? "" : question.trim();
        // longest terms first so "Rahul Sharma" is replaced as a whole before "Rahul"
        List<String> terms = sensitiveTerms.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
        for (String term : terms) {
            if (term == null || term.isBlank()) {
                continue;
            }
            text = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(term) + "(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                    .matcher(text).replaceAll(PERSON);
        }
        text = EMAIL.matcher(text).replaceAll("[email removed]");
        text = LONG_NUMBER.matcher(text).replaceAll("[number removed]");
        return text;
    }
}
