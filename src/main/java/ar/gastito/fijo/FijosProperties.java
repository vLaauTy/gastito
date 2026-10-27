package ar.gastito.fijo;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** @param intervaloRecordatorio como máximo, cada cuánto se manda el recordatorio agrupado de fijos vencidos */
@ConfigurationProperties(prefix = "gastito.fijos")
public record FijosProperties(@DefaultValue("3d") Duration intervaloRecordatorio) {
}
