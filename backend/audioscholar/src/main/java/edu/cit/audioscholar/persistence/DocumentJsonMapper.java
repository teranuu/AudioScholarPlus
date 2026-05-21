package edu.cit.audioscholar.persistence;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.Timestamp;

@Component
public class DocumentJsonMapper {

	private static final String TYPE_FIELD = "__audioscholarType";
	private static final String TIMESTAMP_TYPE = "timestamp";
	private static final String DATE_TYPE = "date";

	private final ObjectMapper objectMapper;

	public DocumentJsonMapper(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public String toJson(Map<String, Object> data) {
		try {
			return objectMapper.writeValueAsString(toJsonSafe(data));
		} catch (Exception e) {
			throw new IllegalArgumentException("Failed to serialize document data", e);
		}
	}

	public Map<String, Object> fromJson(String json) {
		try {
			Map<String, Object> raw = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
			});
			Object decoded = fromJsonSafe(raw);
			if (decoded instanceof Map<?, ?> decodedMap) {
				Map<String, Object> result = new LinkedHashMap<>();
				decodedMap.forEach((key, value) -> result.put(String.valueOf(key), value));
				return result;
			}
			return new LinkedHashMap<>();
		} catch (Exception e) {
			throw new IllegalArgumentException("Failed to deserialize document data", e);
		}
	}

	@SuppressWarnings("unchecked")
	private Object toJsonSafe(Object value) {
		if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
			return value;
		}
		if (value instanceof Timestamp timestamp) {
			Map<String, Object> typed = new LinkedHashMap<>();
			typed.put(TYPE_FIELD, TIMESTAMP_TYPE);
			typed.put("seconds", timestamp.getSeconds());
			typed.put("nanos", timestamp.getNanos());
			return typed;
		}
		if (value instanceof Date date) {
			Map<String, Object> typed = new LinkedHashMap<>();
			typed.put(TYPE_FIELD, DATE_TYPE);
			typed.put("millis", date.getTime());
			return typed;
		}
		if (value instanceof Enum<?> enumValue) {
			return enumValue.name();
		}
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> safe = new LinkedHashMap<>();
			map.forEach((key, mapValue) -> safe.put(String.valueOf(key), toJsonSafe(mapValue)));
			return safe;
		}
		if (value instanceof Iterable<?> iterable) {
			List<Object> safe = new ArrayList<>();
			iterable.forEach(item -> safe.add(toJsonSafe(item)));
			return safe;
		}
		Map<String, Object> converted = objectMapper.convertValue(value, new TypeReference<Map<String, Object>>() {
		});
		return toJsonSafe(converted);
	}

	@SuppressWarnings("unchecked")
	private Object fromJsonSafe(Object value) {
		if (value instanceof Map<?, ?> map) {
			Object type = map.get(TYPE_FIELD);
			if (TIMESTAMP_TYPE.equals(type)) {
				long seconds = numberValue(map.get("seconds")).longValue();
				int nanos = numberValue(map.get("nanos")).intValue();
				return Timestamp.ofTimeSecondsAndNanos(seconds, nanos);
			}
			if (DATE_TYPE.equals(type)) {
				long millis = numberValue(map.get("millis")).longValue();
				return new Date(millis);
			}
			Map<String, Object> decoded = new LinkedHashMap<>();
			map.forEach((key, mapValue) -> decoded.put(String.valueOf(key), fromJsonSafe(mapValue)));
			return decoded;
		}
		if (value instanceof List<?> list) {
			List<Object> decoded = new ArrayList<>();
			list.forEach(item -> decoded.add(fromJsonSafe(item)));
			return decoded;
		}
		return value;
	}

	private Number numberValue(Object value) {
		return value instanceof Number number ? number : 0;
	}
}
