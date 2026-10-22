package ar.gastito.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.http.Cookie;

class AccesoDashboardTest {

    static final String TOKEN = "un-token-largo-de-prueba";
    static final String CELULAR = "192.168.0.50";
    static final String URL = "http://localhost:8090";

    private static AccesoDashboard filtro(String token) {
        return new AccesoDashboard(new DashboardProperties(URL, token),
                new MockEnvironment().withProperty("server.address", token == null ? "127.0.0.1" : "0.0.0.0"));
    }

    private static MockHttpServletRequest pedido(String desde, String host, String ruta) {
        MockHttpServletRequest pedido = new MockHttpServletRequest("GET", ruta);
        pedido.setRemoteAddr(desde);
        pedido.setServerName(host);
        return pedido;
    }

    private record Resultado(MockHttpServletResponse respuesta, boolean paso) {
    }

    private static Resultado pasar(AccesoDashboard filtro, MockHttpServletRequest pedido) throws Exception {
        MockHttpServletResponse respuesta = new MockHttpServletResponse();
        MockFilterChain cadena = new MockFilterChain();
        filtro.doFilter(pedido, respuesta, cadena);
        return new Resultado(respuesta, cadena.getRequest() != null);
    }

    // --- Al arrancar ---

    @Test
    void sinTokenNoSePuedeAbrirALaRed() {
        DashboardProperties sinToken = new DashboardProperties(URL, "");

        assertThatThrownBy(() -> AccesoDashboard.verificarDireccion("0.0.0.0", sinToken))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("GASTITO_DASHBOARD_TOKEN");
        assertThatThrownBy(() -> AccesoDashboard.verificarDireccion("192.168.0.10", sinToken))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AccesoDashboard.verificarDireccion(null, sinToken))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("todas las interfaces");
        assertThatCode(() -> AccesoDashboard.verificarDireccion("127.0.0.1", sinToken)).doesNotThrowAnyException();
        assertThatCode(() -> AccesoDashboard.verificarDireccion("localhost", sinToken)).doesNotThrowAnyException();
    }

    @Test
    void conTokenSePuedeAbrirALaRed() {
        assertThatCode(() -> AccesoDashboard.verificarDireccion("0.0.0.0", new DashboardProperties(URL, TOKEN)))
                .doesNotThrowAnyException();
    }

    // --- Pedidos ---

    @Test
    void desdeEstaPcPasaSinToken() throws Exception {
        assertThat(pasar(filtro(null), pedido("127.0.0.1", "localhost", "/api/resumen")).paso()).isTrue();
        assertThat(pasar(filtro(TOKEN), pedido("127.0.0.1", "127.0.0.1", "/")).paso()).isTrue();
        assertThat(pasar(filtro(TOKEN), pedido("0:0:0:0:0:0:0:1", "localhost", "/")).paso()).isTrue();
    }

    @Test
    void desdeLaRedSinTokenConfiguradoSeRechaza() throws Exception {
        Resultado r = pasar(filtro(null), pedido(CELULAR, "192.168.0.10", "/api/resumen"));

        assertThat(r.paso()).isFalse();
        assertThat(r.respuesta().getStatus()).isEqualTo(403);
    }

    @Test
    void conTokenConfiguradoYSinTokenEnElPedidoResponde401() throws Exception {
        Resultado api = pasar(filtro(TOKEN), pedido(CELULAR, "192.168.0.10", "/api/resumen"));
        Resultado pagina = pasar(filtro(TOKEN), pedido(CELULAR, "192.168.0.10", "/"));

        assertThat(api.paso()).isFalse();
        assertThat(api.respuesta().getStatus()).isEqualTo(401);
        assertThat(api.respuesta().getContentType()).startsWith("application/json");
        assertThat(api.respuesta().getContentAsString()).contains("\"error\"");
        assertThat(pagina.respuesta().getStatus()).isEqualTo(401);
        assertThat(pagina.respuesta().getContentAsString()).contains("?token=");
    }

    @Test
    void conElTokenEnElHeaderPasa() throws Exception {
        MockHttpServletRequest bien = pedido(CELULAR, "192.168.0.10", "/api/resumen");
        bien.addHeader("Authorization", "Bearer " + TOKEN);
        MockHttpServletRequest mal = pedido(CELULAR, "192.168.0.10", "/api/resumen");
        mal.addHeader("Authorization", "Bearer otro");

        assertThat(pasar(filtro(TOKEN), bien).paso()).isTrue();
        assertThat(pasar(filtro(TOKEN), mal).respuesta().getStatus()).isEqualTo(401);
    }

    @Test
    void elTokenEnLaUrlSeGuardaEnUnaCookieYSeSacaDeLaUrl() throws Exception {
        MockHttpServletRequest pedido = pedido(CELULAR, "192.168.0.10", "/");
        pedido.setQueryString("token=" + TOKEN + "&mes=2026-10");
        pedido.setParameter("token", TOKEN);

        Resultado r = pasar(filtro(TOKEN), pedido);

        assertThat(r.paso()).isFalse();
        assertThat(r.respuesta().getStatus()).isEqualTo(302);
        assertThat(r.respuesta().getHeader("Location")).isEqualTo("/?mes=2026-10");
        assertThat(r.respuesta().getHeader("Set-Cookie"))
                .contains(AccesoDashboard.COOKIE + "=" + TOKEN, "HttpOnly", "SameSite=Strict", "Path=/");
    }

    @Test
    void unTokenEquivocadoEnLaUrlNoGuardaCookie() throws Exception {
        MockHttpServletRequest pedido = pedido(CELULAR, "192.168.0.10", "/");
        pedido.setParameter("token", "otro");

        Resultado r = pasar(filtro(TOKEN), pedido);

        assertThat(r.respuesta().getStatus()).isEqualTo(401);
        assertThat(r.respuesta().getHeader("Set-Cookie")).isNull();
    }

    @Test
    void conLaCookiePasa() throws Exception {
        MockHttpServletRequest pedido = pedido(CELULAR, "192.168.0.10", "/api/gastos/por-dia");
        pedido.setCookies(new Cookie(AccesoDashboard.COOKIE, TOKEN));

        assertThat(pasar(filtro(TOKEN), pedido).paso()).isTrue();
    }

    @Test
    void unPedidoLocalConOtroHostSeTrataComoDeAfuera() throws Exception {
        // Una página que hizo apuntar su dominio a 127.0.0.1 (DNS rebinding) no ve los gastos.
        assertThat(pasar(filtro(null), pedido("127.0.0.1", "malicioso.example", "/api/resumen")).respuesta()
                .getStatus()).isEqualTo(403);
        assertThat(pasar(filtro(TOKEN), pedido("127.0.0.1", "malicioso.example", "/api/resumen")).respuesta()
                .getStatus()).isEqualTo(401);
    }
}
