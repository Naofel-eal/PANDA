package io.devflow.application.support.port;

import java.util.Map;

public interface JsonCodec {

    String toJson(Object value);

    Map<String, Object> toMap(String value);

    <T> T fromJson(String value, Class<T> type);
}
