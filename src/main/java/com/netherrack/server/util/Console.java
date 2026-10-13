package com.netherrack.server.util;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;

/**
 * The console's prompt, where commands are typed, kept below whatever the server logs.
 * <p>
 * Lines are read through JLine's line reader, which reads each key itself rather than
 * leaving the terminal to collect a whole line, so it knows what's been typed so far. A line
 * logged while a command is being typed is printed above the prompt, and the prompt is drawn
 * again below it with the command as it was. Up and down go back through earlier commands.
 */
public final class Console {

    private static final String PROMPT = "> ";

    private static volatile LineReader reader;

    private Console() {
    }

    /** Opens the prompt on a terminal, or on one of its own if there's none to share. */
    public static void open(Terminal terminal) {
        LineReaderBuilder builder = LineReaderBuilder.builder();
        if (terminal != null) {
            builder.terminal(terminal);
        }
        reader = builder.build();
    }

    /**
     * The next line typed at the prompt, or null if there's no more input to read, as when
     * it's piped in and runs out. Ctrl+C throws UserInterruptException.
     */
    public static String readLine() {
        try {
            return reader.readLine(PROMPT);
        } catch (EndOfFileException e) {
            return null;
        }
    }

    /** Prints a line of output: above the prompt once there is one, so it's drawn again below. */
    static void print(String line) {
        LineReader current = reader;
        if (current != null) {
            current.printAbove(line);
        } else {
            System.out.println(line);
        }
    }
}
