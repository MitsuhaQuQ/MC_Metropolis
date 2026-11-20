package com.mtm.citybuilder.tools;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Statistics of real towns from OpenStreetMap data, the targets the street layers are tuned against (design
 * document section 8, "用真实城市的统计数据校准"). Reads an OSM XML file (as Overpass writes it) and reports, within a
 * rectangle given in degrees or the data's own extent:
 * <ul>
 *     <li>street orientation entropy and order (Boeing 2019): 36 bins of 10 degrees, both directions of every
 *     segment weighted by length; 1.386 nats for a perfect grid, 3.584 for all directions alike;</li>
 *     <li>intersection kinds: dead ends, three-way and four-or-more-way, among the nodes where streets meet or end;</li>
 *     <li>street length per class and density in km per km², and the mean block area by Euler's formula (faces of the
 *     planar street graph: E - V + C);</li>
 *     <li>places (city, town, village, hamlet): how many per 100 km², and the mean distance to the nearest of a kind.</li>
 * </ul>
 * Only the statistics are used, as parameters, with OpenStreetMap credited (ODbL); the data is not shipped.
 * <p>
 * Arguments: the OSM file, then optionally south west north east in degrees.
 */
public final class OsmStats {
    /** Street classes counted as the street network; lanes (service, footways) are reported apart. */
    static final Set<String> STREETS = Set.of("motorway", "trunk", "primary", "secondary", "tertiary", "unclassified",
            "residential", "living_street", "motorway_link", "trunk_link", "primary_link", "secondary_link",
            "tertiary_link");
    static final Set<String> LANES = Set.of("service", "footway", "path", "pedestrian", "steps", "track");
    private static final int BINS = 36;

    private OsmStats() {
    }

    /** What was read: nodes in metres around the rectangle's centre, street ways, lanes and places. */
    static final class Data {
        final Map<Long, double[]> nodes = new HashMap<>();
        final List<long[]> streets = new ArrayList<>();
        final List<String> streetClasses = new ArrayList<>();
        final List<long[]> lanes = new ArrayList<>();
        final List<String[]> places = new ArrayList<>();
        final List<double[]> placeLatLon = new ArrayList<>();
        double south = Double.NaN;
        double west = Double.NaN;
        double north = Double.NaN;
        double east = Double.NaN;
    }

    /** The statistics, as name and value in order. */
    static Map<String, Double> analyse(InputStream input, double[] box) throws XMLStreamException {
        Map<Long, double[]> latLon = new HashMap<>();
        Data data = new Data();
        XMLStreamReader xml = XMLInputFactory.newFactory().createXMLStreamReader(input);
        long[] way = null;
        List<Long> wayNodes = new ArrayList<>();
        Map<String, String> tags = new HashMap<>();
        String element = null;
        double[] nodeLatLon = null;
        while (xml.hasNext()) {
            int event = xml.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String name = xml.getLocalName();
                switch (name) {
                    case "bounds" -> {
                        data.south = Double.parseDouble(xml.getAttributeValue(null, "minlat"));
                        data.west = Double.parseDouble(xml.getAttributeValue(null, "minlon"));
                        data.north = Double.parseDouble(xml.getAttributeValue(null, "maxlat"));
                        data.east = Double.parseDouble(xml.getAttributeValue(null, "maxlon"));
                    }
                    case "node" -> {
                        element = "node";
                        tags.clear();
                        long id = Long.parseLong(xml.getAttributeValue(null, "id"));
                        nodeLatLon = new double[]{Double.parseDouble(xml.getAttributeValue(null, "lat")),
                                Double.parseDouble(xml.getAttributeValue(null, "lon")), id};
                        latLon.put(id, nodeLatLon);
                    }
                    case "way" -> {
                        element = "way";
                        tags.clear();
                        wayNodes.clear();
                    }
                    case "nd" -> wayNodes.add(Long.parseLong(xml.getAttributeValue(null, "ref")));
                    case "tag" -> tags.put(xml.getAttributeValue(null, "k"), xml.getAttributeValue(null, "v"));
                    default -> {
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                String name = xml.getLocalName();
                if (name.equals("node") && "node".equals(element)) {
                    String place = tags.get("place");
                    if (place != null && nodeLatLon != null) {
                        data.places.add(new String[]{place});
                        data.placeLatLon.add(new double[]{nodeLatLon[0], nodeLatLon[1]});
                    }
                    element = null;
                } else if (name.equals("way") && "way".equals(element)) {
                    String highway = tags.get("highway");
                    if (highway != null && !"yes".equals(tags.get("area"))) {
                        long[] refs = wayNodes.stream().mapToLong(Long::longValue).toArray();
                        if (STREETS.contains(highway)) {
                            data.streets.add(refs);
                            data.streetClasses.add(highway.replace("_link", ""));
                        } else if (LANES.contains(highway)) {
                            data.lanes.add(refs);
                        }
                    }
                    element = null;
                }
            }
        }
        // The rectangle: given, else the file's bounds, else the extent of the nodes
        double south = box != null ? box[0] : data.south;
        double west = box != null ? box[1] : data.west;
        double north = box != null ? box[2] : data.north;
        double east = box != null ? box[3] : data.east;
        if (Double.isNaN(south)) {
            south = Double.POSITIVE_INFINITY;
            west = Double.POSITIVE_INFINITY;
            north = Double.NEGATIVE_INFINITY;
            east = Double.NEGATIVE_INFINITY;
            for (double[] p : latLon.values()) {
                south = Math.min(south, p[0]);
                north = Math.max(north, p[0]);
                west = Math.min(west, p[1]);
                east = Math.max(east, p[1]);
            }
        }
        double centreLat = 0.5 * (south + north);
        double centreLon = 0.5 * (west + east);
        double metresPerLat = 111_320.0;
        double metresPerLon = 111_320.0 * Math.cos(Math.toRadians(centreLat));
        for (Map.Entry<Long, double[]> entry : latLon.entrySet()) {
            double[] p = entry.getValue();
            if (p[0] >= south && p[0] <= north && p[1] >= west && p[1] <= east) {
                data.nodes.put(entry.getKey(), new double[]{(p[1] - centreLon) * metresPerLon, (centreLat - p[0]) * metresPerLat});
            }
        }
        double area = (north - south) * metresPerLat * (east - west) * metresPerLon / 1e6;
        Map<String, Double> report = new LinkedHashMap<>();
        report.put("area km2", area);
        streets(data, area, report);
        places(data, south, west, north, east, metresPerLat, metresPerLon, area, report);
        return report;
    }

    private static void streets(Data data, double area, Map<String, Double> report) {
        double[] bins = new double[BINS];
        Map<String, Double> byClass = new TreeMap<>();
        // Neighbours of each node along the streets, and how many street ways end at it
        Map<Long, Set<Long>> neighbours = new HashMap<>();
        double length = 0.0;
        for (int w = 0; w < data.streets.size(); w++) {
            long[] refs = data.streets.get(w);
            for (int i = 1; i < refs.length; i++) {
                double[] a = data.nodes.get(refs[i - 1]);
                double[] b = data.nodes.get(refs[i]);
                if (a == null || b == null) {
                    continue;
                }
                double dx = b[0] - a[0];
                double dz = b[1] - a[1];
                double segment = Math.hypot(dx, dz);
                if (segment < 1e-6) {
                    continue;
                }
                length += segment;
                byClass.merge(data.streetClasses.get(w), segment, Double::sum);
                // Compass bearing, and its reverse: streets run both ways
                double bearing = Math.toDegrees(Math.atan2(dx, -dz));
                for (double b2 : new double[]{bearing, bearing + 180.0}) {
                    double d = ((b2 % 360.0) + 360.0 + 5.0) % 360.0;
                    bins[(int) (d / 10.0) % BINS] += segment;
                }
                neighbours.computeIfAbsent(refs[i - 1], k -> new HashSet<>()).add(refs[i]);
                neighbours.computeIfAbsent(refs[i], k -> new HashSet<>()).add(refs[i - 1]);
            }
        }
        double total = 0.0;
        for (double bin : bins) {
            total += bin;
        }
        double entropy = 0.0;
        for (double bin : bins) {
            if (bin > 0) {
                double p = bin / total;
                entropy -= p * Math.log(p);
            }
        }
        double grid = Math.log(4.0);
        double uniform = Math.log(BINS);
        double order = 1.0 - Math.pow((entropy - grid) / (uniform - grid), 2.0);
        report.put("street km", length / 1000.0);
        report.put("street density km/km2", length / 1000.0 / area);
        report.put("orientation entropy (nats)", entropy);
        report.put("orientation order (1 grid, 0 none)", order);
        for (Map.Entry<String, Double> entry : byClass.entrySet()) {
            report.put("  share of length: " + entry.getKey(), entry.getValue() / Math.max(1e-9, length));
        }
        // Nodes where streets meet or end: degree other than 2 in the street graph
        int dead = 0;
        int three = 0;
        int four = 0;
        int vertices = 0;
        int edgeEnds = 0;
        for (Set<Long> around : neighbours.values()) {
            int degree = around.size();
            if (degree == 2) {
                continue;
            }
            vertices++;
            edgeEnds += degree;
            if (degree == 1) {
                dead++;
            } else if (degree == 3) {
                three++;
            } else if (degree >= 4) {
                four++;
            }
        }
        int junctions = Math.max(1, dead + three + four);
        report.put("nodes where streets meet or end", (double) vertices);
        report.put("  dead ends", (double) dead / junctions);
        report.put("  three-way", (double) three / junctions);
        report.put("  four-way or more", (double) four / junctions);
        report.put("junctions per km2", (three + four) / area);
        // Blocks by Euler's formula on the simplified graph: edges join the meeting nodes, faces = E - V + C
        int edges = edgeEnds / 2;
        int components = components(neighbours);
        double faces = Math.max(1, edges - vertices + components);
        report.put("blocks (Euler)", faces);
        report.put("mean block area ha", area * 100.0 / faces);
        double lanes = 0.0;
        for (long[] refs : data.lanes) {
            for (int i = 1; i < refs.length; i++) {
                double[] a = data.nodes.get(refs[i - 1]);
                double[] b = data.nodes.get(refs[i]);
                if (a != null && b != null) {
                    lanes += Math.hypot(b[0] - a[0], b[1] - a[1]);
                }
            }
        }
        report.put("lanes (service, footway, path) km per km2", lanes / 1000.0 / area);
    }

    /** Connected parts of the street graph. */
    private static int components(Map<Long, Set<Long>> neighbours) {
        Set<Long> seen = new HashSet<>();
        int parts = 0;
        for (Long start : neighbours.keySet()) {
            if (!seen.add(start)) {
                continue;
            }
            parts++;
            List<Long> stack = new ArrayList<>(List.of(start));
            while (!stack.isEmpty()) {
                long node = stack.remove(stack.size() - 1);
                for (long next : neighbours.getOrDefault(node, Set.of())) {
                    if (seen.add(next)) {
                        stack.add(next);
                    }
                }
            }
        }
        return parts;
    }

    private static void places(Data data, double south, double west, double north, double east, double metresPerLat,
                               double metresPerLon, double area, Map<String, Double> report) {
        Map<String, List<double[]>> byKind = new TreeMap<>();
        for (int i = 0; i < data.places.size(); i++) {
            double[] p = data.placeLatLon.get(i);
            if (p[0] < south || p[0] > north || p[1] < west || p[1] > east) {
                continue;
            }
            byKind.computeIfAbsent(data.places.get(i)[0], k -> new ArrayList<>())
                    .add(new double[]{p[1] * metresPerLon, p[0] * metresPerLat});
        }
        for (Map.Entry<String, List<double[]>> entry : byKind.entrySet()) {
            List<double[]> points = entry.getValue();
            double nearestSum = 0.0;
            int counted = 0;
            for (double[] a : points) {
                double nearest = Double.POSITIVE_INFINITY;
                for (double[] b : points) {
                    if (a != b) {
                        nearest = Math.min(nearest, Math.hypot(a[0] - b[0], a[1] - b[1]));
                    }
                }
                if (nearest < Double.POSITIVE_INFINITY) {
                    nearestSum += nearest;
                    counted++;
                }
            }
            report.put("place " + entry.getKey() + " per 100 km2", points.size() * 100.0 / area);
            report.put("place " + entry.getKey() + " mean nearest m", counted == 0 ? Double.NaN : nearestSum / counted);
        }
    }

    public static void main(String[] args) throws IOException, XMLStreamException {
        if (args.length < 1) {
            System.err.println("Usage: OsmStats <file.osm> [south west north east]");
            System.exit(2);
        }
        double[] box = args.length >= 5 ? new double[]{Double.parseDouble(args[1]), Double.parseDouble(args[2]),
                Double.parseDouble(args[3]), Double.parseDouble(args[4])} : null;
        Map<String, Double> report;
        try (InputStream input = new BufferedInputStream(Files.newInputStream(Path.of(args[0])))) {
            report = analyse(input, box);
        }
        System.out.println("Statistics of " + args[0] + " (data © OpenStreetMap contributors, ODbL)");
        report.forEach((name, value) -> System.out.printf("  %-45s %10.3f%n", name, value));
    }
}
