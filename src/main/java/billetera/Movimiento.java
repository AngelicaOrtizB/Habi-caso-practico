package billetera;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Una fila de la tabla movimientos. Negativo = sale de la cuenta, positivo = entra. */
public record Movimiento(long id, UUID transaccionId, UUID cuentaId, long montoCentavos, OffsetDateTime creadoEn) {

}
