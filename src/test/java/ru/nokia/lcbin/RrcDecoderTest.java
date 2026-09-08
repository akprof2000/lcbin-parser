package ru.nokia.lcbin;

import org.junit.jupiter.api.Test;
import ru.nokia.lcbin.rrc.Geo;
import ru.nokia.lcbin.rrc.RrcUlDcchDecoder;

import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Круговые тесты: сообщение собирается тестовым UPER-кодером с известными значениями, декодер обязан их вернуть.
 * Формулы пересчёта — TS 36.133 (RSRP/RSRQ/RSCP/EcN0/RSSI) и TS 23.032 (координаты).
 */
class RrcDecoderTest {
    private static byte[] hex(String s) { return HexFormat.of().parseHex(s.replace(" ", "")); }
    private final RrcUlDcchDecoder dec = new RrcUlDcchDecoder();

    @Test
    void measurementReportWithLocationAndScell() {
        var m = dec.decode(SampleStream.MEAS_B);
        assertNull(m.warning());
        var mr = m.measurementReport();
        assertEquals(5, mr.measId());
        assertEquals(-90.0, mr.rsrpDbm());
        assertEquals(-9.5, mr.rsrqDb());
        assertTrue(mr.neighbors().isEmpty());
        Geo g = mr.geo();
        assertNotNull(g);
        assertEquals("ellipsoidPointWithAltitude-r10", g.type());
        assertEquals(10.5, (double) g.lat(), 1e-5);
        assertEquals(-20.25, (double) g.lon(), 1e-5);
        assertEquals(30, (int) g.altM());
        assertEquals(123456L, (long) g.gnssTodMs());
        assertNull(g.speedKmh());
        assertNull(g.uncMajorM());
        assertEquals(1, mr.scells().size());
        assertEquals("sf1:-95dBm/-12dB", mr.scellsText());
    }

    @Test
    void locationWithVelocityAndSouthernHemisphere() {
        var p = new SyntheticRrc.Point(-33.9, 151.2, -5);
        byte[] body = SyntheticRrc.measurementReport(3, 60, 30, List.of(new SyntheticRrc.Eutra(1, 0, 0), new SyntheticRrc.Eutra(503, 97, 34)),
                p, null, null);
        // добавим скорость вручную: собираем ту же группу расширения с velocity
        UperWriter w = new UperWriter();
        w.bit(0).bits(1, 4).bit(0).bits(0, 3).bit(0);
        w.bit(1).bit(0).cint(3, 1, 32).cint(60, 0, 97).cint(30, 0, 34);
        w.small(1).bit(0).bit(1);
        UperWriter g = new UperWriter().bit(1).bit(0);
        SyntheticRrc.locationInfo(g, p, 245, 5, 3353000L);
        w.openType(g);
        var m = dec.decode(w.toBytes());
        assertNull(m.warning());
        Geo geo = m.measurementReport().geo();
        assertEquals(-33.9, (double) geo.lat(), 1e-5);
        assertEquals(151.2, (double) geo.lon(), 1e-5);
        assertEquals(-5, (int) geo.altM());
        assertEquals(245, (int) geo.bearingDeg());
        assertEquals(5, (int) geo.speedKmh());
        assertEquals(3353000L, (long) geo.gnssTodMs());
        // а первое сообщение — два соседа с крайними значениями
        var mr = dec.decode(body).measurementReport();
        assertEquals("pci1:-140dBm/-19.5dB", mr.neighbors().get(0).format());
        assertEquals("pci503:-43dBm/-2.5dB", mr.neighbors().get(1).format());
    }

    @Test
    void uncertaintyEllipsoidFormulas() {
        // latSign 0, lat = 2^22 (45°), lon = 2^23 + 2^22 (90°), alt +100, major K=10, minor K=2, orient 0, uncAlt K=17, conf 39
        UperWriter w = new UperWriter();
        w.bit(0).bits(1 << 22, 23).bits((1 << 23) + (1 << 22), 24).bit(0).bits(100, 15);
        w.bits(10, 7).bits(2, 7).bits(0, 8).bits(17, 7).bits(39, 7);
        Geo g = Geo.decodeCoordinates("ellipsoidPointWithAltitudeAndUncertaintyEllipsoid-r11", w.toBytes());
        assertEquals(45.0, (double) g.lat(), 1e-9);
        assertEquals(90.0, (double) g.lon(), 1e-9);
        assertEquals(100, (int) g.altM());
        assertEquals(15.9, (double) g.uncMajorM());   // 10·(1.1^10 − 1)
        assertEquals(2.1, (double) g.uncMinorM());
        assertEquals(0, (int) g.orientDeg());
        assertEquals(23.5, (double) g.uncAltM());     // 45·(1.025^17 − 1)
        assertEquals(39, (int) g.confidence());
        Geo south = Geo.decodeCoordinates("ellipsoid-Point-r10", hex("800000 800000"));
        assertEquals(0.0, (double) south.lat());
        assertEquals(0.0, (double) south.lon());
        Geo bad = Geo.decodeCoordinates("polygon-r11", hex("00"));
        assertNull(bad.lat());
        assertEquals("polygon-r11", bad.type());
    }

    @Test
    void ueInformationResponseWithRachRlfAndMdt() {
        var m = dec.decode(SampleStream.UE_INFO);
        assertEquals("ueInformationResponse-r9", m.name());
        assertNull(m.warning());
        assertEquals(2, m.rachReport().preamblesSent());
        assertTrue(m.rachReport().contentionDetected());

        var rlf = m.rlfReport();
        assertNotNull(rlf);
        assertEquals(-120.0, (double) rlf.lastRsrpDbm());
        assertEquals(-14.5, (double) rlf.lastRsrqDb());
        assertEquals(1, rlf.neighbors().size());
        assertEquals("pci77/f1300:-115dBm/-15dB", rlf.neighbors().get(0).format());
        assertEquals("failedPCell=001-01/12345-7;reestablishmentCell=001-01/12345-8;timeConnFailure=1000ms;type=hof;"
                + "previousPCell=001-01/12346-1;c-rnti=4321;cause=t310-Expiry;timeSinceFailure=5s;", rlf.details());

        var log = m.logMeasReport();
        assertNotNull(log);
        assertEquals("2023-11-14 22:13:20", log.absTime());
        assertEquals("001-01/000001", log.traceRef());
        assertEquals(2, log.traceSessionRef());
        assertEquals(1, log.tceId());
        assertEquals(2, log.entries().size());
        var e0 = log.entries().get(0);
        assertEquals(10, e0.relTimeS());
        assertEquals("001-01/12345-7", e0.servCell());
        assertEquals(-102.0, e0.rsrpDbm());
        assertEquals(-12.5, e0.rsrqDb());
        assertEquals(10.5, (double) e0.geo().lat(), 1e-5);
        assertEquals("pci88/f1300:-110dBm/-14.5dB;utra10700/fdd100:rscp-90dBm/ecn0-4.5dB;gsm68/dcs1800/ncc1bcc3:rssi-61dBm", e0.neighborsText());
        var e1 = log.entries().get(1);
        assertEquals(20, e1.relTimeS());
        assertNull(e1.geo());
        assertNull(e1.neighborsText());
        assertEquals(-104.0, e1.rsrpDbm());
    }

    @Test
    void otherMessagesAreNamedOnly() {
        assertEquals("rrcConnectionSetupComplete", dec.decode(hex("20 00")).name());
        assertEquals("messageClassExtension", dec.decode(hex("80")).name());
        assertEquals("undecodable", dec.decode(new byte[0]).name());
        var spare = dec.decode(hex("0c")); // measurementReport, criticalExtensionsFuture
        assertEquals("measurementReport", spare.name());
        assertNotNull(spare.warning());
    }
}
