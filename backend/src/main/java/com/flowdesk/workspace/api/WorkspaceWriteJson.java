package com.flowdesk.workspace.api;
import com.fasterxml.jackson.databind.JsonNode;
import com.flowdesk.common.api.StrictWriteJson;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
final class WorkspaceWriteJson {
    static JsonNode read(HttpServletRequest request)throws IOException{return StrictWriteJson.read(request,4096);}
    static void requireEmpty(HttpServletRequest request) throws IOException {
        if (!request.getParameterMap().isEmpty() || request.getInputStream().readNBytes(1).length != 0)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST);
    }
    private WorkspaceWriteJson() { }
}
