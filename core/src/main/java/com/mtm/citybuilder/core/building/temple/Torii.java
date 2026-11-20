package com.mtm.citybuilder.core.building.temple;

/**
 * A torii (明神鳥居, the commonest form): two pillars with black feet (根巻), the tie beam (貫) through them, the
 * plaque post (額束) in the middle, and the double top beam: the vermilion lower one (島木) and the black upper one
 * (笠木), running out past the pillars and turning up at the ends. Large torii get pillars and beams two blocks
 * thick. The way through runs along z.
 *
 * @param span    distance between the pillars' centres
 * @param height  height to the top of the upper beam (its turned-up tips stand one higher); raised if needed to
 *                keep {@link #CLEAR} blocks under the tie beam (at least 7)
 * @param palette materials: {@code column} for the pillars and beams, {@code ridge} for the black parts
 */
public record Torii(int span, int height, TemplePalette palette) {

    /** Headroom under the tie beam (貫): three blocks, so a torii can be walked through. */
    public static final int CLEAR = 3;

    public int thickness() {
        return span >= 9 ? 2 : 1;
    }

    public Model build() {
        Model m = new Model();
        TemplePalette p = palette;
        int t = thickness();
        int over = Math.max(2, span / 4); // how far the top beams run past the pillars
        int left = 0, right = span; // pillars' first columns
        // The tie beam leaves at least CLEAR blocks to walk under; a torii too low for that is made taller.
        int nuki = Math.max(CLEAR, height - 2 - Math.max(2, height / 5));
        int shimaki = Math.max(nuki + 2, height - 2), kasagi = shimaki + 1;
        for (int dz = 0; dz < t; dz++) {
            for (int dx = 0; dx < t; dx++) {
                for (int x : new int[]{left + dx, right + dx}) {
                    for (int y = 0; y < shimaki; y++) {
                        m.set(x, y, dz, y == 0 ? p.ridge() : p.column());
                    }
                }
            }
            for (int x = left - over + 1; x < right + t + over - 1; x++) {
                m.set(x, nuki, dz, p.column());
            }
            for (int x = left - over + 1; x < right + t + over - 1; x++) {
                m.set(x, shimaki, dz, p.column());
            }
            for (int x = left - over; x < right + t + over; x++) {
                boolean end = x == left - over || x == right + t + over - 1;
                m.set(x, kasagi, dz, p.ridge());
                if (end) { // the ends turn up (反り)
                    m.set(x, kasagi + 1, dz, p.ridgeStairs() + "[facing=" + (x < left ? "west" : "east") + ",half=bottom]");
                }
            }
            int c0 = (left + right + t - 1) / 2, c1 = (left + right + t) / 2; // one column, or the middle two
            for (int c = c0; c <= c1; c++) {
                for (int y = nuki + 1; y < shimaki; y++) {
                    m.set(c, y, dz, y == nuki + 1 ? p.column() : p.ridge()); // the plaque post and its plaque
                }
            }
        }
        return m;
    }
}
