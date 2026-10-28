package bdsm.common.text;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TokenizerTest {

    private final Tokenizer tokenizer = new Tokenizer();

    @Test
    void lowercasesAndRemovesAccents() {
        assertEquals(List.of("cafe", "ecole"), tokenizer.tokenize("Café ÉCOLE"));
    }

    @Test
    void splitsOnEverythingThatIsNotALetterOrDigit() {
        assertEquals(List.of("sea", "shore", "whale"), tokenizer.tokenize("sea-shore, whale's!"));
    }

    @Test
    void removesStopwordsAndSingleCharacterTokens() {
        assertEquals(List.of("cat", "mat"), tokenizer.tokenize("The cat is on a mat"));
    }

    @Test
    void splitsContractionsAndDropsTheirFragments() {
        assertEquals(List.of("stop"), tokenizer.tokenize("Don't stop"));
    }

    @Test
    void keepsDigitsAndOtherAlphabets() {
        assertEquals(List.of("1813", "ωμεγα", "straße"), tokenizer.tokenize("1813 Ωμέγα Straße"));
    }

    @Test
    void foldsFinalSigma() {
        assertEquals(List.of("οδοσ", "οδοσ", "οδοσ", "βασ"), tokenizer.tokenize("ΟΔΟΣ οδος ΟΔΟΣ-ΒΑΣ"));
    }

    @Test
    void discardsTokensLongerThanFiftyCharacters() {
        String fifty = "x".repeat(50);
        String fiftyOne = "y".repeat(51);
        assertEquals(List.of(fifty), tokenizer.tokenize(fifty + " " + fiftyOne));
    }

    @Test
    void keepsOrderAndRepetitions() {
        assertEquals(List.of("whale", "sea", "whale"), tokenizer.tokenize("whale sea whale"));
    }

    @Test
    void returnsEmptyListForNullOrBlankText() {
        assertEquals(List.of(), tokenizer.tokenize(null));
        assertEquals(List.of(), tokenizer.tokenize("  ,;  "));
    }

    @Test
    void usesTheGivenStopwords() {
        Tokenizer custom = new Tokenizer(Set.of("whale"));
        assertEquals(List.of("the", "sea"), custom.tokenize("the whale sea"));
    }
}
