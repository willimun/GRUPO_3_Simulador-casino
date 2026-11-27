package src.casino;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Jugador del casino. Cada jugador es un <b>hilo independiente</b>
 * ({@link Runnable}) que se sincroniza con la {@link Banca} mediante las
 * barreras de su {@link java.util.concurrent.Phaser}.
 *
 * <h2>Protocolo de una ronda (4 barreras)</h2>
 * <ol>
 *   <li><b>F0 - apertura:</b> espera a que la banca abra la ronda. Justo
 *       después lee {@link Banca#mesaAbierta()}; si es {@code false}, se marcha.</li>
 *   <li><b>Apuesta:</b> retira el importe de su saldo y lo ingresa en la banca
 *       ({@link Banca#recibirApuesta(int)}).</li>
 *   <li><b>F1 - apuestas cerradas:</b> espera a que todos hayan apostado; la
 *       banca gira la ruleta.</li>
 *   <li><b>F2 - resultado visible:</b> lee {@link Banca#getResultado()}, y si
 *       acierta cobra el premio con {@link Banca#pagar(int)}.</li>
 *   <li><b>F3 - liquidación terminada:</b> espera a que todos hayan cobrado.
 *       Si se ha quedado sin dinero, se da de baja con
 *       {@link Banca#abandonar()}.</li>
 * </ol>
 *
 * <h2>Programación multihilo</h2>
 * <ul>
 *   <li><b>Sin espera activa:</b> {@link Banca#esperarFase()} bloquea el hilo
 *       de verdad (estado WAITING), no hay bucles comprobando nada.</li>
 *   <li><b>Exclusión mutua:</b> el saldo del jugador solo lo modifica su propio
 *       hilo, pero se accede con métodos {@code synchronized} para que
 *       cualquier otro hilo lo lea con visibilidad garantizada. El saldo de la
 *       banca está protegido por su propio {@code ReentrantLock} justo.</li>
 *   <li><b>Sin deadlock:</b> todos los jugadores llaman a las barreras en el
 *       mismo orden. Un jugador eliminado avisa con
 *       {@link Banca#jugadorEliminado()} <i>antes</i> de la barrera F3, para
 *       que la banca ya vea el contador actualizado al decidir si cierra, y se
 *       da de baja del Phaser <i>después</i>, para no bloquear a los demás.</li>
 * </ul>
 */
public class Jugador implements Runnable {

    /** Apuesta máxima por ronda. */
    private static final int APUESTA_MAXIMA = 50;

    /** Apuesta mínima por ronda. Con menos saldo que esto el jugador queda eliminado. */
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
     * @param nombre       nombre del jugador
     * @param saldoInicial dinero con el que empieza; debe ser positivo
     * @param banca        banca con la que se sincroniza
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
     * Ciclo de vida del hilo: juega rondas hasta que la banca cierra la mesa
     * o el jugador se queda sin dinero.
     */
    @Override
    public void run() {
        System.out.println(nombre + " se sienta a jugar con " + getSaldo() + " €");
        try {
            while (true) {
                banca.esperarFase();                    // F0: apertura de ronda
                if (!banca.mesaAbierta()) {
                    break;                              // la banca cierra: nos vamos
                }

                Apuesta apuesta = generarApuesta();
                retirar(apuesta.importe());             // el dinero sale de mi saldo...
                banca.recibirApuesta(apuesta.importe()); // ...y entra en el de la banca

                banca.esperarFase();                    // F1: todas las apuestas hechas
                banca.esperarFase();                    // F2: la ruleta ya ha girado

                liquidar(apuesta, banca.getResultado());

                boolean eliminado = getSaldo() < APUESTA_MINIMA;
                if (eliminado) {
                    banca.jugadorEliminado();           // ANTES de F3, la banca lo verá al decidir
                }
                banca.esperarFase();                    // F3: todos han liquidado
                if (eliminado) {
                    banca.abandonar();                  // DESPUÉS de F3: no bloquea a nadie
                    break;
                }
            }
        } finally {
            System.out.println(nombre + " abandona el casino con " + getSaldo() + " €");
        }
    }

    /**
     * Comprueba si la apuesta gana y, en ese caso, cobra el premio a la banca.
     * El premio total es la apuesta devuelta más las ganancias
     * (importe x (multiplicador + 1)). Si la banca no tiene saldo suficiente,
     * {@link Banca#pagar(int)} entrega lo que le quede.
     *
     * @param apuesta   apuesta realizada en esta ronda
     * @param resultado número que ha salido en la ruleta (0-36)
     */
    private void liquidar(Apuesta apuesta, int resultado) {
        int cobrado = 0;
        if (acierta(apuesta, resultado)) {
            cobrado = banca.pagar(apuesta.importe() * (apuesta.tipo().getMultiplicador() + 1));
            ingresar(cobrado);
        }
        System.out.println(nombre + " apuesta " + apuesta.importe() + " € a " + apuesta.tipo()
                + (apuesta.tipo() == TipoApuesta.NUMERO ? " (" + apuesta.numero() + ")" : "")
                + " -> sale " + resultado
                + (cobrado > 0 ? " y GANA " + cobrado + " €" : " y pierde")
                + " (saldo: " + getSaldo() + " €)");
    }

    /**
     * Indica si una apuesta es ganadora. El 0 pierde para par e impar
     * ("gana la banca"), pero sí gana si se apostó al número 0.
     *
     * @param apuesta   apuesta a comprobar
     * @param resultado número que ha salido
     * @return {@code true} si la apuesta acierta
     */
    private boolean acierta(Apuesta apuesta, int resultado) {
        return switch (apuesta.tipo()) {
            case NUMERO -> resultado == apuesta.numero();
            case PAR -> resultado != 0 && resultado % 2 == 0;
            case IMPAR -> resultado % 2 == 1;
        };
    }

    /**
     * Genera una apuesta aleatoria que nunca supera el saldo disponible.
     * Usa {@link ThreadLocalRandom}, seguro con varios hilos porque cada uno
     * tiene su propio generador.
     *
     * @return una apuesta válida
     */
    private Apuesta generarApuesta() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int maximo = Math.min(APUESTA_MAXIMA, getSaldo());
        int importe = rnd.nextInt(APUESTA_MINIMA, maximo + 1);
        TipoApuesta tipo = TipoApuesta.values()[rnd.nextInt(TipoApuesta.values().length)];
        int numero = rnd.nextInt(Ruleta.CASILLAS);
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
     * Suma dinero al saldo de forma atómica.
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