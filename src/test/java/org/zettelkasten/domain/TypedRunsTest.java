package org.zettelkasten.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TypedRunsTest {
  @Test
  void eachRunRetainsItsExactUnicodeLiteralAndKind() {
    var id = UUID.fromString("00000000-0000-0000-0000-000000000001");
    assertEquals("lää · 参照", new TextRun(id, "lää · 参照").literal());
    assertEquals("/1", new CardLinkRun(id, "/1").literal());
    assertEquals("Überblick", new KeytermLinkRun(id, "Überblick").literal());
    assertEquals("b.Source, xiv", new ExRefRun(id, "b.Source, xiv").literal());
    assertThrows(NullPointerException.class, () -> new TextRun(id, null));
    assertThrows(NullPointerException.class, () -> new CardLinkRun(null, "1/1"));
  }
}
