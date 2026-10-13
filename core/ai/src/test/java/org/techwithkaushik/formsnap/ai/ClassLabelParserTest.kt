package org.techwithkaushik.formsnap.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class ClassLabelParserTest {
    @Test
    fun parsesSequentialNumberedLabels() {
        assertEquals(
            listOf("person", "car", "traffic light"),
            ClassLabelParser.parse("0 person\n1: car\n2, traffic light"),
        )
    }

    @Test
    fun preservesSparseNumberedClassIds() {
        assertEquals(
            listOf("person", "Class 1", "car"),
            ClassLabelParser.parse("0 person\n2 car"),
        )
    }

    @Test
    fun preservesSparseIdsInNestedJsonNamesMap() {
        assertEquals(
            listOf("person", "Class 1", "car"),
            ClassLabelParser.parse("""{"names":{"0":"person","2":"car"}}"""),
        )
    }

    @Test
    fun parsesJsonLabelArraysWithoutChangingOrder() {
        assertEquals(
            listOf("person", "bicycle", "traffic light"),
            ClassLabelParser.parse("""["person","bicycle","traffic light"]"""),
        )
    }

    @Test
    fun parsesOrdinaryOneLabelPerLineFiles() {
        assertEquals(
            listOf("person", "bicycle", "traffic light"),
            ClassLabelParser.parse("person\nbicycle\ntraffic light"),
        )
    }
}
