package billetera;

import static billetera.BilleteraServicioTest.llave;
import static billetera.BilleteraServicioTest.pesos;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import billetera.services.BilleteraServicio;
import billetera.services.CobroServicio;

@PruebaDeIntegracion
class CobroServicioTest {

	@Autowired
	CobroServicio cobros;

	@Autowired
	BilleteraServicio billetera;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TransactionTemplate transaccionesBd;

	@AfterEach
	void laPlataCuadra() {
		VerificadorDePlata.verificar(jdbc);
	}

	// ---------- crear ----------

	@Test
	void crearCobroRepartesSinPerderElCentavo() {
		UUID ana = cuenta("Ana");
		UUID beto = cuenta("Beto");
		UUID caro = cuenta("Caro");
		UUID dani = cuenta("Dani");

		DetalleCobro cobro = crearCobro(ana, pesos(100_000), "La cena", beto, caro, dani);

		assertThat(cobro.estado()).isEqualTo(EstadoCobro.ABIERTO);
		assertThat(cobro.cobradorTitular()).isEqualTo("Ana");
		assertThat(cobro.cuotas()).extracting(DetalleCuota::deudorTitular).containsExactly("Beto", "Caro", "Dani");
		assertThat(cobro.cuotas()).extracting(DetalleCuota::montoCentavos)
			.containsExactly(3_333_334L, 3_333_333L, 3_333_333L);
		assertThat(cobro.pendienteCentavos()).isEqualTo(pesos(100_000));
		assertThat(cobro.pagadoCentavos()).isZero();
	}

	@Test
	void crearUnCobroNoMuevePlata() {
		UUID ana = cuenta("Ana");
		UUID beto = cuentaConSaldo("Beto", pesos(100));

		crearCobro(ana, pesos(50), "Taxi", beto);

		assertThat(billetera.consultarSaldo(ana)).isZero();
		assertThat(billetera.consultarSaldo(beto)).isEqualTo(pesos(100));
	}

	@Test
	void quienCobraNoPuedeEstarEnLaLista() {
		UUID ana = cuenta("Ana");
		UUID beto = cuenta("Beto");

		assertThatThrownBy(() -> crearCobro(ana, pesos(100), "La cena", beto, ana))
			.isInstanceOf(OperacionInvalidaException.class)
			.hasMessageContaining("Quien cobra");
	}

	@Test
	void unaPersonaNoPuedeAparecerDosVeces() {
		UUID ana = cuenta("Ana");
		UUID beto = cuenta("Beto");

		assertThatThrownBy(() -> crearCobro(ana, pesos(100), "La cena", beto, beto))
			.isInstanceOf(OperacionInvalidaException.class);
	}

	@Test
	void unCobroSinPersonasSeRechaza() {
		UUID ana = cuenta("Ana");

		assertThatThrownBy(() -> crearCobro(ana, pesos(100), "La cena"))
			.isInstanceOf(OperacionInvalidaException.class);
	}

	@Test
	void unCobroSinDescripcionSeRechaza() {
		UUID ana = cuenta("Ana");
		UUID beto = cuenta("Beto");

		assertThatThrownBy(() -> crearCobro(ana, pesos(100), "   ", beto))
			.isInstanceOf(OperacionInvalidaException.class);
	}

	@Test
	void unDeudorQueNoExisteDa404YNoCreaNada() {
		UUID ana = cuenta("Ana");
		String llave = llave();

		assertThatThrownBy(() -> cobros.crearCobro(
				new SolicitudCobro(ana, pesos(100), "La cena", List.of(UUID.randomUUID()), llave)))
			.isInstanceOf(CuentaNoEncontradaException.class);
		assertThat(cobros.cobrosDe(ana)).isEmpty();
	}

	@Test
	void laCuentaDeFondeoNoPuedeParticipar() {
		UUID ana = cuenta("Ana");

		assertThatThrownBy(() -> crearCobro(ana, pesos(100), "La cena", BilleteraServicio.CUENTA_FONDEO_EXTERNO))
			.isInstanceOf(OperacionInvalidaException.class);
	}

	@Test
	void reintentarLaCreacionConLaMismaLlaveNoDuplicaElCobro() {
		UUID ana = cuenta("Ana");
		UUID beto = cuenta("Beto");
		SolicitudCobro solicitud = new SolicitudCobro(ana, pesos(100), "La cena", List.of(beto), llave());

		DetalleCobro primero = cobros.crearCobro(solicitud);
		DetalleCobro reintento = cobros.crearCobro(solicitud);

		assertThat(reintento.id()).isEqualTo(primero.id());
		assertThat(cobros.cobrosDe(ana)).hasSize(1);
		assertThat(cobros.cuotasPendientesDe(beto)).hasSize(1);
	}

	@Test
	void laMismaLlaveParaOtroCobroEsUnConflicto() {
		UUID ana = cuenta("Ana");
		UUID beto = cuenta("Beto");
		String llave = llave();
		cobros.crearCobro(new SolicitudCobro(ana, pesos(100), "La cena", List.of(beto), llave));

		assertThatThrownBy(() -> cobros.crearCobro(new SolicitudCobro(ana, pesos(999), "La cena", List.of(beto), llave)))
			.isInstanceOf(ConflictoIdempotenciaException.class);
	}

	// ---------- pagar ----------

	@Test
	void pagarUnaCuotaMueveLaPlataAQuienCobra() {
		UUID ana = cuenta("Ana");
		UUID beto = cuentaConSaldo("Beto", pesos(50_000));
		UUID caro = cuenta("Caro");
		DetalleCobro cobro = crearCobro(ana, pesos(60_000), "La cena", beto, caro);

		ResultadoPagoCuota pago = cobros.pagarCuota(cuotaDe(cobro, beto), llave());

		assertThat(pago.estadoCobro()).isEqualTo(EstadoCobro.ABIERTO);
		assertThat(pago.transaccion().tipo()).isEqualTo(TipoTransaccion.PAGO_CUOTA);
		assertThat(billetera.consultarSaldo(beto)).isEqualTo(pesos(20_000));
		assertThat(billetera.consultarSaldo(ana)).isEqualTo(pesos(30_000));

		DetalleCobro despues = cobros.verCobro(cobro.id());
		assertThat(despues.cuotas()).extracting(DetalleCuota::estado)
			.containsExactly(EstadoCuota.PAGADA, EstadoCuota.PENDIENTE);
		assertThat(despues.pagadoCentavos()).isEqualTo(pesos(30_000));
		assertThat(despues.pendienteCentavos()).isEqualTo(pesos(30_000));
	}

	@Test
	void elHistorialDiceQueFueElPagoDeUnaCuotaYDeQue() {
		UUID ana = cuenta("Ana");
		UUID beto = cuentaConSaldo("Beto", pesos(100));
		DetalleCobro cobro = crearCobro(ana, pesos(40), "La cena", beto);

		cobros.pagarCuota(cuotaDe(cobro, beto), llave());

		LineaHistorial linea = billetera.verHistorial(ana, null, 10).get(0);
		assertThat(linea.tipo()).isEqualTo(TipoTransaccion.PAGO_CUOTA);
		assertThat(linea.descripcion()).isEqualTo("La cena");
		assertThat(linea.contraparteTitular()).isEqualTo("Beto");
		assertThat(linea.montoCentavos()).isEqualTo(pesos(40));
	}

	@Test
	void elUltimoPagoCompletaElCobro() {
		UUID ana = cuenta("Ana");
		UUID beto = cuentaConSaldo("Beto", pesos(100));
		UUID caro = cuentaConSaldo("Caro", pesos(100));
		DetalleCobro cobro = crearCobro(ana, pesos(100), "Arriendo", beto, caro);

		cobros.pagarCuota(cuotaDe(cobro, beto), llave());
		ResultadoPagoCuota ultimo = cobros.pagarCuota(cuotaDe(cobro, caro), llave());

		assertThat(ultimo.estadoCobro()).isEqualTo(EstadoCobro.COMPLETADO);
		assertThat(cobros.verCobro(cobro.id()).estado()).isEqualTo(EstadoCobro.COMPLETADO);
		assertThat(billetera.consultarSaldo(ana)).isEqualTo(pesos(100));
	}

	@Test
	void pagarDosVecesLaMismaCuotaSeRechazaYCobraUnaSolaVez() {
		UUID ana = cuenta("Ana");
		UUID beto = cuentaConSaldo("Beto", pesos(100));
		UUID caro = cuenta("Caro");
		DetalleCobro cobro = crearCobro(ana, pesos(60), "La cena", beto, caro);
		UUID cuota = cuotaDe(cobro, beto);
		cobros.pagarCuota(cuota, llave());

		assertThatThrownBy(() -> cobros.pagarCuota(cuota, llave()))
			.isInstanceOf(OperacionNoPermitidaException.class)
			.hasMessageContaining("ya está pagada");
		assertThat(billetera.consultarSaldo(beto)).isEqualTo(pesos(70));
	}

	@Test
	void reintentarElPagoConLaMismaLlaveDevuelveElMismoPago() {
		UUID ana = cuenta("Ana");
		UUID beto = cuentaConSaldo("Beto", pesos(100));
		DetalleCobro cobro = crearCobro(ana, pesos(30), "La cena", beto);
		UUID cuota = cuotaDe(cobro, beto);
		String llave = llave();

		ResultadoPagoCuota primero = cobros.pagarCuota(cuota, llave);
		ResultadoPagoCuota reintento = cobros.pagarCuota(cuota, llave);

		assertThat(reintento.transaccion().transaccionId()).isEqualTo(primero.transaccion().transaccionId());
		assertThat(billetera.consultarSaldo(beto)).isEqualTo(pesos(70));
	}

	@Test
	void laLlaveDeUnPagoNoSirveParaPagarOtraCuota() {
		UUID ana = cuenta("Ana");
		UUID beto = cuentaConSaldo("Beto", pesos(100));
		DetalleCobro cena = crearCobro(ana, pesos(30), "La cena", beto);
		DetalleCobro taxi = crearCobro(ana, pesos(30), "La cena", beto);
		String llave = llave();
		cobros.pagarCuota(cuotaDe(cena, beto), llave);

		// Mismo deudor, mismo cobrador, mismo monto y misma descripción: aun así es otra cuota.
		assertThatThrownBy(() -> cobros.pagarCuota(cuotaDe(taxi, beto), llave))
			.isInstanceOf(ConflictoIdempotenciaException.class);
		assertThat(billetera.consultarSaldo(beto)).isEqualTo(pesos(70));
	}

	@Test
	void laLlaveDeUnaTransferenciaNoSirveParaPagarUnaCuota() {
		UUID ana = cuenta("Ana");
		UUID beto = cuentaConSaldo("Beto", pesos(100));
		DetalleCobro cobro = crearCobro(ana, pesos(30), "La cena", beto);
		String llave = llave();
		billetera.transferir(beto, ana, pesos(30), "La cena", llave);

		assertThatThrownBy(() -> cobros.pagarCuota(cuotaDe(cobro, beto), llave))
			.isInstanceOf(ConflictoIdempotenciaException.class);
	}

	@Test
	void sinFondosLaCuotaSiguePendienteYNoSeMueveNada() {
		UUID ana = cuenta("Ana");
		UUID beto = cuentaConSaldo("Beto", pesos(10));
		DetalleCobro cobro = crearCobro(ana, pesos(30), "La cena", beto);

		assertThatThrownBy(() -> cobros.pagarCuota(cuotaDe(cobro, beto), llave()))
			.isInstanceOf(FondosInsuficientesException.class);

		assertThat(cobros.verCobro(cobro.id()).cuotas()).extracting(DetalleCuota::estado)
			.containsExactly(EstadoCuota.PENDIENTE);
		assertThat(billetera.consultarSaldo(beto)).isEqualTo(pesos(10));
		assertThat(billetera.consultarSaldo(ana)).isZero();
	}

	@Test
	void pagarUnaCuotaQueNoExisteDa404() {
		assertThatThrownBy(() -> cobros.pagarCuota(UUID.randomUUID(), llave()))
			.isInstanceOf(CuotaNoEncontradaException.class);
	}

	// ---------- cancelar ----------

	@Test
	void cancelarAnulaLoPendienteYNoDevuelveLoPagado() {
		UUID ana = cuenta("Ana");
		UUID beto = cuentaConSaldo("Beto", pesos(100));
		UUID caro = cuenta("Caro");
		DetalleCobro cobro = crearCobro(ana, pesos(60), "La cena", beto, caro);
		cobros.pagarCuota(cuotaDe(cobro, beto), llave());

		DetalleCobro cancelado = cobros.cancelarCobro(cobro.id());

		assertThat(cancelado.estado()).isEqualTo(EstadoCobro.CANCELADO);
		assertThat(cancelado.cuotas()).extracting(DetalleCuota::estado)
			.containsExactly(EstadoCuota.PAGADA, EstadoCuota.CANCELADA);
		assertThat(billetera.consultarSaldo(ana)).isEqualTo(pesos(30));
		assertThat(billetera.consultarSaldo(beto)).isEqualTo(pesos(70));
		assertThat(cobros.cuotasPendientesDe(caro)).isEmpty();
	}

	@Test
	void noSePuedePagarUnaCuotaDeUnCobroCancelado() {
		UUID ana = cuenta("Ana");
		UUID beto = cuentaConSaldo("Beto", pesos(100));
		DetalleCobro cobro = crearCobro(ana, pesos(30), "La cena", beto);
		cobros.cancelarCobro(cobro.id());

		assertThatThrownBy(() -> cobros.pagarCuota(cuotaDe(cobro, beto), llave()))
			.isInstanceOf(OperacionNoPermitidaException.class)
			.hasMessageContaining("cancelado");
		assertThat(billetera.consultarSaldo(beto)).isEqualTo(pesos(100));
	}

	@Test
	void noSePuedeCancelarUnCobroCompleto() {
		UUID ana = cuenta("Ana");
		UUID beto = cuentaConSaldo("Beto", pesos(100));
		DetalleCobro cobro = crearCobro(ana, pesos(30), "La cena", beto);
		cobros.pagarCuota(cuotaDe(cobro, beto), llave());

		assertThatThrownBy(() -> cobros.cancelarCobro(cobro.id()))
			.isInstanceOf(OperacionNoPermitidaException.class);
		assertThat(cobros.verCobro(cobro.id()).estado()).isEqualTo(EstadoCobro.COMPLETADO);
	}

	@Test
	void cancelarDosVecesNoEsUnError() {
		UUID ana = cuenta("Ana");
		UUID beto = cuenta("Beto");
		DetalleCobro cobro = crearCobro(ana, pesos(30), "La cena", beto);

		cobros.cancelarCobro(cobro.id());
		DetalleCobro otraVez = cobros.cancelarCobro(cobro.id());

		assertThat(otraVez.estado()).isEqualTo(EstadoCobro.CANCELADO);
	}

	// ---------- consultas ----------

	@Test
	void cadaPersonaVeLoQueDebeYAQuien() {
		UUID ana = cuenta("Ana");
		UUID carlos = cuenta("Carlos");
		UUID beto = cuentaConSaldo("Beto", pesos(100));
		DetalleCobro cena = crearCobro(ana, pesos(30), "La cena", beto);
		crearCobro(carlos, pesos(20), "El taxi", beto);
		cobros.pagarCuota(cuotaDe(cena, beto), llave());

		List<CuotaPendiente> debe = cobros.cuotasPendientesDe(beto);

		assertThat(debe).singleElement().satisfies(cuota -> {
			assertThat(cuota.descripcion()).isEqualTo("El taxi");
			assertThat(cuota.cobradorTitular()).isEqualTo("Carlos");
			assertThat(cuota.montoCentavos()).isEqualTo(pesos(20));
		});
	}

	// ---------- protección de la base de datos (aunque el código Java falle) ----------

	@Test
	void laBaseDeDatosRechazaUnRepartoQueNoSumaElTotal() {
		UUID ana = cuenta("Ana");
		UUID beto = cuenta("Beto");
		UUID cobroId = UUID.randomUUID();

		// Simula un bug en el reparto: cobro de 100 centavos con una cuota de 99.
		assertThatThrownBy(() -> transaccionesBd.executeWithoutResult(estado -> {
			jdbc.sql("""
					INSERT INTO cobros (id, cobrador_cuenta_id, total_centavos, descripcion, llave_idempotencia)
					VALUES (:id, :ana, 100, 'Bug', :llave)""")
				.param("id", cobroId)
				.param("ana", ana)
				.param("llave", llave())
				.update();
			jdbc.sql("""
					INSERT INTO cuotas (id, cobro_id, orden, deudor_cuenta_id, monto_centavos)
					VALUES (:id, :cobro, 1, :beto, 99)""")
				.param("id", UUID.randomUUID())
				.param("cobro", cobroId)
				.param("beto", beto)
				.update();
		})).rootCause().hasMessageContaining("mal repartido");
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

	static UUID cuotaDe(DetalleCobro cobro, UUID deudor) {
		return cobro.cuotas().stream().filter(c -> c.deudorCuentaId().equals(deudor)).findFirst().orElseThrow().id();
	}

}
