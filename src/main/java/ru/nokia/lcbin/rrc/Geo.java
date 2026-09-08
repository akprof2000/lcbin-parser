package ru.nokia.lcbin.rrc;

import ru.nokia.lcbin.codec.BitReader;

/**
 * Геокоординаты из RRC {@code LocationInfo-r10} (TS 36.331). Сами координаты внутри — это OCTET STRING,
 * содержимое которого закодировано по TS 36.355 (LPP) в UPER:
 * <pre>
 *   Ellipsoid-Point:                              latSign(1) lat(23) lon(24)                              = 6 байт
 *   EllipsoidPointWithAltitude:                   + altDir(1) alt(15)                                    = 8 байт
 *   EllipsoidPointWithUncertaintyCircle:          Ellipsoid-Point + uncertainty(7)
 *   EllipsoidPointWithUncertaintyEllipse:         Ellipsoid-Point + major(7) minor(7) orient(8) conf(7)
 *   EllipsoidPointWithAltitudeAndUncertaintyEllipsoid: WithAltitude + major(7) minor(7) orient(8) uncAlt(7) conf(7) = 13 байт
 * </pre>
 * Пересчёт по 3GPP TS 23.032:
 * широта = ±lat × 90 / 2²³; долгота = lon × 360 / 2²⁴, где lon — знаковое (в UPER закодировано со сдвигом −2²³);
 * неопределённость = 10 × (1.1^K − 1) м; неопределённость высоты = 45 × (1.025^K − 1) м.
 *
 * @param type      имя варианта из RRC (например {@code ellipsoidPointWithAltitude-r10})
 * @param lat       широта, градусы (север +)
 * @param lon       долгота, градусы (восток +)
 * @param altM      высота, м (null если нет)
 * @param uncMajorM большая полуось эллипса неопределённости, м (null если нет)
 * @param uncMinorM малая полуось, м
 * @param orientDeg ориентация большой полуоси, градусы от севера
 * @param uncAltM   неопределённость высоты, м
 * @param confidence доверительная вероятность, %
 * @param speedKmh  горизонтальная скорость, км/ч (из horizontalVelocity)
 * @param bearingDeg курс, градусы
 * @param gnssTodMs GNSS time of day, мс
 */
public record Geo(String type, Double lat, Double lon, Integer altM, Double uncMajorM, Double uncMinorM,
                  Integer orientDeg, Double uncAltM, Integer confidence, Integer speedKmh, Integer bearingDeg,
                  Long gnssTodMs) {

    private static final String[] ROOT = {"ellipsoid-Point-r10", "ellipsoidPointWithAltitude-r10"};
    private static final String[] EXT = {"ellipsoidPointWithUncertaintyCircle-r11", "ellipsoidPointWithUncertaintyEllipse-r11",
            "ellipsoidPointWithAltitudeAndUncertaintyEllipsoid-r11", "ellipsoidArc-r11", "polygon-r11"};

    /**
     * Декодирует {@code LocationInfo-r10 ::= SEQUENCE { locationCoordinates-r10 CHOICE{...}, horizontalVelocity-r10
     * OCTET STRING OPTIONAL, gnss-TOD-msec-r10 OCTET STRING OPTIONAL, ... }} из потока RRC.
     */
    public static Geo readLocationInfo(BitReader r) {
        boolean ext = r.readBoolean();
        boolean velPresent = r.readBoolean();
        boolean todPresent = r.readBoolean();
        // CHOICE с маркером расширения: 2 корневых варианта (1 бит) либо расширенные (normally small + open type)
        String type;
        byte[] coords;
        if (r.readBit() == 0) {
            int idx = r.readBit();
            type = ROOT[idx];
            coords = r.readOctetString();
        } else {
            int idx = r.readNormallySmall();
            type = idx < EXT.length ? EXT[idx] : "locationCoordinates-ext" + idx;
            BitReader open = r.readOpenType();
            coords = open.readOctetString();
        }
        Geo g = decodeCoordinates(type, coords);
        Integer speed = null, bearing = null;
        if (velPresent) {
            // HorizontalVelocity (36.355): bearing INTEGER(0..359) 9 бит, horizontalSpeed INTEGER(0..2047) 11 бит
            byte[] v = r.readOctetString();
            if (v.length >= 3) {
                BitReader vr = new BitReader(v);
                bearing = (int) vr.readBits(9);
                speed = (int) vr.readBits(11);
            }
        }
        Long tod = null;
        if (todPresent) {
            byte[] t = r.readOctetString();   // GNSS-TOD-msec INTEGER(0..3599999): 22 бита в 3 байтах
            if (t.length >= 3) tod = new BitReader(t).readBits(22);
        }
        if (ext) r.skipExtensionAdditions();
        return new Geo(g.type, g.lat, g.lon, g.altM, g.uncMajorM, g.uncMinorM, g.orientDeg, g.uncAltM, g.confidence, speed, bearing, tod);
    }

    /** Разбирает OCTET STRING с координатами по типу варианта. Неизвестные типы (дуга, полигон) дают только type. */
    public static Geo decodeCoordinates(String type, byte[] b) {
        try {
            BitReader r = new BitReader(b);
            boolean withAlt = type.contains("WithAltitude");
            if (type.startsWith("ellipsoidArc") || type.startsWith("polygon") || b.length < 6) {
                return new Geo(type, null, null, null, null, null, null, null, null, null, null, null);
            }
            int latSign = r.readBit();
            long latRaw = r.readBits(23);
            long lonRaw = r.readBits(24);                       // закодировано как (lon − (−2²³)), см. X.691 10.5
            double lat = (latSign == 1 ? -1 : 1) * latRaw * 90.0 / (1 << 23);
            double lon = (lonRaw - (1 << 23)) * 360.0 / (1 << 24);
            Integer alt = null;
            if (withAlt) {
                int altDir = r.readBit();
                alt = (altDir == 1 ? -1 : 1) * (int) r.readBits(15);
            }
            Double major = null, minor = null, uncAlt = null;
            Integer orient = null, conf = null;
            if (type.startsWith("ellipsoidPointWithUncertaintyCircle")) {
                major = unc(r.readBits(7));
            } else if (type.startsWith("ellipsoidPointWithUncertaintyEllipse")) {
                major = unc(r.readBits(7));
                minor = unc(r.readBits(7));
                orient = (int) r.readBits(8);
                conf = (int) r.readBits(7);
            } else if (type.startsWith("ellipsoidPointWithAltitudeAndUncertaintyEllipsoid")) {
                major = unc(r.readBits(7));
                minor = unc(r.readBits(7));
                orient = (int) r.readBits(8);
                uncAlt = round1(45 * (Math.pow(1.025, r.readBits(7)) - 1));
                conf = (int) r.readBits(7);
            }
            return new Geo(type, round6(lat), round6(lon), alt, major, minor, orient, uncAlt, conf, null, null, null);
        } catch (RuntimeException e) {
            return new Geo(type, null, null, null, null, null, null, null, null, null, null, null);
        }
    }

    private static double unc(long k) { return round1(10 * (Math.pow(1.1, k) - 1)); }
    private static double round1(double v) { return Math.round(v * 10) / 10.0; }
    private static double round6(double v) { return Math.round(v * 1e6) / 1e6; }
}
