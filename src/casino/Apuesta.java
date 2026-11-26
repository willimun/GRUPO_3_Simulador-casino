package src.casino;

/**
 * Apuesta concreta que un jugador entrega a la banca en una ronda.
 *
 * <p>Es un {@code record}: todos sus campos son finales, así que el objeto es
 * <b>inmutable</b> y por tanto thread-safe. Puede pasar del hilo del jugador
 * al hilo de la banca o de la ruleta sin necesidad de bloqueos.</p>
 *
 * @param tipo    tipo de apuesta (número, color, par/impar)
 * @param numero  número elegido (0-36); solo se usa si {@code tipo} es
 *                {@link TipoApuesta#NUMERO}, en otro caso se ignora
 * @param importe cantidad apostada, siempre mayor que cero
 */
public record Apuesta(TipoApuesta tipo, int numero, int importe) {

    /**
     * Valida los datos al crear la apuesta.
     *
     * @throws IllegalArgumentException si el importe no es positivo o el
     *                                  número está fuera de 0-36
     */
    public Apuesta {
        if (tipo == null) {
            throw new IllegalArgumentException("El tipo de apuesta es obligatorio");
        }
        if (importe <= 0) {
            throw new IllegalArgumentException("El importe debe ser mayor que 0");
        }
        if (numero < 0 || numero > 36) {
            throw new IllegalArgumentException("El número debe estar entre 0 y 36");
        }
    }
}
