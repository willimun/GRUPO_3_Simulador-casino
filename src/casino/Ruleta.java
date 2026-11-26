package src.casino;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Ruleta europea de 37 casillas (0-36). Solo genera números y los describe;
 * no conoce apuestas ni saldos (responsabilidad única).
 *
 * <p><b>Thread-safe:</b> no tiene estado propio y usa {@link ThreadLocalRandom}
 * (un generador por hilo, sin contención), así que no necesita sincronización.
 * Aun así, en la práctica solo la llama el hilo de la banca.</p>
 */
public class Ruleta {
    // Número de casillas : 0 y del 1 al 36. */
    public static final int CASILLAS = 37;
 
 
    /**
     * Gira la ruleta.
     *
     * @return número aleatorio entre 0 y 36, ambos incluidos
     */
 
    public int girar() {
        return ThreadLocalRandom.current().nextInt(CASILLAS);


    }
    public static String descipcion(int numero){

       if (numero == 0) return "CERO , gana la banca";
       return numero % 2 == 0 ? "PAR" : "IMPAR";
    }
    
}
