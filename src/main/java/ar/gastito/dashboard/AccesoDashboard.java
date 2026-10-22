package ar.gastito.dashboard;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Quién puede ver el dashboard. Por defecto el servidor escucha solo en 127.0.0.1 y nadie más de la red llega.
 * <ul>
 * <li>Para abrirlo al celular hay que escuchar en otra dirección (SERVER_ADDRESS) <b>y</b> definir un token
 * (GASTITO_DASHBOARD_TOKEN): sin token, la aplicación no arranca.</li>
 * <li>Los pedidos desde esta PC (a localhost) pasan sin token. Los demás traen el token como
 * {@code Authorization: Bearer}, en la cookie que se guarda al abrir {@code /?token=...} una vez, o responden 401.</li>
 * <li>Un pedido local con otro Host (una página que apunta su dominio a 127.0.0.1) se trata como uno de afuera.</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AccesoDashboard extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AccesoDashboard.class);

    static final String COOKIE = "gastito_token";
    static final String PARAMETRO = "token";
    private static final Duration DURACION_COOKIE = Duration.ofDays(30);
    private static final Set<String> HOSTS_LOCALES = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private final DashboardProperties config;

    public AccesoDashboard(DashboardProperties config, Environment entorno) {
        this.config = config;
        verificarDireccion(entorno.getProperty("server.address"), config);
    }

    /** Sin token, el servidor solo puede escuchar en una dirección local. */
    static void verificarDireccion(String direccion, DashboardProperties config) {
        if (!esLocal(direccion) && !config.tieneToken()) {
            throw new IllegalStateException("""
                    El dashboard quedaría abierto a la red (server.address=%s) sin token. \
                    Definí GASTITO_DASHBOARD_TOKEN en el .env, o sacá SERVER_ADDRESS para que escuche solo en 127.0.0.1."""
                    .formatted(direccion == null || direccion.isBlank() ? "todas las interfaces" : direccion));
        }
    }

    private static boolean esLocal(String direccion) {
        if (direccion == null || direccion.isBlank()) {
            return false; // sin server.address, Tomcat escucha en todas las interfaces
        }
        try {
            return InetAddress.getByName(direccion).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest pedido, HttpServletResponse respuesta, FilterChain cadena)
            throws ServletException, IOException {
        if (esDeEstaPc(pedido)) {
            cadena.doFilter(pedido, respuesta);
            return;
        }
        if (!config.tieneToken()) {
            log.warn("Pedido al dashboard desde afuera de la PC sin token configurado: rechazado");
            responder(pedido, respuesta, HttpStatus.FORBIDDEN, "El dashboard solo se puede abrir desde la PC.");
            return;
        }
        String deLaUrl = pedido.getParameter(PARAMETRO);
        if (deLaUrl != null && coincide(deLaUrl)) {
            // Se guarda en una cookie y se saca de la URL, así no queda en el historial ni en la barra.
            respuesta.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE, config.token())
                    .httpOnly(true).sameSite("Strict").path("/").maxAge(DURACION_COOKIE).build().toString());
            respuesta.setStatus(HttpStatus.FOUND.value());
            respuesta.setHeader(HttpHeaders.LOCATION, sinToken(pedido));
            return;
        }
        if (coincide(bearer(pedido)) || coincide(cookie(pedido))) {
            cadena.doFilter(pedido, respuesta);
            return;
        }
        responder(pedido, respuesta, HttpStatus.UNAUTHORIZED,
                "Falta el token. Abrí el link del dashboard con ?token=… una vez desde este dispositivo.");
    }

    /** Viene de esta PC y apunta a localhost (no a un dominio que alguien hizo apuntar a 127.0.0.1). */
    private static boolean esDeEstaPc(HttpServletRequest pedido) {
        try {
            if (!InetAddress.getByName(pedido.getRemoteAddr()).isLoopbackAddress()) {
                return false;
            }
        } catch (UnknownHostException e) {
            return false;
        }
        String host = pedido.getServerName();
        return host != null && HOSTS_LOCALES.contains(host.toLowerCase());
    }

    /** Compara en tiempo constante, para no dar pistas del token por cuánto tarda en responder. */
    private boolean coincide(String recibido) {
        if (recibido == null || recibido.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(recibido.getBytes(StandardCharsets.UTF_8),
                config.token().getBytes(StandardCharsets.UTF_8));
    }

    private static String bearer(HttpServletRequest pedido) {
        String header = pedido.getHeader(HttpHeaders.AUTHORIZATION);
        return header != null && header.startsWith("Bearer ") ? header.substring("Bearer ".length()).strip() : null;
    }

    private static String cookie(HttpServletRequest pedido) {
        Cookie[] cookies = pedido.getCookies();
        if (cookies == null) {
            return null;
        }
        return Arrays.stream(cookies).filter(c -> COOKIE.equals(c.getName())).map(Cookie::getValue).findFirst()
                .orElse(null);
    }

    /** La misma URL sin el parámetro del token. */
    private static String sinToken(HttpServletRequest pedido) {
        String query = pedido.getQueryString() == null ? "" : Arrays.stream(pedido.getQueryString().split("&"))
                .filter(p -> !p.equals(PARAMETRO) && !p.startsWith(PARAMETRO + "="))
                .collect(Collectors.joining("&"));
        return pedido.getRequestURI() + (query.isEmpty() ? "" : "?" + query);
    }

    private static void responder(HttpServletRequest pedido, HttpServletResponse respuesta, HttpStatus estado,
            String mensaje) throws IOException {
        respuesta.setStatus(estado.value());
        respuesta.setCharacterEncoding(StandardCharsets.UTF_8.name());
        if (pedido.getRequestURI().startsWith("/api/")) {
            respuesta.setContentType(MediaType.APPLICATION_JSON_VALUE);
            respuesta.getWriter().write("{\"error\":\"" + mensaje + "\"}");
        } else {
            respuesta.setContentType(MediaType.TEXT_PLAIN_VALUE);
            respuesta.getWriter().write(mensaje);
        }
    }
}
