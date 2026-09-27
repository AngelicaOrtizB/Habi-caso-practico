package billetera.repositories;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import billetera.TipoTransaccion;
import billetera.TransaccionGuardada;

@Repository
public class TransaccionRepositorio {

	private static final RowMapper<TransaccionGuardada> MAPEO = (rs, fila) -> new TransaccionGuardada(
			rs.getObject("id", UUID.class), TipoTransaccion.valueOf(rs.getString("tipo")),
			rs.getString("llave_idempotencia"), rs.getString("descripcion"),
			rs.getObject("creada_en", OffsetDateTime.class));

	private final JdbcClient jdbc;

	public TransaccionRepositorio(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Si otra transacción de BD sin terminar ya insertó la misma llave, este INSERT
	 * espera a que termine: si ella hace COMMIT falla con llave duplicada, y si hace
	 * ROLLBACK este sigue normalmente.
	 *
	 * @return la fecha de creación que asignó la base de datos
	 */
	public OffsetDateTime insertar(UUID id, TipoTransaccion tipo, String llaveIdempotencia, String descripcion) {
		return jdbc
			.sql("""
					INSERT INTO transacciones (id, tipo, llave_idempotencia, descripcion)
					VALUES (:id, :tipo, :llave, :descripcion)
					RETURNING creada_en""")
			.param("id", id)
			.param("tipo", tipo.name())
			.param("llave", llaveIdempotencia)
			.param("descripcion", descripcion, Types.VARCHAR)
			.query(OffsetDateTime.class)
			.single();
	}

	public Optional<TransaccionGuardada> buscarPorLlave(String llaveIdempotencia) {
		return jdbc
			.sql("SELECT id, tipo, llave_idempotencia, descripcion, creada_en FROM transacciones "
					+ "WHERE llave_idempotencia = :llave")
			.param("llave", llaveIdempotencia)
			.query(MAPEO)
			.optional();
	}

}
