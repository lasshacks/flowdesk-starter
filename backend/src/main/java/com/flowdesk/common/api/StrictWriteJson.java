package com.flowdesk.common.api;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

public final class StrictWriteJson {
    private static final ObjectReader JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public static JsonNode read(HttpServletRequest request,int limit) throws IOException {
        if (!request.getParameterMap().isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        byte[] bytes = request.getInputStream().readNBytes(limit+1);
        if (bytes.length > limit) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);

        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            var body = JSON.readTree(text);
            if (body == null || !body.isObject()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            }
            return body;
        } catch (JsonProcessingException | CharacterCodingException failure) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST); }
    }
    private StrictWriteJson() { }
}
