package src.casino;

/**
 * Tipos de apuesta que un {@link Jugador} puede hacer en la ruleta.
 * Coinciden con lo que sabe describir la {@link Ruleta}: número, par e impar.
 *
 * <p>Es un {@code enum} inmutable, por lo que es seguro compartirlo entre
 * hilos sin ninguna sincronización.</p>
 */
public enum TipoApuesta {

    /** Apuesta a un número concreto (0-36). Paga 35 a 1. */
    NUMERO(35),
    /** Apuesta a número par (el 0 no cuenta). Paga 1 a 1. */
    PAR(1),
    /** Apuesta a número impar. Paga 1 a 1. */
    IMPAR(1);

    private final int multiplicador;

    /**
     * @param multiplicador veces que se gana el importe apostado si se acierta
     */
    TipoApuesta(int multiplicador) {
        this.multiplicador = multiplicador;
    }

    /**
     * Devuelve el multiplicador de pago de este tipo de apuesta.
     *
     * @return ganancia por cada unidad apostada (sin contar la apuesta devuelta)
     */
    public int getMultiplicador() {
        return multiplicador;
    }
}
