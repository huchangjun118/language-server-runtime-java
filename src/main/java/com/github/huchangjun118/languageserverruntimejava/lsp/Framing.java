package com.github.huchangjun118.languageserverruntimejava.lsp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * LSP base-protocol framing: {@code Content-Length} headers plus a JSON body.
 *
 * <p>The reader is resilient: it scans the byte stream for the next
 * {@code Content-Length:} header (the pattern is matched anywhere, not only
 * at line starts), so malformed headers, garbage bytes and length mismatches
 * are reported as framing errors while subsequent valid messages are still
 * delivered. A framing error is surfaced as its own {@link ReadResult} and
 * never aborts the stream.
 */
public final class Framing {
    private Framing() {
    }

    /** Result of one read attempt: exactly one of body/error is non-null. */
    public record ReadResult(byte[] body, String framingError) {
        static ReadResult body(byte[] body) {
            return new ReadResult(body, null);
        }

        static ReadResult error(String error) {
            return new ReadResult(null, error);
        }
    }

    public static final class Reader {
        private static final String PATTERN = "content-length:";
        private final InputStream in;
        private final long maxMessageSize;
        /** Error to surface on the next read call. */
        private String pendingError;

        public Reader(InputStream in, long maxMessageSize) {
            this.in = in;
            this.maxMessageSize = maxMessageSize;
        }

        /** @return next message or framing error, or null on clean EOF. Never throws on malformed input. */
        public ReadResult read() throws IOException {
            if (pendingError != null) {
                String error = pendingError;
                pendingError = null;
                return ReadResult.error(error);
            }
            Long contentLength = null;
            while (contentLength == null) {
                String skipped = scanForContentLengthHeader();
                if (skipped == null) {
                    return null; // clean EOF at a message boundary
                }
                if (!skipped.isBlank()) {
                    noteError("skipped malformed bytes before header: " + abbrev(skipped));
                }
                // We are positioned right after "Content-Length:"; read its value line.
                String valueLine = readLine();
                if (valueLine == null) {
                    return ReadResult.error("unexpected end of stream after Content-Length header");
                }
                Long parsed = parseLength(valueLine.trim());
                if (parsed == null) {
                    noteError("invalid Content-Length value: " + abbrev(valueLine.trim()));
                    continue; // rescan for the next header
                }
                // Consume the rest of the header block up to the blank line.
                if (!consumeHeaderBlock()) {
                    return ReadResult.error("unexpected end of stream in message headers");
                }
                byte[] body = readFully(parsed);
                if (body == null) {
                    return ReadResult.error("truncated message body, expected " + parsed + " bytes");
                }
                contentLength = parsed;
                return ReadResult.body(body);
            }
            return null; // unreachable
        }

        private void noteError(String error) {
            if (pendingError == null) {
                pendingError = error;
            }
        }

        private Long parseLength(String value) {
            try {
                long len = Long.parseLong(value);
                return len >= 0 && len <= maxMessageSize ? len : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }

        /**
         * Scans the stream for the next case-insensitive "Content-Length:" pattern.
         *
         * @return the skipped bytes before the pattern (may be empty), or null on EOF
         */
        private String scanForContentLengthHeader() throws IOException {
            ByteArrayOutputStream skipped = new ByteArrayOutputStream();
            int matched = 0;
            int c;
            while ((c = in.read()) != -1) {
                char lower = Character.toLowerCase((char) c);
                if (lower == PATTERN.charAt(matched)) {
                    matched++;
                    if (matched == PATTERN.length()) {
                        return skipped.toString(StandardCharsets.UTF_8);
                    }
                } else {
                    // Re-emit partially matched prefix as skipped bytes.
                    for (int i = 0; i < matched; i++) {
                        skipped.write(PATTERN.charAt(i));
                    }
                    matched = lower == PATTERN.charAt(0) ? 1 : 0;
                    if (matched == 0) {
                        skipped.write(c);
                    }
                    if (skipped.size() > 4096) {
                        // Keep only the tail of the garbage for the error message.
                        byte[] bytes = skipped.toByteArray();
                        skipped.reset();
                        skipped.write(bytes, bytes.length - 256, 256);
                    }
                }
            }
            if (skipped.size() == 0) {
                return null; // clean EOF
            }
            noteError("unexpected end of stream after: " + abbrev(skipped.toString(StandardCharsets.UTF_8)));
            return null;
        }

        /** Reads header lines until the blank line terminating the block. False on EOF. */
        private boolean consumeHeaderBlock() throws IOException {
            String line;
            while ((line = readLine()) != null) {
                if (line.isEmpty()) {
                    return true;
                }
                // Other headers (Content-Type, ...) are accepted and ignored.
            }
            return false;
        }

        /** Reads one line terminated by \n (a trailing \r is stripped). Null on EOF. */
        private String readLine() throws IOException {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            int c;
            boolean any = false;
            while ((c = in.read()) != -1) {
                any = true;
                if (c == '\n') {
                    break;
                }
                buf.write(c);
            }
            if (!any && c == -1) {
                return null;
            }
            byte[] bytes = buf.toByteArray();
            int len = bytes.length;
            if (len > 0 && bytes[len - 1] == '\r') {
                len--;
            }
            return new String(bytes, 0, len, StandardCharsets.UTF_8);
        }

        private byte[] readFully(long n) throws IOException {
            ByteArrayOutputStream buf = new ByteArrayOutputStream((int) Math.min(n, 1 << 20));
            long remaining = n;
            byte[] chunk = new byte[8192];
            while (remaining > 0) {
                int read = in.read(chunk, 0, (int) Math.min(chunk.length, remaining));
                if (read == -1) {
                    return null;
                }
                buf.write(chunk, 0, read);
                remaining -= read;
            }
            return buf.toByteArray();
        }

        private static String abbrev(String s) {
            return s.length() <= 80 ? s : s.substring(0, 77) + "...";
        }
    }

    /** Thread-safe message writer. */
    public static final class Writer {
        private final OutputStream out;

        public Writer(OutputStream out) {
            this.out = out;
        }

        public synchronized void write(byte[] body) throws IOException {
            byte[] header = ("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
            out.write(header);
            out.write(body);
            out.flush();
        }
    }
}
