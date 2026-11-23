package com.flowdesk.identity.application;

/** Server-resolved identity; never constructed from HTTP parameters. */
public record Actor(long userId) { }
