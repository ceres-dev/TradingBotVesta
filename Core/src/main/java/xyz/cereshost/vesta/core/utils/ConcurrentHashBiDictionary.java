package xyz.cereshost.vesta.core.utils;

import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ConcurrentHashBiDictionary<A, B> implements BiDictionary<A, B> {

    private final ConcurrentHashMap<A, B> ab = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<B, A> ba = new ConcurrentHashMap<>();

    @Override
    public boolean add(A a, B b) {
        if ((a != null && b == null) || (a == null && b != null)) {
            throw new IllegalStateException("Los dos valores tiene ser nulos o no");
        }
        if (a == null) {
            return false;
        }
        ab.put(a, b);
        ba.put(b, a);

        return true;
    }

    @Override
    public B removeLeft(A a) {
        for (Map.Entry<B, A> e : ba.entrySet()) if (e.getValue().equals(a)) ba.remove(e.getKey());
        return ab.remove(a);
    }

    @Override
    public A removeRight(B b) {
        for (Map.Entry<A, B> e : ab.entrySet()) if (e.getValue().equals(b)) ab.remove(e.getKey());
        return ba.remove(b);
    }

    @Override
    public void removerAll() {
        ab.clear();
        ba.clear();
    }

    @Override
    public A getLeft(B b) {
        return ba.get(b);
    }

    @Override
    public B getRight(A a) {
        return ab.get(a);
    }

    @Override
    public HashSet<Entry<A, B>> getAll() {
        HashSet<Entry<A, B>> set = new HashSet<>();
        for (Map.Entry<A, B> e : ab.entrySet()) {
            set.add(new Entry<>(e.getKey(), e.getValue()));
        }
        return set;
    }

}
