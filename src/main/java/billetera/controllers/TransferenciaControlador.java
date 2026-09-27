package billetera.controllers;

import java.util.UUID;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import billetera.ResultadoTransaccion;
import billetera.services.BilleteraServicio;

@RestController
@RequestMapping("/transferencias")
public class TransferenciaControlador {

	public record TransferenciaPedido(UUID origenId, UUID destinoId, long montoCentavos, String descripcion) {
	}

	private final BilleteraServicio billetera;

	public TransferenciaControlador(BilleteraServicio billetera) {
		this.billetera = billetera;
	}

	/**
	 * Un reintento con la misma {@code Idempotency-Key} y los mismos datos responde igual
	 * que la primera vez, sin mover plata de nuevo.
	 */
	@PostMapping
	public ResultadoTransaccion transferir(@RequestBody TransferenciaPedido pedido,
			@RequestHeader("Idempotency-Key") String llaveIdempotencia) {
		return billetera.transferir(pedido.origenId(), pedido.destinoId(), pedido.montoCentavos(),
				pedido.descripcion(), llaveIdempotencia);
	}

}
