package com.winlator.core;

import java.util.ArrayDeque;

/** Keeps the last lines printed by Wine/Box64 so a diagnostic report never needs a re-run. */
public class GuestLogBuffer implements Callback<String> {
    private static final int MAX_LINES = 500;
    private final ArrayDeque<String> lines = new ArrayDeque<>(MAX_LINES);

    @Override
    public synchronized void call(String line) {
        if (lines.size() >= MAX_LINES) lines.pollFirst();
        lines.addLast(line.length() > 400 ? line.substring(0, 400) : line);
    }

    public synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) sb.append(line).append('\n');
        return sb.toString();
    }
}
