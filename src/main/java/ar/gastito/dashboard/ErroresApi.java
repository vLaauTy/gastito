package ar.gastito.dashboard;

import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import ar.gastito.movimiento.MovimientoEspecialException;

/** Los errores de la API como {"error": "..."}, con un mensaje que el dashboard puede mostrar tal cual. */
@RestControllerAdvice(basePackageClasses = DashboardController.class)
class ErroresApi {

    private static final Logger log = LoggerFactory.getLogger(ErroresApi.class);

    record Error(String error) {
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Error> invalido(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new Error(e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Error> parametro(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest().body(new Error("Parámetro inválido: " + e.getName()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Error> cuerpo(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(new Error("El pedido no tiene el formato esperado"));
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Error> noExiste(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new Error("No existe"));
    }

    /** Una juntada, una cuota, un fijo: el mensaje explica qué se puede hacer y se muestra tal cual. */
    @ExceptionHandler(MovimientoEspecialException.class)
    ResponseEntity<Error> especial(MovimientoEspecialException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Error(e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Error> conflicto(IllegalStateException e) {
        log.debug("Conflicto en la API: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new Error("No se puede: ya no está guardado"));
    }
}
