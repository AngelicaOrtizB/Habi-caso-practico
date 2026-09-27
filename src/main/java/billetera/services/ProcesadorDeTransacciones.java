package billetera.services;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import billetera.ResultadoTransaccion;
import billetera.SolicitudTransaccion;

/**
 * Puerta de entrada para mover plata. El trabajo lo hace {@link EjecutorDeTransacciones}
 * dentro de una transacción; esta clase solo atrapa el choque de llaves de idempotencia.
 * <p>
 * Son dos clases (y no una) por cómo funciona {@code @Transactional}: Spring pone un
 * intermediario (proxy) entre las clases, y es ese intermediario el que abre y cierra la
 * transacción. Si este método llamara a un método {@code @Transactional} de su misma
 * clase, la llamada no pasaría por el intermediario y NO habría transacción.
 * <p>
 * Por lo mismo, este método NO debe ser {@code @Transactional}: el {@code catch} tiene que
 * correr FUERA de la transacción que falló, porque en Postgres una transacción con un
 * error ya no acepta más consultas.
 */
@Service
public class ProcesadorDeTransacciones {

	private final EjecutorDeTransacciones ejecutor;

	public ProcesadorDeTransacciones(EjecutorDeTransacciones ejecutor) {
		this.ejecutor = ejecutor;
	}

	public ResultadoTransaccion procesar(SolicitudTransaccion solicitud) {
		try {
			return ejecutor.ejecutar(solicitud);
		}
		catch (DuplicateKeyException e) {
			// Otro request con la misma llave hizo COMMIT mientras esperábamos. Nuestra
			// transacción ya hizo ROLLBACK (no movió nada): leemos el resultado ganador.
			return ejecutor.repetirSiYaExiste(solicitud).orElseThrow(() -> e);
		}
	}

}
