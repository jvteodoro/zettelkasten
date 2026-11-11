package org.zettelkasten.application.port;

import java.util.function.Function;

/** Runs repository work atomically. Repository instances in one callback share one transaction. */
public interface UnitOfWork {
  <T> T transact(Function<Transaction, T> work);

  interface Transaction {
    MainCardRepository mainCards();

    AuthoredReferenceRepository references();

    IndexCardRepository indexCards();

    BibliographyRepository bibliography();
  }
}
