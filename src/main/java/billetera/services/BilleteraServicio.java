package billetera.services;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import billetera.Cuenta;
import billetera.CuentaNoEncontradaException;
import billetera.LineaHistorial;
import billetera.OperacionInvalidaException;
import billetera.ResultadoTransaccion;
import billetera.SolicitudTransaccion;
import billetera.TipoTransaccion;
import billetera.repositories.CuentaRepositorio;
import billetera.repositories.MovimientoRepositorio;


@Service
public class BilleteraServicio {

	/** Cuenta SISTEMA creada en V1: de aquí sale toda carga de saldo simulada. */
	public static final UUID CUENTA_FONDEO_EXTERNO = UUID.fromString("00000000-0000-0000-0000-000000000001");

	static final String DESCRIPCION_CARGA = "Carga de saldo (simulada)";
	static final int LARGO_MAXIMO_TITULAR = 120;
	static final int TAMANO_MAXIMO_PAGINA = 100;

	private final CuentaRepositorio cuentas;
	private final MovimientoRepositorio movimientos;
	private final ProcesadorDeTransacciones procesador;

	public BilleteraServicio(CuentaRepositorio cuentas, MovimientoRepositorio movimientos,
			ProcesadorDeTransacciones procesador) {
		this.cuentas = cuentas;
		this.movimientos = movimientos;
		this.procesador = procesador;
	}

	public Cuenta crearCuenta(String titular) {
		if (titular == null || titular.isBlank()) {
			throw new OperacionInvalidaException("El nombre del titular es obligatorio");
		}
		String limpio = titular.strip();
		if (limpio.length() > LARGO_MAXIMO_TITULAR) {
			throw new OperacionInvalidaException(
					"El nombre del titular no puede superar " + LARGO_MAXIMO_TITULAR + " caracteres");
		}
		return cuentas.insertarCuentaUsuario(UUID.randomUUID(), limpio);
	}

	/** La plata sale de la cuenta de fondeo, que queda negativa: la suma de todo el sistema sigue siendo 0. */
	public ResultadoTransaccion cargarSaldo(UUID cuentaId, long montoCentavos, String llaveIdempotencia) {
		return procesador.procesar(new SolicitudTransaccion(TipoTransaccion.CARGA, llaveIdempotencia,
				CUENTA_FONDEO_EXTERNO, cuentaId, montoCentavos, DESCRIPCION_CARGA));
	}

	public ResultadoTransaccion transferir(UUID origenId, UUID destinoId, long montoCentavos, String descripcion,
			String llaveIdempotencia) {
		return procesador.procesar(new SolicitudTransaccion(TipoTransaccion.TRANSFERENCIA, llaveIdempotencia,
				origenId, destinoId, montoCentavos, descripcion));
	}

	public long consultarSaldo(UUID cuentaId) {
		return buscarCuenta(cuentaId).saldoCentavos();
	}

	public List<Cuenta> listarCuentas() {
		return cuentas.listarCuentasDeUsuario();
	}

	public Cuenta buscarCuenta(UUID cuentaId) {
		return cuentas.buscarPorId(cuentaId).orElseThrow(() -> new CuentaNoEncontradaException(cuentaId));
	}

	/**
	 * @param antesDeMovimientoId cursor: el {@code movimientoId} de la última línea de la
	 * página anterior, o null para la primera página
	 */
	public List<LineaHistorial> verHistorial(UUID cuentaId, Long antesDeMovimientoId, int limite) {
		if (limite < 1 || limite > TAMANO_MAXIMO_PAGINA) {
			throw new OperacionInvalidaException("El tamaño de página debe estar entre 1 y " + TAMANO_MAXIMO_PAGINA);
		}
		buscarCuenta(cuentaId);
		long antes = antesDeMovimientoId != null ? antesDeMovimientoId : Long.MAX_VALUE;
		return movimientos.buscarHistorial(cuentaId, antes, limite);
	}

}
