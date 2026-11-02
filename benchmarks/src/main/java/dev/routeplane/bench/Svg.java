package dev.routeplane.bench;

import java.util.List;
import java.util.Locale;

/** Minimal SVG line-chart renderer (no JavaScript, no external dependencies) for the benchmark reports. */
final class Svg {
    private Svg() {
    }

    record Point(double x, double y) {
    }

    record Series(String name, List<Point> points, boolean dashed) {
        Series(String name, List<Point> points) {
            this(name, points, false);
        }
    }

    record Marker(double x, String label) {
    }

    private static final String[] COLORS = {"#1f77b4", "#d62728", "#2ca02c", "#ff7f0e", "#9467bd", "#8c564b", "#e377c2", "#17becf", "#7f7f7f"};

    static String lineChart(String title, String xLabel, String yLabel, List<Series> series, boolean logX, boolean logY, List<Marker> markers,
                            Double yMinFixed, Double yMaxFixed) {
        int w = 760;
        int h = 380;
        int left = 70;
        int right = 20;
        int top = 36;
        int bottom = 72;
        double xMin = Double.MAX_VALUE;
        double xMax = -Double.MAX_VALUE;
        double yMin = Double.MAX_VALUE;
        double yMax = -Double.MAX_VALUE;
        for (Series s : series) {
            for (Point p : s.points()) {
                if (Double.isNaN(p.y()) || Double.isInfinite(p.y()) || (logY && p.y() <= 0)) {
                    continue;
                }
                xMin = Math.min(xMin, p.x());
                xMax = Math.max(xMax, p.x());
                yMin = Math.min(yMin, p.y());
                yMax = Math.max(yMax, p.y());
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(w).append(' ').append(h).append("\" width=\"").append(w).append("\" height=\"").append(h)
                .append("\" font-family=\"sans-serif\" font-size=\"11\">\n");
        sb.append("<rect width=\"100%\" height=\"100%\" fill=\"white\"/>\n");
        sb.append("<text x=\"").append(w / 2).append("\" y=\"20\" text-anchor=\"middle\" font-size=\"14\" font-weight=\"bold\">").append(esc(title)).append("</text>\n");
        if (xMin == Double.MAX_VALUE) {
            sb.append("<text x=\"").append(w / 2).append("\" y=\"").append(h / 2).append("\" text-anchor=\"middle\">no data</text>\n</svg>\n");
            return sb.toString();
        }
        if (yMinFixed != null) {
            yMin = yMinFixed;
        } else if (!logY) {
            yMin = Math.min(0, yMin);
        }
        if (yMaxFixed != null) {
            yMax = yMaxFixed;
        }
        if (xMax == xMin) {
            xMax = xMin + 1;
        }
        if (yMax <= yMin) {
            yMax = yMin + 1;
        }
        if (logY) {
            yMin = Math.pow(10, Math.floor(Math.log10(yMin)));
            yMax = Math.pow(10, Math.ceil(Math.log10(yMax)));
        } else if (yMaxFixed == null) {
            yMax = niceCeil(yMax);
        }
        double fxMin = logX ? Math.log10(xMin) : xMin;
        double fxMax = logX ? Math.log10(xMax) : xMax;
        double fyMin = logY ? Math.log10(yMin) : yMin;
        double fyMax = logY ? Math.log10(yMax) : yMax;
        int pw = w - left - right;
        int ph = h - top - bottom;
        // grid and y ticks (1-2-5 per decade on a log axis)
        List<Double> yTickValues = new java.util.ArrayList<>();
        if (logY) {
            yTickValues.addAll(logTicks(yMin, yMax));
        } else {
            yTickValues.addAll(linearTicks(yMin, yMax, 5));
        }
        for (double v : yTickValues) {
            double fy = logY ? Math.log10(v) : v;
            double py = top + ph - (fy - fyMin) / (fyMax - fyMin) * ph;
            sb.append(String.format(Locale.ROOT, "<line x1=\"%d\" y1=\"%.1f\" x2=\"%d\" y2=\"%.1f\" stroke=\"#e0e0e0\"/>\n", left, py, left + pw, py));
            sb.append(String.format(Locale.ROOT, "<text x=\"%d\" y=\"%.1f\" text-anchor=\"end\">%s</text>\n", left - 6, py + 4, tick(v)));
        }
        // x ticks: distinct x values of the first series when few, otherwise evenly spaced
        List<Double> xt = new java.util.ArrayList<>();
        if (!series.isEmpty() && series.get(0).points().size() <= 8) {
            series.stream().flatMap(s -> s.points().stream()).map(Point::x).distinct().sorted().forEach(xt::add);
        } else if (logX) {
            xt.addAll(logTicks(xMin, xMax));
        } else {
            xt.addAll(linearTicks(xMin, xMax, 7));
        }
        for (double xv : xt) {
            double fx = logX ? Math.log10(xv) : xv;
            double px = left + (fx - fxMin) / (fxMax - fxMin) * pw;
            sb.append(String.format(Locale.ROOT, "<line x1=\"%.1f\" y1=\"%d\" x2=\"%.1f\" y2=\"%d\" stroke=\"#f0f0f0\"/>\n", px, top, px, top + ph));
            sb.append(String.format(Locale.ROOT, "<text x=\"%.1f\" y=\"%d\" text-anchor=\"middle\">%s</text>\n", px, top + ph + 16, tick(xv)));
        }
        sb.append(String.format(Locale.ROOT, "<rect x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\" fill=\"none\" stroke=\"#444\"/>\n", left, top, pw, ph));
        // markers (vertical event lines)
        if (markers != null) {
            int idx = 0;
            for (Marker m : markers) {
                double fx = logX ? Math.log10(Math.max(m.x(), 1e-9)) : m.x();
                if (fx < fxMin || fx > fxMax) {
                    continue;
                }
                double px = left + (fx - fxMin) / (fxMax - fxMin) * pw;
                sb.append(String.format(Locale.ROOT, "<line x1=\"%.1f\" y1=\"%d\" x2=\"%.1f\" y2=\"%d\" stroke=\"#888\" stroke-dasharray=\"4 3\"/>\n", px, top, px, top + ph));
                sb.append(String.format(Locale.ROOT, "<text x=\"%.1f\" y=\"%d\" font-size=\"10\" fill=\"#555\" transform=\"rotate(-90 %.1f %d)\">%s</text>\n",
                        px - 3, top + ph - 4, px - 3, top + ph - 4, esc(m.label())));
                idx++;
            }
        }
        // series
        int ci = 0;
        for (Series s : series) {
            String color = COLORS[ci % COLORS.length];
            StringBuilder pts = new StringBuilder();
            for (Point p : s.points()) {
                if (Double.isNaN(p.y()) || Double.isInfinite(p.y()) || (logY && p.y() <= 0)) {
                    continue;
                }
                double fx = logX ? Math.log10(p.x()) : p.x();
                double fy = logY ? Math.log10(p.y()) : p.y();
                double px = left + (fx - fxMin) / (fxMax - fxMin) * pw;
                double py = top + ph - (Math.max(fyMin, Math.min(fyMax, fy)) - fyMin) / (fyMax - fyMin) * ph;
                pts.append(String.format(Locale.ROOT, "%.1f,%.1f ", px, py));
            }
            sb.append("<polyline fill=\"none\" stroke=\"").append(color).append("\" stroke-width=\"1.8\"").append(s.dashed() ? " stroke-dasharray=\"6 4\"" : "")
                    .append(" points=\"").append(pts.toString().trim()).append("\"/>\n");
            if (s.points().size() <= 12) {
                for (String pt : pts.toString().trim().split(" ")) {
                    if (!pt.isEmpty()) {
                        String[] xy = pt.split(",");
                        sb.append("<circle cx=\"").append(xy[0]).append("\" cy=\"").append(xy[1]).append("\" r=\"3\" fill=\"").append(color).append("\"/>\n");
                    }
                }
            }
            ci++;
        }
        // axis labels and legend
        sb.append(String.format(Locale.ROOT, "<text x=\"%d\" y=\"%d\" text-anchor=\"middle\">%s</text>\n", left + pw / 2, top + ph + 34, esc(xLabel)));
        sb.append(String.format(Locale.ROOT, "<text x=\"14\" y=\"%d\" text-anchor=\"middle\" transform=\"rotate(-90 14 %d)\">%s</text>\n", top + ph / 2, top + ph / 2, esc(yLabel)));
        int lx = left;
        int ly = h - 18;
        ci = 0;
        for (Series s : series) {
            String color = COLORS[ci % COLORS.length];
            if (lx + 18 + s.name().length() * 6 > w - 10) {
                lx = left;
                ly += 13;
            }
            sb.append(String.format(Locale.ROOT, "<line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"%s\" stroke-width=\"2\"%s/>\n", lx, ly - 4, lx + 14, ly - 4, color, s.dashed() ? " stroke-dasharray=\"4 3\"" : ""));
            sb.append(String.format(Locale.ROOT, "<text x=\"%d\" y=\"%d\">%s</text>\n", lx + 18, ly, esc(s.name())));
            lx += 26 + s.name().length() * 6;
            ci++;
        }
        sb.append("</svg>\n");
        return sb.toString();
    }

    /** Round tick values (step 1, 2 or 5 times a power of ten) covering [min, max] with roughly {@code target} intervals. */
    static List<Double> linearTicks(double min, double max, int target) {
        double raw = (max - min) / Math.max(1, target);
        double mag = Math.pow(10, Math.floor(Math.log10(raw)));
        double n = raw / mag;
        double step = (n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10) * mag;
        List<Double> out = new java.util.ArrayList<>();
        for (double v = Math.ceil(min / step - 1e-9) * step; v <= max + step * 1e-9; v += step) {
            out.add(Math.abs(v) < step * 1e-9 ? 0.0 : v);
        }
        return out;
    }

    /** Ticks at 1, 2 and 5 times each power of ten inside [min, max]. */
    static List<Double> logTicks(double min, double max) {
        List<Double> out = new java.util.ArrayList<>();
        double decade = Math.pow(10, Math.floor(Math.log10(min)));
        for (; decade <= max * 1.0001; decade *= 10) {
            for (double m : new double[] {1, 2, 5}) {
                double v = decade * m;
                if (v >= min * 0.9999 && v <= max * 1.0001) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    private static double niceCeil(double v) {
        if (v <= 0) {
            return 1;
        }
        double mag = Math.pow(10, Math.floor(Math.log10(v)));
        double n = v / mag;
        double nice = n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10;
        return nice * mag;
    }

    static String tick(double v) {
        if (v >= 1000 && v % 1000 == 0) {
            return (long) (v / 1000) + "k";
        }
        if (Math.abs(v - Math.rint(v)) < 1e-9) {
            return Long.toString((long) v);
        }
        if (v >= 10) {
            return String.format(Locale.ROOT, "%.0f", v);
        }
        return new java.math.BigDecimal(v).round(new java.math.MathContext(3)).stripTrailingZeros().toPlainString();
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
