package com.mtm.citybuilder.core.street;

/** A place the streets are laid out around, its land kept for what will stand there. */
public record Anchor(Kind kind, double x, double z) {
    public enum Kind {
        /** The town's actual centre (L3). */
        CORE,
        /** A station of an intercity line. */
        STATION,
        /** The castle of a castle town, on a rise near the centre. */
        CASTLE,
        /** The palace of a planned capital, at the north of its grid. */
        PALACE,
        /** The temple or shrine of a temple town, up the main street. */
        TEMPLE
    }
}
