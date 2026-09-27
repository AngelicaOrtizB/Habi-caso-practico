package billetera;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Lo que ve quien cobra: cuánto era, cuánto le han pagado, cuánto falta y quién pagó.
 *
 * @param pagadoCentavos suma de las cuotas pagadas
 * @param pendienteCentavos suma de las cuotas que faltan por pagar
 */
public record DetalleCobro(UUID id, UUID cobradorCuentaId, String cobradorTitular, long totalCentavos,
		String descripcion, EstadoCobro estado, OffsetDateTime creadoEn, long pagadoCentavos, long pendienteCentavos,
		List<DetalleCuota> cuotas) {

}
