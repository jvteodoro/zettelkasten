package org.zettelkasten.domain;

/**
 * A manually authored Index pointer; stable targets are optional when the literal is unresolved.
 */
public sealed interface IndexTarget permits MainAddressTarget, KeytermTarget, SeeAlsoTarget {
  String literal();
}
