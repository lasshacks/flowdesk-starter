package com.flowdesk.migration;

/** Explicit administrator entry point; excluded from application runtime. */
public final class AppWorkspaceRenameAccessRunner {
    public static void main(String[] args) { AppReadAccessRunner.run(args, "rename"); }
}
