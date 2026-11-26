package src.casino;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Jugador del casino. Cada jugador es un <b>hilo independiente</b>
 * ({@link Runnable}) que repite el ciclo: elegir apuesta, entregarla a la
 * {@link Banca}, esperar el resultado de la ruleta y cobrar o perder.
 *
 * <h2>Programación multihilo</h2>
 * <ul>
 *   <li><b>Recurso compartido:</b> el {@code saldo}. Lo modifica el propio
 *       jugador y puede leerlo o modificarlo la banca desde otro hilo, por lo
 *       que es una sección crítica.</li>
 *   <li><b>Exclusión mutua:</b> todos los accesos al saldo son métodos
 *       {@code synchronized} sobre el propio jugador. Así la operación
 *       "comprobar saldo y restar" de {@link #retirar(int)} es atómica y no
 *       hay race conditions (por ejemplo, dos hilos gastando el mismo dinero).</li>
 *   <li><b>Sin espera activa:</b> mientras la ronda no se resuelve, el jugador
 *       queda bloqueado dentro de {@link Banca#apostar(Apuesta)} (estado
 *       WAITING/BLOCKED del hilo) y no consume CPU.</li>
 *   <li><b>Parada limpia:</b> el hilo termina cuando se queda sin saldo o
 *       cuando se le interrumpe ({@link Thread#interrupt()}), por ejemplo al
 *       cerrar el casino.</li>
 * </ul>
 *
 * <p>Contrato esperado de la banca (acordar con quien implemente la tarjeta 3.0):
 * {@code int apostar(Apuesta a) throws InterruptedException}, que bloquea hasta
 * que la ruleta resuelve la ronda y devuelve el importe total a cobrar
 * (0 si pierde; apuesta más ganancia si acierta).</p>
 */
public class Jugador implements Runnable {

    /** Apuesta máxima por ronda. */
    private static final int APUESTA_MAXIMA = 50;

    /** Apuesta mínima por ronda. */
    private static final int APUESTA_MINIMA = 1;

    /** Nombre del jugador, usado en los mensajes de registro. */
    private final String nombre;

    /** Banca con la que juega. Es final y se asigna una sola vez. */
    private final Banca banca;

    /** Dinero disponible. Protegido por el monitor de {@code this}. */
    private int saldo;

    /**
     * Crea un jugador.
     *
     * @param nombre      nombre del jugador
     * @param saldoInicial dinero con el que empieza; debe ser positivo
     * @param banca       banca a la que entregará sus apuestas
     * @throws IllegalArgumentException si el saldo inicial no es positivo
     */
    public Jugador(String nombre, int saldoInicial, Banca banca) {
        if (saldoInicial <= 0) {
            throw new IllegalArgumentException("El saldo inicial debe ser positivo");
        }
        this.nombre = nombre;
        this.saldo = saldoInicial;
        this.banca = banca;
    }

    /**
     * Ciclo de vida del hilo: juega rondas mientras tenga saldo y no sea
     * interrumpido. Al terminar, deja constancia por consola.
     */
    @Override
    public void run() {
        System.out.println(nombre + " se sienta a jugar con " + getSaldo() + " €");
        try {
            while (!Thread.currentThread().isInterrupted()
                    && getSaldo() >= APUESTA_MINIMA) {
                jugarRonda();
            }
        } catch (InterruptedException e) {
            // Restauramos la marca de interrupción y salimos del bucle.
            Thread.currentThread().interrupt();
        } finally {
            System.out.println(nombre + " abandona el casino con " + getSaldo() + " €");
        }
    }

    /**
     * Juega una ronda completa: retira el importe, lo entrega a la banca,
     * espera el resultado y cobra el premio.
     *
     * <p>Si el hilo es interrumpido mientras espera, la ronda no se ha
     * resuelto, así que se <b>devuelve la apuesta</b> al saldo (caso límite
     * que evita perder dinero por cerrar el casino).</p>
     *
     * @throws InterruptedException si el hilo es interrumpido durante la espera
     */
    private void jugarRonda() throws InterruptedException {
        Apuesta apuesta = generarApuesta();
        if (!retirar(apuesta.importe())) {
            return; // el saldo cambió entre generar la apuesta y retirarla
        }
        try {
            int premio = banca.apostar(apuesta); // bloquea sin espera activa
            ingresar(premio);
            System.out.println(nombre + " apuesta " + apuesta.importe() + " € a "
                    + apuesta.tipo() + (premio > 0 ? " y GANA " + premio : " y pierde")
                    + " (saldo: " + getSaldo() + " €)");
        } catch (InterruptedException e) {
            ingresar(apuesta.importe()); // ronda sin resolver: se devuelve la apuesta
            throw e;
        }
    }

    /**
     * Genera una apuesta aleatoria que nunca supera el saldo disponible.
     * Usa {@link ThreadLocalRandom}, que es seguro y eficiente con varios
     * hilos porque cada uno tiene su propio generador.
     *
     * @return una apuesta válida
     */
    private Apuesta generarApuesta() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int maximo = Math.min(APUESTA_MAXIMA, getSaldo());
        int importe = rnd.nextInt(APUESTA_MINIMA, Math.max(APUESTA_MINIMA, maximo) + 1);
        TipoApuesta tipo = TipoApuesta.values()[rnd.nextInt(TipoApuesta.values().length)];
        int numero = rnd.nextInt(0, 37);
        return new Apuesta(tipo, numero, importe);
    }

    /**
     * Resta dinero del saldo de forma atómica.
     *
     * @param cantidad dinero a retirar (positivo)
     * @return {@code true} si había saldo suficiente y se retiró;
     *         {@code false} si no había saldo y no se modifica nada
     */
    public synchronized boolean retirar(int cantidad) {
        if (cantidad <= 0 || cantidad > saldo) {
            return false;
        }
        saldo -= cantidad;
        return true;
    }

    /**
     * Suma dinero al saldo de forma atómica. Puede llamarla la banca desde
     * otro hilo para pagar un premio.
     *
     * @param cantidad dinero a ingresar; si es 0 o negativo no hace nada
     */
    public synchronized void ingresar(int cantidad) {
        if (cantidad > 0) {
            saldo += cantidad;
        }
    }

    /**
     * Consulta el saldo actual. Es {@code synchronized} para garantizar que
     * se lee el último valor escrito por cualquier hilo (visibilidad).
     *
     * @return saldo actual
     */
    public synchronized int getSaldo() {
        return saldo;
    }

    /**
     * Devuelve el nombre del jugador.
     *
     * @return nombre del jugador
     */
    public String getNombre() {
        return nombre;
    }
}
