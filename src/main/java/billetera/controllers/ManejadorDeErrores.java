package billetera.controllers;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import billetera.ConflictoIdempotenciaException;
import billetera.CuentaNoEncontradaException;
import billetera.FondosInsuficientesException;
import billetera.OperacionInvalidaException;

/**
 * Traduce los errores de negocio a códigos HTTP, para que quien llama sepa qué pasó
 * sin leer el texto. La respuesta usa el formato estándar "problem details" (RFC 9457):
 * {@code {"status": 422, "title": "...", "detail": "..."}}.
 */
@RestControllerAdvice
public class ManejadorDeErrores {

	@ExceptionHandler(CuentaNoEncontradaException.class)
	ProblemDetail cuentaNoEncontrada(CuentaNoEncontradaException e) {
		return problema(HttpStatus.NOT_FOUND, "Cuenta no encontrada", e.getMessage());
	}

	/** 422: el pedido está bien escrito, pero no se puede cumplir con el saldo actual. */
	@ExceptionHandler(FondosInsuficientesException.class)
	ProblemDetail fondosInsuficientes(FondosInsuficientesException e) {
		return problema(HttpStatus.UNPROCESSABLE_CONTENT, "Fondos insuficientes", e.getMessage());
	}

	/** 409: la llave ya se usó para otra operación. El cliente tiene un bug, no debe reintentar. */
	@ExceptionHandler(ConflictoIdempotenciaException.class)
	ProblemDetail conflictoIdempotencia(ConflictoIdempotenciaException e) {
		return problema(HttpStatus.CONFLICT, "Llave de idempotencia repetida", e.getMessage());
	}

	@ExceptionHandler(OperacionInvalidaException.class)
	ProblemDetail operacionInvalida(OperacionInvalidaException e) {
		return problema(HttpStatus.BAD_REQUEST, "Operación inválida", e.getMessage());
	}

	@ExceptionHandler(MissingRequestHeaderException.class)
	ProblemDetail faltaHeader(MissingRequestHeaderException e) {
		return problema(HttpStatus.BAD_REQUEST, "Falta un header",
				"Falta el header obligatorio " + e.getHeaderName());
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ProblemDetail cuerpoIlegible(HttpMessageNotReadableException e) {
		return problema(HttpStatus.BAD_REQUEST, "Cuerpo inválido",
				"El cuerpo del pedido no es un JSON válido. Los montos van en centavos, como número entero");
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ProblemDetail parametroInvalido(MethodArgumentTypeMismatchException e) {
		return problema(HttpStatus.BAD_REQUEST, "Parámetro inválido",
				"El valor de '" + e.getName() + "' no tiene el formato correcto");
	}

	private static ProblemDetail problema(HttpStatus estado, String titulo, String detalle) {
		ProblemDetail problema = ProblemDetail.forStatusAndDetail(estado, detalle);
		problema.setTitle(titulo);
		return problema;
	}

}
