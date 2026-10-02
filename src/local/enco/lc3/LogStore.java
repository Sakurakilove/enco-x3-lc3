package local.enco.lc3;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** Private operation log. Exports are a consistent snapshot; the screen may show only its tail. */
final class LogStore {
    private final File file;
    LogStore(File file) { this.file = file; }
    synchronized void reset() throws IOException { try (FileOutputStream out = new FileOutputStream(file)) { } }
    synchronized void append(String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file, true)) { out.write(text.getBytes("UTF-8")); }
    }
    synchronized byte[] snapshot() throws IOException {
        if (!file.exists()) return new byte[0];
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }
}
