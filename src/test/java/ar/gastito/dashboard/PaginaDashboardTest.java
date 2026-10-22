package ar.gastito.dashboard;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** La aplicación entera: la página, sus archivos, Chart.js desde el WebJar y la API con datos de H2. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaginaDashboardTest {

    @Autowired
    MockMvc mvc;

    @Test
    void sirveLaPaginaYSusArchivos() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("index.html"));
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/webjars/chart.js/dist/chart.umd.min.js")))
                .andExpect(content().string(containsString("Cargar ingreso")));
        mvc.perform(get("/css/app.css")).andExpect(status().isOk());
        mvc.perform(get("/js/app.js")).andExpect(status().isOk());
    }

    @Test
    void chartJsVieneDelJarSinInternet() throws Exception {
        mvc.perform(get("/webjars/chart.js/dist/chart.umd.min.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Chart")));
    }

    @Test
    void laApiAndaConLaBaseVacia() throws Exception {
        mvc.perform(get("/api/resumen").param("mes", "2026-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gastado").value(0.0))
                .andExpect(jsonPath("$.disponible").doesNotExist());
        mvc.perform(get("/api/catalogo")).andExpect(jsonPath("$.categorias.length()").value(13));
    }

    @Test
    void desdeLaRedSinTokenNoSeVeNada() throws Exception {
        mvc.perform(get("/api/resumen").with(pedido -> {
            pedido.setRemoteAddr("192.168.0.50");
            pedido.setServerName("192.168.0.10");
            return pedido;
        })).andExpect(status().isForbidden());
    }
}
