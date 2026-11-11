package org.zettelkasten.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NumericAlphaAddressTest {
  @Test
  void parsesAndPreservesRawLiteralWhileCanonicalizingAsciiCase() {
    var address = NumericAlphaAddress.parse("4214/1A/1");
    assertEquals("4214/1A/1", address.literal());
    assertEquals("4214/1a/1", address.canonical());
    assertEquals(AddressScheme.ANTINET_SLASH_V1, address.scheme());
    assertEquals(1, address.scheme().version());
    assertEquals(address, NumericAlphaAddress.parse("4214/1a/1"));
    assertEquals(0, address.compareTo(NumericAlphaAddress.parse("4214/1a/1")));
  }

  @Test
  void usesNumericTokenOrderAndExpectedAntinetInsertionExamples() {
    assertLess("3118/2", "3118/2a");
    assertLess("3118/2a", "3118/3");
    assertLess("3118/9", "3118/10");
    assertLess("4214/1", "4214/1a");
    assertLess("4214/1a", "4214/1a/1");
    assertLess("4214/1a/1", "4214/1b");
    assertLess("4214/1b", "4214/2");
  }

  @Test
  void seededAddressGenerationSatisfiesComparatorLawsAndEqualityConsistency() {
    var random = new Random(0x5a17L);
    List<NumericAlphaAddress> values = new ArrayList<>();
    for (int i = 0; i < 800; i++) values.add(NumericAlphaAddress.parse(randomAddress(random)));
    for (int i = 0; i < 5000; i++) {
      var left = values.get(random.nextInt(values.size()));
      var middle = values.get(random.nextInt(values.size()));
      var right = values.get(random.nextInt(values.size()));
      int ab = Integer.signum(left.compareTo(middle));
      int ba = Integer.signum(middle.compareTo(left));
      assertEquals(-ab, ba, "antisymmetry");
      if (left.compareTo(middle) <= 0 && middle.compareTo(right) <= 0) {
        assertTrue(left.compareTo(right) <= 0, "transitivity");
      }
      assertEquals(left.equals(middle), left.compareTo(middle) == 0, "equality consistency");
    }
    var sorted = values.stream().distinct().sorted().toList();
    assertEquals((long) sorted.size(), sorted.stream().distinct().count());
    for (int i = 1; i < sorted.size(); i++)
      assertTrue(sorted.get(i - 1).compareTo(sorted.get(i)) < 0);
  }

  @Test
  void permitsTheSingleZeroButRejectsPaddedNumericComponents() {
    assertEquals("0/0a0", NumericAlphaAddress.parse("0/0a0").canonical());
    assertThrows(AddressParseException.class, () -> NumericAlphaAddress.parse("00"));
    assertThrows(AddressParseException.class, () -> NumericAlphaAddress.parse("1/02"));
    assertThrows(AddressParseException.class, () -> NumericAlphaAddress.parse("1/a02"));
  }

  @Test
  void rejectsInvalidWhitespacePunctuationUnicodeAndBounds() {
    for (String value : List.of("", " 1", "1 ", "1//2", "1/", "/1", "1.2", "1/2 3", "１/2", "1/Ä")) {
      assertThrows(AddressParseException.class, () -> NumericAlphaAddress.parse(value), value);
    }
    assertThrows(
        AddressParseException.class, () -> NumericAlphaAddress.parse("1/" + "1/".repeat(32) + "1"));
    assertThrows(AddressParseException.class, () -> NumericAlphaAddress.parse("1".repeat(256)));
    assertThrows(AddressParseException.class, () -> NumericAlphaAddress.parse("1/\uD800"));
    assertThrows(NullPointerException.class, () -> NumericAlphaAddress.parse(null));
  }

  @Test
  void parseCanonicalizationIsIdempotentAndAddressEqualityIsCanonical() {
    var first = NumericAlphaAddress.parse("3118/2A");
    var second = NumericAlphaAddress.parse(first.canonical());
    assertEquals(first.canonical(), second.canonical());
    assertEquals(first, second);
    assertFalse(first.literal().equals(second.literal()));
  }

  @Test
  void placementCapturesNeighborsAndSchemeVersionWithoutInferringParents() {
    UUID card = uuid(1);
    var placement =
        new PlacementSnapshot(
            card, NumericAlphaAddress.parse("3118/2a"), uuid(2), uuid(3), Instant.EPOCH);
    assertEquals("ANTINET_SLASH_V1", placement.schemeId());
    assertEquals(1, placement.schemeVersion());
    assertEquals(uuid(2), placement.predecessorCardId());
    assertThrows(
        IllegalArgumentException.class,
        () -> new PlacementSnapshot(card, placement.address(), card, null, Instant.EPOCH));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PlacementSnapshot(card, placement.address(), uuid(2), uuid(2), Instant.EPOCH));
  }

  @Test
  void defaultComparatorIsDeterministicAcrossLargeNumericTokens() {
    var input = List.of("1/100000000000000000000", "1/9", "1/10");
    var sorted =
        input.stream()
            .map(NumericAlphaAddress::parse)
            .sorted(Comparator.naturalOrder())
            .map(NumericAlphaAddress::canonical)
            .toList();
    assertEquals(List.of("1/9", "1/10", "1/100000000000000000000"), sorted);
  }

  @Test
  void checkedInFixturesAreExecutableContracts() throws Exception {
    var valid = getClass().getResourceAsStream("/fixtures/addresses/v1-valid.tsv");
    assertNotNull(valid);
    try (var reader = new BufferedReader(new InputStreamReader(valid, StandardCharsets.UTF_8))) {
      for (String line : reader.lines().skip(1).toList()) {
        String[] columns = line.split("\t", -1);
        assertEquals(columns[1], NumericAlphaAddress.parse(columns[0]).canonical());
      }
    }
    var invalid = getClass().getResourceAsStream("/fixtures/addresses/v1-invalid.tsv");
    assertNotNull(invalid);
    try (var reader = new BufferedReader(new InputStreamReader(invalid, StandardCharsets.UTF_8))) {
      for (String line : reader.lines().skip(1).toList()) {
        String[] columns = line.split("\t", -1);
        assertThrows(
            AddressParseException.class, () -> NumericAlphaAddress.parse(columns[0]), columns[1]);
      }
    }
    var order = getClass().getResourceAsStream("/fixtures/addresses/v1-order.tsv");
    assertNotNull(order);
    try (var reader = new BufferedReader(new InputStreamReader(order, StandardCharsets.UTF_8))) {
      for (String line : reader.lines().skip(1).toList()) {
        String[] columns = line.split("\t", -1);
        assertLess(columns[0], columns[1]);
      }
    }
    var relative = getClass().getResourceAsStream("/fixtures/addresses/relative-links.tsv");
    assertNotNull(relative);
    try (var reader = new BufferedReader(new InputStreamReader(relative, StandardCharsets.UTF_8))) {
      for (String line : reader.lines().skip(1).toList()) {
        String[] columns = line.split("\t", -1);
        assertEquals(4, columns.length);
        NumericAlphaAddress.parse(columns[0]);
        NumericAlphaAddress.parse(columns[3]);
        assertTrue(List.of("STEM", "BRANCH", "FULL").contains(columns[2]));
      }
    }
  }

  private static void assertLess(String left, String right) {
    assertTrue(
        NumericAlphaAddress.parse(left).compareTo(NumericAlphaAddress.parse(right)) < 0,
        left + " < " + right);
  }

  private static String randomAddress(Random random) {
    int depth = 1 + random.nextInt(5);
    StringBuilder address = new StringBuilder(Integer.toString(random.nextInt(10000)));
    for (int i = 1; i < depth; i++) {
      int value = random.nextInt(100);
      address.append('/').append(value);
      if (random.nextBoolean()) {
        address.append((char) ('a' + random.nextInt(4)));
        if (random.nextBoolean()) address.append(random.nextInt(10));
      }
    }
    return address.toString();
  }

  private static UUID uuid(long tail) {
    return new UUID(0, tail);
  }
}
