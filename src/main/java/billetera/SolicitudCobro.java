package billetera;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Lo que alguien pide para crear un cobro dividido. El constructor valida todo lo que
 * se puede validar sin mirar la base de datos.
 *
 * @param deudores en orden: el centavo que sobra del reparto se lo llevan los primeros
 */
public record SolicitudCobro(UUID cobradorId, long totalCentavos, String descripcion, List<UUID> deudores,
		String llaveIdempotencia) {

	static final int MAXIMO_DEUDORES = 50;

	public SolicitudCobro {
		if (llaveIdempotencia == null || llaveIdempotencia.isBlank()) {
			throw new OperacionInvalidaException("La llave de idempotencia es obligatoria");
		}
		if (llaveIdempotencia.length() > SolicitudTransaccion.LARGO_MAXIMO_LLAVE) {
			throw new OperacionInvalidaException(
					"La llave de idempotencia no puede superar " + SolicitudTransaccion.LARGO_MAXIMO_LLAVE + " caracteres");
		}
		if (cobradorId == null) {
			throw new OperacionInvalidaException("Hay que indicar quién cobra");
		}
		if (totalCentavos <= 0) {
			throw new OperacionInvalidaException("El total debe ser mayor que cero");
		}
		descripcion = descripcion == null ? "" : descripcion.strip();
		if (descripcion.isEmpty()) {
			throw new OperacionInvalidaException("La descripción es obligatoria (ej. 'La cena')");
		}
		if (descripcion.length() > SolicitudTransaccion.LARGO_MAXIMO_DESCRIPCION) {
			throw new OperacionInvalidaException(
					"La descripción no puede superar " + SolicitudTransaccion.LARGO_MAXIMO_DESCRIPCION + " caracteres");
		}
		if (deudores == null || deudores.isEmpty()) {
			throw new OperacionInvalidaException("Hay que indicar al menos una persona a quien cobrarle");
		}
		if (deudores.size() > MAXIMO_DEUDORES) {
			throw new OperacionInvalidaException("Un cobro no puede tener más de " + MAXIMO_DEUDORES + " personas");
		}
		if (deudores.stream().anyMatch(Objects::isNull)) {
			throw new OperacionInvalidaException("Hay una persona sin id en la lista");
		}
		if (new HashSet<>(deudores).size() != deudores.size()) {
			throw new OperacionInvalidaException("Una persona aparece dos veces en la lista");
		}
		if (deudores.contains(cobradorId)) {
			throw new OperacionInvalidaException("Quien cobra no puede estar en la lista: su parte ya la pagó");
		}
		if (totalCentavos < deudores.size()) {
			throw new OperacionInvalidaException("El total no alcanza para darle al menos 1 centavo a cada persona");
		}
		deudores = List.copyOf(deudores);
	}

}
