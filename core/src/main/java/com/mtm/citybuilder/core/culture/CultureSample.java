package com.mtm.citybuilder.core.culture;

/**
 * The culture of a position.
 *
 * @param style  the style the position belongs to
 * @param margin how far the position lies inside its culture, as the difference between the cost of reaching it from
 *               the nearest core of the other style and from the nearest of its own, in blocks of flat land. Near 0 on
 *               the border; large deep inside. On mountains the cost rises fast, so a border on a ridge has a narrow
 *               low margin band, one across plains a wide one.
 */
public record CultureSample(Style style, double margin) {
}
