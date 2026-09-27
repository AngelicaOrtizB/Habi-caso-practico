package billetera.repositories;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import billetera.Cuenta;
import billetera.TipoCuenta;

@Repository
public class CuentaRepositorio {

	private static final String COLUMNAS = "id, titular, tipo, saldo_centavos, creada_en";

	private static final RowMapper<Cuenta> MAPEO = (rs, fila) -> new Cuenta(rs.getObject("id", UUID.class),
			rs.getString("titular"), TipoCuenta.valueOf(rs.getString("tipo")), rs.getLong("saldo_centavos"),
			rs.getObject("creada_en", OffsetDateTime.class));

	private final JdbcClient jdbc;

	public CuentaRepositorio(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Cuenta insertarCuentaUsuario(UUID id, String titular) {
		return jdbc
			.sql("INSERT INTO cuentas (id, titular, tipo) VALUES (:id, :titular, 'USUARIO') RETURNING " + COLUMNAS)
			.param("id", id)
			.param("titular", titular)
			.query(MAPEO)
			.single();
	}

	/** Solo cuentas de usuario: la de fondeo es interna y no se puede usar desde la API. */
	public List<Cuenta> listarCuentasDeUsuario() {
		return jdbc.sql("SELECT " + COLUMNAS + " FROM cuentas WHERE tipo = 'USUARIO' ORDER BY creada_en, id")
			.query(MAPEO)
			.list();
	}

	public Optional<Cuenta> buscarPorId(UUID id) {
		return jdbc.sql("SELECT " + COLUMNAS + " FROM cuentas WHERE id = :id").param("id", id).query(MAPEO).optional();
	}

	/**
	 * Bloquea la fila hasta que termine la transacción de BD: cualquier otra operación
	 * que quiera bloquear la misma cuenta espera aquí. Por eso el saldo que devuelve ya
	 * no puede cambiar mientras validamos y debitamos.
	 */
	public Optional<Cuenta> bloquear(UUID id) {
		return jdbc.sql("SELECT " + COLUMNAS + " FROM cuentas WHERE id = :id FOR UPDATE")
			.param("id", id)
			.query(MAPEO)
			.optional();
	}

	/**
	 * La suma se hace en SQL y no en Java: si el resultado no cabe en un BIGINT, Postgres
	 * lanza un error en vez de dar la vuelta a un número negativo como haría un long.
	 */
	public void sumarAlSaldo(UUID id, long montoCentavos) {
		int filas = jdbc.sql("UPDATE cuentas SET saldo_centavos = saldo_centavos + :monto WHERE id = :id")
			.param("monto", montoCentavos)
			.param("id", id)
			.update();
		if (filas != 1) {
			throw new IllegalStateException("No se pudo actualizar el saldo de la cuenta " + id);
		}
	}

}
