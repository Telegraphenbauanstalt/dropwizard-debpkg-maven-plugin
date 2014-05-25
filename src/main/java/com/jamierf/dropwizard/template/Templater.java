package com.jamierf.dropwizard.template;

import com.jamierf.dropwizard.template.mustache.MustacheTemplater;

import java.io.*;

public abstract class Templater {

    public static Templater getDefault() {
        return new MustacheTemplater();
    }

    public abstract void execute(Reader input, Writer output, String name, Object parameters) throws IOException;

    public String execute(String input, String name, Object parameters) throws IOException {
        if (input == null) {
            return null;
        }

        try (final Reader reader = new StringReader(input)) {
            final StringWriter writer = new StringWriter();
            execute(reader, writer, name, parameters);
            return writer.toString();
        }
    }
}
