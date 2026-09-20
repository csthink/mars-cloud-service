package com.mars.cloud.service.gateway.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * 测试用的假上游：一个 JDK 自带的 HTTP 服务器，模拟被路由到的业务服务。
 *
 * <p>用它而不是真 UPMS，是为了让契约测试离线、确定、不依赖注册中心；
 * 它返回什么由测试逐条规定，网关是否「原样透传」就能被精确断言。
 */
final class FakeUpstream {

    /** 业务服务成功时的信封样例：健康检查透传用。 */
    static final String HEALTH_BODY = "{\"status\":\"UP\"}";

    /** 业务服务自己产生的失败信封样例：网关必须原样透传，不得改写或二次包装。 */
    static final String UPSTREAM_ERROR_BODY =
            "{\"success\":false,\"code\":\"65001\",\"message\":\"UPMS 协议错误\"}";

    private static HttpServer server;

    private FakeUpstream() {
    }

    /** 首次调用时启动；供 {@code @DynamicPropertySource} 在上下文创建阶段拿端口。 */
    static synchronized int port() {
        if (server == null) {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException("假上游启动失败", e);
            }
            server.createContext("/upms/actuator/health", exchange -> respond(exchange, 200, HEALTH_BODY));
            server.createContext("/upms/v1/decision", exchange -> respond(exchange, 400, UPSTREAM_ERROR_BODY));
            server.createContext("/upms/slow", exchange -> {
                try {
                    Thread.sleep(2_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                respond(exchange, 200, HEALTH_BODY);
            });
            // 默认执行器是单线程：慢端点会拖住后续请求，换成线程池让各用例互不影响。
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
        }
        return server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
