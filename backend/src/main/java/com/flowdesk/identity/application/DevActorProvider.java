package com.flowdesk.identity.application;

import com.flowdesk.identity.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
@Profile("dev-actor")
public class DevActorProvider {
    private final UserRepository users;
    private final Environment environment;
    private String email;

    public DevActorProvider(UserRepository users, Environment environment) {
        this.users = users;
        this.environment = environment;
    }

    @PostConstruct
    void validateConfiguration() {
        if (!"127.0.0.1".equals(environment.getProperty("server.address"))
                || !"none".equalsIgnoreCase(environment.getProperty("server.forward-headers-strategy"))
                || environment.containsProperty("server.tomcat.remoteip.remote-ip-header")) {
            throw new IllegalStateException("Development actor requires loopback binding and disabled forwarded headers.");
        }
        email = environment.getProperty("FLOWDESK_DEV_ACTOR_EMAIL", "").trim().toLowerCase(Locale.ROOT);
        if (email.isEmpty() || users.findByEmail(email).isEmpty()) {
            throw new IllegalStateException("Development actor requires an existing configured user.");
        }
    }

    public Actor current() {
        return users.findByEmail(email).map(user -> new Actor(user.getId()))
                .orElseThrow(ActorUnavailableException::new);
    }
}
