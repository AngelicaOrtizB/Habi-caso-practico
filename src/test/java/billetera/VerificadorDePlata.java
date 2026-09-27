package billetera;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Revisa directo en la base de datos las reglas que garantizan que no se pierde ni
 * se crea un peso. Se corre después de cada test, sin importar qué hizo el test.
 */
final class VerificadorDePlata {

	private VerificadorDePlata() {
	}

	static void verificar(JdbcClient jdbc) {
		assertThat(contar(jdbc, "SELECT COALESCE(SUM(saldo_centavos), 0) FROM cuentas"))
			.as("la suma de todos los saldos (incluida la cuenta de fondeo) debe ser 0")
			.isZero();

		assertThat(contar(jdbc, "SELECT count(*) FROM cuentas WHERE tipo = 'USUARIO' AND saldo_centavos < 0"))
			.as("ninguna cuenta de usuario puede tener saldo negativo")
			.isZero();

		assertThat(contar(jdbc, """
				SELECT count(*) FROM cuentas c
				WHERE c.saldo_centavos <> COALESCE(
				    (SELECT SUM(m.monto_centavos) FROM movimientos m WHERE m.cuenta_id = c.id), 0)"""))
			.as("el saldo de cada cuenta debe ser igual a la suma de sus movimientos")
			.isZero();

		assertThat(contar(jdbc, """
				SELECT count(*) FROM (
				    SELECT transaccion_id FROM movimientos
				    GROUP BY transaccion_id HAVING SUM(monto_centavos) <> 0) descuadradas"""))
			.as("los movimientos de cada transacción deben sumar 0")
			.isZero();

		assertThat(contar(jdbc, """
				SELECT count(*) FROM transacciones t
				WHERE (SELECT count(*) FROM movimientos m WHERE m.transaccion_id = t.id) <> 2"""))
			.as("cada transacción debe tener exactamente dos movimientos")
			.isZero();
	}

	private static long contar(JdbcClient jdbc, String sql) {
		return jdbc.sql(sql).query(Long.class).single();
	}

}
