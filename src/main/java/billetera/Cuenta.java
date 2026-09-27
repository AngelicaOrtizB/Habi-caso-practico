package billetera;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Cuenta(UUID id, String titular, TipoCuenta tipo, long saldoCentavos, OffsetDateTime creadaEn) {

}
