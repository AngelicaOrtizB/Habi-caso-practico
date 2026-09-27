package billetera;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Una fila de la tabla cuotas. {@code transaccionId} y {@code pagadaEn} son null hasta que se paga. */
public record Cuota(UUID id, UUID cobroId, UUID deudorCuentaId, int orden, long montoCentavos, EstadoCuota estado,
		UUID transaccionId, OffsetDateTime pagadaEn) {

}
