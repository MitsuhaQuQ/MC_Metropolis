package com.mtm.citybuilder.tools;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OsmStatsTest {
    /** Four streets each way, 0.001 degrees apart, every one crossing all four of the others, sticking out at its ends. */
    private static String grid() {
        StringBuilder xml = new StringBuilder("<osm>\n");
        long id = 1;
        long[][] crossing = new long[4][4];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                crossing[i][j] = id;
                xml.append(String.format("<node id=\"%d\" lat=\"%.6f\" lon=\"%.6f\"/>%n", id++, 35.0 + 0.001 * j, 135.0 + 0.001 * i));
            }
        }
        long way = 1000;
        for (int along = 0; along < 2; along++) {
            for (int line = 0; line < 4; line++) {
                long start = id++;
                long end = id++;
                double fixed = 0.001 * line;
                if (along == 0) {
                    // Running east, at latitude 35.0 + fixed
                    xml.append(String.format("<node id=\"%d\" lat=\"%.6f\" lon=\"%.6f\"/>%n", start, 35.0 + fixed, 134.9995));
                    xml.append(String.format("<node id=\"%d\" lat=\"%.6f\" lon=\"%.6f\"/>%n", end, 35.0 + fixed, 135.0035));
                } else {
                    xml.append(String.format("<node id=\"%d\" lat=\"%.6f\" lon=\"%.6f\"/>%n", start, 34.9995, 135.0 + fixed));
                    xml.append(String.format("<node id=\"%d\" lat=\"%.6f\" lon=\"%.6f\"/>%n", end, 35.0035, 135.0 + fixed));
                }
                xml.append(String.format("<way id=\"%d\"><nd ref=\"%d\"/>", way++, start));
                for (int k = 0; k < 4; k++) {
                    long node = along == 0 ? crossing[k][line] : crossing[line][k];
                    xml.append(String.format("<nd ref=\"%d\"/>", node));
                }
                xml.append(String.format("<nd ref=\"%d\"/><tag k=\"highway\" v=\"residential\"/></way>%n", end));
            }
        }
        xml.append("<node id=\"9001\" lat=\"35.001\" lon=\"135.001\"><tag k=\"place\" v=\"hamlet\"/></node>\n");
        return xml.append("</osm>\n").toString();
    }

    @Test
    void aGridHasTheEntropyOfAGridFourWayJunctionsAndItsBlocks() throws Exception {
        Map<String, Double> report = OsmStats.analyse(new ByteArrayInputStream(grid().getBytes(StandardCharsets.UTF_8)),
                new double[]{34.999, 134.999, 35.004, 135.004});
        assertEquals(Math.log(4.0), report.get("orientation entropy (nats)"), 0.01);
        assertEquals(1.0, report.get("orientation order (1 grid, 0 none)"), 0.02);
        // 16 crossings, all four-way, and the 16 ends of the streets
        assertEquals(0.5, report.get("  four-way or more"), 1e-9);
        assertEquals(0.5, report.get("  dead ends"), 1e-9);
        assertEquals(9.0, report.get("blocks (Euler)"), 1e-9);
        assertEquals(1.0, report.get("  share of length: residential"), 1e-9);
    }
}
