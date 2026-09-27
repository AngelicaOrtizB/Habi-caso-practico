package billetera.services;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import billetera.Cobro;
import billetera.CobroNoEncontradoException;
import billetera.ConflictoIdempotenciaException;
import billetera.Cuenta;
import billetera.CuentaNoEncontradaException;
import billetera.Cuota;
import billetera.CuotaNoEncontradaException;
import billetera.EstadoCobro;
import billetera.EstadoCuota;
import billetera.OperacionInvalidaException;
import billetera.OperacionNoPermitidaException;
import billetera.RepartoDeCobro;
import billetera.ResultadoPagoCuota;
import billetera.ResultadoTransaccion;
import billetera.SolicitudCobro;
import billetera.SolicitudTransaccion;
import billetera.TipoCuenta;
import billetera.TipoTransaccion;
import billetera.repositories.CobroRepositorio;
import billetera.repositories.CuentaRepositorio;
import billetera.repositories.CuotaRepositorio;

/**
 * Las operaciones de cobros divididos que cambian algo. No se llama directo: se entra
 * por {@link CobroServicio}, que maneja los reintentos simultáneos con la misma llave.
 * <p>
 * Orden de bloqueo, igual en todas las operaciones: cobro → cuota → cuentas. Como todas
 * bloquean primero el cobro, pagar y cancelar el mismo cobro nunca corren a la vez, y
 * nadie puede quedar esperando a otro que a su vez lo espera (deadlock).
 */
@Service
public class EjecutorDeCobros {

	private final CobroRepositorio cobros;
	private final CuotaRepositorio cuotas;
	private final CuentaRepositorio cuentas;
	private final EjecutorDeTransacciones transacciones;

	public EjecutorDeCobros(CobroRepositorio cobros, CuotaRepositorio cuotas, CuentaRepositorio cuentas,
			EjecutorDeTransacciones transacciones) {
		this.cobros = cobros;
		this.cuotas = cuotas;
		this.cuentas = cuentas;
		this.transacciones = transacciones;
	}

	/**
	 * Crea el cobro y sus cuotas en una transacción. Al hacer COMMIT, la base de datos
	 * revisa que las cuotas sumen exactamente el total.
	 *
	 * @return el id del cobro (el nuevo, o el original si es un reintento con la misma llave)
	 */
	@Transactional
	public UUID crear(SolicitudCobro solicitud) {
		Optional<UUID> existente = repetirCreacionSiYaExiste(solicitud);
		if (existente.isPresent()) {
			return existente.get();
		}

		exigirCuentaDeUsuario(solicitud.cobradorId());
		solicitud.deudores().forEach(this::exigirCuentaDeUsuario);

		UUID cobroId = UUID.randomUUID();
		cobros.insertar(cobroId, solicitud.cobradorId(), solicitud.totalCentavos(), solicitud.descripcion(),
				solicitud.llaveIdempotencia());
		List<Long> montos = RepartoDeCobro.repartir(solicitud.totalCentavos(), solicitud.deudores().size());
		for (int i = 0; i < montos.size(); i++) {
			cuotas.insertar(UUID.randomUUID(), cobroId, i + 1, solicitud.deudores().get(i), montos.get(i));
		}
		return cobroId;
	}

	/** Si la llave ya se usó para crear un cobro, devuelve ese cobro si pedía lo mismo, o conflicto si no. */
	public Optional<UUID> repetirCreacionSiYaExiste(SolicitudCobro solicitud) {
		return cobros.buscarPorLlave(solicitud.llaveIdempotencia()).map(cobro -> {
			boolean mismoPedido = cobro.cobradorCuentaId().equals(solicitud.cobradorId())
					&& cobro.totalCentavos() == solicitud.totalCentavos()
					&& cobro.descripcion().equals(solicitud.descripcion())
					&& cuotas.deudoresEnOrden(cobro.id()).equals(solicitud.deudores());
			if (!mismoPedido) {
				throw new ConflictoIdempotenciaException(solicitud.llaveIdempotencia());
			}
			return cobro.id();
		});
	}

	/**
	 * Paga una cuota completa: mueve la plata del deudor a quien cobra y marca la cuota
	 * como pagada, en la MISMA transacción. Nunca queda "pagada sin plata" ni al revés.
	 * Si es la última cuota pendiente, el cobro pasa a COMPLETADO en esa misma transacción.
	 */
	@Transactional
	public ResultadoPagoCuota pagar(UUID cuotaId, String llaveIdempotencia) {
		// El cobro al que pertenece una cuota nunca cambia, así que se puede leer sin
		// bloquear solo para saber qué cobro hay que bloquear primero.
		UUID cobroId = cuotas.buscarPorId(cuotaId).orElseThrow(() -> new CuotaNoEncontradaException(cuotaId)).cobroId();
		Cobro cobro = cobros.bloquear(cobroId).orElseThrow(() -> new CobroNoEncontradoException(cobroId));
		Cuota cuota = cuotas.bloquear(cuotaId).orElseThrow(() -> new CuotaNoEncontradaException(cuotaId));

		// Se revisa la llave DESPUÉS de bloquear: si otro reintento con esta misma llave
		// pagó mientras esperábamos el bloqueo, aquí ya vemos su pago y lo devolvemos.
		SolicitudTransaccion solicitud = solicitudDePago(cobro, cuota, llaveIdempotencia);
		Optional<ResultadoPagoCuota> repetido = repetirPago(cobro, cuota, solicitud);
		if (repetido.isPresent()) {
			return repetido.get();
		}

		if (cobro.estado() == EstadoCobro.CANCELADO) {
			throw new OperacionNoPermitidaException("El cobro fue cancelado: esta cuota ya no se puede pagar");
		}
		if (cuota.estado() == EstadoCuota.PAGADA) {
			throw new OperacionNoPermitidaException("Esta cuota ya está pagada");
		}
		if (cuota.estado() == EstadoCuota.CANCELADA) {
			throw new OperacionNoPermitidaException("Esta cuota fue cancelada");
		}

		// Se une a esta misma transacción: si el deudor no tiene fondos, se deshace todo.
		ResultadoTransaccion pago = transacciones.ejecutar(solicitud);
		cuotas.marcarPagada(cuota.id(), pago.transaccionId());

		EstadoCobro estado = cobro.estado();
		if (cuotas.contarPendientes(cobro.id()) == 0) {
			estado = EstadoCobro.COMPLETADO;
			cobros.cambiarEstado(cobro.id(), estado);
		}
		return new ResultadoPagoCuota(cuota.id(), cobro.id(), estado, pago);
	}

	/** Para después de un choque de llaves: lee sin bloquear, fuera de la transacción que falló. */
	public Optional<ResultadoPagoCuota> repetirPagoSiYaExiste(UUID cuotaId, String llaveIdempotencia) {
		Cuota cuota = cuotas.buscarPorId(cuotaId).orElseThrow(() -> new CuotaNoEncontradaException(cuotaId));
		Cobro cobro = cobros.buscarPorId(cuota.cobroId())
			.orElseThrow(() -> new CobroNoEncontradoException(cuota.cobroId()));
		return repetirPago(cobro, cuota, solicitudDePago(cobro, cuota, llaveIdempotencia));
	}

	/**
	 * Cancela solo lo que falta por pagar. Lo que ya se pagó NO se devuelve
	 * automáticamente: quien cobra ya recibió esa plata y decide qué hacer con ella.
	 * Cancelar dos veces no es un error.
	 */
	@Transactional
	public void cancelar(UUID cobroId) {
		Cobro cobro = cobros.bloquear(cobroId).orElseThrow(() -> new CobroNoEncontradoException(cobroId));
		switch (cobro.estado()) {
			case CANCELADO -> {
			}
			case COMPLETADO -> throw new OperacionNoPermitidaException(
					"El cobro ya está completo: todas las cuotas se pagaron");
			case ABIERTO -> {
				cuotas.cancelarPendientes(cobroId);
				cobros.cambiarEstado(cobroId, EstadoCobro.CANCELADO);
			}
		}
	}

	/**
	 * Un pago de cuota es un movimiento de plata del deudor a quien cobra. La descripción
	 * es la del cobro, para que en el historial diga "La cena" y no una línea suelta.
	 */
	private static SolicitudTransaccion solicitudDePago(Cobro cobro, Cuota cuota, String llaveIdempotencia) {
		return new SolicitudTransaccion(TipoTransaccion.PAGO_CUOTA, llaveIdempotencia, cuota.deudorCuentaId(),
				cobro.cobradorCuentaId(), cuota.montoCentavos(), cobro.descripcion());
	}

	/**
	 * La llave ya existe si hay una transacción con ella. Es un reintento válido solo si
	 * esa transacción es justo la que pagó ESTA cuota; si no, la llave se usó para otra
	 * cosa y es un conflicto.
	 */
	private Optional<ResultadoPagoCuota> repetirPago(Cobro cobro, Cuota cuota, SolicitudTransaccion solicitud) {
		return transacciones.repetirSiYaExiste(solicitud).map(existente -> {
			if (!existente.transaccionId().equals(cuota.transaccionId())) {
				throw new ConflictoIdempotenciaException(solicitud.llaveIdempotencia());
			}
			return new ResultadoPagoCuota(cuota.id(), cobro.id(), cobro.estado(), existente);
		});
	}

	private void exigirCuentaDeUsuario(UUID cuentaId) {
		Cuenta cuenta = cuentas.buscarPorId(cuentaId).orElseThrow(() -> new CuentaNoEncontradaException(cuentaId));
		if (cuenta.tipo() != TipoCuenta.USUARIO) {
			throw new OperacionInvalidaException("Una cuenta del sistema no puede participar en un cobro");
		}
	}

}
