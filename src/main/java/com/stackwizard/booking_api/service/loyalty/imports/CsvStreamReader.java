package com.stackwizard.booking_api.service.loyalty.imports;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Streaming RFC 4180 reader: UTF-8 with or without BOM, delimiter detected from the header line ({@code ,} {@code ;}
 * or tab, as Excel exports differ by locale), quoted fields with escaped quotes and line breaks. Memory stays
 * constant whatever the file size.
 */
public final class CsvStreamReader implements Closeable {
    private final PushbackReader in;
    private final char delimiter;
    private long recordNo;

    public CsvStreamReader(InputStream stream, Character forcedDelimiter) throws IOException {
        BufferedReader buffered = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8), 1 << 16);
        buffered.mark(1 << 16);
        int first = buffered.read();
        if (first != 0xFEFF) {
            buffered.reset();
            buffered.mark(1 << 16);
        } else {
            buffered.mark(1 << 16);
        }
        char detected = forcedDelimiter != null ? forcedDelimiter : detect(buffered);
        buffered.reset();
        this.in = new PushbackReader(buffered, 2);
        this.delimiter = detected;
    }

    public char delimiter() {
        return delimiter;
    }

    /** Next record, or {@code null} at end of file. Blank lines are skipped. */
    public List<String> next() throws IOException {
        while (true) {
            List<String> record = readRecord();
            if (record == null) {
                return null;
            }
            if (record.size() == 1 && record.get(0).isEmpty()) {
                continue;
            }
            recordNo++;
            return record;
        }
    }

    public long recordNo() {
        return recordNo;
    }

    private List<String> readRecord() throws IOException {
        int c = in.read();
        if (c == -1) {
            return null;
        }
        in.unread(c);
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean fieldStart = true;
        while (true) {
            c = in.read();
            if (c == -1) {
                fields.add(field.toString());
                return fields;
            }
            char ch = (char) c;
            if (quoted) {
                if (ch == '"') {
                    int n = in.read();
                    if (n == '"') {
                        field.append('"');
                    } else {
                        quoted = false;
                        if (n != -1) {
                            in.unread(n);
                        }
                    }
                } else {
                    field.append(ch);
                }
                continue;
            }
            if (ch == '"' && fieldStart) {
                quoted = true;
                fieldStart = false;
                continue;
            }
            if (ch == delimiter) {
                fields.add(field.toString());
                field.setLength(0);
                fieldStart = true;
                continue;
            }
            if (ch == '\r') {
                int n = in.read();
                if (n != '\n' && n != -1) {
                    in.unread(n);
                }
                fields.add(field.toString());
                return fields;
            }
            if (ch == '\n') {
                fields.add(field.toString());
                return fields;
            }
            field.append(ch);
            fieldStart = false;
        }
    }

    private static char detect(BufferedReader reader) throws IOException {
        String header = reader.readLine();
        if (header == null) {
            return ',';
        }
        int commas = count(header, ',');
        int semis = count(header, ';');
        int tabs = count(header, '\t');
        if (tabs > commas && tabs > semis) {
            return '\t';
        }
        return semis > commas ? ';' : ',';
    }

    private static int count(String s, char c) {
        int n = 0;
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"') {
                quoted = !quoted;
            } else if (ch == c && !quoted) {
                n++;
            }
        }
        return n;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
