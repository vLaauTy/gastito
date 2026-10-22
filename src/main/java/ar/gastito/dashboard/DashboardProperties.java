package ar.gastito.dashboard;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param token si está definido (GASTITO_DASHBOARD_TOKEN en el .env), los pedidos que no vienen de esta PC lo
 *              tienen que traer. Sin token, el dashboard solo se puede abrir desde la PC.
 */
@ConfigurationProperties(prefix = "gastito.dashboard")
public record DashboardProperties(String token) {

    public boolean tieneToken() {
        return token != null && !token.isBlank();
    }
}
