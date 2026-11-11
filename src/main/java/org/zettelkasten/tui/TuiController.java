package org.zettelkasten.tui;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.zettelkasten.application.AddressConflictException;
import org.zettelkasten.application.MainCardService;
import org.zettelkasten.application.OrderedNeighbors;
import org.zettelkasten.domain.BodyRun;
import org.zettelkasten.domain.MainCard;

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
    CONFIRM_DISCARD
  }

  private final MainCardService cards;
  private final Supplier<UUID> ids;
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

  public TuiController(MainCardService cards, Supplier<UUID> ids) {
    this.cards = Objects.requireNonNull(cards, "cards");
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
    if (key.getKeyType() == KeyType.Escape) {
      screen = Screen.HOME;
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
          case 'i' -> screen = Screen.INDEX;
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
      }
      return;
    }
    if (key.getKeyType() == KeyType.Enter && screen == Screen.DESK) {
      openSelectedDraft();
    } else if (key.getKeyType() == KeyType.ArrowUp && screen == Screen.DESK) {
      selectedDraft = Math.max(0, selectedDraft - 1);
    } else if (key.getKeyType() == KeyType.ArrowDown && screen == Screen.DESK) {
      selectedDraft = Math.min(Math.max(0, drafts.size() - 1), selectedDraft + 1);
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
        lines.add("Index Box");
        lines.add("Index entries are deliberate written pointers, kept in authored order.");
        lines.add("Open the printed address manually in Main. No global search or backlinks.");
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
