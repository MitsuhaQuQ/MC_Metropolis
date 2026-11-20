package com.mtm.citybuilder.core.building;

import java.util.Map;

/**
 * A style of the catalogue: a kind of building, its palettes and, for each slot it varies, the weights of its options.
 *
 * @param name     its name in the catalogue
 * @param type     the kind of building it builds
 * @param storeys  the storeys it always has, or null to take them from the district and the street
 * @param palettes palette names and their weights
 * @param options  for each slot it varies, option names and their weights; a slot it does not name takes the default
 */
public record Style(String name, Building.Type type, Integer storeys, Map<String, Double> palettes,
                    Map<Slot, Map<String, Double>> options) {
}
