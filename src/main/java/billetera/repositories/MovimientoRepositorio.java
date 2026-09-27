package billetera.repositories;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import billetera.LineaHistorial;
import billetera.Movimiento;
import billetera.TipoTransaccion;

/** La tabla movimientos solo admite INSERT: no hay métodos para editar ni borrar. */
@Repository
public class MovimientoRepositorio {

	private static final RowMapper<Movimiento> MAPEO = (rs, fila) -> new Movimiento(rs.getLong("id"),
			rs.getObject("transaccion_id", UUID.class), rs.getObject("cuenta_id", UUID.class),
			rs.getLong("monto_centavos"), rs.getObject("creado_en", OffsetDateTime.class));

	private static final RowMapper<LineaHistorial> MAPEO_HISTORIAL = (rs, fila) -> new LineaHistorial(
			rs.getLong("id"), rs.getObject("transaccion_id", UUID.class), TipoTransaccion.valueOf(rs.getString("tipo")),
			rs.getLong("monto_centavos"), rs.getObject("contraparte_id", UUID.class),
			rs.getString("contraparte_titular"), rs.getString("descripcion"),
			rs.getObject("creado_en", OffsetDateTime.class));

	private final JdbcClient jdbc;

	public MovimientoRepositorio(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void insertar(UUID transaccionId, UUID cuentaId, long montoCentavos) {
		jdbc.sql("INSERT INTO movimientos (transaccion_id, cuenta_id, monto_centavos) VALUES (:tx, :cuenta, :monto)")
			.param("tx", transaccionId)
			.param("cuenta", cuentaId)
			.param("monto", montoCentavos)
			.update();
	}

	public List<Movimiento> buscarPorTransaccion(UUID transaccionId) {
		return jdbc
			.sql("SELECT id, transaccion_id, cuenta_id, monto_centavos, creado_en FROM movimientos "
					+ "WHERE transaccion_id = :tx ORDER BY id")
			.param("tx", transaccionId)
			.query(MAPEO)
			.list();
	}

	/**
	 * Historial de la cuenta, del más nuevo al más viejo. Cada transacción tiene
	 * exactamente dos movimientos, así que "el otro" movimiento de la misma
	 * transacción dice quién es la contraparte.
	 *
	 * @param antesDeId solo devuelve movimientos con id menor a este (cursor de paginación)
	 */
	public List<LineaHistorial> buscarHistorial(UUID cuentaId, long antesDeId, int limite) {
		return jdbc.sql("""
				SELECT m.id, m.transaccion_id, t.tipo, m.monto_centavos, t.descripcion, m.creado_en,
				       c.id AS contraparte_id, c.titular AS contraparte_titular
				FROM movimientos m
				JOIN transacciones t ON t.id = m.transaccion_id
				JOIN movimientos otro ON otro.transaccion_id = m.transaccion_id AND otro.cuenta_id <> m.cuenta_id
				JOIN cuentas c ON c.id = otro.cuenta_id
				WHERE m.cuenta_id = :cuenta AND m.id < :antes
				ORDER BY m.id DESC
				LIMIT :limite""")
			.param("cuenta", cuentaId)
			.param("antes", antesDeId)
			.param("limite", limite)
			.query(MAPEO_HISTORIAL)
			.list();
	}

}
