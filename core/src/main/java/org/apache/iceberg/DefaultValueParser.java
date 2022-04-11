/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iceberg;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.relocated.com.google.common.io.BaseEncoding;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;

public class DefaultValueParser {

  public static Object parseDefaultFromJson(Type type, JsonNode jsonNode) {

    switch (type.typeId()) {
      case BOOLEAN:
        return jsonNode.booleanValue();
      case INTEGER:
        return jsonNode.intValue();
      case LONG:
        return jsonNode.longValue();
      case FLOAT:
        return jsonNode.floatValue();
      case DOUBLE:
        return jsonNode.doubleValue();
      case DECIMAL:
        return jsonNode.decimalValue();
      case STRING:
      case UUID:
        return jsonNode.textValue();
      case DATE:
        return LocalDate.parse(jsonNode.textValue());
      case TIME:
        return LocalTime.parse(jsonNode.textValue());
      case TIMESTAMP:
        if (((Types.TimestampType) type).shouldAdjustToUTC()) {
          return OffsetDateTime.parse(jsonNode.textValue());
        } else {
          return LocalDateTime.parse(jsonNode.textValue());
        }
      case FIXED:
        byte[] fixedBytes = BaseEncoding.base16().decode(jsonNode.textValue().toUpperCase(Locale.ROOT).replaceFirst(
            "^0X",
            ""));
        return ByteBuffer.allocate(((Types.FixedType) type).length()).put(fixedBytes);
      case BINARY:
        byte[] binaryBytes = BaseEncoding.base16().decode(jsonNode.textValue().toUpperCase(Locale.ROOT).replaceFirst(
            "^0X", ""));
        return ByteBuffer.wrap(binaryBytes);
      case LIST:
        List<Object> defaultList = new ArrayList<>();
        for (JsonNode element : jsonNode) {
          defaultList.add(parseDefaultFromJson(type.asListType().elementType(), element));
        }
        return defaultList;
      case MAP:
        Map<Object, Object> defaultMap = new HashMap<>();
        List<JsonNode> keysAndValues = StreamSupport
            .stream(jsonNode.spliterator(), false)
            .collect(Collectors.toList());
        JsonNode keys = keysAndValues.get(0);
        JsonNode values = keysAndValues.get(1);

        List<JsonNode> keyList = Lists.newArrayList(keys.iterator());
        List<JsonNode> valueList = Lists.newArrayList(values.iterator());

        for (int i = 0; i < keyList.size(); i++) {
          defaultMap.put(
              parseDefaultFromJson(type.asMapType().keyType(), keyList.get(i)),
              parseDefaultFromJson(type.asMapType().valueType(), valueList.get(i)));
        }
        return defaultMap;
      case STRUCT:
        Map<Integer, Object> defaultStruct = new HashMap<>();
        for (Types.NestedField subField : type.asStructType().fields()) {
          String fieldIdAsString = String.valueOf(subField.fieldId());
          Object value = jsonNode.has(fieldIdAsString) ? parseDefaultFromJson(
              subField.type(),
              jsonNode.get(fieldIdAsString)) : null;
          if (value != null) {
            defaultStruct.put(subField.fieldId(), value);
          }
        }
        return defaultStruct;
      default:
        return null;
    }
  }

  public static JsonNode validateDefault(String name, Type type, JsonNode defaultValue) {
    if (defaultValue != null && !isValidDefault(type, defaultValue)) {
      throw new ValidationException("Invalid default value for field %s: %s not a %s", name, defaultValue, type);
    }
    return defaultValue;
  }

  @SuppressWarnings("checkstyle:CyclomaticComplexity")
  public static boolean isValidDefault(Type type, JsonNode defaultValue) {
    if (defaultValue == null) {
      return false;
    }
    switch (type.typeId()) {
      case BOOLEAN:
        return defaultValue.isBoolean();
      case INTEGER:
        return defaultValue.isIntegralNumber() && defaultValue.canConvertToInt();
      case LONG:
        return defaultValue.isIntegralNumber() && defaultValue.canConvertToLong();
      case FLOAT:
      case DOUBLE:
      case DECIMAL:
        return defaultValue.isNumber();
      case STRING:
      case UUID:
      case DATE:
      case TIME:
      case TIMESTAMP:
        return defaultValue.isTextual();
      case FIXED:
      case BINARY:
        return defaultValue.isTextual() &&
            (defaultValue.textValue().startsWith("0x") || defaultValue.textValue().startsWith("0X"));
      case LIST:
        if (!defaultValue.isArray()) {
          return false;
        }
        for (JsonNode element : defaultValue) {
          if (!isValidDefault(type.asListType().elementType(), element)) {
            return false;
          }
        }
        return true;
      case MAP:
        if (!defaultValue.isArray()) {
          return false;
        }
        List<JsonNode> keysAndValues = StreamSupport
            .stream(defaultValue.spliterator(), false)
            .collect(Collectors.toList());
        if (keysAndValues.size() != 2) {
          return false;
        }
        JsonNode keys = keysAndValues.get(0);
        JsonNode values = keysAndValues.get(1);
        if (!keys.isArray() || !values.isArray()) {
          return false;
        }
        List<JsonNode> keyList = Lists.newArrayList(keys.iterator());
        List<JsonNode> valueList = Lists.newArrayList(values.iterator());
        if (keyList.size() != valueList.size()) {
          return false;
        }
        for (int i = 0; i < keyList.size(); i++) {
          if (!isValidDefault(type.asMapType().keyType(), keyList.get(i)) ||
              !isValidDefault(type.asMapType().valueType(), valueList.get(i))) {
            return false;
          }
        }
        return true;
      case STRUCT:
        if (!defaultValue.isObject()) {
          return false;
        }
        for (Types.NestedField subType : type.asStructType().fields()) {
          String fieldId = String.valueOf(subType.fieldId());
          if (!isValidDefault(subType.type(), defaultValue.has(fieldId) ? defaultValue.get(fieldId) : null)) {
            return false;
          }
        }
        return true;
      default:
        return false;
    }
  }
}
