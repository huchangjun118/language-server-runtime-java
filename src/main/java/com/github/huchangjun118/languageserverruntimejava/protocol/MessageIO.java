package com.github.huchangjun118.languageserverruntimejava.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.huchangjun118.languageserverruntimejava.json.JsonSupport;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reads and writes LSP base protocol messages (Content-Length framed JSON)
 * on a pair of streams. Thread safe for concurrent writes.
 */
public class MessageIO {

    /** Upper bound for a single message, guards against absurd headers. */
    private static final int MAX_CONTENT_LENGTH = 256 * 1024 * 1024;

    private final InputStream in;
    private final OutputStream out;
    private final Object writeLock = new Object();

    public MessageIO(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
    }

    /**
     * Reads the next framed message.
     *
     * @return the parsed JSON body, or null on clean end of stream
     * @throws ProtocolException if the framing is malformed
     * @throws IOException      on I/O errors, including invalid JSON in the body
     */
    public JsonNode readMessage() throws IOException, ProtocolException {
        int contentLength = -1;
        boolean sawAnyHeaderByte = false;
        while (true) {
            String line = readHeaderLine();
            if (line == null) {
                if (!sawAnyHeaderByte) {
                    return null; // clean end of stream between messages
                }
                throw new ProtocolException("unexpected end of stream inside message headers");
            }
            sawAnyHeaderByte = true;
            if (line.isEmpty()) {
                break; // end of headers
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new ProtocolException("malformed header line: " + printable(line));
            }
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (name.equalsIgnoreCase("Content-Length")) {
                try {
                    contentLength = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw new ProtocolException("invalid Content-Length value: " + printable(value));
                }
                if (contentLength < 0 || contentLength > MAX_CONTENT_LENGTH) {
                    throw new ProtocolException("Content-Length out of range: " + contentLength);
                }
            }
            // Unknown headers (e.g. Content-Type) are ignored per base protocol.
        }
        if (contentLength < 0) {
            throw new ProtocolException("missing Content-Length header");
        }
        byte[] body = readFully(contentLength);
        return JsonSupport.MAPPER.readTree(body);
    }

    /** Writes a message with correct framing. Safe to call from many threads. */
    public void writeMessage(JsonNode body) throws IOException {
        byte[] payload = JsonSupport.MAPPER.writeValueAsBytes(body);
        byte[] header = ("Content-Length: " + payload.length + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        synchronized (writeLock) {
            out.write(header);
            out.write(payload);
            out.flush();
        }
    }

    /**
     * Reads one header line terminated by CRLF (a lone LF is also accepted).
     *
     * @return the line without the terminator, or null if EOF hit before any byte
     */
    private String readHeaderLine() throws IOException, ProtocolException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        boolean sawAny = false;
        while (true) {
            int b = in.read();
            if (b < 0) {
                return sawAny ? throwEof() : null;
            }
            sawAny = true;
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                buffer.write(b);
            }
            if (buffer.size() > 8192) {
                throw new ProtocolException("header line too long");
            }
        }
        return buffer.toString(StandardCharsets.US_ASCII);
    }

    private String throwEof() throws ProtocolException {
        throw new ProtocolException("unexpected end of stream inside message headers");
    }

    private byte[] readFully(int length) throws IOException, ProtocolException {
        byte[] body = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(body, offset, length - offset);
            if (read < 0) {
                throw new ProtocolException("unexpected end of stream inside message body: expected "
                        + length + " bytes, got " + offset);
            }
            offset += read;
        }
        return body;
    }

    private static String printable(String value) {
        return value.length() <= 80 ? "'" + value + "'" : "'" + value.substring(0, 80) + "...'";
    }
}
