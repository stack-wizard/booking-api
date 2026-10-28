package com.stackwizard.booking_api.service.loyalty.imports;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsvStreamReaderTest {

    @Test
    void readsBomSemicolonQuotesAndMultilineFields() throws Exception {
        String csv = "﻿Ime;Prezime;Napomena\r\n"
                + "Marko;Čukon;\"Ima ; u tekstu\"\r\n"
                + "\r\n"
                + "Ana;\"O\"\"Neil\";\"dva\nreda\"\n"
                + "Iva;Horvat;";
        List<List<String>> rows = read(csv, null);
        assertThat(rows).containsExactly(
                List.of("Ime", "Prezime", "Napomena"),
                List.of("Marko", "Čukon", "Ima ; u tekstu"),
                List.of("Ana", "O\"Neil", "dva\nreda"),
                List.of("Iva", "Horvat", ""));
    }

    @Test
    void detectsCommaAndTab() throws Exception {
        assertThat(read("a,b\n1,2\n", null)).containsExactly(List.of("a", "b"), List.of("1", "2"));
        assertThat(read("a\tb\n1\t2\n", null)).containsExactly(List.of("a", "b"), List.of("1", "2"));
    }

    @Test
    void forcedDelimiterWins() throws Exception {
        assertThat(read("a;b,c\n1;2,3\n", ',')).containsExactly(List.of("a;b", "c"), List.of("1;2", "3"));
    }

    private static List<List<String>> read(String csv, Character delimiter) throws Exception {
        List<List<String>> out = new ArrayList<>();
        try (CsvStreamReader reader = new CsvStreamReader(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), delimiter)) {
            List<String> r;
            while ((r = reader.next()) != null) {
                out.add(r);
            }
        }
        return out;
    }
}
