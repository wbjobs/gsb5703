package com.gsb.tpc;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only write-ahead log. Every record is flushed and fsync'd before
 * the coordinator acts on it, so a crash at any point leaves a replayable
 * history. Format (one record per line, space separated):
 *
 *   BEGIN     &lt;txId&gt;
 *   ENLIST    &lt;txId&gt; &lt;participantIndex&gt;
 *   DECISION  &lt;txId&gt; COMMIT|ABORT
 *   ACK       &lt;txId&gt; &lt;participantIndex&gt;
 *   DONE      &lt;txId&gt;
 */
final class RecoveryLog {

    private static final String FILE_NAME = "coordinator.log";
    private static final String UTF_8 = "UTF-8";

    private final File file;
    private final FileOutputStream out;

    RecoveryLog(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create log directory: " + dir);
        }
        this.file = new File(dir, FILE_NAME);
        this.out = new FileOutputStream(file, true);
    }

    synchronized void append(String record) throws IOException {
        out.write(record.getBytes(UTF_8));
        out.write('\n');
        out.flush();
        out.getFD().sync();
    }

    synchronized List<String> readAll() throws IOException {
        List<String> records = new ArrayList<String>();
        if (!file.isFile()) {
            return records;
        }
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.length() > 0) {
                    records.add(line);
                }
            }
        } finally {
            reader.close();
        }
        return records;
    }

    synchronized void close() {
        try {
            out.close();
        } catch (IOException ignored) {
            // nothing sensible to do on close
        }
    }
}
