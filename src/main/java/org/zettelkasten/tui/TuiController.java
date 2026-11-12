package org.zettelkasten.tui;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.zettelkasten.application.AddressConflictException;
import org.zettelkasten.application.BibTeXImportResult;
import org.zettelkasten.application.BibliographyService;
import org.zettelkasten.application.IndexService;
import org.zettelkasten.application.MainCardService;
import org.zettelkasten.application.OrderedNeighbors;
import org.zettelkasten.domain.BibCard;
import org.zettelkasten.domain.BibNote;
import org.zettelkasten.domain.BibliographicSource;
import org.zettelkasten.domain.BodyRun;
import org.zettelkasten.domain.IndexEntry;
import org.zettelkasten.domain.KeytermIndexCard;
import org.zettelkasten.domain.KeytermTarget;
import org.zettelkasten.domain.ListIndexCard;
import org.zettelkasten.domain.MainAddressTarget;
import org.zettelkasten.domain.MainCard;
import org.zettelkasten.domain.SeeAlsoTarget;

/** Keyboard-only screen state machine. It never follows a typed reference. */
public final class TuiController {
  public enum Screen {
    HOME,
    DESK,
    MAIN,
    INDEX,
    BIB,
    HELP,
    EDITOR,
    INSTALL_ADDRESS,
    MAIN_ADDRESS,
    CONFIRM_DISCARD,
    CONFIRM_SAVED_DRAFT_DISCARD,
    INDEX_LIST_CARD,
    KEYTERM_CARDS,
    KEYTERM_CARD,
    INDEX_INPUT,
    BIB_SOURCE,
    BIB_CARD,
    BIB_INPUT,
    BIB_IMPORT_PREVIEW
  }

  private enum IndexInputStage {
    OVERFLOW,
    ENTRY_TEXT,
    ENTRY_TYPE,
    ENTRY_TARGET
  }

  private enum BibInputStage {
    IMPORT_PATH,
    IMPORT_NAMESPACE,
    CARD_GOAL,
    CARD_OVERVIEW,
    NOTE_TYPE,
    NOTE_LOCATOR,
    NOTE_TEXT
  }

  private final MainCardService cards;
  private final Supplier<UUID> ids;
  private final IndexService indexCards;
  private final BibliographyService bibliography;
  private Screen screen = Screen.HOME;
  private List<MainCard> drafts = List.of();
  private List<MainCard> installed = List.of();
  private int selectedDraft;
  private int selectedMain;
  private int selectedHomeSection;
  private MainCard currentDraft;
  private EditorBuffer editor;
  private final StringBuilder addressInput = new StringBuilder();
  private String status = "";
  private boolean quitRequested;
  private char selectedDivider = 'A';
  private List<ListIndexCard> indexLists = List.of();
  private List<KeytermIndexCard> keytermCards = List.of();
  private List<IndexEntry> indexEntries = List.of();
  private int selectedIndexList;
  private int selectedKeyterm;
  private int selectedIndexEntry;
  private UUID selectedIndexOwner;
  private boolean selectedOwnerIsList;
  private final StringBuilder indexInput = new StringBuilder();
  private IndexInputStage indexInputStage;
  private Screen indexInputReturnScreen;
  private String indexAuthoredText;
  private char indexTargetKind;
  private List<BibliographicSource> sources = List.of();
  private int selectedSource;
  private BibliographicSource currentSource;
  private BibCard currentBibCard;
  private List<BibNote> bibNotes = List.of();
  private boolean showingBibBack;
  private final StringBuilder bibInput = new StringBuilder();
  private BibInputStage bibInputStage;
  private Screen bibInputReturnScreen;
  private String pendingBibText;
  private String pendingBibSourceName;
  private Path pendingBibPath;
  private String pendingBibNamespace;
  private String pendingReadingGoal;
  private BibNote.LocatorType pendingLocatorType;
  private String pendingLocator;
  private BibTeXImportResult bibImportResult;
  private boolean bibImportApplied;

  public TuiController(MainCardService cards, Supplier<UUID> ids) {
    this(cards, null, null, ids);
  }

  public TuiController(MainCardService cards, IndexService indexCards, Supplier<UUID> ids) {
    this(cards, indexCards, null, ids);
  }

  public TuiController(
      MainCardService cards,
      IndexService indexCards,
      BibliographyService bibliography,
      Supplier<UUID> ids) {
    this.cards = Objects.requireNonNull(cards, "cards");
    this.indexCards = indexCards;
    this.bibliography = bibliography;
    this.ids = Objects.requireNonNull(ids, "ids");
  }

  public void handle(KeyStroke key) {
    Objects.requireNonNull(key, "key");
    if (key.getKeyType() == KeyType.EOF) {
      quitRequested = true;
      return;
    }
    if (key.getKeyType() == KeyType.Character && key.isCtrlDown() && key.getCharacter() == 3) {
      status = "Ctrl+C received; no card data was discarded.";
      return;
    }
    if (screen == Screen.EDITOR) {
      handleEditor(key);
      return;
    }
    if (screen == Screen.CONFIRM_DISCARD) {
      handleDiscardConfirmation(key);
      return;
    }
    if (screen == Screen.CONFIRM_SAVED_DRAFT_DISCARD) {
      handleSavedDraftDiscardConfirmation(key);
      return;
    }
    if (screen == Screen.INSTALL_ADDRESS || screen == Screen.MAIN_ADDRESS) {
      handleAddressPrompt(key);
      return;
    }
    if (screen == Screen.INDEX_INPUT) {
      handleIndexInput(key);
      return;
    }
    if (screen == Screen.BIB_INPUT) {
      handleBibInput(key);
      return;
    }
    if (key.getKeyType() == KeyType.Escape) {
      handleBack();
      return;
    }
    if (key.getKeyType() == KeyType.Character && !key.isCtrlDown()) {
      char value = Character.toLowerCase(key.getCharacter());
      if (value == 'q') {
        quitRequested = true;
        return;
      }
      if (value == 'j' || value == 'k') {
        moveSelection(value == 'j' ? 1 : -1);
        return;
      }
      if (value == 'h' && screen != Screen.INDEX && screen != Screen.BIB_CARD) {
        handleBack();
        return;
      }
      switch (screen) {
        case HOME -> {
          if (value == 'l') openSelectedHomeSection();
          else if (value == '?') screen = Screen.HELP;
        }
        case DESK -> {
          if (value == 'n') createDraft();
          else if (value == 'f') beginAddressPrompt(Screen.INSTALL_ADDRESS);
          else if (value == 'x') beginSavedDraftDiscard();
          else if (value == 'l') openSelectedDraft();
        }
        case MAIN -> {
          if (value == 'g') beginAddressPrompt(Screen.MAIN_ADDRESS);
        }
        case INDEX -> {
          if (value == 'h') selectDivider((char) Math.max('A', selectedDivider - 1));
          else if (value == 'l') selectDivider((char) Math.min('Z', selectedDivider + 1));
          else if (value == '+') beginIndexInput(IndexInputStage.OVERFLOW);
          else if (value == '*') openKeytermCards();
        }
        case INDEX_LIST_CARD, KEYTERM_CARD -> {
          if (value == 'a') beginIndexInput(IndexInputStage.ENTRY_TEXT);
          else if (value == 'p' && screen == Screen.INDEX_LIST_CARD) promoteSelectedEntry();
        }
        case KEYTERM_CARDS -> {
          if (value == 'l') openSelectedKeyterm();
        }
        case BIB -> {
          if (value == 'l') openSelectedSource();
          else if (value == 'i') beginBibInput(BibInputStage.IMPORT_PATH);
        }
        case BIB_SOURCE -> {
          if (value == 'c') beginBibInput(BibInputStage.CARD_GOAL);
          else if (value == 'l') openResearchCard();
        }
        case BIB_CARD -> {
          if (value == 'a') beginBibInput(BibInputStage.NOTE_TYPE);
          else if (value == 'h') showingBibBack = false;
          else if (value == 'l') showingBibBack = true;
        }
        case BIB_IMPORT_PREVIEW -> {
          if (value == 'a') applyBibImport();
        }
        case HELP -> {
          // h is handled by the common back action above.
        }
        default -> {}
      }
      return;
    }
    if (key.getKeyType() == KeyType.Enter) {
      switch (screen) {
        case HOME -> openSelectedHomeSection();
        case DESK -> openSelectedDraft();
        case INDEX -> openSelectedIndexList();
        case INDEX_LIST_CARD, KEYTERM_CARD -> showSelectedEntryPointer();
        case KEYTERM_CARDS -> openSelectedKeyterm();
        case BIB -> openSelectedSource();
        case BIB_SOURCE -> openResearchCard();
        default -> {}
      }
    }
  }

  private void moveSelection(int direction) {
    switch (screen) {
      case HOME -> selectedHomeSection = Math.max(0, Math.min(3, selectedHomeSection + direction));
      case DESK -> {
        refreshDrafts();
        selectedDraft =
            Math.max(0, Math.min(Math.max(0, drafts.size() - 1), selectedDraft + direction));
      }
      case MAIN -> moveMain(direction);
      case INDEX ->
          selectedIndexList =
              Math.max(
                  0, Math.min(Math.max(0, indexLists.size() - 1), selectedIndexList + direction));
      case INDEX_LIST_CARD, KEYTERM_CARD -> moveIndexEntry(direction);
      case KEYTERM_CARDS -> moveKeyterm(direction);
      case BIB -> {
        refreshSources();
        selectedSource =
            Math.max(0, Math.min(Math.max(0, sources.size() - 1), selectedSource + direction));
      }
      default -> {}
    }
  }

  private void handleBack() {
    screen =
        switch (screen) {
          case INDEX_LIST_CARD, KEYTERM_CARDS -> Screen.INDEX;
          case KEYTERM_CARD -> Screen.KEYTERM_CARDS;
          case BIB_SOURCE -> Screen.BIB;
          case BIB_CARD -> Screen.BIB_SOURCE;
          case BIB_IMPORT_PREVIEW -> Screen.BIB;
          default -> Screen.HOME;
        };
    status = "";
  }

  public Screen screen() {
    return screen;
  }

  public boolean quitRequested() {
    return quitRequested;
  }

  public String status() {
    return status;
  }

  public EditorBuffer editor() {
    return editor;
  }

  public List<String> viewLines() {
    List<String> lines = new ArrayList<>();
    switch (screen) {
      case HOME -> {
        lines.add((selectedHomeSection == 0 ? "> " : "  ") + "Desk — unfiled Main drafts");
        lines.add(
            (selectedHomeSection == 1 ? "> " : "  ")
                + "Main — browse installed cards by physical order");
        lines.add(
            (selectedHomeSection == 2 ? "> " : "  ") + "Index — manually authored entry cards");
        lines.add((selectedHomeSection == 3 ? "> " : "  ") + "Bib — sources and research cards");
        lines.add("J/K Select    L or Enter Open");
        lines.add("Q  Quit");
      }
      case DESK -> {
        lines.add("Drafts: " + drafts.size() + "    J/K Select    L Edit    H Home");
        lines.add("N New    F File    X Discard");
        if (drafts.isEmpty()) lines.add("The Desk is empty.");
        for (int index = 0; index < drafts.size(); index++) {
          MainCard draft = drafts.get(index);
          String marker = index == selectedDraft ? "> " : "  ";
          String title =
              draft.authoredBody().stream().map(BodyRun::literal).reduce("", String::concat);
          lines.add(marker + (index + 1) + "  " + (title.isBlank() ? "(blank draft)" : title));
        }
      }
      case EDITOR -> {
        lines.add("Draft — no address until you choose one during filing");
        lines.addAll(List.of(editor.renderedText().split("\\n", -1)));
        if (editor.softLimitWarning())
          lines.add(
              (editor.softLimitReached() ? "Soft limit reached: " : "Approaching soft limit: ")
                  + editor.codePointCount()
                  + "/"
                  + editor.softLimit()
                  + " code points; text is not truncated.");
        if (editor.mode() == EditorBuffer.Mode.CARD_REFERENCE
            || editor.mode() == EditorBuffer.Mode.INDEX_LITERAL
            || editor.mode() == EditorBuffer.Mode.EXTERNAL_REFERENCE
            || editor.mode() == EditorBuffer.Mode.COMMAND)
          lines.add(editor.promptLabel() + editor.promptText());
        if (!editor.validationMessage().isEmpty()) lines.add(editor.validationMessage());
        if (editor.mode() == EditorBuffer.Mode.TEXT) lines.add("INSERT  Type freely    Esc NORMAL");
        else if (editor.mode() == EditorBuffer.Mode.NORMAL)
          lines.add(
              "NORMAL  I Insert    H/J/K/L Move    R Card ref    T Keyterm    E ExRef    :w Save");
        else if (editor.mode() == EditorBuffer.Mode.COMMAND)
          lines.add("COMMAND  Enter Run    Esc Cancel");
        else lines.add("Literal prompt    Enter Insert    Esc Return to editor");
      }
      case INSTALL_ADDRESS -> {
        lines.add("Choose a numeric-alpha address manually. No address suggestions are shown.");
        lines.add("Address: " + addressInput);
        if (!addressInput.isEmpty()) lines.addAll(insertionContext(addressInput.toString()));
        lines.add("Enter Install    Esc Cancel");
      }
      case MAIN -> {
        lines.add(
            "Main — physical address order    J/K Next/previous    G Exact address    H Home");
        if (installed.isEmpty()) lines.add("No installed Main cards.");
        else {
          MainCard card = installed.get(selectedMain);
          lines.add("Address " + card.addressLiteral());
          lines.addAll(card.authoredBody().stream().map(BodyRun::literal).toList());
          if (!card.annotations().isEmpty()) {
            lines.add("Annotations:");
            card.annotations()
                .forEach(
                    annotation ->
                        lines.addAll(annotation.runs().stream().map(BodyRun::literal).toList()));
          }
          lines.add("J/K move by filing order. Printed references are not selectable.");
        }
      }
      case MAIN_ADDRESS -> {
        lines.add("Enter an exact address to position the Main box. No typeahead.");
        lines.add("Address: " + addressInput);
        lines.add("Enter Open    Esc Cancel");
      }
      case INDEX -> {
        lines.add(
            "Index Box — divider "
                + selectedDivider
                + "    H/L Divider    J/K List card    Enter Open");
        lines.add("+ New overflow list card    * Dedicated keyterm cards");
        if (indexLists.isEmpty()) lines.add("No list cards under " + selectedDivider + ".");
        for (int index = 0; index < indexLists.size(); index++) {
          ListIndexCard card = indexLists.get(index);
          lines.add(
              (index == selectedIndexList ? "> " : "  ")
                  + selectedDivider
                  + " ("
                  + card.overflowNumber()
                  + ")");
        }
        lines.add("Entries are authored in order. Main pointers are read and typed manually.");
      }
      case INDEX_LIST_CARD, KEYTERM_CARD -> {
        lines.add(selectedOwnerIsList ? "List Index card" : "Dedicated keyterm card");
        lines.add("J/K Select entry    A Add entry    Enter Read pointer    H Back");
        if (selectedOwnerIsList) lines.add("P Promote selected entry to a dedicated keyterm card");
        if (indexEntries.isEmpty()) lines.add("No authored entries yet.");
        for (int index = 0; index < indexEntries.size(); index++) {
          IndexEntry entry = indexEntries.get(index);
          String marker = index == selectedIndexEntry ? "> " : "  ";
          String redirect = entry.promotedToKeytermCardId() == null ? "" : " [promoted]";
          lines.add(marker + entry.authoredText() + " → " + entry.target().literal() + redirect);
        }
        lines.add("Pointers are printed text; open Main and enter an address yourself.");
      }
      case KEYTERM_CARDS -> {
        lines.add("Dedicated keyterm cards — alphabetical    J/K Select    L/Enter Open    H Back");
        if (keytermCards.isEmpty()) lines.add("No dedicated keyterm cards.");
        for (int index = 0; index < keytermCards.size(); index++) {
          lines.add((index == selectedKeyterm ? "> " : "  ") + keytermCards.get(index).keyterm());
        }
      }
      case INDEX_INPUT -> {
        lines.add(indexInputPrompt());
        lines.add(indexInput.toString());
        lines.add("Enter Continue    Esc Cancel");
      }
      case BIB -> {
        lines.add(
            "Bibliography Box — J/K Select    L/Enter Read citation    I Import BibTeX    H Home");
        if (sources.isEmpty()) lines.add("No bibliographic sources are filed.");
        for (int index = 0; index < sources.size(); index++) {
          BibliographicSource source = sources.get(index);
          lines.add((index == selectedSource ? "> " : "  ") + citationLine(source));
        }
      }
      case BIB_SOURCE -> {
        lines.add("Bibliographic source — imported metadata is distinct from your research card.");
        lines.add("Citation: " + currentSource.creatorsText() + " — " + currentSource.title());
        if (currentSource.publicationYear() != null)
          lines.add("Year: " + currentSource.publicationYear());
        if (currentSource.containerTitle() != null)
          lines.add("Container: " + currentSource.containerTitle());
        lines.addAll(
            bibliography.getExternalAliases(currentSource.id()).stream()
                .map(alias -> "Citation key: " + alias.externalId())
                .toList());
        if (bibliography.findBibCardForSource(currentSource.id()).isPresent())
          lines.add("Research BibCard exists. L/Enter to read it.");
        else lines.add("No user-authored research BibCard yet. C Create goal and overview.");
        lines.add(
            "H/Esc Return to source list. Main ExRefs are entered literally with E in editor NORMAL mode.");
      }
      case BIB_CARD -> {
        lines.add(
            showingBibBack ? "Research BibCard — back / BibNotes" : "Research BibCard — front");
        lines.add("Source: " + currentSource.creatorsText() + " — " + currentSource.title());
        if (!showingBibBack) {
          lines.add("Reading goal: " + currentBibCard.readingGoal());
          lines.add("Source overview: " + currentBibCard.sourceOverview());
          lines.add("H/L Flip front/back    A Append a located BibNote");
        } else {
          if (bibNotes.isEmpty()) lines.add("No user-authored BibNotes.");
          for (BibNote note : bibNotes)
            lines.add("(" + note.locatorLiteral() + ") " + note.authoredText());
          lines.add("H/L Flip front/back    A Append a located BibNote");
        }
      }
      case BIB_INPUT -> {
        lines.add(bibInputPrompt());
        lines.add(bibInput.toString());
        lines.add("Enter Continue    Esc Cancel");
      }
      case BIB_IMPORT_PREVIEW -> {
        lines.add(
            bibImportApplied
                ? "BibTeX reconciliation result"
                : "BibTeX reconciliation preview — no changes applied");
        lines.add("Source file: " + pendingBibSourceName + "    Namespace: " + pendingBibNamespace);
        lines.add("Result: " + bibImportResult.status());
        for (var entry : bibImportResult.entries())
          lines.add(entry.citationKey() + " — " + entry.outcome() + ": " + entry.diagnostic());
        if (!bibImportApplied) lines.add("A Apply the whole file    Esc Cancel");
        else lines.add("Esc Return to Bibliography");
      }
      case HELP -> {
        lines.add("Keyboard help");
        lines.add("Home: J/K select box, L or Enter open, Q quit");
        lines.add("Desk: J/K select draft, L edit, N new, F file, X discard");
        lines.add("Main: J/K physical neighbors, G exact address; printed links are not actions");
        lines.add("Index: H/L divider, J/K list; + overflow; * keyterms; A append; P promote");
        lines.add("Bib: J/K sources, L/Enter citation, I import, C research card, A note");
        lines.add(
            "H returns to the parent screen; on Index dividers and BibCards H/L is contextual.");
        lines.add("Editor: INSERT types; Esc enters NORMAL; I resumes typing; Esc again returns.");
        lines.add(
            "NORMAL: H/J/K/L move; R/T/E enter literal prompts; :w saves; :q exits; :wq saves/exits.");
        lines.add(
            "Esc returns or cancels a literal prompt. No search, suggestions, or link following.");
      }
      case CONFIRM_DISCARD -> {
        lines.add("Unsaved edits are in memory. Save them before returning to the Desk?");
        lines.add("Y Save    N Discard these unsaved edits    Esc Continue editing");
      }
      case CONFIRM_SAVED_DRAFT_DISCARD -> {
        lines.add(
            "Discard this saved Desk draft? Its content will remain in the archive tombstone.");
        lines.add("Y Discard draft    N or Esc Keep draft");
      }
    }
    return List.copyOf(lines);
  }

  private void openDesk() {
    refreshDrafts();
    screen = Screen.DESK;
  }

  private void openSelectedHomeSection() {
    switch (selectedHomeSection) {
      case 0 -> openDesk();
      case 1 -> openMain();
      case 2 -> openIndex();
      case 3 -> openBibliography();
      default -> throw new IllegalStateException("Unknown home section");
    }
  }

  private void openIndex() {
    if (indexCards == null) {
      screen = Screen.INDEX;
      status = "Index workflows are unavailable in this session.";
      return;
    }
    selectDivider(selectedDivider);
    screen = Screen.INDEX;
  }

  private void openBibliography() {
    if (bibliography == null) {
      screen = Screen.BIB;
      status = "Bibliography workflows are unavailable in this session.";
      return;
    }
    refreshSources();
    screen = Screen.BIB;
  }

  private void refreshSources() {
    sources = bibliography.getSources();
    selectedSource = Math.max(0, Math.min(selectedSource, Math.max(0, sources.size() - 1)));
  }

  private void openSelectedSource() {
    if (sources.isEmpty()) return;
    currentSource = sources.get(selectedSource);
    screen = Screen.BIB_SOURCE;
  }

  private void openResearchCard() {
    var found = bibliography.findBibCardForSource(currentSource.id());
    if (found.isEmpty()) {
      status = "This source has no user-authored research BibCard. Press C to create one.";
      return;
    }
    currentBibCard = found.get();
    bibNotes = bibliography.getBibNotes(currentBibCard.id());
    showingBibBack = false;
    screen = Screen.BIB_CARD;
  }

  private static String citationLine(BibliographicSource source) {
    String creators =
        source.creatorsText().isBlank() ? "(creator not recorded)" : source.creatorsText();
    String year = source.publicationYear() == null ? "" : " (" + source.publicationYear() + ")";
    return creators
        + year
        + " — "
        + (source.title().isBlank() ? "(untitled source)" : source.title());
  }

  private void beginBibInput(BibInputStage stage) {
    if (bibliography == null) return;
    bibInputStage = stage;
    bibInputReturnScreen = screen;
    bibInput.setLength(0);
    screen = Screen.BIB_INPUT;
  }

  private String bibInputPrompt() {
    return switch (bibInputStage) {
      case IMPORT_PATH -> "Enter the path of a local .bib file to import.";
      case IMPORT_NAMESPACE ->
          "Enter a stable namespace for this source file; it scopes citation keys.";
      case CARD_GOAL -> "Enter your reading goal for this source.";
      case CARD_OVERVIEW -> "Enter your authored overview of the source.";
      case NOTE_TYPE -> "Choose locator kind: P page, T time, S section, O other.";
      case NOTE_LOCATOR -> "Enter the locator exactly as printed (for example xiv or 00:32:18).";
      case NOTE_TEXT -> "Enter your brief, user-authored BibNote.";
    };
  }

  private void handleBibInput(KeyStroke key) {
    if (key.getKeyType() == KeyType.Escape) {
      screen = bibInputReturnScreen;
      return;
    }
    if (key.getKeyType() == KeyType.Backspace) {
      if (!bibInput.isEmpty()) {
        int codePoint = bibInput.codePointBefore(bibInput.length());
        bibInput.delete(bibInput.length() - Character.charCount(codePoint), bibInput.length());
      }
      return;
    }
    if (key.getKeyType() == KeyType.Character
        && !key.isCtrlDown()
        && !key.isAltDown()
        && !Character.isISOControl(key.getCharacter())) {
      char value = key.getCharacter();
      if (bibInputStage == BibInputStage.NOTE_TYPE) {
        pendingLocatorType =
            switch (Character.toUpperCase(value)) {
              case 'P' -> BibNote.LocatorType.PAGE;
              case 'T' -> BibNote.LocatorType.TIME;
              case 'S' -> BibNote.LocatorType.SECTION;
              case 'O' -> BibNote.LocatorType.OTHER;
              default -> null;
            };
        if (pendingLocatorType == null) {
          status = "Choose P, T, S, or O for the authored locator kind.";
        } else {
          bibInputStage = BibInputStage.NOTE_LOCATOR;
          status = "";
        }
      } else {
        bibInput.append(value);
        status = "";
      }
      return;
    }
    if (key.getKeyType() != KeyType.Enter) return;
    try {
      switch (bibInputStage) {
        case IMPORT_PATH -> readBibTeXFile();
        case IMPORT_NAMESPACE -> {
          pendingBibNamespace = requiredInput(bibInput.toString(), "Namespace");
          bibImportResult =
              bibliography.dryRunImport(pendingBibNamespace, pendingBibSourceName, pendingBibText);
          bibImportApplied = false;
          screen = Screen.BIB_IMPORT_PREVIEW;
        }
        case CARD_GOAL -> {
          pendingReadingGoal = requiredInput(bibInput.toString(), "Reading goal");
          bibInputStage = BibInputStage.CARD_OVERVIEW;
          bibInput.setLength(0);
        }
        case CARD_OVERVIEW -> {
          String overview = requiredInput(bibInput.toString(), "Source overview");
          currentBibCard =
              bibliography.createBibCard(currentSource.id(), pendingReadingGoal, overview);
          bibNotes = List.of();
          showingBibBack = false;
          screen = Screen.BIB_CARD;
          status = "User-authored research BibCard created.";
        }
        case NOTE_TYPE -> status = "Choose P, T, S, or O for the authored locator kind.";
        case NOTE_LOCATOR -> {
          pendingLocator = requiredInput(bibInput.toString(), "Locator");
          bibInputStage = BibInputStage.NOTE_TEXT;
          bibInput.setLength(0);
        }
        case NOTE_TEXT -> {
          String text = requiredInput(bibInput.toString(), "BibNote text");
          bibliography.addBibNote(currentBibCard.id(), pendingLocatorType, pendingLocator, text);
          bibNotes = bibliography.getBibNotes(currentBibCard.id());
          showingBibBack = true;
          screen = Screen.BIB_CARD;
          status = "BibNote appended.";
        }
      }
    } catch (IOException | RuntimeException exception) {
      status =
          exception.getMessage() == null
              ? "Bibliography operation could not be completed."
              : exception.getMessage();
    }
  }

  private void readBibTeXFile() throws IOException {
    Path path = Path.of(requiredInput(bibInput.toString(), "BibTeX file path"));
    if (!Files.isRegularFile(path))
      throw new IllegalArgumentException("Path is not a regular file.");
    if (Files.size(path) > 16L * 1024 * 1024)
      throw new IllegalArgumentException("BibTeX file exceeds the 16 MiB import limit.");
    byte[] content = Files.readAllBytes(path);
    try {
      pendingBibText =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
              .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
              .decode(java.nio.ByteBuffer.wrap(content))
              .toString();
    } catch (java.nio.charset.CharacterCodingException exception) {
      throw new IllegalArgumentException("BibTeX file is not valid UTF-8.", exception);
    }
    pendingBibPath = path;
    pendingBibSourceName = path.getFileName().toString();
    bibInputStage = BibInputStage.IMPORT_NAMESPACE;
    bibInput.setLength(0);
  }

  private void applyBibImport() {
    if (bibImportApplied || bibImportResult == null) return;
    try {
      bibImportResult =
          bibliography.applyImport(pendingBibNamespace, pendingBibSourceName, pendingBibText);
      bibImportApplied = true;
      refreshSources();
      status =
          bibImportResult.status() == BibTeXImportResult.Status.APPLIED
              ? "BibTeX file applied atomically."
              : "BibTeX file rejected; no new sources were committed. Review the diagnostics.";
    } catch (RuntimeException exception) {
      status = safeMessage(exception);
    }
  }

  private static String requiredInput(String value, String label) {
    if (value.isBlank()) throw new IllegalArgumentException(label + " cannot be empty.");
    return value;
  }

  private void selectDivider(char divider) {
    selectedDivider = divider;
    indexLists = indexCards == null ? List.of() : indexCards.getListCards(divider);
    selectedIndexList = Math.min(selectedIndexList, Math.max(0, indexLists.size() - 1));
  }

  private void openSelectedIndexList() {
    if (indexLists.isEmpty()) return;
    ListIndexCard selected = indexLists.get(selectedIndexList);
    openIndexOwner(selected.id(), true);
    screen = Screen.INDEX_LIST_CARD;
  }

  private void openKeytermCards() {
    if (indexCards == null) return;
    keytermCards = indexCards.getKeytermCards();
    selectedKeyterm = Math.min(selectedKeyterm, Math.max(0, keytermCards.size() - 1));
    screen = Screen.KEYTERM_CARDS;
  }

  private void openSelectedKeyterm() {
    if (keytermCards.isEmpty()) return;
    openIndexOwner(keytermCards.get(selectedKeyterm).id(), false);
    screen = Screen.KEYTERM_CARD;
  }

  private void openIndexOwner(UUID ownerId, boolean listCard) {
    selectedIndexOwner = ownerId;
    selectedOwnerIsList = listCard;
    indexEntries = indexCards.getEntries(ownerId);
    selectedIndexEntry = 0;
  }

  private void moveIndexEntry(int direction) {
    if (indexEntries.isEmpty()) return;
    selectedIndexEntry =
        Math.max(0, Math.min(indexEntries.size() - 1, selectedIndexEntry + direction));
  }

  private void moveKeyterm(int direction) {
    if (keytermCards.isEmpty()) return;
    selectedKeyterm = Math.max(0, Math.min(keytermCards.size() - 1, selectedKeyterm + direction));
  }

  private void showSelectedEntryPointer() {
    if (indexEntries.isEmpty()) return;
    status =
        "Printed pointer: "
            + indexEntries.get(selectedIndexEntry).target().literal()
            + ". Open Main and type an address manually if this is an address.";
  }

  private void promoteSelectedEntry() {
    if (indexEntries.isEmpty()) return;
    try {
      KeytermIndexCard promoted =
          indexCards.promoteEntry(selectedIndexOwner, indexEntries.get(selectedIndexEntry).id());
      indexEntries = indexCards.getEntries(selectedIndexOwner);
      keytermCards = indexCards.getKeytermCards();
      status =
          "Promoted to dedicated keyterm card '"
              + promoted.keyterm()
              + "'; original entry retained.";
    } catch (RuntimeException exception) {
      status = safeMessage(exception);
    }
  }

  private void beginIndexInput(IndexInputStage stage) {
    if (indexCards == null) return;
    indexInputStage = stage;
    indexInputReturnScreen = screen;
    indexInput.setLength(0);
    screen = Screen.INDEX_INPUT;
  }

  private String indexInputPrompt() {
    return switch (indexInputStage) {
      case OVERFLOW -> "Enter an explicit overflow number for divider " + selectedDivider + ".";
      case ENTRY_TEXT -> "Enter the authored keyterm or entry text.";
      case ENTRY_TYPE -> "Choose target kind: M Main address, K keyterm card, S See Also.";
      case ENTRY_TARGET -> "Enter the target exactly as written; absent targets are allowed.";
    };
  }

  private void handleIndexInput(KeyStroke key) {
    if (key.getKeyType() == KeyType.Escape) {
      screen = indexInputReturnScreen;
      return;
    }
    if (key.getKeyType() == KeyType.Backspace) {
      if (!indexInput.isEmpty()) indexInput.deleteCharAt(indexInput.length() - 1);
      return;
    }
    if (key.getKeyType() == KeyType.Character
        && !key.isCtrlDown()
        && !key.isAltDown()
        && !Character.isISOControl(key.getCharacter())) {
      char value = key.getCharacter();
      if (indexInputStage == IndexInputStage.ENTRY_TYPE) {
        char kind = Character.toUpperCase(value);
        if (kind == 'M' || kind == 'K' || kind == 'S') {
          indexTargetKind = kind;
          indexInputStage = IndexInputStage.ENTRY_TARGET;
          indexInput.setLength(0);
        }
      } else {
        indexInput.append(value);
      }
      return;
    }
    if (key.getKeyType() != KeyType.Enter) return;
    try {
      if (indexInputStage == IndexInputStage.OVERFLOW) {
        int overflow = Integer.parseInt(indexInput.toString());
        if (overflow < 1) throw new IllegalArgumentException("Overflow number must be positive.");
        ListIndexCard created = indexCards.createListCard(selectedDivider, overflow);
        selectDivider(selectedDivider);
        selectedIndexList = indexLists.indexOf(created);
        openIndexOwner(created.id(), true);
        screen = Screen.INDEX_LIST_CARD;
        status = "Created " + selectedDivider + " (" + overflow + ").";
      } else if (indexInputStage == IndexInputStage.ENTRY_TEXT) {
        if (indexInput.isEmpty()) throw new IllegalArgumentException("Entry text cannot be empty.");
        indexAuthoredText = indexInput.toString();
        indexInput.setLength(0);
        indexInputStage = IndexInputStage.ENTRY_TYPE;
      } else if (indexInputStage == IndexInputStage.ENTRY_TYPE) {
        status = "Choose M, K, or S for the authored target kind.";
      } else {
        String literal = indexInput.toString();
        var target =
            switch (indexTargetKind) {
              case 'M' -> new MainAddressTarget(literal, null);
              case 'K' -> new KeytermTarget(literal, null);
              case 'S' -> new SeeAlsoTarget(literal);
              default -> throw new IllegalStateException("Unknown Index target kind");
            };
        indexCards.addEntry(selectedIndexOwner, indexAuthoredText, target);
        indexEntries = indexCards.getEntries(selectedIndexOwner);
        selectedIndexEntry = indexEntries.size() - 1;
        screen = indexInputReturnScreen;
        status = "Authored Index entry added in insertion order.";
      }
    } catch (RuntimeException exception) {
      status = safeMessage(exception);
    }
  }

  private void createDraft() {
    currentDraft = cards.createDraft(List.of());
    editor = new EditorBuffer(currentDraft.authoredBody(), ids);
    screen = Screen.EDITOR;
    status = "New draft saved on the Desk.";
  }

  private void openSelectedDraft() {
    refreshDrafts();
    if (drafts.isEmpty()) return;
    currentDraft = drafts.get(selectedDraft);
    editor = new EditorBuffer(currentDraft.authoredBody(), ids);
    screen = Screen.EDITOR;
    status = "";
  }

  private void beginSavedDraftDiscard() {
    refreshDrafts();
    if (drafts.isEmpty()) return;
    currentDraft = drafts.get(selectedDraft);
    screen = Screen.CONFIRM_SAVED_DRAFT_DISCARD;
  }

  private void handleSavedDraftDiscardConfirmation(KeyStroke key) {
    if (key.getKeyType() == KeyType.Escape
        || (key.getKeyType() == KeyType.Character
            && Character.toLowerCase(key.getCharacter()) == 'n')) {
      screen = Screen.DESK;
      return;
    }
    if (key.getKeyType() == KeyType.Character && Character.toLowerCase(key.getCharacter()) == 'y') {
      try {
        cards.discardDraft(currentDraft.id(), currentDraft.revision());
        status = "Saved Desk draft discarded and retained in the archive tombstone.";
        refreshDrafts();
        screen = Screen.DESK;
      } catch (RuntimeException exception) {
        status = safeMessage(exception);
        refreshDrafts();
        screen = Screen.DESK;
      }
    }
  }

  private void handleEditor(KeyStroke key) {
    EditorBuffer.Action action = editor.handle(key);
    if (action == EditorBuffer.Action.SAVE || action == EditorBuffer.Action.SAVE_AND_EXIT) {
      try {
        currentDraft = cards.editDraft(currentDraft.id(), currentDraft.revision(), editor.runs());
        editor.markSaved();
        status = "Draft saved.";
        if (action == EditorBuffer.Action.SAVE_AND_EXIT) {
          editor = null;
          screen = Screen.DESK;
          refreshDrafts();
        }
      } catch (RuntimeException exception) {
        status = safeMessage(exception);
      }
    } else if (action == EditorBuffer.Action.EXIT) {
      if (editor.dirty()) screen = Screen.CONFIRM_DISCARD;
      else {
        screen = Screen.DESK;
        editor = null;
        refreshDrafts();
      }
    }
  }

  private void handleDiscardConfirmation(KeyStroke key) {
    if (key.getKeyType() == KeyType.Escape
        || (key.getKeyType() == KeyType.Character
            && Character.toLowerCase(key.getCharacter()) != 'y'
            && Character.toLowerCase(key.getCharacter()) != 'n')) {
      screen = Screen.EDITOR;
    } else if (key.getKeyType() == KeyType.Character) {
      if (Character.toLowerCase(key.getCharacter()) == 'y') {
        try {
          currentDraft = cards.editDraft(currentDraft.id(), currentDraft.revision(), editor.runs());
          status = "Draft saved.";
        } catch (RuntimeException exception) {
          status = safeMessage(exception);
          screen = Screen.EDITOR;
          return;
        }
      } else if (Character.toLowerCase(key.getCharacter()) == 'n') {
        status = "Unsaved editor changes discarded; saved Desk draft remains.";
      }
      screen = Screen.DESK;
      editor = null;
      refreshDrafts();
    }
  }

  private void beginAddressPrompt(Screen promptScreen) {
    refreshDrafts();
    if (promptScreen == Screen.INSTALL_ADDRESS && drafts.isEmpty()) {
      status = "There is no Desk draft to file.";
      return;
    }
    screen = promptScreen;
    addressInput.setLength(0);
  }

  private void handleAddressPrompt(KeyStroke key) {
    if (key.getKeyType() == KeyType.Escape) {
      screen = screen == Screen.INSTALL_ADDRESS ? Screen.DESK : Screen.MAIN;
      return;
    }
    if (key.getKeyType() == KeyType.Backspace) {
      if (!addressInput.isEmpty()) addressInput.deleteCharAt(addressInput.length() - 1);
      return;
    }
    if (key.getKeyType() == KeyType.Character
        && !key.isCtrlDown()
        && !key.isAltDown()
        && !Character.isISOControl(key.getCharacter())) {
      addressInput.append(key.getCharacter());
      return;
    }
    if (key.getKeyType() != KeyType.Enter) return;
    if (screen == Screen.INSTALL_ADDRESS) {
      try {
        MainCard draft = drafts.get(selectedDraft);
        cards.installMainCard(draft.id(), draft.revision(), addressInput.toString());
        status = "Card filed at " + addressInput + ".";
        openDesk();
      } catch (AddressConflictException
          | IllegalArgumentException
          | IllegalStateException exception) {
        status = safeMessage(exception) + " Draft remains on the Desk.";
        screen = Screen.DESK;
        refreshDrafts();
      }
    } else {
      try {
        MainCard exact = cards.getMainByExactAddress(addressInput.toString()).orElse(null);
        if (exact == null) {
          status = "No Main card is filed at exactly '" + addressInput + "'.";
          return;
        }
        refreshInstalled();
        selectedMain = 0;
        for (int index = 0; index < installed.size(); index++) {
          if (installed.get(index).id().equals(exact.id())) {
            selectedMain = index;
            break;
          }
        }
        screen = Screen.MAIN;
        status = "Positioned at " + exact.addressLiteral() + ".";
      } catch (RuntimeException exception) {
        status = "Address could not be read: " + safeMessage(exception);
      }
    }
  }

  private List<String> insertionContext(String proposedAddress) {
    try {
      if (cards.getMainByExactAddress(proposedAddress).isPresent())
        return List.of("This exact address is already occupied.");
      OrderedNeighbors neighbors = cards.getInsertionNeighbors(proposedAddress);
      return List.of(
          "Previous physical card: "
              + neighbors.predecessor().map(MainCard::addressLiteral).orElse("none"),
          "Next physical card: "
              + neighbors.successor().map(MainCard::addressLiteral).orElse("none"));
    } catch (IllegalArgumentException exception) {
      return List.of("Enter a complete ANTINET_SLASH_V1 address to inspect drawer neighbors.");
    }
  }

  private void openMain() {
    refreshInstalled();
    selectedMain = 0;
    screen = Screen.MAIN;
  }

  private void moveMain(int direction) {
    refreshInstalled();
    if (installed.isEmpty()) return;
    selectedMain = Math.max(0, Math.min(installed.size() - 1, selectedMain + direction));
  }

  private void refreshDrafts() {
    drafts = cards.getDrafts();
    selectedDraft = Math.max(0, Math.min(selectedDraft, Math.max(0, drafts.size() - 1)));
  }

  private void refreshInstalled() {
    installed = cards.getInstalledInAddressOrder();
    selectedMain = Math.max(0, Math.min(selectedMain, Math.max(0, installed.size() - 1)));
  }

  private static String safeMessage(RuntimeException exception) {
    return exception.getMessage() == null
        ? "Operation could not be completed."
        : exception.getMessage();
  }
}
