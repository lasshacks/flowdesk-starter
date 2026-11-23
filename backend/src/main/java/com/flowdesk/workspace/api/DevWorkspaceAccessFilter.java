package com.flowdesk.workspace.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;

@Component
@Profile("dev-actor")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DevWorkspaceAccessFilter extends OncePerRequestFilter {
    private final ObjectMapper mapper;

    public DevWorkspaceAccessFilter(ObjectMapper mapper) { this.mapper = mapper; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !path.equals("/api/workspaces") && !path.startsWith("/api/workspaces/")
                && !path.startsWith("/api/workspace-invitations/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        // Only a numeric socket peer address is considered. Forwarded headers never select a peer or actor.
        String peer = request.getRemoteAddr();
        boolean loopback = peer != null && peer.matches("[0-9a-fA-F:.]+")
                && InetAddress.getByName(peer).isLoopbackAddress();
        if (!loopback) {
            response.setStatus(403);
            response.setContentType("application/problem+json");
            mapper.writeValue(response.getOutputStream(), WorkspaceErrors.problem(403));
            return;
        }
        chain.doFilter(request, response);
    }
}
