package com.diplay.app;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class BinaryXmlManifestParserTest {
    @Test
    public void rejectsInputShorterThanRootHeader() {
        expectIOException(new byte[7]);
    }

    @Test
    public void rejectsNonBinaryXmlRoot() {
        byte[] data = emptyRoot();
        data[0] = 0;
        data[1] = 0;
        expectIOException(data);
    }

    @Test
    public void rejectsDeclaredSizeThatDoesNotMatchInput() {
        byte[] data = emptyRoot();
        data[4] = 9;
        expectIOException(data);
    }

    @Test
    public void acceptsMinimalWellFormedXmlContainer() throws Exception {
        BinaryXmlManifestParser.Document document =
                BinaryXmlManifestParser.parse(emptyRoot());
        assertEquals(0, document.roots.size());
    }

    private static byte[] emptyRoot() {
        return new byte[] { 3, 0, 8, 0, 8, 0, 0, 0 };
    }

    private static void expectIOException(byte[] data) {
        try {
            BinaryXmlManifestParser.parse(data);
            fail("Expected IOException");
        } catch (IOException expected) {
            // Expected malformed input rejection.
        }
    }
}
