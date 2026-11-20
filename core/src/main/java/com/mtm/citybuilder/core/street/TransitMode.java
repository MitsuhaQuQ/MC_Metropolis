package com.mtm.citybuilder.core.street;

/** How a stretch of railway through a town is built. */
public enum TransitMode {
    /** Under an arterial, as Kyoto's subway runs under Karasuma-dori. */
    UNDERGROUND,
    /** On a viaduct, the streets passing under it, as the JR Sagano line through Kyoto. */
    ELEVATED,
    /** At grade: a barrier the streets cross only at level crossings, with roads running alongside. */
    GROUND
}
