package nom.tam.image.comp.opt;

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

import java.util.Arrays;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

public class Quantize {

    private static final double RANDOM_START_VALUE = 16807.0;

    private static final int MINIMUM_PIXEL_WIDTH = 9;

    private static final int N4 = 4;

    private static final int N6 = 6;

    private static final int N8 = 8;

    private static final double NOISE_5_MULTIPLICATOR = 0.1772048;

    private static final double NOISE_3_MULTIPLICATOR = 0.6052697;

    private static final double NOISE_2_MULTIPLICATOR = 1.0483579;

    private static final double ROUNDING_HALF = 0.5;

    private static final int RANDOM_MULTIPLICATOR = 500;

    private static final double DEFAULT_QUANT_LEVEL = 4.;

    private static final double MAX_INT_AS_DOUBLE = (double) Integer.MAX_VALUE;

    private static final int LAST_RANDOM_VALUE = 1043618065;

    public enum Dither {
        NO_DITHER(-1),
        SUBTRACTIVE_DITHER_1(1),
        SUBTRACTIVE_DITHER_2(2);

        private final int value;

        private Dither(int value) {
            this.value = value;
        }

    }

    class DoubleArrayPointer {

        private final double[] array;

        private int startIndex;

        public DoubleArrayPointer(double[] arrayIn) {
            this.array = arrayIn;
        }

        public DoubleArrayPointer copy(long l) {
            DoubleArrayPointer result = new DoubleArrayPointer(this.array);
            result.startIndex = (int) l;
            return result;
        }

        public double get(int ii) {
            return this.array[ii + this.startIndex];
        }
    }

    /**
     * DO NOT CHANGE THIS; used when quantizing real numbers
     */
    private static final int N_RANDOM = 10000;

    /**
     * number of reserved values, starting with
     */
    private static final long N_RESERVED_VALUES = 10;

    /**
     * and including NULL_VALUE. These values may not be used to represent the
     * quantized and scaled floating point pixel values If lossy Hcompression is
     * used, and the array contains null values, then it is also possible for
     * the compressed values to slightly exceed the range of the actual
     * (lossless) values so we must reserve a little more space value used to
     * represent undefined pixels
     */
    private static final int NULL_VALUE = Integer.MIN_VALUE + 1;

    /**
     * value used to represent zero-valued pixels
     */
    private static final int ZERO_VALUE = Integer.MIN_VALUE + 2;

    private static float[] initRandoms() {

        /* initialize an array of random numbers */

        int ii;
        double a = RANDOM_START_VALUE;
        double m = MAX_INT_AS_DOUBLE;
        double temp, seed;

        /* allocate array for the random number sequence */
        float[] randomValue = new float[N_RANDOM];

        /*
         * We need a portable algorithm that anyone can use to generate this
         * exact same sequence of random number. The C 'rand' function is not
         * suitable because it is not available to Fortran or Java programmers.
         * Instead, use a well known simple algorithm published here:
         * "Random number generators: good ones are hard to find",
         * Communications of the ACM, Volume 31 , Issue 10 (October 1988) Pages:
         * 1192 - 1201
         */

        /* initialize the random numbers */
        seed = 1;
        for (ii = 0; ii < N_RANDOM; ii++) {
            temp = a * seed;
            seed = temp - m * (int) (temp / m);
            randomValue[ii] = (float) (seed / m);
        }

        /*
         * IMPORTANT NOTE: the 10000th seed value must have the value 1043618065
         * if the algorithm has been implemented correctly
         */

        if ((int) seed != LAST_RANDOM_VALUE) {
            throw new IllegalArgumentException("randomValue generated incorrect random number sequence");
        }
        return randomValue;
    }

    private double bScale;

    private double bZero;

    private int intMaxValue;

    private int intMinValue;

    /**
     * maximum non-null value
     */
    private double maxValue;

    /**
     * minimum non-null value
     */
    private double minValue;

    /**
     * number of good, non-null pixels?
     */
    private long ngood;

    /**
     * returned 2nd order MAD of all non-null pixels
     */
    private double noise2;

    /**
     * returned 3rd order MAD of all non-null pixels
     */
    private double noise3;

    /* returned 5th order MAD of all non-null pixels */
    private double noise5;

    private int[] intData;

    private double xminval;

    private double xmaxval;

    private double xnoise2;

    private double xnoise3;

    private double xnoise5;

    private final boolean nullcheck;

    private final double nullValue;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "intended exposure of mutable data")
    public int[] getIntData() {
        return intData;
    }

    private final float[] randomValues;

    public Quantize(boolean nullcheck, double nullValue) {
        this.randomValues = initRandoms();
        this.nullcheck = nullcheck;
        this.nullValue = nullValue;

    }

    private void swapElements(double[] array, int one, int second) {
        double value = array[one];
        array[one] = array[second];
        array[second] = value;
    }

    /**
     * arguments: long row i: tile number = row number in the binary table
     * double fdata[] i: array of image pixels to be compressed long nxpix i:
     * number of pixels in each row of fdata long nypix i: number of rows in
     * fdata nullcheck i: check for nullvalues in fdata? double in_null_value i:
     * value used to represent undefined pixels in fdata float qlevel i:
     * quantization level int dither_method i; which dithering method to use int
     * idata[] o: values of fdata after applying bzero and bscale double bscale
     * o: scale factor double bzero o: zero offset int iminval o: minimum
     * quantized value that is returned int imaxval o: maximum quantized value
     * that is returned The function value will be one if the input fdata were
     * copied to idata; in this case the parameters bscale and bzero can be used
     * to convert back to nearly the original floating point values: fdata ~=
     * idata * bscale + bzero. If the function value is zero, the data were not
     * copied to idata.
     * 
     * @param ditherSeed
     *            seed for the dithering
     * @param fdata
     *            the data to quantinize
     * @param nxpix
     *            the image width
     * @param nypix
     *            the image hight
     * @param qlevel
     *            the quantification level to use
     * @param ditherMethod
     *            the dithering method to use
     * @return true if the quantification was possible
     */
    public boolean quantize(long ditherSeed, double[] fdata, int nxpix, int nypix, float qlevel, Dither ditherMethod) {
        int i;
        int iseed = 0;
        long nx;
        // MAD 2nd, 3rd, and 5th order noise values
        double stdev;
        double delta; /* bscale, 1 in intdata = delta in fdata */
        double zeropt; /* bzero */
        int nextRandom = 0;
        long iqfactor;

        nx = (long) nxpix * (long) nypix;
        this.intData = new int[(int) nx];
        if (nx <= 1L) {
            this.bScale = 1.;
            this.bZero = 0.;
            return false;
        }
        if (qlevel >= 0.) {
            /* estimate background noise using MAD pixel differences */
            calculateNoise(fdata, nxpix, nypix);
            // special case of an image filled with Nulls
            if (nullcheck && this.ngood == 0) {
                /* set parameters to dummy values, which are not used */
                this.minValue = 0.;
                this.maxValue = 1.;
                stdev = 1;
            } else {
                // use the minimum of noise2, noise3, and noise5 as the best
                // noise value
                stdev = this.noise3;
                if (this.noise2 != 0. && this.noise2 < stdev) {
                    stdev = this.noise2;
                }
                if (this.noise5 != 0. && this.noise5 < stdev) {
                    stdev = this.noise5;
                }
            }
            if (qlevel == 0.) {
                delta = stdev / DEFAULT_QUANT_LEVEL; /* default quantization */
            } else {
                delta = stdev / qlevel;
            }
            if (delta == 0.) {
                return false; /* don't quantize */
            }
        } else {
            /* negative value represents the absolute quantization level */
            delta = -qlevel;
            /* only nned to calculate the min and max values */
            calculateNoise(fdata, nxpix, nypix);
        }
        /* check that the range of quantized levels is not > range of int */
        if ((this.maxValue - this.minValue) / delta > 2. * MAX_INT_AS_DOUBLE - N_RESERVED_VALUES) {
            return false; /* don't quantize */
        }
        if (ditherSeed > 0) {
            /* we need to dither the quantized values */
            /* initialize the index to the next random number in the list */
            iseed = (int) ((ditherSeed - 1) % N_RANDOM);
            nextRandom = (int) (this.randomValues[iseed] * RANDOM_MULTIPLICATOR);
        }
        if (this.ngood == nx) { /* don't have to check for nulls */
            /* return all positive values, if possible since some */
            /* compression algorithms either only work for positive integers, */
            /* or are more efficient. */

            if (ditherMethod == Dither.SUBTRACTIVE_DITHER_2) {
                /*
                 * shift the range to be close to the value used to represent
                 * zeros
                 */
                zeropt = this.minValue - delta * (NULL_VALUE + N_RESERVED_VALUES);
            } else if ((this.maxValue - this.minValue) / delta < MAX_INT_AS_DOUBLE - N_RESERVED_VALUES) {
                zeropt = this.minValue;
                // fudge the zero point so it is an integer multiple of delta
                // This helps to ensure the same scaling will be performed if
                // the file undergoes multiple fpack/funpack cycles
                iqfactor = (long) (zeropt / delta + ROUNDING_HALF);
                zeropt = iqfactor * delta;
            } else {
                /* center the quantized levels around zero */
                zeropt = (this.minValue + this.maxValue) / 2.;
            }
            if (ditherSeed > 0) { /* dither the values when quantizing */
                for (i = 0; i < nx; i++) {

                    if (ditherMethod == Dither.SUBTRACTIVE_DITHER_2 && fdata[i] == 0.0) {
                        intData[i] = ZERO_VALUE;
                    } else {
                        intData[i] = nint((fdata[i] - zeropt) / delta + this.randomValues[nextRandom] - ROUNDING_HALF);
                    }

                    nextRandom++;
                    if (nextRandom == N_RANDOM) {
                        iseed++;
                        nextRandom = (int) (this.randomValues[iseed] * RANDOM_MULTIPLICATOR);
                    }
                }
            } else { /* do not dither the values */
                for (i = 0; i < nx; i++) {
                    intData[i] = nint((fdata[i] - zeropt) / delta);
                }
            }
        } else {
            /* data contains null values; shift the range to be */
            /* close to the value used to represent null values */
            zeropt = this.minValue - delta * (NULL_VALUE + N_RESERVED_VALUES);
            if (ditherSeed > 0) { /* dither the values */
                for (i = 0; i < nx; i++) {
                    if (fdata[i] != nullValue) {
                        if (ditherMethod == Dither.SUBTRACTIVE_DITHER_2 && fdata[i] == 0.0) {
                            intData[i] = ZERO_VALUE;
                        } else {
                            intData[i] = nint((fdata[i] - zeropt) / delta + this.randomValues[nextRandom] - ROUNDING_HALF);
                        }
                    } else {
                        intData[i] = NULL_VALUE;
                    }

                    /* increment the random number index, regardless */
                    nextRandom++;
                    if (nextRandom == N_RANDOM) {
                        iseed++;
                        nextRandom = (int) (this.randomValues[iseed] * RANDOM_MULTIPLICATOR);
                    }
                }
            } else { /* do not dither the values */
                for (i = 0; i < nx; i++) {
                    if (fdata[i] != nullValue) {
                        intData[i] = nint((fdata[i] - zeropt) / delta);
                    } else {
                        intData[i] = NULL_VALUE;
                    }
                }
            }
        }
        /* calc min and max values */
        this.intMinValue = nint((this.minValue - zeropt) / delta);
        this.intMaxValue = nint((this.maxValue - zeropt) / delta);
        this.bScale = delta;
        this.bZero = zeropt;
        return true; /* yes, data have been quantized */
    }

    /**
     * Estimate the median and background noise in the input image using 2nd,
     * 3rd and 5th order Median Absolute Differences. The noise in the
     * background of the image is calculated using the MAD algorithms developed
     * for deriving the signal to noise ratio in spectra (see issue #42 of the
     * ST-ECF newsletter, http://www.stecf.org/documents/newsletter/) 3rd order:
     * noise = 1.482602 / sqrt(6) * median (abs(2*flux(i) - flux(i-2) -
     * flux(i+2))) The returned estimates are the median of the values that are
     * computed for each row of the image.
     * 
     * @param arrayIn
     *            2 dimensional array of image pixels
     * @param nx
     *            number of pixels in each row of the image
     * @param ny
     *            number of rows in the image
     * @param nullcheck
     *            check for null values, if true
     * @param nullvalue
     *            value of null pixels, if nullcheck is true
     * @return error status
     */
    private void calculateNoise(double[] arrayIn, int nx, int ny) {
        DoubleArrayPointer array = new DoubleArrayPointer(arrayIn);
        initializeNoise();
        if (nx < MINIMUM_PIXEL_WIDTH) {
            // treat entire array as an image with a single row
            nx = nx * ny;
            ny = 1;
        }
        if (calculateNoiseShortRow(array, nx, ny)) {
            return;
        }
        DoubleArrayPointer rowpix;
        int nrows = 0, nrows2 = 0;
        long ngoodpix = 0;
        /* allocate arrays used to compute the median and noise estimates */
        double[] differences2 = new double[nx];
        double[] differences3 = new double[nx];
        double[] differences5 = new double[nx];
        double[] diffs2 = new double[ny];
        double[] diffs3 = new double[ny];
        double[] diffs5 = new double[ny];
        /* loop over each row of the image */
        for (int jj = 0; jj < ny; jj++) {
            rowpix = array.copy(jj * nx); /* point to first pixel in the row */
            int ii = 0;
            ii = findNextValidPixelWithNullCheck(nx, rowpix, ii);
            if (ii == nx) {
                break; /* hit end of row */
            }
            double v1 = getNextPixelAndCheckMinMax(rowpix, ii);
            ii = findNextValidPixelWithNullCheck(nx, rowpix, ++ii);
            if (ii == nx) {
                break; /* hit end of row */
            }
            double v2 = getNextPixelAndCheckMinMax(rowpix, ii);
            ii = findNextValidPixelWithNullCheck(nx, rowpix, ++ii);
            if (ii == nx) {
                break; /* hit end of row */
            }
            double v3 = getNextPixelAndCheckMinMax(rowpix, ii);
            ii = findNextValidPixelWithNullCheck(nx, rowpix, ++ii);
            if (ii == nx) {
                break; /* hit end of row */
            }
            double v4 = getNextPixelAndCheckMinMax(rowpix, ii);
            ii = findNextValidPixelWithNullCheck(nx, rowpix, ++ii);
            if (ii == nx) {
                break; /* hit end of row */
            }
            double v5 = getNextPixelAndCheckMinMax(rowpix, ii);
            ii = findNextValidPixelWithNullCheck(nx, rowpix, ++ii);
            if (ii == nx) {
                break; /* hit end of row */
            }
            double v6 = getNextPixelAndCheckMinMax(rowpix, ii);
            ii = findNextValidPixelWithNullCheck(nx, rowpix, ++ii);
            if (ii == nx) {
                break; /* hit end of row */
            }
            double v7 = getNextPixelAndCheckMinMax(rowpix, ii);
            ii = findNextValidPixelWithNullCheck(nx, rowpix, ++ii);
            if (ii == nx) {
                break; /* hit end of row */
            }
            double v8 = getNextPixelAndCheckMinMax(rowpix, ii);
            /* now populate the differences arrays */
            /* for the remaining pixels in the row */
            int nvals = 0;
            int nvals2 = 0;
            for (ii++; ii < nx; ii++) {
                ii = findNextValidPixelWithNullCheck(nx, rowpix, ii);
                if (ii == nx) {
                    break; /* hit end of row */
                }
                double v9 = getNextPixelAndCheckMinMax(rowpix, ii);
                /* construct array of absolute differences */
                if (!(v5 == v6 && v6 == v7)) {
                    differences2[nvals2] = Math.abs(v5 - v7);
                    nvals2++;
                }
                if (!(v3 == v4 && v4 == v5 && v5 == v6 && v6 == v7)) {
                    differences3[nvals] = Math.abs(2 * v5 - v3 - v7);
                    differences5[nvals] = Math.abs(N6 * v5 - N4 * v3 - N4 * v7 + v1 + v9);
                    nvals++;
                } else {
                    /* ignore constant background regions */
                    ngoodpix++;
                }
                /* shift over 1 pixel */
                v1 = v2;
                v2 = v3;
                v3 = v4;
                v4 = v5;
                v5 = v6;
                v6 = v7;
                v7 = v8;
                v8 = v9;
            } /* end of loop over pixels in the row */
            // compute the median diffs
            // Note that there are 8 more pixel values than there are diffs
            // values.
            ngoodpix += nvals + N8;
            if (nvals == 0) {
                continue; /* cannot compute medians on this row */
            } else if (nvals == 1) {
                if (nvals2 == 1) {
                    diffs2[nrows2] = differences2[0];
                    nrows2++;
                }
                diffs3[nrows] = differences3[0];
                diffs5[nrows] = differences5[0];
            } else {
                /* quick_select returns the median MUCH faster than using qsort */
                if (nvals2 > 1) {
                    diffs2[nrows2] = quickSelect(differences2, nvals);
                    nrows2++;
                }
                diffs3[nrows] = quickSelect(differences3, nvals);
                diffs5[nrows] = quickSelect(differences5, nvals);
            }
            nrows++;
        } /* end of loop over rows */
        /* compute median of the values for each row */
        if (nrows == 0) {
            xnoise3 = 0;
            xnoise5 = 0;
        } else if (nrows == 1) {
            xnoise3 = diffs3[0];
            xnoise5 = diffs5[0];
        } else {
            Arrays.sort(diffs3, 0, nrows);
            Arrays.sort(diffs5, 0, nrows);
            xnoise3 = (diffs3[(nrows - 1) / 2] + diffs3[nrows / 2]) / 2.;
            xnoise5 = (diffs5[(nrows - 1) / 2] + diffs5[nrows / 2]) / 2.;
        }
        if (nrows2 == 0) {
            xnoise2 = 0;
        } else if (nrows2 == 1) {
            xnoise2 = diffs2[0];
        } else {
            Arrays.sort(diffs2, 0, nrows2);
            xnoise2 = (diffs2[(nrows2 - 1) / 2] + diffs2[nrows2 / 2]) / 2.;
        }
        setNoiseResult(ngoodpix);
    }

    private boolean calculateNoiseShortRow(DoubleArrayPointer array, int nx, int ny) {
        /* rows must have at least 9 pixels */
        if (nx < MINIMUM_PIXEL_WIDTH) {
            int ngoodpix = 0;
            for (int index = 0; index < nx; index++) {
                if (nullcheck && array.get(index) == nullValue) {
                    continue;
                } else {
                    if (array.get(index) < xminval) {
                        xminval = array.get(index);
                    }
                    if (array.get(index) > xmaxval) {
                        xmaxval = array.get(index);
                    }
                    ngoodpix++;
                }
            }
            setNoiseResult(ngoodpix);
            return true;
        }
        return false;
    }

    private void setNoiseResult(long ngoodpix) {
        this.minValue = xminval;
        this.maxValue = xmaxval;
        this.ngood = ngoodpix;
        this.noise2 = NOISE_2_MULTIPLICATOR * xnoise2;
        this.noise3 = NOISE_3_MULTIPLICATOR * xnoise3;
        this.noise5 = NOISE_5_MULTIPLICATOR * xnoise5;
    }

    private void initializeNoise() {
        xnoise2 = 0;
        xnoise3 = 0;
        xnoise5 = 0;
        xminval = Double.MAX_VALUE;
        xmaxval = Double.MIN_VALUE;
    }

    private double getNextPixelAndCheckMinMax(DoubleArrayPointer rowpix, int ii) {
        double pixelValue = rowpix.get(ii); /* store the good pixel value */
        if (pixelValue < xminval) {
            xminval = pixelValue;
        }
        if (pixelValue > xmaxval) {
            xmaxval = pixelValue;
        }
        return pixelValue;
    }

    /**
     * find the 4nd valid pixel in row (to be skipped)
     * 
     * @param nx
     * @param nullcheck
     * @param nullvalue
     * @param rowpix
     * @param ii
     * @return
     */
    private int findNextValidPixelWithNullCheck(int nx, DoubleArrayPointer rowpix, int ii) {
        if (nullcheck) {
            while (ii < nx && rowpix.get(ii) == nullValue) {
                ii++;
            }
        }
        return ii;
    }

    public double getBScale() {
        return this.bScale;
    }

    public double getBZero() {
        return this.bZero;
    }

    public int getIntMaxValue() {
        return this.intMaxValue;
    }

    public int getIntMinValue() {
        return this.intMinValue;
    }

    protected double getNoise2() {
        return this.noise2;
    }

    protected double getNoise3() {
        return this.noise3;
    }

    protected double getNoise5() {
        return this.noise5;
    }

    private int nint(double x) {
        return x >= 0. ? (int) (x + ROUNDING_HALF) : (int) (x - ROUNDING_HALF);
    }

    private double quickSelect(double[] arr, int n) {
        int low, high;
        int median;
        int middle, ll, hh;

        low = 0;
        high = n - 1;
        median = (low + high) >>> 1; // was (low + high) / 2;
        for (;;) {
            if (high <= low) {
                return arr[median];
            }

            if (high == low + 1) { /* Two elements only */
                if (arr[low] > arr[high]) {
                    swapElements(arr, low, high);
                }
                return arr[median];
            }

            /* Find median of low, middle and high items; swap into position low */
            middle = (low + high) >>> 1; // was (low + high) / 2;
            if (arr[middle] > arr[high]) {
                swapElements(arr, middle, high);
            }
            if (arr[low] > arr[high]) {
                swapElements(arr, low, high);
            }
            if (arr[middle] > arr[low]) {
                swapElements(arr, middle, low);
            }

            /* Swap low item (now in position middle) into position (low+1) */
            swapElements(arr, middle, low + 1);

            /* Nibble from each end towards middle, swapping items when stuck */
            ll = low + 1;
            hh = high;
            for (;;) {
                do {
                    ll++;
                } while (arr[low] > arr[ll]);
                do {
                    hh--;
                } while (arr[hh] > arr[low]);

                if (hh < ll) {
                    break;
                }

                swapElements(arr, ll, hh);
            }

            /* Swap middle item (in position low) back into correct position */
            swapElements(arr, low, hh);

            /* Re-set active partition */
            if (hh <= median) {
                low = ll;
            }
            if (hh >= median) {
                high = hh - 1;
            }
        }
    }

}