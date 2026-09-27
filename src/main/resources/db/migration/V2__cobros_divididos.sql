-- V2: cobros divididos.
-- Ejemplo: Ana pagó la cena de $100.000 y le cobra su parte a Beto, Caro y Dani.
-- Un cobro tiene una cuota por persona; pagar una cuota mueve plata del deudor a
-- quien cobra, con los mismos movimientos (que suman 0) que una transferencia.

-- ============================================================
-- Nuevo tipo de transacción: el pago de una cuota
-- ============================================================
-- En el historial aparece como "pago de cuota", no como una transferencia suelta.
ALTER TABLE transacciones DROP CONSTRAINT transacciones_tipo_check;
ALTER TABLE transacciones ADD CONSTRAINT chk_tipo_transaccion
    CHECK (tipo IN ('CARGA', 'TRANSFERENCIA', 'PAGO_CUOTA'));

-- ============================================================
-- COBROS
-- ============================================================
CREATE TABLE cobros (
    id                 UUID PRIMARY KEY,
    -- Quien pagó y ahora cobra. No tiene cuota: su parte ya la puso.
    cobrador_cuenta_id UUID         NOT NULL REFERENCES cuentas(id),
    total_centavos     BIGINT       NOT NULL CHECK (total_centavos > 0),
    descripcion        VARCHAR(255) NOT NULL,
    -- Crear un cobro no mueve plata, pero si un reintento lo creara dos veces,
    -- los deudores verían dos cobros y podrían pagar dos veces.
    llave_idempotencia VARCHAR(100) NOT NULL UNIQUE,
    estado             VARCHAR(12)  NOT NULL DEFAULT 'ABIERTO'
                       CHECK (estado IN ('ABIERTO', 'COMPLETADO', 'CANCELADO')),
    creado_en          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_cobros_cobrador ON cobros(cobrador_cuenta_id, creado_en DESC);

-- ============================================================
-- CUOTAS (lo que debe cada persona)
-- ============================================================
CREATE TABLE cuotas (
    id                UUID PRIMARY KEY,
    cobro_id          UUID        NOT NULL REFERENCES cobros(id),
    deudor_cuenta_id  UUID        NOT NULL REFERENCES cuentas(id),
    -- 1, 2, 3... en el orden en que se pidió el cobro. Importa porque el
    -- centavo que sobra del reparto se lo llevan los primeros.
    orden             INT         NOT NULL CHECK (orden > 0),
    monto_centavos    BIGINT      NOT NULL CHECK (monto_centavos > 0),
    estado            VARCHAR(10) NOT NULL DEFAULT 'PENDIENTE'
                      CHECK (estado IN ('PENDIENTE', 'PAGADA', 'CANCELADA')),
    -- La transacción con la que se pagó. UNIQUE: una misma transacción
    -- no puede "pagar" dos cuotas.
    transaccion_id    UUID UNIQUE REFERENCES transacciones(id),
    pagada_en         TIMESTAMPTZ,
    -- Una persona tiene una sola cuota por cobro.
    CONSTRAINT uq_una_cuota_por_persona UNIQUE (cobro_id, deudor_cuenta_id),
    CONSTRAINT uq_orden_en_el_cobro UNIQUE (cobro_id, orden),
    -- PAGADA si y solo si tiene la transacción con que se pagó.
    CONSTRAINT chk_pagada_con_transaccion CHECK (
        (estado = 'PAGADA' AND transaccion_id IS NOT NULL AND pagada_en IS NOT NULL)
        OR
        (estado <> 'PAGADA' AND transaccion_id IS NULL AND pagada_en IS NULL)
    )
);

-- Para "¿qué debo?": las cuotas pendientes de una persona.
CREATE INDEX idx_cuotas_deudor ON cuotas(deudor_cuenta_id, estado);

-- Garantía a nivel de base de datos: al hacer COMMIT, las cuotas de un cobro
-- deben sumar exactamente su total. Si el reparto pierde o inventa un
-- centavo, Postgres rechaza todo. Es DEFERRED por la misma razón que en V1:
-- las cuotas se insertan de a una y la suma solo cuadra al final.
CREATE FUNCTION verificar_reparto_del_cobro() RETURNS TRIGGER AS $$
DECLARE
    total        BIGINT;
    suma_cuotas  BIGINT;
BEGIN
    SELECT total_centavos INTO total FROM cobros WHERE id = NEW.cobro_id;
    SELECT COALESCE(SUM(monto_centavos), 0) INTO suma_cuotas
    FROM cuotas
    WHERE cobro_id = NEW.cobro_id;

    IF suma_cuotas <> total THEN
        RAISE EXCEPTION 'Cobro % mal repartido: cuotas = %, total = %',
            NEW.cobro_id, suma_cuotas, total;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_reparto_del_cobro
    AFTER INSERT OR UPDATE OF monto_centavos ON cuotas
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION verificar_reparto_del_cobro();
