package org.zettelkasten.tui;

import com.googlecode.lanterna.screen.Screen;
import java.io.IOException;
import java.util.Objects;

/** Guarantees terminal stop and close even when the event loop fails. */
public final class TerminalSession {
  private TerminalSession() {}

  public static void run(Screen screen, ScreenTask eventLoop) throws IOException {
    Objects.requireNonNull(screen, "screen");
    Objects.requireNonNull(eventLoop, "eventLoop");
    Throwable failure = null;
    boolean started = false;
    try {
      screen.startScreen();
      started = true;
      eventLoop.run();
    } catch (IOException | RuntimeException | Error exception) {
      failure = exception;
      throw exception;
    } finally {
      try {
        if (started) screen.stopScreen();
      } catch (IOException stopFailure) {
        if (failure != null) failure.addSuppressed(stopFailure);
        else throw stopFailure;
      } finally {
        try {
          screen.close();
        } catch (IOException closeFailure) {
          if (failure != null) failure.addSuppressed(closeFailure);
          else throw closeFailure;
        }
      }
    }
  }

  @FunctionalInterface
  public interface ScreenTask {
    void run() throws IOException;
  }
}
