package com.flowdesk.migration;
/** Explicit administrator action, excluded from the application artifact. */
public final class AppProjectArchiveAccessRunner {
    public static void main(String[] args) { AppReadAccessRunner.run(args, "projectArchive"); }
}
