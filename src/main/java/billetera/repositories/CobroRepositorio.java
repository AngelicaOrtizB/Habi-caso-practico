package billetera.repositories;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import billetera.Cobro;
import billetera.EstadoCobro;

@Repository
public class CobroRepositorio {

	private static final String COLUMNAS = "id, cobrador_cuenta_id, total_centavos, descripcion, estado, "
			+ "llave_idempotencia, creado_en";

	private static final RowMapper<Cobro> MAPEO = (rs, fila) -> new Cobro(rs.getObject("id", UUID.class),
			rs.getObject("cobrador_cuenta_id", UUID.class), rs.getLong("total_centavos"), rs.getString("descripcion"),
			EstadoCobro.valueOf(rs.getString("estado")), rs.getString("llave_idempotencia"),
			rs.getObject("creado_en", OffsetDateTime.class));

	private final JdbcClient jdbc;

	public CobroRepositorio(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void insertar(UUID id, UUID cobradorId, long totalCentavos, String descripcion, String llaveIdempotencia) {
		jdbc.sql("""
				INSERT INTO cobros (id, cobrador_cuenta_id, total_centavos, descripcion, llave_idempotencia)
				VALUES (:id, :cobrador, :total, :descripcion, :llave)""")
			.param("id", id)
			.param("cobrador", cobradorId)
			.param("total", totalCentavos)
			.param("descripcion", descripcion)
			.param("llave", llaveIdempotencia)
			.update();
	}

	public Optional<Cobro> buscarPorId(UUID id) {
		return jdbc.sql("SELECT " + COLUMNAS + " FROM cobros WHERE id = :id").param("id", id).query(MAPEO).optional();
	}

	public Optional<Cobro> buscarPorLlave(String llaveIdempotencia) {
		return jdbc.sql("SELECT " + COLUMNAS + " FROM cobros WHERE llave_idempotencia = :llave")
			.param("llave", llaveIdempotencia)
			.query(MAPEO)
			.optional();
	}

	/**
	 * Bloquea el cobro hasta que termine la transacción de BD. Pagar y cancelar lo
	 * bloquean PRIMERO, así que nunca corren al mismo tiempo sobre el mismo cobro.
	 */
	public Optional<Cobro> bloquear(UUID id) {
		return jdbc.sql("SELECT " + COLUMNAS + " FROM cobros WHERE id = :id FOR UPDATE")
			.param("id", id)
			.query(MAPEO)
			.optional();
	}

	public void cambiarEstado(UUID id, EstadoCobro estado) {
		jdbc.sql("UPDATE cobros SET estado = :estado WHERE id = :id")
			.param("estado", estado.name())
			.param("id", id)
			.update();
	}

	/** Los cobros que creó esta persona, del más nuevo al más viejo. */
	public List<Cobro> listarPorCobrador(UUID cobradorId) {
		return jdbc.sql("SELECT " + COLUMNAS + " FROM cobros WHERE cobrador_cuenta_id = :cobrador "
				+ "ORDER BY creado_en DESC, id")
			.param("cobrador", cobradorId)
			.query(MAPEO)
			.list();
	}

}
