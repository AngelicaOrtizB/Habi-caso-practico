package billetera;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Una fila de la tabla cobros. */
public record Cobro(UUID id, UUID cobradorCuentaId, long totalCentavos, String descripcion, EstadoCobro estado,
		String llaveIdempotencia, OffsetDateTime creadoEn) {

}
