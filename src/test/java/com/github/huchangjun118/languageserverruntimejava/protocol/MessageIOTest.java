package com.github.huchangjun118.languageserverruntimejava.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageIOTest {

    private static MessageIO reader(String raw) {
        return new MessageIO(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream());
    }

    @Test
    void readsWellFormedMessage() throws Exception {
        MessageIO io = reader("Content-Length: 2\r\n\r\n{}");
        JsonNode message = io.readMessage();
        assertTrue(message.isObject());
        assertNull(io.readMessage(), "clean EOF between messages returns null");
    }

    @Test
    void ignoresUnknownHeadersAndIsCaseInsensitive() throws Exception {
        MessageIO io = reader("content-length: 2\r\nContent-Type: application/vscode-jsonrpc; charset=utf-8\r\n\r\n{}");
        assertTrue(io.readMessage().isObject());
    }

    @Test
    void readsUtf8BodyByByteLength() throws Exception {
        String body = "{\"text\":\"héllo→世界\"}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        MessageIO io = reader("Content-Length: " + bytes.length + "\r\n\r\n" + body);
        assertEquals("héllo→世界", io.readMessage().get("text").asText());
    }

    @Test
    void rejectsHeaderWithoutColon() {
        MessageIO io = reader("Content-Length 2\r\n\r\n{}");
        ProtocolException e = assertThrows(ProtocolException.class, io::readMessage);
        assertTrue(e.getMessage().contains("malformed header"), e.getMessage());
    }

    @Test
    void rejectsNonNumericContentLength() {
        MessageIO io = reader("Content-Length: abc\r\n\r\n{}");
        ProtocolException e = assertThrows(ProtocolException.class, io::readMessage);
        assertTrue(e.getMessage().contains("invalid Content-Length"), e.getMessage());
    }

    @Test
    void rejectsMissingContentLength() {
        MessageIO io = reader("Content-Type: text/plain\r\n\r\n{}");
        ProtocolException e = assertThrows(ProtocolException.class, io::readMessage);
        assertTrue(e.getMessage().contains("missing Content-Length"), e.getMessage());
    }

    @Test
    void rejectsTruncatedBody() {
        MessageIO io = reader("Content-Length: 10\r\n\r\n{}");
        ProtocolException e = assertThrows(ProtocolException.class, io::readMessage);
        assertTrue(e.getMessage().contains("unexpected end of stream inside message body"),
                e.getMessage());
    }

    @Test
    void rejectsTruncatedHeaders() {
        MessageIO io = reader("Content-Length: 10");
        assertThrows(ProtocolException.class, io::readMessage);
    }

    @Test
    void recoversOnNextMessageAfterBadHeader() throws Exception {
        // A bad header line followed by a well-formed message: the reader must
        // fail the first message and still parse the second one.
        MessageIO io = reader("garbage-header\r\nContent-Length: 2\r\n\r\n{}");
        assertThrows(ProtocolException.class, io::readMessage);
        assertTrue(io.readMessage().isObject());
    }

    @Test
    void writesFramedMessage() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MessageIO io = new MessageIO(new ByteArrayInputStream(new byte[0]), out);
        io.writeMessage(com.github.huchangjun118.languageserverruntimejava.json.JsonSupport.MAPPER
                .readTree("{\"a\":1}"));
        String raw = out.toString(StandardCharsets.UTF_8);
        assertEquals("Content-Length: 7\r\n\r\n{\"a\":1}", raw);
    }
}
