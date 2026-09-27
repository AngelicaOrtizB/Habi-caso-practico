package billetera;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Lo que ve quien debe: a quién, cuánto y de qué. */
public record CuotaPendiente(UUID cuotaId, UUID cobroId, String descripcion, UUID cobradorCuentaId,
		String cobradorTitular, long montoCentavos, OffsetDateTime cobroCreadoEn) {

}
