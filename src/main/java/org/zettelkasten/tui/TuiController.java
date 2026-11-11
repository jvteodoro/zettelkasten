package org.zettelkasten.tui;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.zettelkasten.application.AddressConflictException;
import org.zettelkasten.application.IndexService;
import org.zettelkasten.application.MainCardService;
import org.zettelkasten.application.OrderedNeighbors;
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
    INDEX_LIST_CARD,
    KEYTERM_CARDS,
    KEYTERM_CARD,
    INDEX_INPUT
  }

  private enum IndexInputStage {
    OVERFLOW,
    ENTRY_TEXT,
    ENTRY_TYPE,
    ENTRY_TARGET
  }

  private final MainCardService cards;
  private final Supplier<UUID> ids;
  private final IndexService indexCards;
  private Screen screen = Screen.HOME;
  private List<MainCard> drafts = List.of();
  private List<MainCard> installed = List.of();
  private int selectedDraft;
  private int selectedMain;
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

  public TuiController(MainCardService cards, Supplier<UUID> ids) {
    this(cards, null, ids);
  }

  public TuiController(MainCardService cards, IndexService indexCards, Supplier<UUID> ids) {
    this.cards = Objects.requireNonNull(cards, "cards");
    this.indexCards = indexCards;
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
    if (screen == Screen.INSTALL_ADDRESS || screen == Screen.MAIN_ADDRESS) {
      handleAddressPrompt(key);
      return;
    }
    if (screen == Screen.INDEX_INPUT) {
      handleIndexInput(key);
      return;
    }
    if (key.getKeyType() == KeyType.Escape) {
      screen =
          switch (screen) {
            case INDEX_LIST_CARD -> Screen.INDEX;
            case KEYTERM_CARDS -> Screen.INDEX;
            case KEYTERM_CARD -> Screen.KEYTERM_CARDS;
            default -> Screen.HOME;
          };
      status = "";
      return;
    }
    if (key.getKeyType() == KeyType.Character && !key.isCtrlDown()) {
      char value = Character.toLowerCase(key.getCharacter());
      if (value == 'q') {
        quitRequested = true;
        return;
      }
      if (screen == Screen.HOME) {
        switch (value) {
          case 'd' -> openDesk();
          case 'm' -> openMain();
          case 'i' -> openIndex();
          case 'b' -> screen = Screen.BIB;
          case '?' -> screen = Screen.HELP;
          default -> {}
        }
      } else if (screen == Screen.DESK) {
        switch (value) {
          case 'n' -> createDraft();
          case 'f' -> beginAddressPrompt(Screen.INSTALL_ADDRESS);
          default -> {}
        }
      } else if (screen == Screen.MAIN) {
        switch (value) {
          case 'n' -> moveMain(1);
          case 'p' -> moveMain(-1);
          case 'g' -> beginAddressPrompt(Screen.MAIN_ADDRESS);
          default -> {}
        }
      } else if (screen == Screen.INDEX) {
        if (value >= 'a' && value <= 'z') selectDivider(value);
        else if (value == '+') beginIndexInput(IndexInputStage.OVERFLOW);
        else if (value == '*') openKeytermCards();
      } else if (screen == Screen.INDEX_LIST_CARD || screen == Screen.KEYTERM_CARD) {
        if (value == 'a') beginIndexInput(IndexInputStage.ENTRY_TEXT);
        else if (value == 'p' && screen == Screen.INDEX_LIST_CARD) promoteSelectedEntry();
        else if (value == 'n') moveIndexEntry(1);
        else if (value == 'u') moveIndexEntry(-1);
      } else if (screen == Screen.KEYTERM_CARDS) {
        if (value == 'u') moveKeyterm(-1);
        else if (value == 'n') moveKeyterm(1);
      }
      return;
    }
    if (key.getKeyType() == KeyType.Enter && screen == Screen.DESK) {
      openSelectedDraft();
    } else if (key.getKeyType() == KeyType.ArrowUp && screen == Screen.DESK) {
      selectedDraft = Math.max(0, selectedDraft - 1);
    } else if (key.getKeyType() == KeyType.ArrowDown && screen == Screen.DESK) {
      selectedDraft = Math.min(Math.max(0, drafts.size() - 1), selectedDraft + 1);
    } else if (screen == Screen.INDEX && key.getKeyType() == KeyType.ArrowLeft) {
      selectDivider((char) Math.max('A', selectedDivider - 1));
    } else if (screen == Screen.INDEX && key.getKeyType() == KeyType.ArrowRight) {
      selectDivider((char) Math.min('Z', selectedDivider + 1));
    } else if (screen == Screen.INDEX && key.getKeyType() == KeyType.ArrowUp) {
      selectedIndexList = Math.max(0, selectedIndexList - 1);
    } else if (screen == Screen.INDEX && key.getKeyType() == KeyType.ArrowDown) {
      selectedIndexList = Math.min(Math.max(0, indexLists.size() - 1), selectedIndexList + 1);
    } else if (screen == Screen.INDEX && key.getKeyType() == KeyType.Enter) {
      openSelectedIndexList();
    } else if (screen == Screen.INDEX_LIST_CARD && key.getKeyType() == KeyType.ArrowUp) {
      moveIndexEntry(-1);
    } else if (screen == Screen.INDEX_LIST_CARD && key.getKeyType() == KeyType.ArrowDown) {
      moveIndexEntry(1);
    } else if (screen == Screen.INDEX_LIST_CARD && key.getKeyType() == KeyType.Enter) {
      showSelectedEntryPointer();
    } else if (screen == Screen.KEYTERM_CARDS && key.getKeyType() == KeyType.ArrowUp) {
      moveKeyterm(-1);
    } else if (screen == Screen.KEYTERM_CARDS && key.getKeyType() == KeyType.ArrowDown) {
      moveKeyterm(1);
    } else if (screen == Screen.KEYTERM_CARDS && key.getKeyType() == KeyType.Enter) {
      openSelectedKeyterm();
    } else if (screen == Screen.KEYTERM_CARD && key.getKeyType() == KeyType.ArrowUp) {
      moveIndexEntry(-1);
    } else if (screen == Screen.KEYTERM_CARD && key.getKeyType() == KeyType.ArrowDown) {
      moveIndexEntry(1);
    } else if (screen == Screen.KEYTERM_CARD && key.getKeyType() == KeyType.Enter) {
      showSelectedEntryPointer();
    }
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
        lines.add("D  Desk — unfiled Main drafts");
        lines.add("M  Main — browse installed cards by physical order");
        lines.add("I  Index — manually authored entry cards");
        lines.add("B  Bib — sources and research cards");
        lines.add("Q  Quit");
      }
      case DESK -> {
        lines.add("Drafts: " + drafts.size() + "    N New    Enter Edit    F File");
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
        if (editor.mode() != EditorBuffer.Mode.TEXT)
          lines.add(editor.promptLabel() + editor.promptText());
        if (!editor.validationMessage().isEmpty()) lines.add(editor.validationMessage());
        lines.add("Ctrl+L card reference  Ctrl+K keyterm  Ctrl+E ExRef  Ctrl+S Save  Esc Back");
      }
      case INSTALL_ADDRESS -> {
        lines.add("Choose a numeric-alpha address manually. No address suggestions are shown.");
        lines.add("Address: " + addressInput);
        if (!addressInput.isEmpty()) lines.addAll(insertionContext(addressInput.toString()));
        lines.add("Enter Install    Esc Cancel");
      }
      case MAIN -> {
        lines.add("Main — physical address order    G Exact address    N/P Adjacent card");
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
          lines.add("N/P move by filing order. Printed references are not selectable.");
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
                + "    ←/→ Divider    ↑/↓ List card    Enter Open");
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
        lines.add("↑/↓ Select entry    A Add entry    Enter Read pointer");
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
        lines.add("Dedicated keyterm cards — alphabetical    ↑/↓ Select    Enter Open");
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
        lines.add("Bibliography Box");
        lines.add("Bibliographic sources and authored research notes.");
        lines.add("Import and BibNote workflows are implemented in later project issues.");
      }
      case HELP -> {
        lines.add("Keyboard help");
        lines.add("Home: D Desk, M Main, I Index, B Bib, Q Quit");
        lines.add("Desk: N New draft, Enter Edit, F File at a manually chosen address");
        lines.add("Main: G exact address, N/P physical neighbors; printed links are not actions");
        lines.add("Index: A–Z divider; + overflow; * keyterms; A append entry; P promote");
        lines.add("Editor: Ctrl+S Save, Ctrl+L card reference, Ctrl+K keyterm, Ctrl+E ExRef");
        lines.add(
            "Esc returns or cancels a literal prompt. No search, suggestions, or link following.");
      }
      case CONFIRM_DISCARD -> {
        lines.add("Unsaved edits are in memory. Save them before returning to the Desk?");
        lines.add("Y Save    N Discard these unsaved edits    Esc Continue editing");
      }
    }
    return List.copyOf(lines);
  }

  private void openDesk() {
    refreshDrafts();
    screen = Screen.DESK;
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

  private void handleEditor(KeyStroke key) {
    EditorBuffer.Action action = editor.handle(key);
    if (action == EditorBuffer.Action.SAVE) {
      try {
        currentDraft = cards.editDraft(currentDraft.id(), currentDraft.revision(), editor.runs());
        editor = new EditorBuffer(currentDraft.authoredBody(), ids);
        status = "Draft saved.";
      } catch (RuntimeException exception) {
        status = safeMessage(exception);
      }
    } else if (action == EditorBuffer.Action.EXIT) {
      if (editor.dirty()) screen = Screen.CONFIRM_DISCARD;
      else {
        screen = Screen.DESK;
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
