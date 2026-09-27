package billetera.controllers;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import billetera.CuotaPendiente;
import billetera.DetalleCobro;
import billetera.ResultadoPagoCuota;
import billetera.SolicitudCobro;
import billetera.services.CobroServicio;

@RestController
public class CobroControlador {

	/** @param deudores en orden: el centavo que sobra del reparto se lo llevan los primeros */
	public record CrearCobroPedido(UUID cobradorId, long totalCentavos, String descripcion, List<UUID> deudores) {
	}

	private final CobroServicio cobros;

	public CobroControlador(CobroServicio cobros) {
		this.cobros = cobros;
	}

	@PostMapping("/cobros")
	@ResponseStatus(HttpStatus.CREATED)
	public DetalleCobro crear(@RequestBody CrearCobroPedido pedido,
			@RequestHeader("Idempotency-Key") String llaveIdempotencia) {
		return cobros.crearCobro(new SolicitudCobro(pedido.cobradorId(), pedido.totalCentavos(), pedido.descripcion(),
				pedido.deudores(), llaveIdempotencia));
	}

	/** Cuánto era, cuánto le han pagado, cuánto falta y quién pagó. */
	@GetMapping("/cobros/{id}")
	public DetalleCobro ver(@PathVariable UUID id) {
		return cobros.verCobro(id);
	}

	/** No mueve plata: cancelar dos veces da el mismo resultado, así que no necesita llave. */
	@PostMapping("/cobros/{id}/cancelacion")
	public DetalleCobro cancelar(@PathVariable UUID id) {
		return cobros.cancelarCobro(id);
	}

	@PostMapping("/cuotas/{id}/pago")
	public ResultadoPagoCuota pagar(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String llaveIdempotencia) {
		return cobros.pagarCuota(id, llaveIdempotencia);
	}

	@GetMapping("/cuentas/{id}/cobros")
	public List<DetalleCobro> cobrosDe(@PathVariable UUID id) {
		return cobros.cobrosDe(id);
	}

	@GetMapping("/cuentas/{id}/cuotas-pendientes")
	public List<CuotaPendiente> cuotasPendientes(@PathVariable UUID id) {
		return cobros.cuotasPendientesDe(id);
	}

}
