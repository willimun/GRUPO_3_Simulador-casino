package src.casino;

import java.util.Locale;
import java.util.concurrent.Phaser;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Croupier de la mesa. Es un hilo (dirige las rondas) y a la vez el recurso
 * compartido que guarda el saldo de la banca.
 *
 * <p><b>Sincronización de fases:</b> un {@link Phaser} con 1 banca + N jugadores.
 * Cada ronda pasa por 4 barreras: F0 apertura, F1 apuestas cerradas,
 * F2 ruleta girada, F3 liquidación terminada. Se usa Phaser y no CyclicBarrier
 * porque los jugadores eliminados se dan de baja ({@code arriveAndDeregister}).</p>
 *
 * <p><b>Protección del saldo:</b> {@link ReentrantLock} justo (fair) para evitar
 * race conditions e inanición.</p>
 */


public class Banca implements Runnable {

   private final Ruleta ruleta = new Ruleta();                      // la ruleta es una clase aparte
    private final Phaser fase;                                        // barrera de fases compartida
    private final ReentrantLock lockSaldo = new ReentrantLock(true);  // true = justo (orden de llegada)
    private long saldo;                                               // SOLO se toca con lockSaldo tomado
    private final int maxRondas;
    private final long pausaMs;
    private final AtomicInteger activos;                              // contador atómico, sin lock
    private volatile boolean quebrada = false;                        // volatile: lo leen varios hilos
    private volatile boolean mesaAbierta = true;
    private volatile int resultado = -1;                              // solo escribe la banca
    private int rondasJugadas = 0;                                    // solo escribe la banca

    /**
     * @param saldoInicial saldo inicial de la banca (€)
     * @param nJugadores   jugadores en la mesa
     * @param maxRondas    límite de rondas
     * @param pausaMs      pausa entre rondas en ms (0 = ninguna)
     */
    public Banca(long saldoInicial, int nJugadores, int maxRondas, long pausaMs) {
        this.saldo = saldoInicial;
        this.maxRondas = maxRondas;
        this.pausaMs = pausaMs;
        this.activos = new AtomicInteger(nJugadores);
        // CLAVE: se registran todos desde el constructor; registrar tarde causaría carreras.
        this.fase = new Phaser(1 + nJugadores);
    }

    // ================= API para jugadores (thread-safe) =================

    /** Bloquea al jugador hasta que todos lleguen a la barrera (bloqueo real, sin espera activa). */
    public void esperarFase() { fase.arriveAndAwaitAdvance(); }

    /** Da de baja al jugador; cuenta como llegada, así no bloquea al resto. */
    public void abandonar() { fase.arriveAndDeregister(); }

    /** Resta un jugador activo de forma atómica. */
    public void jugadorEliminado() { activos.decrementAndGet(); }

    /** @return jugadores activos en mesa */
    public int getActivos() { return activos.get(); }

    /** @return {@code true} si la mesa sigue abierta (leer justo después de F0) */
    public boolean mesaAbierta() { return mesaAbierta; }

    /** @return número de la ruleta; válido tras F2 */
    public int getResultado() { return resultado; }

    /**
     * Ingresa una apuesta en el saldo de la banca.
     * Atómico: {@code saldo += x} NO lo es por sí solo, por eso va con lock.
     *
     * @param importe euros apostados
     */
    public void recibirApuesta(int importe) {
        lockSaldo.lock();
        try {
            saldo += importe;
        } finally {
            lockSaldo.unlock(); // siempre se libera, aunque haya excepción
        }
    }

    /**
     * Paga un premio. Atómico: comprobar y descontar bajo el mismo lock evita
     * el fallo "check-then-act" (dos ganadores gastando el mismo dinero).
     * Si no hay saldo suficiente paga lo que queda y declara la quiebra.
     *
     * @param importe premio total a pagar
     * @return importe realmente pagado
     */
    public int pagar(int importe) {
        lockSaldo.lock();
        try {
            if (saldo >= importe) {          // caso normal
                saldo -= importe;
                return importe;
            }
            int pagado = (int) saldo;        // caso quiebra: entrega lo que queda
            saldo = 0;
            quebrada = true;
            return pagado;
        } finally {
            lockSaldo.unlock();              // el finally se ejecuta también con el return
        }
    }

    /** @return saldo actual (leído bajo lock para que sea consistente) */
    public long getSaldo() {
        lockSaldo.lock();
        try {
            return saldo;
        } finally {
            lockSaldo.unlock();
        }
    }

    /** @return {@code true} si la banca no pudo pagar */
    public boolean estaQuebrada() { return quebrada; }

    /** @return rondas completadas (consultar tras el join) */
    public int getRondasJugadas() { return rondasJugadas; }

    /**
     * Formatea euros con punto de miles (49.820).
     *
     * @param valor importe
     * @return texto formateado
     */
    public static String eur(long valor) { return String.format(Locale.GERMANY, "%,d", valor); }

    // ================= Hilo del croupier =================

    /**
     * Una iteración = una ronda. El método solo "cuenta la historia" de la ronda;
     * el detalle está en métodos pequeños. Estructura de cada vuelta:
     * <pre>
     *   decidir si se cierra -> F0 (apertura) -> F1 (apuestas cerradas)
     *   -> girar ruleta -> F2 (resultado visible) -> F3 (liquidado) -> estado
     * </pre>
     * El bloque finally garantiza que nadie queda bloqueado aunque algo falle.
     */
    @Override
    public void run() {
        try {
            while (true) {
                boolean cerrar = debeCerrar();
                if (cerrar) {
                    mesaAbierta = false;               // se publica ANTES de F0: los jugadores lo leen DESPUÉS
                } else {
                    rondasJugadas++;
                    System.out.println("\n=== RONDA " + rondasJugadas + " ===");
                }

                fase.arriveAndAwaitAdvance();          // F0: apertura (o aviso de cierre)
                if (cerrar) break;                     // los jugadores ven mesaAbierta=false y se marchan

                fase.arriveAndAwaitAdvance();          // F1: todas las apuestas están hechas
                girarRuleta();                         // FASE 2: solo la banca gira
                fase.arriveAndAwaitAdvance();          // F2: el resultado ya es visible
                fase.arriveAndAwaitAdvance();          // F3: todos han liquidado (FASE 3)

                imprimirEstado();
                if (pausaMs > 0) Thread.sleep(pausaMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();        // restaura el flag, no se traga la señal
            System.err.println("[ERROR] Banca interrumpida. Se cierra la mesa.");
        } catch (RuntimeException e) {
            System.err.println("[ERROR] Fallo inesperado en la banca: " + e);
        } finally {
            mesaAbierta = false;
            fase.forceTermination();                   // pase lo que pase, no deja a nadie esperando
        }
    }

    /** @return {@code true} si hay que cerrar: quiebra, límite de rondas o mesa vacía */
    private boolean debeCerrar() {
        return quebrada || rondasJugadas >= maxRondas || activos.get() == 0;
    }

    /** Pide a la ruleta un número y lo anuncia. Solo este hilo escribe 'resultado'; el Phaser garantiza su visibilidad. */
    private void girarRuleta() {
        resultado = ruleta.girar();
        System.out.println("[RULETA] La Banca gira la ruleta... ¡Número " + resultado
                + " (" + Ruleta.descipcion(resultado) + ")!");
    }

    /** Muestra el estado al acabar la ronda y avisa si la banca ha quebrado. */
    private void imprimirEstado() {
        System.out.println("[ESTADO] Saldo Banca: " + eur(getSaldo())
                + "€ | Jugadores activos en mesa: " + activos.get());
        if (quebrada) {
            System.out.println("[QUIEBRA] ¡La banca no puede afrontar los pagos! Cierra la mesa.");
        }
    } 
    
}
