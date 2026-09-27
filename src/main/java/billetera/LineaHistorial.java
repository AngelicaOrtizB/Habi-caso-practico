package billetera;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Una línea del historial, vista desde la cuenta consultada.
 *
 * @param movimientoId cursor para pedir la página siguiente
 * @param montoCentavos negativo si salió plata de la cuenta, positivo si entró
 * @param contraparteId la otra cuenta de la transacción
 */
public record LineaHistorial(long movimientoId, UUID transaccionId, TipoTransaccion tipo, long montoCentavos,
		UUID contraparteId, String contraparteTitular, String descripcion, OffsetDateTime fecha) {

}
