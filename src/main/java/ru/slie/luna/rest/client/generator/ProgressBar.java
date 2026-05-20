package ru.slie.luna.rest.client.generator;

import java.io.PrintWriter;

public class ProgressBar {
    private final int barLength;
    private final int total;
    private final PrintWriter out;
    private final long startTime;

    public ProgressBar(PrintWriter out, int total) {
        this.barLength = 30;
        this.total = total;
        this.out = out;
        this.startTime = System.currentTimeMillis();
    }

    public synchronized void print(long count, String message) {
        double progressPercent = (double) count / total;
        int numChars = (int) (progressPercent * barLength);

        long elapsedTimeMs = System.currentTimeMillis() - startTime;
        double tasksPerSecond = 0.0;
        if (elapsedTimeMs > 0) {
            tasksPerSecond = (double) count / (elapsedTimeMs / 1000.0);
        }

        String progressChars = "█".repeat(numChars);
        String emptyChars = " ".repeat(barLength - numChars);
        out.printf("\r[%s%s] %d/%d (%d%%) | %.1f t/s | %s",
                progressChars, emptyChars,
                count, total,
                (int) (progressPercent * 100),
                tasksPerSecond,
                message);
        out.flush();
    }

    public void append(String message) {
        out.print(message);
        out.flush();
    }
}
