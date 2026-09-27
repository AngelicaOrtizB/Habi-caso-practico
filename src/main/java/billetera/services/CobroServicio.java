package billetera.services;

import java.util.List;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import billetera.Cobro;
import billetera.CobroNoEncontradoException;
import billetera.CuentaNoEncontradaException;
import billetera.CuotaPendiente;
import billetera.DetalleCobro;
import billetera.DetalleCuota;
import billetera.EstadoCuota;
import billetera.ResultadoPagoCuota;
import billetera.SolicitudCobro;
import billetera.repositories.CobroRepositorio;
import billetera.repositories.CuentaRepositorio;
import billetera.repositories.CuotaRepositorio;

/**
 * Cobros divididos: "pagué la cena y le cobro su parte a cada uno".
 * <p>
 * Igual que {@link ProcesadorDeTransacciones}, NO es {@code @Transactional}: el trabajo lo
 * hace {@link EjecutorDeCobros} dentro de una transacción, y aquí se atrapa el choque de
 * llaves FUERA de la transacción que falló.
 */
@Service
public class CobroServicio {

	private final EjecutorDeCobros ejecutor;
	private final CobroRepositorio cobros;
	private final CuotaRepositorio cuotas;
	private final CuentaRepositorio cuentas;

	public CobroServicio(EjecutorDeCobros ejecutor, CobroRepositorio cobros, CuotaRepositorio cuotas,
			CuentaRepositorio cuentas) {
		this.ejecutor = ejecutor;
		this.cobros = cobros;
		this.cuotas = cuotas;
		this.cuentas = cuentas;
	}

	public DetalleCobro crearCobro(SolicitudCobro solicitud) {
		UUID cobroId;
		try {
			cobroId = ejecutor.crear(solicitud);
		}
		catch (DuplicateKeyException e) {
			// Otro reintento con la misma llave creó el cobro mientras esperábamos.
			cobroId = ejecutor.repetirCreacionSiYaExiste(solicitud).orElseThrow(() -> e);
		}
		return verCobro(cobroId);
	}

	public ResultadoPagoCuota pagarCuota(UUID cuotaId, String llaveIdempotencia) {
		try {
			return ejecutor.pagar(cuotaId, llaveIdempotencia);
		}
		catch (DuplicateKeyException e) {
			return ejecutor.repetirPagoSiYaExiste(cuotaId, llaveIdempotencia).orElseThrow(() -> e);
		}
	}

	public DetalleCobro cancelarCobro(UUID cobroId) {
		ejecutor.cancelar(cobroId);
		return verCobro(cobroId);
	}

	public DetalleCobro verCobro(UUID cobroId) {
		Cobro cobro = cobros.buscarPorId(cobroId).orElseThrow(() -> new CobroNoEncontradoException(cobroId));
		return detalle(cobro);
	}

	/** Los cobros que creó esta persona, del más nuevo al más viejo. */
	public List<DetalleCobro> cobrosDe(UUID cuentaId) {
		exigirCuenta(cuentaId);
		return cobros.listarPorCobrador(cuentaId).stream().map(this::detalle).toList();
	}

	/** Lo que debe esta persona. */
	public List<CuotaPendiente> cuotasPendientesDe(UUID cuentaId) {
		exigirCuenta(cuentaId);
		return cuotas.pendientesDe(cuentaId);
	}

	private DetalleCobro detalle(Cobro cobro) {
		String cobradorTitular = cuentas.buscarPorId(cobro.cobradorCuentaId()).orElseThrow().titular();
		List<DetalleCuota> suyas = cuotas.buscarDetallePorCobro(cobro.id());
		long pagado = sumar(suyas, EstadoCuota.PAGADA);
		long pendiente = sumar(suyas, EstadoCuota.PENDIENTE);
		return new DetalleCobro(cobro.id(), cobro.cobradorCuentaId(), cobradorTitular, cobro.totalCentavos(),
				cobro.descripcion(), cobro.estado(), cobro.creadoEn(), pagado, pendiente, suyas);
	}

	private static long sumar(List<DetalleCuota> cuotas, EstadoCuota estado) {
		return cuotas.stream().filter(c -> c.estado() == estado).mapToLong(DetalleCuota::montoCentavos).sum();
	}

	private void exigirCuenta(UUID cuentaId) {
		cuentas.buscarPorId(cuentaId).orElseThrow(() -> new CuentaNoEncontradaException(cuentaId));
	}

}
