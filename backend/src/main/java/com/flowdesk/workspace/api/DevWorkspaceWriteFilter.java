package com.flowdesk.workspace.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

@Component
@Profile("dev-actor")
@ConditionalOnProperty(name = "flowdesk.dev.workspace-write-enabled", havingValue = "true")
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class DevWorkspaceWriteFilter extends OncePerRequestFilter {
    private final DevWorkspaceWriteSettings settings;
    private final ObjectMapper mapper;

    public DevWorkspaceWriteFilter(DevWorkspaceWriteSettings settings, ObjectMapper mapper) {
        this.settings = settings;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        boolean create = request.getMethod().equals("POST") && (path.equals("/api/workspaces")
                || path.matches("/api/workspaces/[^/]+/projects")
                || path.matches("/api/workspaces/[^/]+/projects/[^/]+/tasks")
                || path.matches("/api/workspaces/[^/]+/invitations")
                || path.equals("/api/workspace-invitations/accept")
                || path.matches("/api/workspaces/[^/]+/ownership-transfer"));
        boolean rename = request.getMethod().equals("PATCH") && request.getServletPath().startsWith("/api/workspaces/");
        boolean removeMember = request.getMethod().equals("DELETE")
                && (path.matches("/api/workspaces/[^/]+/members/[^/]+")
                || path.matches("/api/workspaces/[^/]+/invitations/[^/]+"));
        return !create && !rename && !removeMember;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var origins = Collections.list(request.getHeaders("Origin"));
        var sites = Collections.list(request.getHeaders("Sec-Fetch-Site"));
        if (origins.size() != 1 || !settings.allows(origins.getFirst()) || sites.size() > 1
                || sites.stream().anyMatch("cross-site"::equalsIgnoreCase)) {
            reject(response, 403); return;
        }
        if (request.getMethod().equals("DELETE")) {
            if (request.getContentLengthLong() > 0 || (request.getHeader("Content-Encoding") != null
                    && !request.getHeader("Content-Encoding").equalsIgnoreCase("identity"))) {
                reject(response, 415); return;
            }
        } else {
            try {
                var type = MediaType.parseMediaType(request.getContentType());
                if (!type.getType().equals("application") || !type.getSubtype().equals("json")
                        || (type.getCharset() != null && !type.getCharset().equals(StandardCharsets.UTF_8))
                        || (request.getHeader("Content-Encoding") != null && !request.getHeader("Content-Encoding").equalsIgnoreCase("identity"))) {
                    reject(response, 415); return;
                }
            } catch (IllegalArgumentException failure) { reject(response, 415); return; }
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, int status) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        mapper.writeValue(response.getOutputStream(), WorkspaceErrors.problem(status));
    }
}
