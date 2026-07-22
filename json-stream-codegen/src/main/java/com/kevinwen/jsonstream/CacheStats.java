/*
 * Copyright (c) 2026 Kevin Wen.
 * Licensed under the MIT License. See LICENSE in the repository root.
 */
package com.kevinwen.jsonstream;

/** Snapshot of compilation-cache activity. */
public record CacheStats(
    long hits, long misses, long generations, long compilations, int cachedQueries) {}
