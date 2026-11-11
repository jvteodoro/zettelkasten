package org.zettelkasten.tui;

import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.zettelkasten.application.IndexService;
import org.zettelkasten.application.MainCardService;

/** Lanterna event loop for the analog-first box shell. */
public final class TuiApplication {
  private final TuiController controller;
  private final TerminalRenderer renderer;

  public TuiApplication(MainCardService cards, Supplier<UUID> ids) {
    this(new TuiController(cards, ids), new TerminalRenderer());
  }

  public TuiApplication(MainCardService cards, IndexService indexCards, Supplier<UUID> ids) {
    this(new TuiController(cards, indexCards, ids), new TerminalRenderer());
  }

  TuiApplication(TuiController controller, TerminalRenderer renderer) {
    this.controller = Objects.requireNonNull(controller, "controller");
    this.renderer = Objects.requireNonNull(renderer, "renderer");
  }

  public void run() throws IOException {
    Screen screen = new DefaultTerminalFactory().setInputTimeout(250).createScreen();
    run(screen);
  }

  void run(Screen screen) throws IOException {
    TerminalSession.run(
        screen,
        () -> {
          while (!controller.quitRequested()) {
            renderer.render(screen, controller);
            var key = screen.readInput();
            if (key != null) controller.handle(key);
          }
        });
  }
}
