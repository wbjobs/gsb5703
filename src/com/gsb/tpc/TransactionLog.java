package com.gsb.tpc;

import java.util.List;

/**
 * Durable write-ahead log used by the coordinator to survive crashes.
 * Implementations must make every appended record durable (flushed and
 * synced) before returning.
 */
public interface TransactionLog {

    /**
     * Append a record and make it durable before returning.
     * Throws {@link java.io.UncheckedIOException} if the record cannot be
     * made durable.
     */
    void append(String record);

    /** All records ever appended, in append order (including pre-crash ones). */
    List<String> records();
}
