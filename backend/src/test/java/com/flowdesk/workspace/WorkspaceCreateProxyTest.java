package com.flowdesk.workspace;

import com.flowdesk.support.IsolatedReadDatabase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static com.flowdesk.workspace.WorkspaceReadApiTest.*;

@Tag("frontend-proxy")
@Testcontainers
class WorkspaceCreateProxyTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withDatabaseName("create_proxy").withUsername("postgres").withPassword(IsolatedReadDatabase.password());

    @Test void realVitePreservesOriginAndBackendDecidesWriteAccess() throws Exception {
        var database=new IsolatedReadDatabase(POSTGRES,true);database.grantCreate().success();database.grantRename().success();database.grantProject().success();database.grantProjectUpdate().success();database.grantTask().success();database.grantTaskUpdate().success();database.grantTaskStatusChange().success();database.grantTaskStatusHistoryRead().success();database.grantTaskAssigneeDueDate().success();
        try(var context=startFor(database,"dev-actor",WorkspaceCreateApiTest.options())) {
            int port=((ServletWebServerApplicationContext)context).getWebServer().getPort();
            var script=new ClassPathResource("verification/vite-proxy.mjs").getFile().getAbsolutePath();
            var builder=new ProcessBuilder("node",script,Path.of("../frontend").toAbsolutePath().normalize().toString(),"http://127.0.0.1:"+port)
                    .redirectErrorStream(true);
            builder.environment().keySet().removeIf(key->key.matches("(?i)(FLOWDESK_.*|POSTGRES_.*|SPRING_.*|DEBUG|FLYWAY_.*)"));
            var vite=builder.start();
            try {
                var ready=CompletableFuture.supplyAsync(()->{
                    try(var input=new BufferedReader(new InputStreamReader(vite.getInputStream(),StandardCharsets.UTF_8))) {
                        String line;while((line=input.readLine())!=null) {
                            if(line.matches("VITE_PORT=[0-9]+"))return Integer.parseInt(line.substring(10));
                        }
                        throw new IllegalStateException("Vite exited before readiness; run npm ci in frontend.");
                    } catch(java.io.IOException failure){throw new IllegalStateException("Vite startup failed.");}
                });
                int vitePort=ready.get(30,TimeUnit.SECONDS);
                var url=URI.create("http://127.0.0.1:"+vitePort+"/api/workspaces");
                var request=HttpRequest.newBuilder(url).version(java.net.http.HttpClient.Version.HTTP_1_1).timeout(Duration.ofSeconds(10))
                        .header("Origin","http://127.0.0.1:5173").header("Content-Type","application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"Proxy creation\"}")).build();
                var response=HTTP.send(request,HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(201);
                String id=body(response).path("id").asText();
                assertThat(body(response).path("version").asText()).isEqualTo("0");
                assertThat(database.query("SELECT count(*) FROM flowdesk.workspace_memberships m JOIN flowdesk.users u ON u.id=m.user_id WHERE m.workspace_id="+id+" AND m.role='OWNER' AND u.email='dave@example.test'")).isEqualTo("1");
                var renameUrl=URI.create(url+"/"+id);
                var rename=HttpRequest.newBuilder(renameUrl).version(java.net.http.HttpClient.Version.HTTP_1_1).timeout(Duration.ofSeconds(10))
                        .header("Origin","http://127.0.0.1:5173").header("Content-Type","application/json")
                        .method("PATCH",HttpRequest.BodyPublishers.ofString("{\"name\":\"Proxy rename\",\"version\":\"0\"}")).build();
                var renamed=HTTP.send(rename,HttpResponse.BodyHandlers.ofString());
                assertThat(renamed.statusCode()).isEqualTo(200);
                assertThat(body(renamed).path("version").asText()).isEqualTo("1");
                var blockedRename=HttpRequest.newBuilder(renameUrl).version(java.net.http.HttpClient.Version.HTTP_1_1).timeout(Duration.ofSeconds(10))
                        .header("Origin","http://evil.test").header("Content-Type","application/json")
                        .method("PATCH",HttpRequest.BodyPublishers.ofString("{\"name\":\"Blocked rename\",\"version\":\"1\"}")).build();
                problem(HTTP.send(blockedRename,HttpResponse.BodyHandlers.ofString()),403,"DEV_ACCESS_DENIED");
                assertThat(database.query("SELECT name FROM flowdesk.workspaces WHERE id="+id)).isEqualTo("Proxy rename");
                var projectUrl=URI.create(url+"/"+id+"/projects");
                for(String origin:new String[]{"http://127.0.0.1:5173","http://evil.test"}) {
                    var nested=HttpRequest.newBuilder(projectUrl).version(java.net.http.HttpClient.Version.HTTP_1_1).timeout(Duration.ofSeconds(10))
                            .header("Origin",origin).header("Content-Type","application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"Proxy project\"}")).build();
                    var projectResponse=HTTP.send(nested,HttpResponse.BodyHandlers.ofString());
                    if(origin.contains("evil"))problem(projectResponse,403,"DEV_ACCESS_DENIED");
                    else assertThat(projectResponse.statusCode()).isEqualTo(201);
                }
                assertThat(database.query("SELECT count(*) FROM flowdesk.projects WHERE workspace_id="+id)).isEqualTo("1");
                String projectId=database.query("SELECT id FROM flowdesk.projects WHERE workspace_id="+id);
                var projectDetailUrl=URI.create(projectUrl+"/"+projectId);
                for(String origin:new String[]{"http://127.0.0.1:5173","http://evil.test"}) {
                    var projectPatch=HttpRequest.newBuilder(projectDetailUrl).version(java.net.http.HttpClient.Version.HTTP_1_1).timeout(Duration.ofSeconds(10))
                            .header("Origin",origin).header("Content-Type","application/json")
                            .method("PATCH",HttpRequest.BodyPublishers.ofString("{\"name\":\"Proxy updated project\",\"description\":null,\"version\":\"0\"}")).build();
                    var result=HTTP.send(projectPatch,HttpResponse.BodyHandlers.ofString());
                    if(origin.contains("evil"))problem(result,403,"DEV_ACCESS_DENIED");
                    else {assertThat(result.statusCode()).isEqualTo(200);assertThat(body(result).path("version").asText()).isEqualTo("1");}
                }
                assertThat(database.query("SELECT name||'|'||version FROM flowdesk.projects WHERE id="+projectId)).isEqualTo("Proxy updated project|1");
                var taskUrl=URI.create(projectDetailUrl+"/tasks");
                for(String origin:new String[]{"http://127.0.0.1:5173","http://evil.test"}) {
                    var taskPost=HttpRequest.newBuilder(taskUrl).version(java.net.http.HttpClient.Version.HTTP_1_1).timeout(Duration.ofSeconds(10))
                            .header("Origin",origin).header("Content-Type","application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"Proxy Task\"}")).build();
                    var result=HTTP.send(taskPost,HttpResponse.BodyHandlers.ofString());
                    if(origin.contains("evil"))problem(result,403,"DEV_ACCESS_DENIED");
                    else {assertThat(result.statusCode()).isEqualTo(201);assertThat(body(result).path("status").asText()).isEqualTo("TODO");assertThat(body(result).path("priority").asText()).isEqualTo("MEDIUM");}
                }
                assertThat(database.query("SELECT count(*) FROM flowdesk.tasks WHERE project_id="+projectId)).isEqualTo("1");
                assertThat(database.query("SELECT version FROM flowdesk.projects WHERE id="+projectId)).isEqualTo("1");
                String taskId=database.query("SELECT id FROM flowdesk.tasks WHERE project_id="+projectId);
                for(String origin:new String[]{"http://127.0.0.1:5173","http://evil.test"}) {
                    var taskPatch=HttpRequest.newBuilder(URI.create(taskUrl+"/"+taskId)).version(java.net.http.HttpClient.Version.HTTP_1_1).timeout(Duration.ofSeconds(10))
                            .header("Origin",origin).header("Content-Type","application/json")
                            .method("PATCH",HttpRequest.BodyPublishers.ofString("{\"title\":\"Proxy updated Task\",\"description\":null,\"priority\":\"HIGH\",\"version\":\"0\"}")).build();
                    var result=HTTP.send(taskPatch,HttpResponse.BodyHandlers.ofString());
                    if(origin.contains("evil"))problem(result,403,"DEV_ACCESS_DENIED");
                    else {assertThat(result.statusCode()).isEqualTo(200);assertThat(body(result).path("version").asText()).isEqualTo("1");assertThat(body(result).path("status").asText()).isEqualTo("TODO");}
                }
                assertThat(database.query("SELECT title||'|'||priority||'|'||version FROM flowdesk.tasks WHERE id="+taskId)).isEqualTo("Proxy updated Task|HIGH|1");
                assertThat(database.query("SELECT version FROM flowdesk.projects WHERE id="+projectId)).isEqualTo("1");
                assertThat(database.query("SELECT count(*) FROM flowdesk.task_status_activities")).isEqualTo("0");
                for(String origin:new String[]{"http://127.0.0.1:5173","http://evil.test"}) {
                    var statusPatch=HttpRequest.newBuilder(URI.create(taskUrl+"/"+taskId+"/status")).version(java.net.http.HttpClient.Version.HTTP_1_1).timeout(Duration.ofSeconds(10))
                            .header("Origin",origin).header("Content-Type","application/json")
                            .method("PATCH",HttpRequest.BodyPublishers.ofString("{\"status\":\"DONE\",\"version\":\"1\"}")).build();
                    var result=HTTP.send(statusPatch,HttpResponse.BodyHandlers.ofString());
                    if(origin.contains("evil"))problem(result,403,"DEV_ACCESS_DENIED");
                    else {assertThat(result.statusCode()).isEqualTo(200);assertThat(body(result).path("version").asText()).isEqualTo("2");assertThat(body(result).path("status").asText()).isEqualTo("DONE");}
                }
                assertThat(database.query("SELECT count(*) FROM flowdesk.task_status_activities WHERE task_id="+taskId+" AND from_status='TODO' AND to_status='DONE' AND task_version=2")).isEqualTo("1");
                var historyRequest=HttpRequest.newBuilder(URI.create(taskUrl+"/"+taskId+"/status-activities?size=1"))
                        .version(java.net.http.HttpClient.Version.HTTP_1_1).timeout(Duration.ofSeconds(10))
                        .header("Accept","application/json").GET().build();
                var historyResponse=HTTP.send(historyRequest,HttpResponse.BodyHandlers.ofString());
                assertThat(historyResponse.statusCode()).isEqualTo(200);
                var historyPage=body(historyResponse);assertThat(historyPage.size()).isEqualTo(8);
                assertThat(historyPage.path("taskId").asText()).isEqualTo(taskId);
                assertThat(historyPage.path("items").size()).isEqualTo(1);
                assertThat(historyPage.path("items").get(0).path("taskVersion").asText()).isEqualTo("2");
                assertThat(historyPage.path("items").get(0).path("actor").path("displayName").asText()).isEqualTo("Dave");
                assertThat(historyPage.path("nextBeforeVersion").isNull()).isTrue();
                assertThat(historyResponse.body()).doesNotContain("email","example.test");
                var forbidden=HttpRequest.newBuilder(url).version(java.net.http.HttpClient.Version.HTTP_1_1).timeout(Duration.ofSeconds(10))
                        .header("Origin","http://evil.test").header("Content-Type","application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"Blocked proxy creation\"}")).build();
                problem(HTTP.send(forbidden,HttpResponse.BodyHandlers.ofString()),403,"DEV_ACCESS_DENIED");
                assertThat(database.query("SELECT count(*) FROM flowdesk.workspaces WHERE name='Blocked proxy creation'")).isEqualTo("0");
            } finally {
                if(vite.isAlive()) { vite.getOutputStream().write("close\n".getBytes(StandardCharsets.UTF_8));vite.getOutputStream().flush(); }
                if(!vite.waitFor(10,TimeUnit.SECONDS))vite.destroyForcibly().waitFor();
            }
        }
    }
}
