package com.wds.sixstars.contextoverlay.tree;

import java.util.Locale;
import java.util.Objects;

/** Stable ordered predicate components; no automatic truth or inverse inference. */
public final class TreePredicate {
    public static final TreePredicate IS_A = of("is","a");
    public static final TreePredicate IS_FROM = of("is","from");
    public static final TreePredicate IS_IN = of("is","in");
    public static final TreePredicate IS_PART_OF = of("is","part","of");
    private final String key;

    private TreePredicate(String key) { this.key=key; }

    public static TreePredicate of(String... components) {
        Objects.requireNonNull(components);
        if(components.length==0 || components.length>8)
            throw new IllegalArgumentException("Use 1–8 predicate components");
        StringBuilder b=new StringBuilder();
        for(String component:components) {
            String token=Objects.requireNonNull(component).toLowerCase(Locale.ROOT);
            if(!token.matches("[a-z][a-z0-9_-]{0,31}"))
                throw new IllegalArgumentException("Invalid predicate component");
            if(b.length()>0)b.append('.');
            b.append(token);
        }
        return new TreePredicate(b.toString());
    }

    public static TreePredicate parse(String key) {
        return of(Objects.requireNonNull(key).split("\\.",-1));
    }
    public String key(){return key;}
    public String[] components(){return key.split("\\.");}
    @Override public String toString(){return key;}
    @Override public boolean equals(Object value){return value instanceof TreePredicate && key.equals(((TreePredicate)value).key);}
    @Override public int hashCode(){return key.hashCode();}
}
