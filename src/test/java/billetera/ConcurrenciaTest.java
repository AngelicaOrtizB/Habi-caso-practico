package billetera;

import static billetera.BilleteraServicioTest.llave;
import static billetera.BilleteraServicioTest.pesos;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import billetera.services.BilleteraServicio;

/**
 * Varios hilos golpean las mismas cuentas al mismo tiempo. Cada hilo usa su propia
 * conexión, así que la base de datos ve operaciones realmente simultáneas.
 */
@PruebaDeIntegracion
class ConcurrenciaTest {

	@Autowired
	BilleteraServicio billetera;

	@Autowired
	JdbcClient jdbc;

	@AfterEach
	void laPlataCuadra() {
		VerificadorDePlata.verificar(jdbc);
	}

	@Test
	void transferenciasAleatoriasNoCambianElTotalNiDejanSaldosNegativos() throws Exception {
		int cantidadCuentas = 10;
		long saldoInicial = pesos(1_000);
		List<UUID> cuentas = new ArrayList<>();
		for (int i = 0; i < cantidadCuentas; i++) {
			UUID id = billetera.crearCuenta("Persona " + i).id();
			billetera.cargarSaldo(id, saldoInicial, llave());
			cuentas.add(id);
		}

		// Montos grandes a propósito: muchas transferencias van a fallar por fondos.
		List<Integer> exitosasPorHilo = alMismoTiempo(8, hilo -> {
			ThreadLocalRandom azar = ThreadLocalRandom.current();
			int exitosas = 0;
			for (int i = 0; i < 300; i++) {
				UUID origen = cuentas.get(azar.nextInt(cantidadCuentas));
				UUID destino = cuentas.get(azar.nextInt(cantidadCuentas));
				if (origen.equals(destino)) {
					continue;
				}
				try {
					billetera.transferir(origen, destino, azar.nextLong(1, pesos(500)), null, llave());
					exitosas++;
				}
				catch (FondosInsuficientesException e) {
					// Esperado: la cuenta no tenía plata suficiente en ese momento.
				}
			}
			return exitosas;
		});

		long total = cuentas.stream().mapToLong(billetera::consultarSaldo).sum();
		assertThat(total).isEqualTo(cantidadCuentas * saldoInicial);
		assertThat(cuentas).allSatisfy(id -> assertThat(billetera.consultarSaldo(id)).isNotNegative());
		assertThat(exitosasPorHilo.stream().mapToInt(Integer::intValue).sum()).isPositive();
	}

	@Test
	void transferenciasCruzadasNoSeTrabanEntreSi() throws Exception {
		UUID ana = billetera.crearCuenta("Ana").id();
		UUID beto = billetera.crearCuenta("Beto").id();
		billetera.cargarSaldo(ana, pesos(10_000), llave());
		billetera.cargarSaldo(beto, pesos(10_000), llave());

		// Mitad de los hilos Ana→Beto y mitad Beto→Ana. Si las cuentas se bloquearan
		// en distinto orden, Postgres detectaría un deadlock y lanzaría un error.
		alMismoTiempo(16, hilo -> {
			for (int i = 0; i < 50; i++) {
				if (hilo % 2 == 0) {
					billetera.transferir(ana, beto, pesos(1), null, llave());
				}
				else {
					billetera.transferir(beto, ana, pesos(1), null, llave());
				}
			}
			return null;
		});

		assertThat(billetera.consultarSaldo(ana)).isEqualTo(pesos(10_000));
		assertThat(billetera.consultarSaldo(beto)).isEqualTo(pesos(10_000));
	}

	@Test
	void retirosSimultaneosNoPuedenGastarMasDeLoQueHay() throws Exception {
		UUID ana = billetera.crearCuenta("Ana").id();
		billetera.cargarSaldo(ana, pesos(100), llave());

		// 20 hilos intentan sacar $10 al mismo tiempo: solo alcanza para 10.
		List<Boolean> resultados = alMismoTiempo(20, hilo -> {
			UUID destino = billetera.crearCuenta("Destino " + hilo).id();
			try {
				billetera.transferir(ana, destino, pesos(10), null, llave());
				return true;
			}
			catch (FondosInsuficientesException e) {
				return false;
			}
		});

		assertThat(resultados).filteredOn(exito -> exito).hasSize(10);
		assertThat(billetera.consultarSaldo(ana)).isZero();
	}

	@Test
	void reintentosSimultaneosConLaMismaLlaveMuevenLaPlataUnaSolaVez() throws Exception {
		UUID ana = billetera.crearCuenta("Ana").id();
		UUID beto = billetera.crearCuenta("Beto").id();
		billetera.cargarSaldo(ana, pesos(100), llave());
		String llave = llave();

		List<ResultadoTransaccion> resultados = alMismoTiempo(20,
				hilo -> billetera.transferir(ana, beto, pesos(30), "Mercado", llave));

		assertThat(resultados).extracting(ResultadoTransaccion::transaccionId).containsOnly(resultados.get(0).transaccionId());
		assertThat(billetera.consultarSaldo(ana)).isEqualTo(pesos(70));
		assertThat(billetera.consultarSaldo(beto)).isEqualTo(pesos(30));
	}

	@Test
	void cargasSimultaneasSeAplicanTodas() throws Exception {
		UUID ana = billetera.crearCuenta("Ana").id();

		alMismoTiempo(20, hilo -> billetera.cargarSaldo(ana, pesos(1), llave()));

		assertThat(billetera.consultarSaldo(ana)).isEqualTo(pesos(20));
	}

	/**
	 * Arranca todos los hilos a la vez (esperan en la "largada") para maximizar los
	 * choques. Si alguno lanza una excepción no esperada, el test falla.
	 */
	private static <T> List<T> alMismoTiempo(int hilos, IntFunction<T> tarea) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(hilos);
		CountDownLatch largada = new CountDownLatch(1);
		try {
			List<Future<T>> futuros = new ArrayList<>();
			for (int i = 0; i < hilos; i++) {
				int hilo = i;
				futuros.add(pool.submit(() -> {
					largada.await();
					return tarea.apply(hilo);
				}));
			}
			largada.countDown();
			List<T> resultados = new ArrayList<>();
			for (Future<T> futuro : futuros) {
				resultados.add(futuro.get(2, TimeUnit.MINUTES));
			}
			return resultados;
		}
		finally {
			pool.shutdownNow();
		}
	}

}
