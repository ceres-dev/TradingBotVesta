package xyz.cereshost.vesta.core.utils;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public interface BiDictionary<A, B> {

    @Contract(value = "null, !null -> fail; !null, null -> fail; null, null -> false; !null, !null, -> true")
    boolean add(A a, B b);

    default void addAll(@NotNull SequencedCollection<A> a, @NotNull SequencedCollection<B> b) {
        if (a.size() != b.size()) {
            throw new IllegalStateException("La cantidad de elementos son diferentes");
        }
        List<A> aList = a instanceof List<A> ? (List<A>) a : new ArrayList<>(a);
        List<B> bList = b instanceof List<B> ? (List<B>) b : new ArrayList<>(b);

        for (int i = 0; i<aList.size(); i++) {
            add(aList.get(i), bList.get(i));
        }
    }

    default void addAll(@NotNull BiDictionary<A, B> dictionary) {
        for (Entry<A, B> entry : dictionary.getAll()) add(entry);
    }

    default void add(@NotNull Entry<A, B> entry) {
        add(entry.left(), entry.right());
    }

    B removeLeft(A a);

    A removeRight(B b);

    void removerAll();

    @Contract(pure = true)
    A getLeft(B b);

    @Contract(pure = true)
    B getRight(A a);

    @Contract(pure = true)
    HashSet<Entry<A, B>> getAll();

    record Entry<A, B>(A left, B right){}
}
