package com.gsb.tpc;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A {@link TransactionLog} backed by a single append-only file. Every record
 * is one UTF-8 line; each append is flushed and fsynced before returning, so
 * a coordinator crash never loses a written decision.
 */
public final class FileTransactionLog implements TransactionLog {

    private final File file;

    public FileTransactionLog(File file) {
        this.file = file;
    }

    public FileTransactionLog(String path) {
        this(new File(path));
    }

    @Override
    public synchronized void append(String record) {
        try {
            File parent = file.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("cannot create log directory " + parent);
            }
            FileOutputStream out = new FileOutputStream(file, true);
            try {
                out.write(record.getBytes(StandardCharsets.UTF_8));
                out.write('\n');
                out.flush();
                out.getFD().sync();
            } finally {
                out.close();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to append to transaction log " + file, e);
        }
    }

    @Override
    public synchronized List<String> records() {
        if (!file.isFile()) {
            return Collections.emptyList();
        }
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            List<String> result = new ArrayList<String>(lines.size());
            for (String line : lines) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read transaction log " + file, e);
        }
    }
}
