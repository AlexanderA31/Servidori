package es.ucm.fdi.iu.model;

/**
 * Used to json-ize objects.
 * @param <T>
 */
public interface Transferable<T> {
    T toTransfer();
}
