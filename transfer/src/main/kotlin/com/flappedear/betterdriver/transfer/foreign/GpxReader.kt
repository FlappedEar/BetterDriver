package com.flappedear.betterdriver.transfer.foreign

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.xml.XMLConstants
import javax.xml.parsers.ParserConfigurationException
import javax.xml.parsers.SAXParserFactory

/**
 * Reads GPX 1.1 (and 1.0) track points (FBBD-164): every `trkpt` of every track and segment, in
 * file order, with its time, `ele`, and speed (m/s) and course from GPX 1.0 or the Garmin
 * TrackPointExtension when present. A point without a time is left out. Each `trkseg` gets its own
 * [ImportPoint.segment], so speed and heading are never derived across a gap. Streams with SAX, so
 * a long file is never held in memory; DOCTYPEs are refused, so no entity can be expanded.
 */
internal object GpxReader {

    @Suppress("ThrowsCount") // One message per way the XML can fail.
    fun read(input: InputStream, sink: ImportSink) {
        val handler = Handler(sink)
        try {
            val reader = factory().newSAXParser().xmlReader
            reader.contentHandler = handler
            reader.errorHandler = handler
            reader.entityResolver = handler
            reader.parse(InputSource(input))
        } catch (e: SAXParseException) {
            throw ImportFormatException("the GPX file is damaged (line ${e.lineNumber})", e)
        } catch (e: SAXException) {
            // A limit or format error from the handler comes wrapped.
            (e.exception as? ImportFormatException)?.let { throw it }
            throw ImportFormatException("the GPX file is damaged: ${e.message}", e)
        } catch (e: ParserConfigurationException) {
            throw ImportFormatException("GPX cannot be read on this device", e)
        }
        if (!handler.sawGpx) throw ImportFormatException("this is not a GPX file")
    }

    private fun factory() = SAXParserFactory.newInstance().apply {
        isNamespaceAware = true
        // Best effort: not every parser (Android's) knows each feature.
        trySet(XMLConstants.FEATURE_SECURE_PROCESSING)
        trySet("http://apache.org/xml/features/disallow-doctype-decl")
        trySet("http://xml.org/sax/features/external-general-entities", false)
        trySet("http://xml.org/sax/features/external-parameter-entities", false)
    }

    private fun SAXParserFactory.trySet(feature: String, value: Boolean = true) {
        try {
            setFeature(feature, value)
        } catch (_: SAXException) {
            // Not supported here.
        } catch (_: ParserConfigurationException) {
            // Not supported here.
        }
    }

    private class Handler(private val sink: ImportSink) : DefaultHandler() {
        var sawGpx = false
        private var segment = 0
        private var inPoint = false
        private var lat = Double.NaN
        private var lon = Double.NaN
        private var time: Long? = null
        private var ele = Double.NaN
        private var speedMs = Double.NaN
        private var course = Double.NaN
        private var sats = Double.NaN
        private val text = StringBuilder()
        private var collecting = false

        override fun startDocument() {
            sawGpx = false
        }

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
            val name = nameOf(localName, qName)
            when {
                name == "gpx" -> sawGpx = true
                !sawGpx -> throw SAXException(ImportFormatException("this is not a GPX file"))
                name == "trkseg" -> segment++
                name == "trkpt" -> {
                    inPoint = true
                    lat = attributes.getValue("lat")?.trim()?.toDoubleOrNull() ?: Double.NaN
                    lon = attributes.getValue("lon")?.trim()?.toDoubleOrNull() ?: Double.NaN
                    time = null
                    ele = Double.NaN
                    speedMs = Double.NaN
                    course = Double.NaN
                    sats = Double.NaN
                }
                inPoint && name in Values -> {
                    collecting = true
                    text.setLength(0)
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (collecting && text.length + length <= MaxValueChars) text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val name = nameOf(localName, qName)
            if (collecting && name in Values) {
                collecting = false
                value(name, text.toString().trim())
            } else if (name == "trkpt" && inPoint) {
                inPoint = false
                pointEnded()
            }
        }

        private fun value(name: String, v: String) {
            val number = v.toDoubleOrNull() ?: Double.NaN
            when (name) {
                "time" -> time = parseTime(v)
                "ele" -> ele = number
                "speed" -> speedMs = number
                "course" -> course = number
                "sat" -> sats = number
            }
        }

        private fun pointEnded() {
            val t = time
            if (t == null || lat.isNaN() || lon.isNaN()) {
                sink.skipped()
                return
            }
            sink.point(
                ImportPoint(
                    utcMicros = t,
                    lat = lat,
                    lon = lon,
                    speedKmh = speedMs * KmhPerMs,
                    headingDeg = course,
                    altM = ele,
                    sats = sats,
                    segment = segment,
                ),
            )
        }

        override fun resolveEntity(publicId: String?, systemId: String?) =
            throw SAXException(ImportFormatException("the GPX file refers to other files"))

        override fun fatalError(e: SAXParseException) = throw e

        private fun nameOf(localName: String?, qName: String?): String =
            localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty().substringAfter(':')
    }

    private val Values = setOf("time", "ele", "speed", "course", "sat")
    private const val MaxValueChars = 256
    private const val KmhPerMs = 3.6
    private const val MicrosPerSecond = 1_000_000L
    private const val NanosPerMicro = 1_000L

    /** ISO 8601 "2026-10-12T10:42:01.250Z" or with an offset; one with no zone is taken as UTC. Null if unreadable. */
    fun parseTime(s: String): Long? = try {
        val instant = try {
            OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
        } catch (_: DateTimeParseException) {
            LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC)
        }
        instant.epochSecond * MicrosPerSecond + instant.nano / NanosPerMicro
    } catch (_: DateTimeParseException) {
        null
    }
}
