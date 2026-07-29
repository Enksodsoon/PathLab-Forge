package org.pathlab.forge.conversion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class BioFormatsMetadataParserTest {
    @Test
    void extractsRgbSeriesFromOmeXml() {
        var xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <OME xmlns="http://www.openmicroscopy.org/Schemas/OME/2016-06">
                  <Image ID="Image:0" Name="Label">
                    <Pixels ID="Pixels:0" DimensionOrder="XYCZT" Type="uint8"
                      SizeX="8021" SizeY="9366" SizeC="3" SizeZ="1" SizeT="1"
                      PhysicalSizeX="0.2738" PhysicalSizeXUnit="µm"
                      PhysicalSizeY="0.2738" PhysicalSizeYUnit="µm"/>
                  </Image>
                </OME>
                """;

        var series = BioFormatsMetadataParser.parse(xml);

        assertEquals(1, series.size());
        assertEquals(0, series.get(0).index());
        assertEquals("Label", series.get(0).name());
        assertEquals(8021, series.get(0).width());
        assertEquals(9366, series.get(0).height());
        assertEquals(3, series.get(0).channels());
        assertEquals(0.2738, series.get(0).physicalSizeX());
    }

    @Test
    void preservesImageOrderWhenIdsAreNotNumeric() {
        var xml = """
                <OME>
                  <Image ID="preview"><Pixels SizeX="512" SizeY="184" SizeC="3"
                    SizeZ="1" SizeT="1" Type="uint8"/></Image>
                  <Image ID="main"><Pixels SizeX="1000" SizeY="2000" SizeC="3"
                    SizeZ="1" SizeT="1" Type="uint8"/></Image>
                </OME>
                """;

        var series = BioFormatsMetadataParser.parse(xml);

        assertEquals(0, series.get(0).index());
        assertEquals(1, series.get(1).index());
    }
}
