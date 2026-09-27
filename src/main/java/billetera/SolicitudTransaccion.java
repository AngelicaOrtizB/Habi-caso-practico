package billetera;

import java.util.UUID;

/**
 * Lo que alguien pide mover: de qué cuenta, a cuál y cuánto. El constructor
 * valida todo lo que se puede validar sin mirar la base de datos.
 */
public record SolicitudTransaccion(TipoTransaccion tipo, String llaveIdempotencia, UUID cuentaOrigenId,
		UUID cuentaDestinoId, long montoCentavos, String descripcion) {

	static final int LARGO_MAXIMO_LLAVE = 100;
	static final int LARGO_MAXIMO_DESCRIPCION = 255;

	public SolicitudTransaccion {
		if (tipo == null) {
			throw new OperacionInvalidaException("El tipo de transacción es obligatorio");
		}
		if (llaveIdempotencia == null || llaveIdempotencia.isBlank()) {
			throw new OperacionInvalidaException("La llave de idempotencia es obligatoria");
		}
		if (llaveIdempotencia.length() > LARGO_MAXIMO_LLAVE) {
			throw new OperacionInvalidaException(
					"La llave de idempotencia no puede superar " + LARGO_MAXIMO_LLAVE + " caracteres");
		}
		if (cuentaOrigenId == null || cuentaDestinoId == null) {
			throw new OperacionInvalidaException("La cuenta de origen y la de destino son obligatorias");
		}
		if (cuentaOrigenId.equals(cuentaDestinoId)) {
			throw new OperacionInvalidaException("La cuenta de origen y la de destino deben ser distintas");
		}
		if (montoCentavos <= 0) {
			throw new OperacionInvalidaException("El monto debe ser mayor que cero");
		}
		// Se normaliza aquí para que un reintento con " La cena " y "La cena" sea el mismo pedido.
		if (descripcion != null) {
			descripcion = descripcion.strip();
			if (descripcion.isEmpty()) {
				descripcion = null;
			}
			else if (descripcion.length() > LARGO_MAXIMO_DESCRIPCION) {
				throw new OperacionInvalidaException(
						"La descripción no puede superar " + LARGO_MAXIMO_DESCRIPCION + " caracteres");
			}
		}
	}

}
