/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package linktiger.http;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A test from the <a href="https://github.com/w3c/web-platform-tests/tree/master/url">Web Platform
 * URL test suite</a>. Each test is a line of the file {@code urltestdata.txt}; the format is
 * informally specified by its JavaScript parser {@code urltestparser.js}; with which this class
 * attempts to be compatible.
 *
 * <p>Each line of the urltestdata.text file specifies a test. Lines look like this: <pre>   {@code
 *
 *   http://example\t.\norg http://example.org/foo/bar s:http h:example.org p:/
 * }</pre>
 */
public final class WebPlatformUrlTestData {
  String input;
  String base;
  String scheme = "";
  String username = "";
  String password = null;
  String host = "";
  String port = "";
  String path = "";
  String query = "";
  String fragment = "";

  public boolean expectParseFailure() {
    return scheme.isEmpty();
  }

  private void set(String name, String value) {
    switch (name) {
      case "s":
        scheme = value;
        break;
      case "u":
        username = value;
        break;
      case "pass":
        password = value;
        break;
      case "h":
        host = value;
        break;
      case "port":
        port = value;
        break;
      case "p":
        path = value;
        break;
      case "q":
        query = value;
        break;
      case "f":
        fragment = value;
        break;
      default:
        throw new IllegalArgumentException("unexpected attribute: " + value);
    }
  }

  @Override public String toString() {
    return Util.format("Parsing: <%s> against <%s>", input, base);
  }

  public static List<WebPlatformUrlTestData> load() throws IOException {
    List<WebPlatformUrlTestData> list = new ArrayList<>();

    @SuppressWarnings("DataFlowIssue")
    var lines = Files.readAllLines(Path.of(WebPlatformUrlTest.class.getResource("/web-platform-test-urltestdata.txt").getFile()));
    for (var line : lines) {
      if (line.isEmpty() || line.startsWith("#")) continue;

      int i = 0;
      String[] parts = line.split(" ");
      WebPlatformUrlTestData element = new WebPlatformUrlTestData();
      element.input = unescape(parts[i++]);

      String base = i < parts.length ? parts[i++] : null;
      element.base = (base == null || base.isEmpty())
          ? list.getLast().base
          : unescape(base);

      for (; i < parts.length; i++) {
        String piece = parts[i];
        if (piece.startsWith("#")) continue;
        String[] nameAndValue = piece.split(":", 2);
        element.set(nameAndValue[0], unescape(nameAndValue[1]));
      }

      list.add(element);
    }
    return list;
  }

  private static String unescape(String s) {
    var result = new StringBuilder();
    int i = 0;
    while (i < s.length()) {
      int c = s.codePointAt(i);
      if (c != '\\') {
        result.append((char) c);
      } else {
        i += Character.charCount(c);
        c = s.codePointAt(i);
        switch (c) {
          case '\\':
            result.append('\\');
            break;
          case '#':
            result.append('#');
            break;
          case 'n':
            result.append('\n');
            break;
          case 'r':
            result.append('\r');
            break;
          case 's':
            result.append(' ');
            break;
          case 't':
            result.append('\t');
            break;
          case 'f':
            result.append('\f');
            break;
          case 'u':
            result.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
            i += 4;
            break;
          default:
            throw new IllegalArgumentException("unexpected escape character in " + s);
        }
      }

      i += Character.charCount(c);
    }

    return result.toString();
  }
}
