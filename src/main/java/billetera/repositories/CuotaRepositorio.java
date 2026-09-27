package billetera.repositories;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import billetera.Cuota;
import billetera.CuotaPendiente;
import billetera.DetalleCuota;
import billetera.EstadoCuota;

@Repository
public class CuotaRepositorio {

	private static final String COLUMNAS = "id, cobro_id, deudor_cuenta_id, orden, monto_centavos, estado, "
			+ "transaccion_id, pagada_en";

	private static final RowMapper<Cuota> MAPEO = (rs, fila) -> new Cuota(rs.getObject("id", UUID.class),
			rs.getObject("cobro_id", UUID.class), rs.getObject("deudor_cuenta_id", UUID.class), rs.getInt("orden"),
			rs.getLong("monto_centavos"), EstadoCuota.valueOf(rs.getString("estado")),
			rs.getObject("transaccion_id", UUID.class), rs.getObject("pagada_en", OffsetDateTime.class));

	private final JdbcClient jdbc;

	public CuotaRepositorio(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void insertar(UUID id, UUID cobroId, int orden, UUID deudorId, long montoCentavos) {
		jdbc.sql("""
				INSERT INTO cuotas (id, cobro_id, orden, deudor_cuenta_id, monto_centavos)
				VALUES (:id, :cobro, :orden, :deudor, :monto)""")
			.param("id", id)
			.param("cobro", cobroId)
			.param("orden", orden)
			.param("deudor", deudorId)
			.param("monto", montoCentavos)
			.update();
	}

	public Optional<Cuota> buscarPorId(UUID id) {
		return jdbc.sql("SELECT " + COLUMNAS + " FROM cuotas WHERE id = :id").param("id", id).query(MAPEO).optional();
	}

	/** Se llama con el cobro ya bloqueado: el orden de bloqueo es siempre cobro → cuota → cuentas. */
	public Optional<Cuota> bloquear(UUID id) {
		return jdbc.sql("SELECT " + COLUMNAS + " FROM cuotas WHERE id = :id FOR UPDATE")
			.param("id", id)
			.query(MAPEO)
			.optional();
	}

	public List<UUID> deudoresEnOrden(UUID cobroId) {
		return jdbc.sql("SELECT deudor_cuenta_id FROM cuotas WHERE cobro_id = :cobro ORDER BY orden")
			.param("cobro", cobroId)
			.query(UUID.class)
			.list();
	}

	public List<DetalleCuota> buscarDetallePorCobro(UUID cobroId) {
		return jdbc.sql("""
				SELECT q.id, q.deudor_cuenta_id, c.titular, q.monto_centavos, q.estado, q.pagada_en
				FROM cuotas q
				JOIN cuentas c ON c.id = q.deudor_cuenta_id
				WHERE q.cobro_id = :cobro
				ORDER BY q.orden""")
			.param("cobro", cobroId)
			.query((rs, fila) -> new DetalleCuota(rs.getObject("id", UUID.class),
					rs.getObject("deudor_cuenta_id", UUID.class), rs.getString("titular"),
					rs.getLong("monto_centavos"), EstadoCuota.valueOf(rs.getString("estado")),
					rs.getObject("pagada_en", OffsetDateTime.class)))
			.list();
	}

	/** Lo que debe esta persona: sus cuotas pendientes, de la más vieja a la más nueva. */
	public List<CuotaPendiente> pendientesDe(UUID deudorId) {
		return jdbc.sql("""
				SELECT q.id, q.cobro_id, co.descripcion, co.cobrador_cuenta_id, c.titular, q.monto_centavos,
				       co.creado_en
				FROM cuotas q
				JOIN cobros co ON co.id = q.cobro_id
				JOIN cuentas c ON c.id = co.cobrador_cuenta_id
				WHERE q.deudor_cuenta_id = :deudor AND q.estado = 'PENDIENTE'
				ORDER BY co.creado_en, q.id""")
			.param("deudor", deudorId)
			.query((rs, fila) -> new CuotaPendiente(rs.getObject("id", UUID.class),
					rs.getObject("cobro_id", UUID.class), rs.getString("descripcion"),
					rs.getObject("cobrador_cuenta_id", UUID.class), rs.getString("titular"),
					rs.getLong("monto_centavos"), rs.getObject("creado_en", OffsetDateTime.class)))
			.list();
	}

	/** El WHERE estado = 'PENDIENTE' es otra defensa: una cuota pagada nunca se vuelve a marcar. */
	public void marcarPagada(UUID id, UUID transaccionId) {
		int filas = jdbc.sql("""
				UPDATE cuotas SET estado = 'PAGADA', transaccion_id = :tx, pagada_en = now()
				WHERE id = :id AND estado = 'PENDIENTE'""")
			.param("tx", transaccionId)
			.param("id", id)
			.update();
		if (filas != 1) {
			throw new IllegalStateException("La cuota " + id + " no estaba pendiente");
		}
	}

	public void cancelarPendientes(UUID cobroId) {
		jdbc.sql("UPDATE cuotas SET estado = 'CANCELADA' WHERE cobro_id = :cobro AND estado = 'PENDIENTE'")
			.param("cobro", cobroId)
			.update();
	}

	public long contarPendientes(UUID cobroId) {
		return jdbc.sql("SELECT count(*) FROM cuotas WHERE cobro_id = :cobro AND estado = 'PENDIENTE'")
			.param("cobro", cobroId)
			.query(Long.class)
			.single();
	}

}
