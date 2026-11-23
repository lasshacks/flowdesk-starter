package com.flowdesk.migration;
/** Explicit administrator action, excluded from the application artifact. */
public final class AppTaskArchiveAccessRunner {
    public static void main(String[] args) { AppReadAccessRunner.run(args, "taskArchive"); }
}
