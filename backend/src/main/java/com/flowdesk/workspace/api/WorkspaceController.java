package com.flowdesk.workspace.api;

import com.flowdesk.workspace.application.WorkspaceList;
import com.flowdesk.workspace.application.WorkspaceMemberList;
import com.flowdesk.workspace.application.WorkspaceQueryService;
import com.flowdesk.workspace.application.WorkspaceView;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

@RestController
@Profile("dev-actor")
@RequestMapping(value = "/api/workspaces", produces = "application/json")
public class WorkspaceController {
    private final WorkspaceQueryService service;

    public WorkspaceController(WorkspaceQueryService service) { this.service = service; }

    @GetMapping
    public WorkspaceList list(@RequestParam MultiValueMap<String, String> query) {
        validateKeys(query, Set.of("page", "size"));
        int page = (int) number(query.getFirst("page"), 0, 10000, "0");
        int size = (int) number(query.getFirst("size"), 1, 100, "20");
        return service.list(page, size);
    }

    @GetMapping("/{workspaceId}")
    public WorkspaceView detail(@PathVariable String workspaceId, @RequestParam MultiValueMap<String, String> query) {
        validateKeys(query, Set.of());
        return service.detail(number(workspaceId, 1, Long.MAX_VALUE, ""));
    }

    @GetMapping("/{workspaceId}/members")
    public WorkspaceMemberList members(@PathVariable String workspaceId, @RequestParam MultiValueMap<String, String> query) {
        validateKeys(query, Set.of("page", "size"));
        long id = number(workspaceId, 1, Long.MAX_VALUE, "");
        int page = (int) number(query.getFirst("page"), 0, 10000, "0");
        int size = (int) number(query.getFirst("size"), 1, 100, "20");
        return service.members(id, page, size);
    }

    private static void validateKeys(MultiValueMap<String, String> query, Set<String> allowed) {
        if (query.entrySet().stream().anyMatch(entry -> !allowed.contains(entry.getKey()) || entry.getValue().size() != 1)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
    }

    static long number(String value, long min, long max, String fallback) {
        String input = value == null ? fallback : value;
        try {
            if (!input.matches("[0-9]{1,19}")) throw new NumberFormatException();
            long result = Long.parseLong(input);
            if (result < min || result > max) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
    }
}
