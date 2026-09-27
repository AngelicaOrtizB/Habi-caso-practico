package billetera;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public record ResultadoTransaccion(UUID transaccionId, TipoTransaccion tipo, UUID cuentaOrigenId,
		UUID cuentaDestinoId, long montoCentavos, String descripcion, OffsetDateTime fecha) {

	/** Un reintento con la misma llave solo es válido si pide exactamente lo mismo. */
	public boolean coincideCon(SolicitudTransaccion solicitud) {
		return tipo == solicitud.tipo() && cuentaOrigenId.equals(solicitud.cuentaOrigenId())
				&& cuentaDestinoId.equals(solicitud.cuentaDestinoId()) && montoCentavos == solicitud.montoCentavos()
				&& Objects.equals(descripcion, solicitud.descripcion());
	}

}
