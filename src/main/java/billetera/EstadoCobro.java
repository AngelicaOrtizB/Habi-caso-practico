package billetera;

public enum EstadoCobro {

	/** Tiene al menos una cuota pendiente. */
	ABIERTO,

	/** Todas las cuotas se pagaron. Pasa a este estado en la misma transacción del último pago. */
	COMPLETADO,

	/** Quien cobra lo canceló: las cuotas pendientes quedaron canceladas y las pagadas no se devuelven. */
	CANCELADO

}
