package billetera;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DetalleCuota(UUID id, UUID deudorCuentaId, String deudorTitular, long montoCentavos, EstadoCuota estado,
		OffsetDateTime pagadaEn) {

}
