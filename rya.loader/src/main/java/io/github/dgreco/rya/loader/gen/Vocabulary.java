/*
 * Copyright 2026 David Greco
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.dgreco.rya.loader.gen;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pseudo-words for eleven languages, built from syllables so no dictionary has to be shipped.
 * Words are drawn with a Zipf distribution (frequent words are short, like in real text), which
 * gives the text index realistic hot terms. The vocabulary depends only on the language, never
 * on the seed, so the same words appear in every dataset.
 */
public final class Vocabulary {

    /** The languages of generated text, with their share of the text (per hundred). */
    public enum Language {
        EN("en", 35, 20_000), DE("de", 10, 20_000), FR("fr", 10, 20_000), ES("es", 8, 20_000),
        IT("it", 7, 20_000), PT("pt", 5, 20_000), RU("ru", 7, 8_000), EL("el", 4, 8_000),
        AR("ar", 4, 8_000), JA("ja", 5, 8_000), ZH("zh", 5, 8_000);

        private final String tag;
        private final int weight;
        private final int size;

        Language(final String tag, final int weight, final int size) {
            this.tag = tag;
            this.weight = weight;
            this.size = size;
        }

        public String tag() {
            return tag;
        }

        /** Whether words are written without spaces between them (Chinese, Japanese). */
        public boolean unspaced() {
            return this == JA || this == ZH;
        }
    }

    private static final String[][] LATIN = {
        // onsets, nuclei, codas
        {"", "b", "c", "d", "f", "g", "h", "j", "k", "l", "m", "n", "p", "r", "s", "t", "v", "w", "y", "br", "ch", "cl",
            "cr", "dr", "fl", "gr", "pl", "pr", "sh", "sl", "st", "str", "th", "tr"},
        {"a", "e", "i", "o", "u", "ea", "ee", "oo", "ou", "ai", "y"},
        {"", "", "n", "r", "s", "t", "l", "m", "nd", "st", "ng", "ck", "rd"},
    };
    private static final Map<Language, String[][]> INVENTORY = new EnumMap<>(Language.class);

    static {
        INVENTORY.put(Language.EN, LATIN);
        INVENTORY.put(Language.DE, new String[][] {
            {"", "b", "d", "f", "g", "h", "k", "l", "m", "n", "p", "r", "s", "t", "w", "z", "sch", "pf", "kn", "st", "br", "gr"},
            {"a", "e", "i", "o", "u", "ä", "ö", "ü", "ei", "au", "eu", "ie"},
            {"", "n", "r", "s", "t", "ch", "ng", "ß", "tz", "rg", "ck"}});
        INVENTORY.put(Language.FR, new String[][] {
            {"", "b", "c", "ch", "d", "f", "g", "j", "l", "m", "n", "p", "qu", "r", "s", "t", "v", "ç", "gr", "pl"},
            {"a", "e", "é", "è", "ê", "i", "o", "u", "ou", "eau", "ai", "oi", "an", "on"},
            {"", "", "s", "r", "t", "x", "n", "l"}});
        INVENTORY.put(Language.ES, new String[][] {
            {"", "b", "c", "ch", "d", "f", "g", "j", "l", "ll", "m", "n", "ñ", "p", "r", "s", "t", "v", "z", "pr", "tr"},
            {"a", "e", "i", "o", "u", "á", "é", "í", "ó", "ue", "ia", "io"},
            {"", "", "n", "s", "r", "l", "z"}});
        INVENTORY.put(Language.IT, new String[][] {
            {"", "b", "c", "d", "f", "g", "l", "m", "n", "p", "r", "s", "t", "v", "z", "gl", "gn", "sc", "ch", "tr"},
            {"a", "e", "i", "o", "u", "à", "è", "ò", "ia", "io"},
            {"", "", "", "n", "l", "r"}});
        INVENTORY.put(Language.PT, new String[][] {
            {"", "b", "c", "d", "f", "g", "j", "l", "lh", "m", "n", "nh", "p", "r", "s", "t", "v", "ç", "x"},
            {"a", "e", "i", "o", "u", "ã", "õ", "á", "ê", "ão", "ei", "ou"},
            {"", "", "s", "r", "l", "m"}});
        INVENTORY.put(Language.RU, new String[][] {
            {"", "б", "в", "г", "д", "ж", "з", "к", "л", "м", "н", "п", "р", "с", "т", "ф", "х", "ц", "ч", "ш", "щ"},
            {"а", "е", "и", "о", "у", "ы", "я", "ю", "ё"},
            {"", "", "й", "н", "р", "с", "т", "л", "к"}});
        INVENTORY.put(Language.EL, new String[][] {
            {"", "β", "γ", "δ", "ζ", "θ", "κ", "λ", "μ", "ν", "ξ", "π", "ρ", "σ", "τ", "φ", "χ", "ψ"},
            {"α", "ε", "η", "ι", "ο", "υ", "ω", "ά", "έ", "ή", "ί", "ό", "ύ", "ώ"},
            {"", "", "ν", "ρ"}});
        INVENTORY.put(Language.AR, new String[][] {
            {"ب", "ت", "ث", "ج", "ح", "خ", "د", "ذ", "ر", "ز", "س", "ش", "ص", "ط", "ع", "غ", "ف", "ق", "ك", "ل", "م", "ن", "ه", "و", "ي"},
            {"ا", "و", "ي", "", ""},
            {"", "", "ن", "ل", "م", "ر"}});
    }

    private static final String KANA = "かきくけこさしすせそたちつてとなにぬねのはひふへほまみむめもやゆよらりるれろわをんあいうえお";

    private static final Vocabulary INSTANCE = new Vocabulary();

    private final Map<Language, String[]> words = new EnumMap<>(Language.class);
    private final Language[] languageByShare = new Language[100];

    private Vocabulary() {
        for (final Language language : Language.values()) {
            words.put(language, build(language));
        }
        int i = 0;
        for (final Language language : Language.values()) {
            for (int k = 0; k < language.weight; k++) {
                languageByShare[i++] = language;
            }
        }
    }

    public static Vocabulary get() {
        return INSTANCE;
    }

    /** The {@code rank}-th most frequent word of a language. */
    public String word(final Language language, final int rank) {
        return words.get(language)[rank];
    }

    public int size(final Language language) {
        return words.get(language).length;
    }

    /** A language picked by its share of the text. */
    public Language language(final SplitMix64 random) {
        return languageByShare[random.nextInt(languageByShare.length)];
    }

    private static String[] build(final Language language) {
        final SplitMix64 random = new SplitMix64(SplitMix64.mix64(0x5EEDL * (language.ordinal() + 1)));
        final String[] out = new String[language.size];
        final Set<String> seen = new HashSet<>();
        for (int rank = 0; rank < language.size; rank++) {
            String w = null;
            for (int attempt = 0; attempt < 20 && (w == null || !seen.add(w)); attempt++) {
                w = make(language, random, syllables(rank) + attempt / 5);
            }
            out[rank] = w;
        }
        return out;
    }

    /** Frequent words are short. */
    private static int syllables(final int rank) {
        return rank < 10 ? 1 : rank < 300 ? 2 : rank < 5000 ? 3 : 4;
    }

    private static String make(final Language language, final SplitMix64 random, final int syllables) {
        final StringBuilder b = new StringBuilder();
        switch (language) {
            case ZH -> {
                for (int i = 0; i < Math.min(3, syllables); i++) {
                    b.appendCodePoint(0x4E00 + (random.nextInt(3000) * 7919) % 20902);
                }
            }
            case JA -> {
                for (int i = 0; i < syllables + 1; i++) {
                    if (random.chance(0.3)) {
                        b.appendCodePoint(0x4E00 + (random.nextInt(3000) * 7919) % 20902);
                    } else {
                        b.append(KANA.charAt(random.nextInt(KANA.length())));
                    }
                }
            }
            default -> {
                final String[][] inv = INVENTORY.get(language);
                for (int i = 0; i < syllables; i++) {
                    b.append(inv[0][random.nextInt(inv[0].length)]);
                    b.append(inv[1][random.nextInt(inv[1].length)]);
                    if (i == syllables - 1 || random.chance(0.3)) {
                        b.append(inv[2][random.nextInt(inv[2].length)]);
                    }
                }
                if (language == Language.EL && random.chance(0.4)) {
                    b.append('ς');
                }
                if (b.isEmpty()) {
                    b.append(inv[1][0]);
                }
            }
        }
        return b.toString();
    }

    /** Capitalizes the first letter (no change for scripts without case). */
    public static String capitalize(final String word) {
        if (word.isEmpty()) {
            return word;
        }
        final int first = word.codePointAt(0);
        return new String(Character.toChars(Character.toTitleCase(first))) + word.substring(Character.charCount(first));
    }

    /** Upper case, for codes and identifiers. */
    public static String code(final String word) {
        return word.toUpperCase(Locale.ROOT);
    }
}
