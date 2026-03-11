package xyz.cereshost.vesta.core.ia.utils;

public interface Normalizer<T> {

    void fit(T source);

    T transform (T source);
    T inverseTransform (T source);
}
