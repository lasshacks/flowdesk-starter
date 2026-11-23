package com.flowdesk.project.api;
import com.flowdesk.project.application.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Set;
@RestController @Profile("dev-actor")
@RequestMapping(value="/api/workspaces/{workspaceId}/projects",produces="application/json")
public class ProjectController {
    private final ProjectQueryService service;
    public ProjectController(ProjectQueryService service) { this.service = service; }

    @GetMapping
    public ProjectList list(@PathVariable String workspaceId, @RequestParam MultiValueMap<String,String> query) {
        keys(query, Set.of("page", "size", "includeArchived"));
        int page = (int) number(query.getFirst("page"), 0, 10000, "0");
        int size = (int) number(query.getFirst("size"), 1, 100, "20");
        String include = query.getFirst("includeArchived");
        if (include != null && !include.equals("true") && !include.equals("false")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return service.list(id(workspaceId), page, size, "true".equals(include));
    }

    @GetMapping("/{projectId}")
    public ProjectView detail(@PathVariable String workspaceId, @PathVariable String projectId,
                              @RequestParam MultiValueMap<String,String> query) {
        keys(query, Set.of());
        return service.detail(id(workspaceId), id(projectId));
    }

    static long version(String value) { return number(value, 0, Long.MAX_VALUE, ""); }

    static long id(String value) { return number(value, 1, Long.MAX_VALUE, ""); }

    static void keys(MultiValueMap<String,String> query, Set<String> allowed) {
        if (query.entrySet().stream().anyMatch(e -> !allowed.contains(e.getKey()) || e.getValue().size() != 1)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
    }

    private static long number(String value, long min, long max, String fallback) {
        String input = value == null ? fallback : value;
        try {
            if (!input.matches("0|[1-9][0-9]{0,18}")) throw new NumberFormatException();
            long result = Long.parseLong(input);
            if (result < min || result > max) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
    }
}
