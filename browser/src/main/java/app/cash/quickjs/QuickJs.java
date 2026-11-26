package app.cash.quickjs;

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Closeable;
import java.util.List;
import tsundoku.browser.BrowserScripts;

/**
 * Takes the place of AndroidCompat's QuickJs in the browser target only (first
 * on its classpath, and in its place in the slim jar): the same API extensions
 * are compiled against, with every script run by the browser's own JavaScript
 * engine through {@link BrowserScripts}. Results are translated exactly as
 * AndroidCompat's QuickJs translates them, a script's error included (a map
 * with "error", not a throw), so an extension sees the same values in a tab as
 * on a desktop.
 *
 * Binding a Java object into the script ({@link #set}) is not supported: the
 * script runs in another JavaScript context, with no way back into Java. A
 * script evaluated after a binding fails with a QuickJsException that says so.
 */
public final class QuickJs implements Closeable {
    private final ObjectMapper mapper = new ObjectMapper();
    private boolean bound;

    public static QuickJs create() {
        return new QuickJs();
    }

    public QuickJs() {}

    public Object evaluate(String script, String ignoredFileName) {
        return evaluate(script);
    }

    public Object evaluate(String script) {
        if (bound) {
            throw new QuickJsException("Binding Java objects into a script is not supported in the browser", null);
        }
        String answer = BrowserScripts.evaluate(script);
        try {
            JsonNode node = mapper.readTree(answer);
            if (node.has("error")) {
                return translateTypeFromJson(mapper.createObjectNode().put("error", node.get("error").asText()).toString());
            }
            JsonNode value = node.get("value");
            return value == null || value.isNull() ? null : translateTypeFromJson(value.toString());
        } catch (Exception e) {
            throw new QuickJsException("JSON parsing error: " + e.getMessage() + " for input: " + answer, e);
        }
    }

    private Object translateTypeFromJson(String json) throws Exception {
        return translateType(mapper.readValue(json, Object.class));
    }

    // AndroidCompat's QuickJs.translateType, unchanged.
    private Object translateType(Object obj) {
        if (obj instanceof Boolean) {
            return obj;
        } else if (obj instanceof Number) {
            Number num = (Number) obj;
            if (num.doubleValue() == num.intValue()) {
                return num.intValue();
            } else {
                return num.doubleValue();
            }
        } else if (obj instanceof String) {
            return obj;
        } else if (obj instanceof List) {
            List<?> list = (List<?>) obj;
            if (list.isEmpty()) {
                return new int[0];
            }
            Object first = list.get(0);
            if (first instanceof Boolean) {
                return list.stream().map(Boolean.class::cast).toArray(Boolean[]::new);
            } else if (first instanceof Number) {
                return list.stream().mapToDouble(o -> ((Number) o).doubleValue()).toArray();
            } else if (first instanceof String) {
                return list.toArray(new String[0]);
            } else {
                return list.toArray();
            }
        }
        return obj;
    }

    public byte[] compile(String sourceCode, String ignoredFileName) {
        return sourceCode.getBytes();
    }

    public Object execute(byte[] bytecode) {
        return evaluate(new String(bytecode));
    }

    public <T> void set(String name, Class<T> ignoredType, T object) {
        bound = true;
    }

    @Override
    public void close() {}
}
