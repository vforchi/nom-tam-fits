package nom.tam.image.comp.tile;

/*
 * #%L
 * nom.tam FITS library
 * %%
 * Copyright (C) 1996 - 2015 nom-tam-fits
 * %%
 * This is free and unencumbered software released into the public domain.
 * 
 * Anyone is free to copy, modify, publish, use, compile, sell, or
 * distribute this software, either in source code form or as a compiled
 * binary, for any purpose, commercial or non-commercial, and by any
 * means.
 * 
 * In jurisdictions that recognize copyright laws, the author or authors
 * of this software dedicate any and all copyright interest in the
 * software to the public domain. We make this dedication for the benefit
 * of the public at large and to the detriment of our heirs and
 * successors. We intend this dedication to be an overt act of
 * relinquishment in perpetuity of all present and future rights to this
 * software under copyright law.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 * #L%
 */

import static nom.tam.fits.header.Compression.ZBITPIX;
import static nom.tam.fits.header.Compression.ZNAXIS;
import static nom.tam.fits.header.Compression.ZNAXISn;
import static nom.tam.fits.header.Compression.ZTILEn;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import nom.tam.fits.FitsException;
import nom.tam.fits.FitsFactory;
import nom.tam.fits.Header;
import nom.tam.fits.HeaderCardException;
import nom.tam.fits.header.Compression;
import nom.tam.fits.header.Standard;
import nom.tam.image.comp.ICompressOption;
import nom.tam.image.comp.ITileCompressor;
import nom.tam.image.comp.ITileCompressorProvider.ITileCompressorControl;
import nom.tam.image.comp.TileCompressorAlternativProvider;
import nom.tam.image.comp.TileCompressorProvider;
import nom.tam.image.comp.hdu.CompressedImageData;
import nom.tam.image.comp.rice.RiceCompressOption;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class TileCompressorProviderTest {

    static class Access2 extends CompressedImageData {

        private static Header emptyHeader() {
            Header header = new Header();
            try {
                header.card(Standard.NAXIS1).value(1);
            } catch (HeaderCardException e) {
                throw new RuntimeException();
            }
            return header;
        }

        public Access2() throws FitsException {
            super(emptyHeader());
        }

        TileOperation getTile() {
            return new TileDecompressor(getTileArray(), 0);
        }

        TileOperationsOfImage getTileArray() {
            return new TileOperationsOfImage(this);
        }
    }

    public static class BrokenClass extends TileCompressorProvider implements ITileCompressor<ByteBuffer> {

        public BrokenClass(BrokenOption option) {
            if (exceptionInConstructor) {
                throw new RuntimeException("could not instanciate");
            }
        }

        @Override
        public boolean compress(ByteBuffer buffer, ByteBuffer compressed) {
            if (exceptionInMethod) {
                throw new RuntimeException("could not compress");
            }
            return true;
        }

        @Override
        public void decompress(ByteBuffer compressed, ByteBuffer buffer) {
            if (exceptionInMethod) {
                throw new RuntimeException("could not decompress");
            }
        }

        private ITileCompressorControl getProvider() {
            return TileCompressorAlternativProvider.createControl(BrokenClass.class);
        }
    }

    public static class BrokenOption extends RiceCompressOption {

        public BrokenOption() {
            if (exceptionInConstructor) {
                throw new RuntimeException("could not instanciate");
            }
        }
    }

    private static boolean exceptionInConstructor;

    private static boolean exceptionInMethod;

    @Before
    public void setup() {
        exceptionInConstructor = false;
        exceptionInMethod = false;
    }

    @Test
    public void testAlternativeTileProcessor() throws Exception {
        ITileCompressorControl compressor = TileCompressorProvider.findCompressorControl(null, "X", long.class);
        Assert.assertTrue(compressor.getClass().getName().indexOf(TileCompressorAlternativProvider.class.getSimpleName()) > 0);

        Assert.assertNotNull(TileCompressorProvider.findCompressorControl(null, "X", long.class));

        Assert.assertNull(TileCompressorProvider.findCompressorControl("AA", Compression.ZCMPTYPE_RICE_1, int.class));
        Assert.assertNull(TileCompressorProvider.findCompressorControl(Compression.ZQUANTIZ_SUBTRACTIVE_DITHER_2, "BB", int.class));
        Assert.assertNull(TileCompressorProvider.findCompressorControl(Compression.ZQUANTIZ_SUBTRACTIVE_DITHER_2, Compression.ZCMPTYPE_RICE_1, String.class));

        Assert.assertNotNull(TileCompressorProvider.findCompressorControl(null, Compression.ZCMPTYPE_GZIP_2, int.class));
    }

    @Test(expected = IllegalStateException.class)
    public void testBadProviderCasesBadCompressConstruct() {
        ITileCompressorControl provider = new BrokenClass(null).getProvider();
        ICompressOption options = provider.option();
        exceptionInConstructor = true;
        provider.decompress(null, null, options);
    }

    @Test
    public void testBadProviderCasesBadCompressMethod() {
        ITileCompressorControl provider = new BrokenClass(null).getProvider();
        ICompressOption options = provider.option();
        exceptionInMethod = true;
        Assert.assertFalse(provider.compress(null, null, options));
    }

    @Test(expected = IllegalStateException.class)
    public void testBadProviderCasesBadDeCompressMethod() {
        ITileCompressorControl provider = new BrokenClass(null).getProvider();
        ICompressOption options = provider.option();
        exceptionInMethod = true;
        provider.decompress(null, null, options);
    }

    @Test(expected = IllegalStateException.class)
    public void testBadProviderCasesBadOption() {
        ITileCompressorControl provider = new BrokenClass(null).getProvider();
        exceptionInConstructor = true;
        provider.option();
    }

    @Test
    public void testBadProviderCasesSuccess() {
        ITileCompressorControl provider = new BrokenClass(null).getProvider();
        ICompressOption options = provider.option();
        provider.decompress(null, null, options);
    }

    @Test
    public void testBadProviderCasesSuccessCompressMethod() {
        ITileCompressorControl provider = new BrokenClass(null).getProvider();
        ICompressOption options = provider.option();
        provider.decompress(null, null, options);
        provider.compress(null, null, options);
    }

    @Test(expected = IllegalStateException.class)
    public void testTileCompressionError() throws Exception {
        TileOperation tileOperation = new Access2().getTile();
        tileOperation.execute(FitsFactory.threadPool());
        Thread.sleep(20);
        tileOperation.waitForResult();
    }

    @Test
    public void testTileToString() throws Exception {
        String toString = new Access2().getTile().toString();
        Assert.assertEquals("TileDecompressor(0,null,0)", toString);
    }

    @Test(expected = FitsException.class)
    public void testTileWrongHeader1() throws Exception {
        TileOperationsOfImage operationsOfImage = new TileOperationsOfImage(null);
        Header header = new Header();
        header.addValue(ZBITPIX, 32);
        header.addValue(ZNAXIS, 2);
        header.addValue(ZNAXISn.n(1), 100);
        header.addValue(ZTILEn.n(1), 15);
        header.addValue(ZTILEn.n(2), 15);
        operationsOfImage.readHeader(header);
    }

    @Test
    public void testTileSizes() throws Exception {
        int[] sizes = {
            1,
            3,
            15,
            33,
            50,
            66,
            100
        };
        for (int tileWidth : sizes) {
            for (int tileHeigth : sizes) {
                testTileSizes(tileWidth, tileHeigth);
            }
        }
    }

    private void testTileSizes(int tileWidth, int tileHeigth) throws HeaderCardException, FitsException {
        int imageSize = 100;
        TileOperationsOfImage operationsOfImage = new TileOperationsOfImage(null);
        Buffer buffer = IntBuffer.allocate(imageSize * imageSize);
        Header header = new Header();
        header.addValue(ZBITPIX, 32);
        header.addValue(ZNAXIS, 2);
        header.addValue(ZNAXISn.n(1), imageSize);
        header.addValue(ZNAXISn.n(2), imageSize);
        header.addValue(ZTILEn.n(1), tileWidth);
        header.addValue(ZTILEn.n(2), tileHeigth);

        operationsOfImage.readHeader(header);
        operationsOfImage.prepareUncompressedData(buffer);
        List<TileOperation> tiles = getTiles(operationsOfImage);
        int heigth = 0;
        int width = 0;
        int pixels = 0;
        for (TileOperation tileOperation : tiles) {
            if (tileWidth == imageSize) {
                heigth += tileOperation.tileBuffer.getHeight();
            } else if (tileHeigth == imageSize) {
                width += tileOperation.tileBuffer.getWidth();
            }
            pixels += tileOperation.tileBuffer.getHeight() * tileOperation.tileBuffer.getWidth();
        }
        Assert.assertEquals(imageSize * imageSize, pixels);
        if (heigth != 0) {
            Assert.assertEquals(imageSize, heigth);
        }
        if (width != 0) {
            Assert.assertEquals(imageSize, width);
        }
    }

    private List<TileOperation> getTiles(TileOperationsOfImage operationsOfImage) {
        List<TileOperation> tiles = new ArrayList<>();
        try {
            for (int index = 0; index < 10000; index++) {
                tiles.add(operationsOfImage.getTile(index));
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            return tiles;
        }
        return tiles;
    }
}