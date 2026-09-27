package billetera;

import static billetera.BilleteraServicioTest.llave;
import static billetera.BilleteraServicioTest.pesos;
import static billetera.CobroServicioTest.cuotaDe;
import static billetera.ConcurrenciaTest.alMismoTiempo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import billetera.services.BilleteraServicio;
import billetera.services.CobroServicio;

@PruebaDeIntegracion
class CobrosConcurrenciaTest {

	@Autowired
	CobroServicio cobros;

	@Autowired
	BilleteraServicio billetera;

	@Autowired
	JdbcClient jdbc;

	@AfterEach
	void laPlataCuadra() {
		VerificadorDePlata.verificar(jdbc);
	}

	@Test
	void veintePagosSimultaneosDeLaMismaCuotaCobranUnaSolaVez() throws Exception {
		UUID ana = cuenta("Ana");
		UUID beto = cuentaConSaldo("Beto", pesos(1_000));
		DetalleCobro cobro = crearCobro(ana, pesos(30), "La cena", beto);
		UUID cuota = cuotaDe(cobro, beto);

		// Cada hilo con su propia llave: son 20 pagos distintos, no reintentos.
		List<Boolean> resultados = alMismoTiempo(20, hilo -> {
			try {
				cobros.pagarCuota(cuota, llave());
				return true;
			}
			catch (OperacionNoPermitidaException e) {
				return false;
			}
		});

		assertThat(resultados).filteredOn(exito -> exito).hasSize(1);
		assertThat(billetera.consultarSaldo(beto)).isEqualTo(pesos(970));
		assertThat(billetera.consultarSaldo(ana)).isEqualTo(pesos(30));
	}

	@Test
	void veinteReintentosSimultaneosDelMismoPagoDevuelvenLaMismaTransaccion() throws Exception {
		UUID ana = cuenta("Ana");
		UUID beto = cuentaConSaldo("Beto", pesos(1_000));
		DetalleCobro cobro = crearCobro(ana, pesos(30), "La cena", beto);
		UUID cuota = cuotaDe(cobro, beto);
		String llave = llave();

		List<ResultadoPagoCuota> resultados = alMismoTiempo(20, hilo -> cobros.pagarCuota(cuota, llave));

		assertThat(resultados).extracting(r -> r.transaccion().transaccionId())
			.containsOnly(resultados.get(0).transaccion().transaccionId());
		assertThat(billetera.consultarSaldo(beto)).isEqualTo(pesos(970));
	}

	@Test
	void veinteCreacionesSimultaneasConLaMismaLlaveCreanUnSoloCobro() throws Exception {
		UUID ana = cuenta("Ana");
		UUID beto = cuenta("Beto");
		SolicitudCobro solicitud = new SolicitudCobro(ana, pesos(100), "La cena", List.of(beto), llave());

		List<DetalleCobro> resultados = alMismoTiempo(20, hilo -> cobros.crearCobro(solicitud));

		assertThat(resultados).extracting(DetalleCobro::id).containsOnly(resultados.get(0).id());
		assertThat(cobros.cobrosDe(ana)).hasSize(1);
	}

	/**
	 * Todos pagan mientras quien cobra cancela, al mismo tiempo. Sin importar quién gane
	 * cada carrera, al final: cada peso que recibió quien cobra corresponde a una cuota
	 * PAGADA, y el estado del cobro coincide con el de sus cuotas (lo revisa el verificador).
	 */
	@Test
	void pagarYCancelarAlMismoTiempoDejaTodoCoherente() throws Exception {
		for (int ronda = 0; ronda < 10; ronda++) {
			UUID ana = cuenta("Ana");
			List<UUID> deudores = new ArrayList<>();
			for (int i = 0; i < 8; i++) {
				deudores.add(cuentaConSaldo("Persona " + i, pesos(100)));
			}
			DetalleCobro cobro = crearCobro(ana, pesos(400), "La cena", deudores.toArray(UUID[]::new));

			// Hilos 0..7 pagan cada uno su cuota; el hilo 8 cancela.
			alMismoTiempo(9, hilo -> {
				try {
					if (hilo == 8) {
						cobros.cancelarCobro(cobro.id());
					}
					else {
						cobros.pagarCuota(cuotaDe(cobro, deudores.get(hilo)), llave());
					}
				}
				catch (OperacionNoPermitidaException e) {
					// Esperado: pagar después de la cancelación, o cancelar después del último pago.
				}
				return null;
			});

			DetalleCobro fin = cobros.verCobro(cobro.id());
			assertThat(billetera.consultarSaldo(ana)).isEqualTo(fin.pagadoCentavos());
			assertThat(fin.pendienteCentavos()).isZero();
		}
	}

	/**
	 * Pagos de cuotas mezclados con transferencias entre las mismas personas. Los pagos
	 * bloquean cobro → cuota → cuentas y las transferencias solo cuentas (en orden de id):
	 * si hubiera un orden inconsistente, Postgres detectaría un deadlock y lanzaría un error.
	 */
	@Test
	void pagosYTransferenciasMezcladosNoSeTraban() throws Exception {
		UUID ana = cuentaConSaldo("Ana", pesos(10_000));
		UUID beto = cuentaConSaldo("Beto", pesos(10_000));
		UUID caro = cuentaConSaldo("Caro", pesos(10_000));
		List<UUID> cuotasPorPagar = new ArrayList<>();
		for (int i = 0; i < 40; i++) {
			DetalleCobro cobro = crearCobro(ana, pesos(10), "Cobro " + i, beto, caro);
			cuotasPorPagar.add(cuotaDe(cobro, beto));
			cuotasPorPagar.add(cuotaDe(cobro, caro));
		}
		long totalAntes = billetera.consultarSaldo(ana) + billetera.consultarSaldo(beto)
				+ billetera.consultarSaldo(caro);

		alMismoTiempo(8, hilo -> {
			ThreadLocalRandom azar = ThreadLocalRandom.current();
			for (int i = hilo; i < cuotasPorPagar.size(); i += 8) {
				cobros.pagarCuota(cuotasPorPagar.get(i), llave());
				List<UUID> tres = new ArrayList<>(List.of(ana, beto, caro));
				UUID origen = tres.remove(azar.nextInt(3));
				UUID destino = tres.get(azar.nextInt(2));
				billetera.transferir(origen, destino, pesos(1), null, llave());
			}
			return null;
		});

		long totalDespues = billetera.consultarSaldo(ana) + billetera.consultarSaldo(beto)
				+ billetera.consultarSaldo(caro);
		assertThat(totalDespues).isEqualTo(totalAntes);
		assertThat(cobros.cobrosDe(ana)).extracting(DetalleCobro::estado).containsOnly(EstadoCobro.COMPLETADO);
	}

	// ---------- ayudas ----------

	private UUID cuenta(String titular) {
		return billetera.crearCuenta(titular).id();
	}

	private UUID cuentaConSaldo(String titular, long centavos) {
		UUID id = cuenta(titular);
		billetera.cargarSaldo(id, centavos, llave());
		return id;
	}

	private DetalleCobro crearCobro(UUID cobrador, long totalCentavos, String descripcion, UUID... deudores) {
		return cobros.crearCobro(new SolicitudCobro(cobrador, totalCentavos, descripcion, List.of(deudores), llave()));
	}

}
