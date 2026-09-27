package billetera;

import java.util.UUID;

/** @param estadoCobro COMPLETADO si este fue el último pago */
public record ResultadoPagoCuota(UUID cuotaId, UUID cobroId, EstadoCobro estadoCobro,
		ResultadoTransaccion transaccion) {

}
