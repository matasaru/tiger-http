/*
 * Copyright (C) 2014 Square, Inc.
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
package linktiger.http.impl;

import okio.ByteString;

/** HTTP header: the name is an ASCII string, but the value can be UTF-8. */
public final class Header {

  /** Name in case-insensitive ASCII encoding. */
  public final ByteString name;
  /** Value in UTF-8 encoding. */
  public final ByteString value;

  // TODO: search for toLowerCase and consider moving logic here.
  public Header(String name, String value) {
    this(ByteString.encodeUtf8(name), ByteString.encodeUtf8(value));
  }

  public Header(ByteString name, String value) {
    this(name, ByteString.encodeUtf8(value));
  }

  public Header(ByteString name, ByteString value) {
    this.name = name;
    this.value = value;
  }

  @Override public boolean equals(Object other) {
    if (other instanceof Header that) {
      return this.name.equals(that.name) &&
              this.value.equals(that.value);
    }
    return false;
  }

  @Override public int hashCode() {
    int result = 17;
    result = 31 * result + name.hashCode();
    result = 31 * result + value.hashCode();
    return result;
  }

  @Override public String toString() {
    return Util.format("%s: %s", name.utf8(), value.utf8());
  }
}
