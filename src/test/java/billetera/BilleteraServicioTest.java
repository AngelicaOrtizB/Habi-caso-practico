package billetera;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import billetera.services.BilleteraServicio;

/**
 * Cada test crea sus propias cuentas, así que no hace falta limpiar la base entre
 * tests (y no se podría: la tabla movimientos no deja borrar).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BilleteraServicioTest {

	private static final UUID FONDEO = BilleteraServicio.CUENTA_FONDEO_EXTERNO;

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

	// ---------- crear cuenta ----------

	@Test
	void cuentaNuevaEmpiezaEnCero() {
		Cuenta cuenta = billetera.crearCuenta("  Ana  ");

		assertThat(cuenta.titular()).isEqualTo("Ana");
		assertThat(cuenta.tipo()).isEqualTo(TipoCuenta.USUARIO);
		assertThat(billetera.consultarSaldo(cuenta.id())).isZero();
	}

	@Test
	void crearCuentaRechazaNombreVacio() {
		assertThatThrownBy(() -> billetera.crearCuenta("   ")).isInstanceOf(OperacionInvalidaException.class);
	}

	// ---------- cargar saldo ----------

	@Test
	void cargarSaldoSumaALaCuentaYRestaDeLaCuentaDeFondeo() {
		Cuenta ana = billetera.crearCuenta("Ana");
		long fondeoAntes = billetera.consultarSaldo(FONDEO);

		ResultadoTransaccion resultado = billetera.cargarSaldo(ana.id(), pesos(50_000), llave());

		assertThat(resultado.tipo()).isEqualTo(TipoTransaccion.CARGA);
		assertThat(billetera.consultarSaldo(ana.id())).isEqualTo(pesos(50_000));
		assertThat(billetera.consultarSaldo(FONDEO)).isEqualTo(fondeoAntes - pesos(50_000));
	}

	@Test
	void cargarSaldoRechazaMontoCeroONegativo() {
		Cuenta ana = billetera.crearCuenta("Ana");

		assertThatThrownBy(() -> billetera.cargarSaldo(ana.id(), 0, llave()))
			.isInstanceOf(OperacionInvalidaException.class);
		assertThatThrownBy(() -> billetera.cargarSaldo(ana.id(), -100, llave()))
			.isInstanceOf(OperacionInvalidaException.class);
		assertThat(billetera.consultarSaldo(ana.id())).isZero();
	}

	@Test
	void cargarSaldoACuentaInexistenteFalla() {
		assertThatThrownBy(() -> billetera.cargarSaldo(UUID.randomUUID(), pesos(1_000), llave()))
			.isInstanceOf(CuentaNoEncontradaException.class);
	}

	// ---------- transferir ----------

	@Test
	void transferirMueveLaPlata() {
		Cuenta ana = cuentaConSaldo("Ana", pesos(50_000));
		Cuenta beto = billetera.crearCuenta("Beto");

		ResultadoTransaccion resultado = billetera.transferir(ana.id(), beto.id(), pesos(20_000), "La cena", llave());

		assertThat(resultado.descripcion()).isEqualTo("La cena");
		assertThat(billetera.consultarSaldo(ana.id())).isEqualTo(pesos(30_000));
		assertThat(billetera.consultarSaldo(beto.id())).isEqualTo(pesos(20_000));
	}

	@Test
	void transferirPuedeDejarLaCuentaEnCeroExacto() {
		Cuenta ana = cuentaConSaldo("Ana", pesos(100));
		Cuenta beto = billetera.crearCuenta("Beto");

		billetera.transferir(ana.id(), beto.id(), pesos(100), null, llave());

		assertThat(billetera.consultarSaldo(ana.id())).isZero();
	}

	@Test
	void transferirSinFondosSuficientesNoCambiaNada() {
		Cuenta ana = cuentaConSaldo("Ana", pesos(100));
		Cuenta beto = billetera.crearCuenta("Beto");

		assertThatThrownBy(() -> billetera.transferir(ana.id(), beto.id(), pesos(100) + 1, null, llave()))
			.isInstanceOf(FondosInsuficientesException.class);

		assertThat(billetera.consultarSaldo(ana.id())).isEqualTo(pesos(100));
		assertThat(billetera.consultarSaldo(beto.id())).isZero();
	}

	@Test
	void unaOperacionRechazadaNoGastaLaLlave() {
		Cuenta ana = billetera.crearCuenta("Ana");
		Cuenta beto = billetera.crearCuenta("Beto");
		String llave = llave();

		assertThatThrownBy(() -> billetera.transferir(ana.id(), beto.id(), pesos(10), null, llave))
			.isInstanceOf(FondosInsuficientesException.class);

		// Ana carga saldo y reintenta con la misma llave: ahora sí debe pasar.
		billetera.cargarSaldo(ana.id(), pesos(10), llave());
		billetera.transferir(ana.id(), beto.id(), pesos(10), null, llave);

		assertThat(billetera.consultarSaldo(beto.id())).isEqualTo(pesos(10));
	}

	@Test
	void transferirALaMismaCuentaSeRechaza() {
		Cuenta ana = cuentaConSaldo("Ana", pesos(100));

		assertThatThrownBy(() -> billetera.transferir(ana.id(), ana.id(), pesos(10), null, llave()))
			.isInstanceOf(OperacionInvalidaException.class);
	}

	@Test
	void laCuentaDeFondeoNoSePuedeUsarEnTransferencias() {
		Cuenta ana = cuentaConSaldo("Ana", pesos(100));

		assertThatThrownBy(() -> billetera.transferir(FONDEO, ana.id(), pesos(10), null, llave()))
			.isInstanceOf(OperacionInvalidaException.class);
		assertThatThrownBy(() -> billetera.transferir(ana.id(), FONDEO, pesos(10), null, llave()))
			.isInstanceOf(OperacionInvalidaException.class);
		assertThat(billetera.consultarSaldo(ana.id())).isEqualTo(pesos(100));
	}

	@Test
	void transferirACuentaInexistenteNoMueveNada() {
		Cuenta ana = cuentaConSaldo("Ana", pesos(100));

		assertThatThrownBy(() -> billetera.transferir(ana.id(), UUID.randomUUID(), pesos(10), null, llave()))
			.isInstanceOf(CuentaNoEncontradaException.class);
		assertThat(billetera.consultarSaldo(ana.id())).isEqualTo(pesos(100));
	}

	// ---------- idempotencia ----------

	@Test
	void reintentarConLaMismaLlaveDevuelveElMismoResultadoSinMoverPlataOtraVez() {
		Cuenta ana = cuentaConSaldo("Ana", pesos(100));
		Cuenta beto = billetera.crearCuenta("Beto");
		String llave = llave();

		ResultadoTransaccion primero = billetera.transferir(ana.id(), beto.id(), pesos(30), "Arriendo", llave);
		ResultadoTransaccion reintento = billetera.transferir(ana.id(), beto.id(), pesos(30), "Arriendo", llave);

		assertThat(reintento).isEqualTo(primero);
		assertThat(billetera.consultarSaldo(ana.id())).isEqualTo(pesos(70));
		assertThat(billetera.consultarSaldo(beto.id())).isEqualTo(pesos(30));
	}

	@Test
	void reintentarUnaCargaConLaMismaLlaveCargaUnaSolaVez() {
		Cuenta ana = billetera.crearCuenta("Ana");
		String llave = llave();

		billetera.cargarSaldo(ana.id(), pesos(100), llave);
		billetera.cargarSaldo(ana.id(), pesos(100), llave);

		assertThat(billetera.consultarSaldo(ana.id())).isEqualTo(pesos(100));
	}

	@Test
	void laMismaLlaveConOtrosDatosEsUnConflicto() {
		Cuenta ana = cuentaConSaldo("Ana", pesos(100));
		Cuenta beto = billetera.crearCuenta("Beto");
		String llave = llave();
		billetera.transferir(ana.id(), beto.id(), pesos(30), null, llave);

		assertThatThrownBy(() -> billetera.transferir(ana.id(), beto.id(), pesos(31), null, llave))
			.isInstanceOf(ConflictoIdempotenciaException.class);
		assertThat(billetera.consultarSaldo(ana.id())).isEqualTo(pesos(70));
	}

	// ---------- historial ----------

	@Test
	void historialMuestraMontosConSignoYContraparteDelMasNuevoAlMasViejo() {
		Cuenta ana = cuentaConSaldo("Ana", pesos(100));
		Cuenta beto = billetera.crearCuenta("Beto");
		billetera.transferir(ana.id(), beto.id(), pesos(40), "La cena", llave());

		List<LineaHistorial> historial = billetera.verHistorial(ana.id(), null, 10);

		assertThat(historial).hasSize(2);
		LineaHistorial transferencia = historial.get(0);
		assertThat(transferencia.tipo()).isEqualTo(TipoTransaccion.TRANSFERENCIA);
		assertThat(transferencia.montoCentavos()).isEqualTo(-pesos(40));
		assertThat(transferencia.contraparteTitular()).isEqualTo("Beto");
		assertThat(transferencia.descripcion()).isEqualTo("La cena");
		LineaHistorial carga = historial.get(1);
		assertThat(carga.tipo()).isEqualTo(TipoTransaccion.CARGA);
		assertThat(carga.montoCentavos()).isEqualTo(pesos(100));
		assertThat(carga.contraparteId()).isEqualTo(FONDEO);

		assertThat(billetera.verHistorial(beto.id(), null, 10)).singleElement()
			.satisfies(linea -> assertThat(linea.montoCentavos()).isEqualTo(pesos(40)));
	}

	@Test
	void historialSePaginaConCursor() {
		Cuenta ana = billetera.crearCuenta("Ana");
		for (int i = 1; i <= 5; i++) {
			billetera.cargarSaldo(ana.id(), pesos(i), llave());
		}

		List<LineaHistorial> pagina1 = billetera.verHistorial(ana.id(), null, 2);
		List<LineaHistorial> pagina2 = billetera.verHistorial(ana.id(), pagina1.get(1).movimientoId(), 2);
		List<LineaHistorial> pagina3 = billetera.verHistorial(ana.id(), pagina2.get(1).movimientoId(), 2);

		assertThat(pagina1).extracting(LineaHistorial::montoCentavos).containsExactly(pesos(5), pesos(4));
		assertThat(pagina2).extracting(LineaHistorial::montoCentavos).containsExactly(pesos(3), pesos(2));
		assertThat(pagina3).extracting(LineaHistorial::montoCentavos).containsExactly(pesos(1));
	}

	@Test
	void historialDeCuentaInexistenteFalla() {
		assertThatThrownBy(() -> billetera.verHistorial(UUID.randomUUID(), null, 10))
			.isInstanceOf(CuentaNoEncontradaException.class);
	}

	// ---------- protecciones de la base de datos (aunque el código Java falle) ----------

	@Test
	void laBaseDeDatosNoDejaEditarNiBorrarMovimientos() {
		Cuenta ana = cuentaConSaldo("Ana", pesos(100));

		assertThatThrownBy(() -> jdbc.sql("UPDATE movimientos SET monto_centavos = 1 WHERE cuenta_id = :c")
			.param("c", ana.id())
			.update()).hasMessageContaining("inmutable");
		assertThatThrownBy(() -> jdbc.sql("DELETE FROM movimientos WHERE cuenta_id = :c").param("c", ana.id()).update())
			.hasMessageContaining("inmutable");
	}

	@Test
	void laBaseDeDatosRechazaUnaTransaccionQueNoSumaCero() {
		Cuenta ana = billetera.crearCuenta("Ana");
		UUID transaccionId = UUID.randomUUID();

		// Simula un bug: escribe la entrada de plata pero "olvida" la salida.
		assertThatThrownBy(() -> transaccionesBd.executeWithoutResult(estado -> {
			jdbc.sql("INSERT INTO transacciones (id, tipo, llave_idempotencia) VALUES (:id, 'CARGA', :llave)")
				.param("id", transaccionId)
				.param("llave", llave())
				.update();
			jdbc.sql("INSERT INTO movimientos (transaccion_id, cuenta_id, monto_centavos) VALUES (:tx, :c, 100)")
				.param("tx", transaccionId)
				.param("c", ana.id())
				.update();
		})).rootCause().hasMessageContaining("descuadrada");
	}

	@Test
	void laBaseDeDatosNoDejaUnSaldoDeUsuarioNegativo() {
		Cuenta ana = billetera.crearCuenta("Ana");

		assertThatThrownBy(() -> jdbc.sql("UPDATE cuentas SET saldo_centavos = -1 WHERE id = :id")
			.param("id", ana.id())
			.update()).hasMessageContaining("chk_saldo_no_negativo");
	}

	// ---------- ayudas ----------

	private Cuenta cuentaConSaldo(String titular, long centavos) {
		Cuenta cuenta = billetera.crearCuenta(titular);
		billetera.cargarSaldo(cuenta.id(), centavos, llave());
		return cuenta;
	}

	static long pesos(long pesos) {
		return pesos * 100;
	}

	static String llave() {
		return UUID.randomUUID().toString();
	}

}
