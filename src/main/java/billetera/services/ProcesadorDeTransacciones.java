package billetera.services;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import billetera.Cuenta;
import billetera.ConflictoIdempotenciaException;
import billetera.CuentaNoEncontradaException;
import billetera.FondosInsuficientesException;
import billetera.Movimiento;
import billetera.OperacionInvalidaException;
import billetera.ResultadoTransaccion;
import billetera.SolicitudTransaccion;
import billetera.TipoCuenta;
import billetera.TipoTransaccion;
import billetera.repositories.CuentaRepositorio;
import billetera.repositories.MovimientoRepositorio;
import billetera.repositories.TransaccionRepositorio;

/**
 * Único lugar del sistema que mueve plata. Cada solicitud corre en UNA transacción
 * de BD, en este orden:
 * <ol>
 * <li>Si la llave de idempotencia ya existe, devuelve el resultado original (o conflicto).</li>
 * <li>Inserta la fila en {@code transacciones}. Va ANTES de bloquear cuentas: dos
 * reintentos simultáneos con la misma llave chocan aquí, sin tener cuentas bloqueadas.</li>
 * <li>Bloquea las cuentas en orden de id.</li>
 * <li>Valida tipos de cuenta y fondos, ya con las cuentas bloqueadas.</li>
 * <li>Escribe dos movimientos que suman 0 y actualiza los dos saldos.</li>
 * </ol>
 * Si algo falla, la transacción de BD hace ROLLBACK completo: no queda plata a medio
 * mover ni la llave de idempotencia gastada.
 * <p>
 * Usa {@link TransactionTemplate} en vez de {@code @Transactional} porque el choque de
 * llaves hay que atraparlo FUERA de la transacción que falló, que ya no sirve.
 */
@Service
public class ProcesadorDeTransacciones {

	private final TransactionTemplate transaccionesBd;
	private final CuentaRepositorio cuentas;
	private final TransaccionRepositorio transacciones;
	private final MovimientoRepositorio movimientos;

	public ProcesadorDeTransacciones(TransactionTemplate transaccionesBd, CuentaRepositorio cuentas,
			TransaccionRepositorio transacciones, MovimientoRepositorio movimientos) {
		this.transaccionesBd = transaccionesBd;
		this.cuentas = cuentas;
		this.transacciones = transacciones;
		this.movimientos = movimientos;
	}

	public ResultadoTransaccion procesar(SolicitudTransaccion solicitud) {
		try {
			return transaccionesBd.execute(estado -> procesarDentroDeTransaccion(solicitud));
		}
		catch (DuplicateKeyException e) {
			// Otro request con la misma llave hizo COMMIT mientras esperábamos. Nuestra
			// transacción ya hizo ROLLBACK (no movió nada): leemos el resultado ganador.
			return buscarExistente(solicitud.llaveIdempotencia()).map(existente -> repetir(existente, solicitud))
				.orElseThrow(() -> e);
		}
	}

	private ResultadoTransaccion procesarDentroDeTransaccion(SolicitudTransaccion solicitud) {
		Optional<ResultadoTransaccion> existente = buscarExistente(solicitud.llaveIdempotencia());
		if (existente.isPresent()) {
			return repetir(existente.get(), solicitud);
		}

		UUID transaccionId = UUID.randomUUID();
		OffsetDateTime fecha = transacciones.insertar(transaccionId, solicitud.tipo(),
				solicitud.llaveIdempotencia(), solicitud.descripcion());

		Map<UUID, Cuenta> bloqueadas = bloquearEnOrden(solicitud.cuentaOrigenId(), solicitud.cuentaDestinoId());
		Cuenta origen = bloqueadas.get(solicitud.cuentaOrigenId());
		Cuenta destino = bloqueadas.get(solicitud.cuentaDestinoId());

		validar(solicitud, origen, destino);

		long monto = solicitud.montoCentavos();
		movimientos.insertar(transaccionId, origen.id(), -monto);
		movimientos.insertar(transaccionId, destino.id(), monto);
		cuentas.sumarAlSaldo(origen.id(), -monto);
		cuentas.sumarAlSaldo(destino.id(), monto);

		return new ResultadoTransaccion(transaccionId, solicitud.tipo(), origen.id(), destino.id(), monto,
				solicitud.descripcion(), fecha);
	}

	/**
	 * Siempre en el mismo orden (el de los ids), sin importar quién envía y quién recibe.
	 * Si A→B bloqueara A y luego B, y al mismo tiempo B→A bloqueara B y luego A, cada
	 * una esperaría a la otra para siempre (deadlock).
	 */
	private Map<UUID, Cuenta> bloquearEnOrden(UUID... ids) {
		List<UUID> ordenados = Arrays.stream(ids).sorted().toList();
		Map<UUID, Cuenta> bloqueadas = new HashMap<>();
		for (UUID id : ordenados) {
			bloqueadas.put(id, cuentas.bloquear(id).orElseThrow(() -> new CuentaNoEncontradaException(id)));
		}
		return bloqueadas;
	}

	/**
	 * Se llama con las cuentas ya bloqueadas: nadie puede cambiar el saldo entre esta
	 * validación y el débito. Si se validara antes de bloquear, dos transferencias al
	 * mismo tiempo podrían ver el mismo saldo y gastarlo dos veces.
	 */
	private static void validar(SolicitudTransaccion solicitud, Cuenta origen, Cuenta destino) {
		if (destino.tipo() == TipoCuenta.SISTEMA) {
			throw new OperacionInvalidaException("Una cuenta del sistema no puede recibir plata");
		}
		if (origen.tipo() == TipoCuenta.SISTEMA && solicitud.tipo() != TipoTransaccion.CARGA) {
			throw new OperacionInvalidaException("Una cuenta del sistema solo puede enviar plata en una carga de saldo");
		}
		if (origen.tipo() == TipoCuenta.USUARIO && solicitud.tipo() == TipoTransaccion.CARGA) {
			throw new OperacionInvalidaException("Una carga de saldo solo puede salir de una cuenta del sistema");
		}
		if (origen.tipo() == TipoCuenta.USUARIO && origen.saldoCentavos() < solicitud.montoCentavos()) {
			throw new FondosInsuficientesException(origen.id());
		}
	}

	private static ResultadoTransaccion repetir(ResultadoTransaccion existente, SolicitudTransaccion solicitud) {
		if (!existente.coincideCon(solicitud)) {
			throw new ConflictoIdempotenciaException(solicitud.llaveIdempotencia());
		}
		return existente;
	}

	/**
	 * Reconstruye el resultado original desde la BD. No hace falta guardar la solicitud
	 * aparte: la transacción y sus dos movimientos ya dicen quién, a quién y cuánto.
	 */
	private Optional<ResultadoTransaccion> buscarExistente(String llave) {
		return transacciones.buscarPorLlave(llave).map(tx -> {
			List<Movimiento> suyos = movimientos.buscarPorTransaccion(tx.id());
			if (suyos.size() != 2) {
				throw new IllegalStateException("La transacción " + tx.id() + " no tiene exactamente dos movimientos");
			}
			Movimiento salida = suyos.stream().filter(m -> m.montoCentavos() < 0).findFirst().orElseThrow();
			Movimiento entrada = suyos.stream().filter(m -> m.montoCentavos() > 0).findFirst().orElseThrow();
			return new ResultadoTransaccion(tx.id(), tx.tipo(), salida.cuentaId(), entrada.cuentaId(),
					entrada.montoCentavos(), tx.descripcion(), tx.creadaEn());
		});
	}

}
