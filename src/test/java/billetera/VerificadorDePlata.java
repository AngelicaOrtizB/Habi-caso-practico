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

		verificarCobros(jdbc);
	}

	/** Reglas de los cobros divididos: que el reparto cuadre y que "pagada" signifique plata movida. */
	private static void verificarCobros(JdbcClient jdbc) {
		assertThat(contar(jdbc, """
				SELECT count(*) FROM cobros c
				WHERE c.total_centavos <> (SELECT SUM(q.monto_centavos) FROM cuotas q WHERE q.cobro_id = c.id)"""))
			.as("las cuotas de cada cobro deben sumar exactamente su total")
			.isZero();

		assertThat(contar(jdbc, """
				SELECT count(*) FROM cuotas q
				JOIN cobros c ON c.id = q.cobro_id
				WHERE q.estado = 'PAGADA' AND NOT EXISTS (
				    SELECT 1 FROM transacciones t
				    JOIN movimientos sale ON sale.transaccion_id = t.id
				        AND sale.cuenta_id = q.deudor_cuenta_id AND sale.monto_centavos = -q.monto_centavos
				    JOIN movimientos entra ON entra.transaccion_id = t.id
				        AND entra.cuenta_id = c.cobrador_cuenta_id AND entra.monto_centavos = q.monto_centavos
				    WHERE t.id = q.transaccion_id AND t.tipo = 'PAGO_CUOTA')"""))
			.as("cada cuota pagada debe tener su pago: el monto exacto, del deudor a quien cobra")
			.isZero();

		assertThat(contar(jdbc, """
				SELECT count(*) FROM transacciones t
				WHERE t.tipo = 'PAGO_CUOTA' AND NOT EXISTS (SELECT 1 FROM cuotas q WHERE q.transaccion_id = t.id)"""))
			.as("no puede haber un pago de cuota que no haya marcado ninguna cuota como pagada")
			.isZero();

		assertThat(contar(jdbc, """
				SELECT count(*) FROM cobros c
				WHERE (c.estado = 'COMPLETADO' AND EXISTS (
				          SELECT 1 FROM cuotas q WHERE q.cobro_id = c.id AND q.estado <> 'PAGADA'))
				   OR (c.estado = 'ABIERTO' AND NOT EXISTS (
				          SELECT 1 FROM cuotas q WHERE q.cobro_id = c.id AND q.estado = 'PENDIENTE'))
				   OR (c.estado = 'ABIERTO' AND EXISTS (
				          SELECT 1 FROM cuotas q WHERE q.cobro_id = c.id AND q.estado = 'CANCELADA'))
				   OR (c.estado = 'CANCELADO' AND EXISTS (
				          SELECT 1 FROM cuotas q WHERE q.cobro_id = c.id AND q.estado = 'PENDIENTE'))"""))
			.as("el estado de cada cobro debe coincidir con el de sus cuotas")
			.isZero();
	}

	private static long contar(JdbcClient jdbc, String sql) {
		return jdbc.sql(sql).query(Long.class).single();
	}

}
