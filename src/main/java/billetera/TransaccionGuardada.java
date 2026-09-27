package billetera;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TransaccionGuardada(UUID id, TipoTransaccion tipo, String llaveIdempotencia, String descripcion,
		OffsetDateTime creadaEn) {

}
