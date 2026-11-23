package com.flowdesk.migration;

/** Explicit administrator entry point; excluded from the application runtime. */
public final class AppWorkspaceCreateAccessRunner {
    public static void main(String[] args) { AppReadAccessRunner.run(args, true); }
}
