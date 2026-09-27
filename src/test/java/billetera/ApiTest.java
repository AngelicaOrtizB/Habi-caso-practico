package billetera;

import static billetera.BilleteraServicioTest.llave;
import static billetera.BilleteraServicioTest.pesos;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.jayway.jsonpath.JsonPath;

/** Prueba la API como la usaría una app: pedidos HTTP con JSON, headers y códigos de estado. */
@PruebaDeIntegracion
class ApiTest {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@AfterEach
	void laPlataCuadra() {
		VerificadorDePlata.verificar(jdbc);
	}

	@Test
	void flujoCompletoDeLaCena() {
		String ana = crearCuenta("Ana");
		String beto = crearCuenta("Beto");

		assertThat(cargar(ana, pesos(50_000), llave())).hasStatus(HttpStatus.OK)
			.bodyJson()
			.extractingPath("$.tipo")
			.isEqualTo("CARGA");

		assertThat(transferir(ana, beto, pesos(20_000), "La cena", llave())).hasStatus(HttpStatus.OK)
			.bodyJson()
			.extractingPath("$.descripcion")
			.isEqualTo("La cena");

		assertThat(mvc.get().uri("/cuentas/{id}", ana)).hasStatus(HttpStatus.OK)
			.bodyJson()
			.extractingPath("$.saldoCentavos")
			.isEqualTo(3_000_000);

		MvcTestResult historial = mvc.get().uri("/cuentas/{id}/historial", beto).exchange();
		assertThat(historial).hasStatus(HttpStatus.OK);
		assertThat(historial).bodyJson().extractingPath("$.lineas[0].montoCentavos").isEqualTo(2_000_000);
		assertThat(historial).bodyJson().extractingPath("$.lineas[0].contraparteTitular").isEqualTo("Ana");
	}

	@Test
	void crearCuentaResponde201ConSaldoCero() {
		assertThat(mvc.post().uri("/cuentas").contentType(MediaType.APPLICATION_JSON).content("""
				{"titular": "Ana"}""")).hasStatus(HttpStatus.CREATED).bodyJson().extractingPath("$.saldoCentavos").isEqualTo(0);
	}

	@Test
	void sinFondosResponde422ConMensajeEnEspanol() {
		String ana = crearCuenta("Ana");
		String beto = crearCuenta("Beto");

		assertThat(transferir(ana, beto, pesos(10), null, llave())).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
			.bodyJson()
			.extractingPath("$.detail")
			.asString()
			.contains("no tiene saldo suficiente");
	}

	@Test
	void cuentaInexistenteResponde404() {
		assertThat(mvc.get().uri("/cuentas/{id}", UUID.randomUUID())).hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void idMalEscritoResponde400() {
		assertThat(mvc.get().uri("/cuentas/no-es-un-id")).hasStatus(HttpStatus.BAD_REQUEST);
	}

	@Test
	void sinIdempotencyKeyResponde400YNoMuevePlata() {
		String ana = crearCuenta("Ana");

		assertThat(mvc.post().uri("/cuentas/{id}/cargas", ana).contentType(MediaType.APPLICATION_JSON).content("""
				{"montoCentavos": 1000}""")).hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.extractingPath("$.detail")
			.asString()
			.contains("Idempotency-Key");
		assertThat(saldo(ana)).isZero();
	}

	@Test
	void reintentoConLaMismaLlaveDevuelveLaMismaTransaccion() {
		String ana = crearCuenta("Ana");
		String llave = llave();

		String primera = idTransaccion(cargar(ana, pesos(100), llave));
		String reintento = idTransaccion(cargar(ana, pesos(100), llave));

		assertThat(reintento).isEqualTo(primera);
		assertThat(saldo(ana)).isEqualTo(pesos(100));
	}

	@Test
	void mismaLlaveConOtroMontoResponde409() {
		String ana = crearCuenta("Ana");
		String llave = llave();
		cargar(ana, pesos(100), llave);

		assertThat(cargar(ana, pesos(200), llave)).hasStatus(HttpStatus.CONFLICT);
		assertThat(saldo(ana)).isEqualTo(pesos(100));
	}

	@Test
	void montoConDecimalesSeRechazaEnVezDeRecortarse() {
		String ana = crearCuenta("Ana");

		assertThat(mvc.post().uri("/cuentas/{id}/cargas", ana).header("Idempotency-Key", llave())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"montoCentavos": 100.5}""")).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(saldo(ana)).isZero();
	}

	@Test
	void campoMalEscritoSeRechaza() {
		String ana = crearCuenta("Ana");

		assertThat(mvc.post().uri("/cuentas/{id}/cargas", ana).header("Idempotency-Key", llave())
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"monto": 1000}""")).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(saldo(ana)).isZero();
	}

	@Test
	void historialDevuelveElCursorDeLaPaginaSiguiente() {
		String ana = crearCuenta("Ana");
		for (int i = 1; i <= 3; i++) {
			cargar(ana, pesos(i), llave());
		}

		String pagina1 = cuerpo(mvc.get().uri("/cuentas/{id}/historial?limite=2", ana).exchange());
		Number siguiente = JsonPath.read(pagina1, "$.siguiente");
		String pagina2 = cuerpo(mvc.get().uri("/cuentas/{id}/historial?limite=2&antesDe={s}", ana, siguiente).exchange());

		List<Number> montos1 = JsonPath.read(pagina1, "$.lineas[*].montoCentavos");
		List<Number> montos2 = JsonPath.read(pagina2, "$.lineas[*].montoCentavos");
		assertThat(montos1).extracting(Number::longValue).containsExactly(pesos(3), pesos(2));
		assertThat(montos2).extracting(Number::longValue).containsExactly(pesos(1));
		assertThat((Object) JsonPath.read(pagina2, "$.siguiente")).isNull();
	}

	// ---------- ayudas ----------

	private String crearCuenta(String titular) {
		return JsonPath.read(cuerpo(mvc.post().uri("/cuentas").contentType(MediaType.APPLICATION_JSON).content("""
				{"titular": "%s"}""".formatted(titular)).exchange()), "$.id");
	}

	private MvcTestResult cargar(String cuentaId, long montoCentavos, String llave) {
		return mvc.post().uri("/cuentas/{id}/cargas", cuentaId).header("Idempotency-Key", llave)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"montoCentavos": %d}""".formatted(montoCentavos))
			.exchange();
	}

	private MvcTestResult transferir(String origen, String destino, long montoCentavos, String descripcion,
			String llave) {
		String descripcionJson = descripcion == null ? "null" : "\"" + descripcion + "\"";
		return mvc.post().uri("/transferencias").header("Idempotency-Key", llave)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"origenId": "%s", "destinoId": "%s", "montoCentavos": %d, "descripcion": %s}"""
				.formatted(origen, destino, montoCentavos, descripcionJson))
			.exchange();
	}

	private long saldo(String cuentaId) {
		return JsonPath.<Number>read(cuerpo(mvc.get().uri("/cuentas/{id}", cuentaId).exchange()), "$.saldoCentavos")
			.longValue();
	}

	private static String idTransaccion(MvcTestResult resultado) {
		return JsonPath.read(cuerpo(resultado), "$.transaccionId");
	}

	private static String cuerpo(MvcTestResult resultado) {
		try {
			return resultado.getResponse().getContentAsString(StandardCharsets.UTF_8);
		}
		catch (UnsupportedEncodingException e) {
			throw new IllegalStateException(e);
		}
	}

}
