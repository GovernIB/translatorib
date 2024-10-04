package es.caib.translatorib.api.error;

/**
 * Tipus possibles d'error. Emprat dins {@link ErrorBean} per enviar la informació d'un
 * error del client.
 *
 * @author Indra
 */
public enum ErrorType {
    /**
     * Error de les regles lògiques de l'aplicació
     */
    APLICACIO,

    /**
     * Error a la validació de paràmetres
     */
    VALIDACIO,

    /**
     * Error al format de la petició
     */
    PETICIO
}