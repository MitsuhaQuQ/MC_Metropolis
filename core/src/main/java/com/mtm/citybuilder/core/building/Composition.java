package com.mtm.citybuilder.core.building;

import java.util.Map;

/**
 * How one building fills its slots: the style it was drawn from and the option it took for each slot the style
 * varies. Buildings of one development share theirs.
 *
 * @param style   the style's name
 * @param options the option of each slot the style varies
 */
public record Composition(String style, Map<Slot, String> options) {
    /** A slot's option, or the slot's default if the style does not vary it. */
    public String get(Slot slot) {
        String option = options.get(slot);
        return option == null ? slot.fallback() : option;
    }

    /** Whether a slot took this option. */
    public boolean is(Slot slot, String option) {
        return get(slot).equals(option);
    }

    /** The same composition with one slot's option replaced (a choice the lot rules out, as parking on a narrow lot). */
    public Composition with(Slot slot, String option) {
        java.util.EnumMap<Slot, String> copy = new java.util.EnumMap<>(Slot.class);
        copy.putAll(options);
        copy.put(slot, option);
        return new Composition(style, java.util.Collections.unmodifiableMap(copy));
    }
}
