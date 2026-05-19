package ru.slie.luna.rest.client.generator;

import java.io.PrintWriter;

public class ProgressBar {
    private final int barLength;
    private final int total;
    private final PrintWriter out;

    public ProgressBar(PrintWriter out, int total) {
        this.barLength = 30;
        this.total = total;
        this.out = out;
    }

    public void clear() {
        out.printf("\r");
        out.flush();
    }

    public void print(int count, String message) {
        double progressPercent = (double) count / total;
        int numChars = (int) (progressPercent * barLength);

        String progressChars = "█".repeat(numChars);
        String emptyChars = " ".repeat(barLength - numChars);
        out.printf("\r[%s%s] %d/%d (%d%%) | %s",
                progressChars, emptyChars,
                count, total,
                (int) (progressPercent * 100), message);
        out.flush();
    }

    public void append(String message) {
        out.print(message);
        out.flush();
    }
}
