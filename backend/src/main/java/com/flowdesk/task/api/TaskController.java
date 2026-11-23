package com.flowdesk.task.api;
import com.flowdesk.task.application.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;
@RestController @Profile("dev-actor")
@RequestMapping(value="/api/workspaces/{workspaceId}/projects/{projectId}/tasks",produces="application/json")
public class TaskController {
    private final TaskQueryService service;
    public TaskController(TaskQueryService service) { this.service = service; }

    @GetMapping
    public TaskList list(@PathVariable String workspaceId, @PathVariable String projectId, @RequestParam MultiValueMap<String,String> query) {
        keys(query, Set.of("page", "size", "includeArchived"));
        int page = (int) number(query.getFirst("page"), 0, 10000, "0");
        int size = (int) number(query.getFirst("size"), 1, 100, "20");
        String include=query.getFirst("includeArchived");
        if(include!=null&&!include.matches("true|false"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return service.list(id(workspaceId), id(projectId), page, size, "true".equals(include));
    }

    @GetMapping("/{taskId}")
    public TaskView detail(@PathVariable String workspaceId, @PathVariable String projectId, @PathVariable String taskId,
                              @RequestParam MultiValueMap<String,String> query) {
        keys(query, Set.of());
        return service.detail(id(workspaceId), id(projectId), id(taskId));
    }


    static long version(String value) { return number(value, 0, Long.MAX_VALUE, ""); }

    static long id(String value) { return number(value, 1, Long.MAX_VALUE, ""); }

    static Long nullableId(JsonNode value) {
        if(value==null || value.isNull()) return null;
        if(!value.isTextual())throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return id(value.textValue());
    }

    static LocalDate nullableDate(JsonNode value) {
        if(value==null || value.isNull())return null;
        if(!value.isTextual() || !value.textValue().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        try {
            var result=LocalDate.parse(value.textValue());
            if(result.getYear()<1)throw new DateTimeParseException("Invalid date",value.textValue(),0);
            return result;
        } catch(DateTimeParseException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST); }
    }

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
