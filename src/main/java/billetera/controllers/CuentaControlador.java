package billetera.controllers;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import billetera.Cuenta;
import billetera.LineaHistorial;
import billetera.ResultadoTransaccion;
import billetera.services.BilleteraServicio;

@RestController
@RequestMapping("/cuentas")
public class CuentaControlador {

	public record CrearCuentaPedido(String titular) {
	}

	public record CargaPedido(long montoCentavos) {
	}

	/**
	 * @param siguiente el valor de {@code antesDe} para pedir la página siguiente, o null
	 * si no hay más
	 */
	public record PaginaHistorial(List<LineaHistorial> lineas, Long siguiente) {
	}

	private final BilleteraServicio billetera;

	public CuentaControlador(BilleteraServicio billetera) {
		this.billetera = billetera;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public Cuenta crear(@RequestBody CrearCuentaPedido pedido) {
		return billetera.crearCuenta(pedido.titular());
	}

	@GetMapping
	public List<Cuenta> listar() {
		return billetera.listarCuentas();
	}

	/** Incluye el saldo actual en {@code saldoCentavos}. */
	@GetMapping("/{id}")
	public Cuenta consultar(@PathVariable UUID id) {
		return billetera.buscarCuenta(id);
	}

	@PostMapping("/{id}/cargas")
	public ResultadoTransaccion cargar(@PathVariable UUID id, @RequestBody CargaPedido pedido,
			@RequestHeader("Idempotency-Key") String llaveIdempotencia) {
		return billetera.cargarSaldo(id, pedido.montoCentavos(), llaveIdempotencia);
	}

	@GetMapping("/{id}/historial")
	public PaginaHistorial historial(@PathVariable UUID id, @RequestParam(required = false) Long antesDe,
			@RequestParam(defaultValue = "20") int limite) {
		List<LineaHistorial> lineas = billetera.verHistorial(id, antesDe, limite);
		// Página llena = puede haber más. En el peor caso, la siguiente llega vacía.
		Long siguiente = lineas.size() == limite ? lineas.get(lineas.size() - 1).movimientoId() : null;
		return new PaginaHistorial(lineas, siguiente);
	}

}
