package panamage.jxl.imagej;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.LUT;

/**
 * The ImageJ metadata of a JPEG XL file that is not part of the image data:
 * the dimensions of the hyperstack, the calibration, the lookup table and
 * display range of every channel, the display mode, the slice labels and the
 * info text. It is stored as JSON in a Brotli-compressed box of type
 * {@value #BOX_TYPE}:
 * <pre>
 * {"version":1, "channels":2, "slices":10, "frames":5, "rgb":false,
 *  "x":{"scale":0.2, "origin":0, "unit":"micron"}, "y":{...}, "z":{...},
 *  "t":{"interval":30, "unit":"sec"},
 *  "value":{"function":1, "coefficients":[-32768, 1], "unit":"Gray Value"},
 *  "channelDisplays":[{"lut":"&lt;768 bytes in hex: red, green, blue&gt;", "min":0, "max":4095}, ...],
 *  "mode":"composite", "labels":["DAPI", null, ...], "info":"..."}
 * </pre>
 * The frames of the file hold one slice and time point each, the slices of a
 * time point one after the other. Every field except the dimensions is
 * optional; unknown fields are ignored.
 *
 * @param channels        the number of channels (C) of the ImageJ image, 1
 *                        for an RGB color image
 * @param slices          the number of slices (Z)
 * @param frames          the number of time points (T)
 * @param rgb             whether the image is an ImageJ RGB color image
 * @param x               the calibration of the X axis, or {@code null}
 * @param y               the calibration of the Y axis, or {@code null}
 * @param z               the calibration of the Z axis, or {@code null}
 * @param frameInterval   the time between frames, or 0 if unknown
 * @param timeUnit        the unit of the frame interval, or {@code null}
 * @param value           the calibration of the sample values, or
 *                        {@code null}
 * @param channelDisplays the lookup table and display range of every channel,
 *                        or an empty list
 * @param mode            the display mode of a composite image
 *                        ({@code composite}, {@code color} or
 *                        {@code grayscale}), or {@code null}
 * @param labels          the label of every plane in the order of the ImageJ
 *                        stack, {@code null} for none, or an empty list
 * @param info            the info text, or {@code null}
 */
record StackMetadata(int channels, int slices, int frames, boolean rgb, Axis x, Axis y, Axis z, double frameInterval,
        String timeUnit, ValueCalibration value, List<ChannelDisplay> channelDisplays, String mode,
        List<String> labels, String info) {

    /** The type of the box with the metadata. */
    static final String BOX_TYPE = "ijmd";

    /** The version of the JSON format. */
    static final int VERSION = 1;

    private static final int LUT_SIZE = 256;
    private static final List<String> MODES = List.of("composite", "color", "grayscale");

    /**
     * The calibration of a spatial axis, as in ImageJ.
     *
     * @param scale  the size of a pixel
     * @param origin the origin, in pixels
     * @param unit   the unit
     */
    record Axis(double scale, double origin, String unit) {

        Axis {
            Objects.requireNonNull(unit, "unit");
        }
    }

    /**
     * The calibration of the sample values, as in ImageJ.
     *
     * @param function     the calibration function, for example
     *                     {@link Calibration#STRAIGHT_LINE}
     * @param coefficients the coefficients of the function
     * @param unit         the unit of the calibrated values
     */
    record ValueCalibration(int function, double[] coefficients, String unit) {

        ValueCalibration {
            Objects.requireNonNull(coefficients, "coefficients");
            Objects.requireNonNull(unit, "unit");
        }
    }

    /**
     * The lookup table and the display range of a channel.
     *
     * @param lut the lookup table, 256 reds, 256 greens and 256 blues
     * @param min the lowest sample of the display range
     * @param max the highest sample of the display range
     */
    record ChannelDisplay(byte[] lut, double min, double max) {

        ChannelDisplay {
            if (lut.length != 3 * LUT_SIZE) {
                throw new IllegalArgumentException("A lookup table has " + 3 * LUT_SIZE + " bytes: " + lut.length);
            }
        }

        LUT toLut() {
            LUT result = new LUT(Arrays.copyOfRange(lut, 0, LUT_SIZE), Arrays.copyOfRange(lut, LUT_SIZE, 2 * LUT_SIZE),
                    Arrays.copyOfRange(lut, 2 * LUT_SIZE, 3 * LUT_SIZE));
            result.min = min;
            result.max = max;
            return result;
        }

        static ChannelDisplay of(LUT lut, double min, double max) {
            if (lut == null || lut.getMapSize() != LUT_SIZE) {
                return null;
            }
            byte[] bytes = new byte[3 * LUT_SIZE];
            byte[] part = new byte[LUT_SIZE];
            lut.getReds(part);
            System.arraycopy(part, 0, bytes, 0, LUT_SIZE);
            lut.getGreens(part);
            System.arraycopy(part, 0, bytes, LUT_SIZE, LUT_SIZE);
            lut.getBlues(part);
            System.arraycopy(part, 0, bytes, 2 * LUT_SIZE, LUT_SIZE);
            return new ChannelDisplay(bytes, min, max);
        }
    }

    StackMetadata {
        if (channels < 1 || slices < 1 || frames < 1) {
            throw new IllegalArgumentException("Invalid dimensions: " + channels + " x " + slices + " x " + frames);
        }
        channelDisplays = List.copyOf(channelDisplays);
        if (!channelDisplays.isEmpty() && channelDisplays.size() != channels) {
            throw new IllegalArgumentException("Expected " + channels + " channel displays: " + channelDisplays.size());
        }
        if (mode != null && !MODES.contains(mode)) {
            throw new IllegalArgumentException("Unknown mode: " + mode);
        }
        labels = labels.isEmpty() ? List.of() : Collections.unmodifiableList(new ArrayList<>(labels));
        if (!labels.isEmpty() && labels.size() != channels * slices * frames) {
            throw new IllegalArgumentException("Expected " + channels * slices * frames + " labels: " + labels.size());
        }
    }

    /**
     * Returns metadata with only the dimensions.
     *
     * @param channels the number of channels
     * @param slices   the number of slices
     * @param frames   the number of time points
     * @param rgb      whether the image is an ImageJ RGB color image
     * @return the metadata
     */
    static StackMetadata of(int channels, int slices, int frames, boolean rgb) {
        return new StackMetadata(channels, slices, frames, rgb, null, null, null, 0, null, null, List.of(), null,
                List.of(), null);
    }

    /**
     * Returns metadata with the given time between frames.
     *
     * @param frameInterval the time between frames
     * @param timeUnit      the unit
     * @return the metadata
     */
    StackMetadata withFrameInterval(double frameInterval, String timeUnit) {
        return new StackMetadata(channels, slices, frames, rgb, x, y, z, frameInterval, timeUnit, value,
                channelDisplays, mode, labels, info);
    }

    /**
     * Collects the metadata of an ImageJ image.
     *
     * @param imp the image
     * @return the metadata
     */
    static StackMetadata of(ImagePlus imp) {
        Calibration cal = imp.getCalibration();
        boolean rgb = imp.getType() == ImagePlus.COLOR_RGB;
        ValueCalibration value = null;
        if (!rgb && cal.getFunction() != Calibration.NONE && cal.getCoefficients() != null) {
            value = new ValueCalibration(cal.getFunction(), cal.getCoefficients().clone(), cal.getValueUnit());
        }
        List<ChannelDisplay> displays = rgb ? List.of() : channelDisplays(imp);
        String mode = imp instanceof CompositeImage composite ? mode(composite.getMode()) : null;
        ImageStack stack = imp.getStack();
        List<String> labels = new ArrayList<>();
        boolean anyLabel = false;
        for (int n = 1; n <= stack.getSize(); n++) {
            String label = stack.getSliceLabel(n);
            labels.add(label);
            anyLabel |= label != null;
        }
        return new StackMetadata(imp.getNChannels(), imp.getNSlices(), imp.getNFrames(), rgb,
                new Axis(cal.pixelWidth, cal.xOrigin, cal.getXUnit()),
                new Axis(cal.pixelHeight, cal.yOrigin, cal.getYUnit()),
                new Axis(cal.pixelDepth, cal.zOrigin, cal.getZUnit()), cal.frameInterval, cal.getTimeUnit(), value,
                displays, mode, anyLabel ? labels : List.of(), imp.getInfoProperty());
    }

    private static String mode(int mode) {
        int index = mode - IJ.COMPOSITE;
        return index >= 0 && index < MODES.size() ? MODES.get(index) : null;
    }

    private static List<ChannelDisplay> channelDisplays(ImagePlus imp) {
        List<ChannelDisplay> displays = new ArrayList<>();
        if (imp instanceof CompositeImage composite) {
            for (int c = 1; c <= composite.getNChannels(); c++) {
                LUT lut = composite.getChannelLut(c);
                ChannelDisplay display = ChannelDisplay.of(lut, lut.min, lut.max);
                if (display == null) {
                    return List.of();
                }
                displays.add(display);
            }
            return displays;
        }
        if (imp.getNChannels() != 1) {
            return List.of();
        }
        ChannelDisplay display = ChannelDisplay.of(imp.getProcessor().getLut(), imp.getDisplayRangeMin(),
                imp.getDisplayRangeMax());
        return display == null ? List.of() : List.of(display);
    }

    /**
     * Applies the calibration, lookup tables, display ranges, display mode,
     * slice labels and info text to an ImageJ image with the dimensions of
     * this metadata.
     *
     * @param imp the image
     */
    void applyTo(ImagePlus imp) {
        Calibration cal = imp.getCalibration();
        if (x != null) {
            cal.pixelWidth = x.scale();
            cal.xOrigin = x.origin();
            cal.setXUnit(x.unit());
        }
        if (y != null) {
            cal.pixelHeight = y.scale();
            cal.yOrigin = y.origin();
            cal.setYUnit(y.unit());
        }
        if (z != null) {
            cal.pixelDepth = z.scale();
            cal.zOrigin = z.origin();
            cal.setZUnit(z.unit());
        }
        if (frameInterval > 0) {
            cal.frameInterval = frameInterval;
        }
        if (timeUnit != null) {
            cal.setTimeUnit(timeUnit);
        }
        if (value != null && imp.getType() != ImagePlus.COLOR_RGB) {
            cal.setFunction(value.function(), value.coefficients().clone(), value.unit());
        }
        if (imp instanceof CompositeImage composite) {
            if (channelDisplays.size() == composite.getNChannels()) {
                for (int c = 1; c <= channelDisplays.size(); c++) {
                    composite.setChannelLut(channelDisplays.get(c - 1).toLut(), c);
                }
            }
            if (mode != null) {
                composite.setMode(IJ.COMPOSITE + MODES.indexOf(mode));
            }
        } else if (channelDisplays.size() == 1 && imp.getType() != ImagePlus.COLOR_RGB) {
            ChannelDisplay display = channelDisplays.get(0);
            imp.setLut(display.toLut());
            imp.setDisplayRange(display.min(), display.max());
        }
        ImageStack stack = imp.getStack();
        if (labels.size() == stack.getSize()) {
            for (int n = 1; n <= labels.size(); n++) {
                stack.setSliceLabel(labels.get(n - 1), n);
            }
            if (stack.getSize() == 1 && labels.get(0) != null) {
                imp.setProperty("Label", labels.get(0));
            }
        }
        if (info != null) {
            imp.setProperty("Info", info);
        }
    }

    /**
     * Returns the metadata as UTF-8 encoded JSON.
     *
     * @return the content of the box
     */
    byte[] toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("version", VERSION);
        json.put("channels", channels);
        json.put("slices", slices);
        json.put("frames", frames);
        json.put("rgb", rgb);
        putAxis(json, "x", x);
        putAxis(json, "y", y);
        putAxis(json, "z", z);
        if (frameInterval > 0 || timeUnit != null) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("interval", frameInterval);
            if (timeUnit != null) {
                t.put("unit", timeUnit);
            }
            json.put("t", t);
        }
        if (value != null) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("function", value.function());
            List<Object> coefficients = new ArrayList<>();
            for (double coefficient : value.coefficients()) {
                coefficients.add(coefficient);
            }
            v.put("coefficients", coefficients);
            v.put("unit", value.unit());
            json.put("value", v);
        }
        if (!channelDisplays.isEmpty()) {
            List<Object> displays = new ArrayList<>();
            for (ChannelDisplay display : channelDisplays) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("lut", HexFormat.of().formatHex(display.lut()));
                d.put("min", display.min());
                d.put("max", display.max());
                displays.add(d);
            }
            json.put("channelDisplays", displays);
        }
        if (mode != null) {
            json.put("mode", mode);
        }
        if (!labels.isEmpty()) {
            json.put("labels", labels);
        }
        if (info != null) {
            json.put("info", info);
        }
        return Json.write(json).getBytes(StandardCharsets.UTF_8);
    }

    private static void putAxis(Map<String, Object> json, String name, Axis axis) {
        if (axis != null) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("scale", axis.scale());
            a.put("origin", axis.origin());
            a.put("unit", axis.unit());
            json.put(name, a);
        }
    }

    /**
     * Reads metadata from the content of a box.
     *
     * @param content the UTF-8 encoded JSON
     * @return the metadata
     * @throws IllegalArgumentException if the content is not valid metadata
     */
    static StackMetadata fromJson(byte[] content) {
        Map<?, ?> json = asMap(Json.parse(new String(content, StandardCharsets.UTF_8)), "metadata");
        List<ChannelDisplay> displays = new ArrayList<>();
        List<?> jsonDisplays = (List<?>) optional(json, "channelDisplays", List.class);
        if (jsonDisplays != null) {
            for (Object element : jsonDisplays) {
                Map<?, ?> d = asMap(element, "channel display");
                displays.add(new ChannelDisplay(parseHex(require(d, "lut", String.class)),
                        require(d, "min", Number.class).doubleValue(), require(d, "max", Number.class).doubleValue()));
            }
        }
        ValueCalibration value = null;
        Map<?, ?> v = (Map<?, ?>) optional(json, "value", Map.class);
        if (v != null) {
            List<?> list = require(v, "coefficients", List.class);
            double[] coefficients = new double[list.size()];
            for (int i = 0; i < coefficients.length; i++) {
                coefficients[i] = asNumber(list.get(i), "coefficient");
            }
            value = new ValueCalibration(integer(require(v, "function", Number.class)), coefficients,
                    require(v, "unit", String.class));
        }
        Map<?, ?> t = (Map<?, ?>) optional(json, "t", Map.class);
        double frameInterval = 0;
        String timeUnit = null;
        if (t != null) {
            Number interval = (Number) optional(t, "interval", Number.class);
            frameInterval = interval == null ? 0 : interval.doubleValue();
            timeUnit = (String) optional(t, "unit", String.class);
        }
        List<String> labels = new ArrayList<>();
        List<?> jsonLabels = (List<?>) optional(json, "labels", List.class);
        if (jsonLabels != null) {
            for (Object label : jsonLabels) {
                if (label != null && !(label instanceof String)) {
                    throw new IllegalArgumentException("A label is not a string: " + label);
                }
                labels.add((String) label);
            }
        }
        Boolean rgb = (Boolean) optional(json, "rgb", Boolean.class);
        return new StackMetadata(integer(require(json, "channels", Number.class)),
                integer(require(json, "slices", Number.class)), integer(require(json, "frames", Number.class)),
                rgb != null && rgb, axis(json, "x"), axis(json, "y"), axis(json, "z"), frameInterval, timeUnit, value,
                displays, (String) optional(json, "mode", String.class), labels,
                (String) optional(json, "info", String.class));
    }

    private static Axis axis(Map<?, ?> json, String name) {
        Map<?, ?> a = (Map<?, ?>) optional(json, name, Map.class);
        if (a == null) {
            return null;
        }
        return new Axis(require(a, "scale", Number.class).doubleValue(),
                require(a, "origin", Number.class).doubleValue(), require(a, "unit", String.class));
    }

    private static byte[] parseHex(String hex) {
        try {
            return HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid lookup table", e);
        }
    }

    private static int integer(Number number) {
        double d = number.doubleValue();
        if (d != Math.rint(d) || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Not an integer: " + number);
        }
        return (int) d;
    }

    private static double asNumber(Object value, String what) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("The " + what + " is not a number: " + value);
        }
        return number.doubleValue();
    }

    private static Map<?, ?> asMap(Object value, String what) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("The " + what + " is not a JSON object");
        }
        return map;
    }

    private static <T> T require(Map<?, ?> json, String key, Class<T> type) {
        Object value = optional(json, key, type);
        if (value == null) {
            throw new IllegalArgumentException("Missing field: " + key);
        }
        return type.cast(value);
    }

    private static Object optional(Map<?, ?> json, String key, Class<?> type) {
        Object value = json.get(key);
        if (value != null && !type.isInstance(value)) {
            throw new IllegalArgumentException("The field " + key + " is not a " + type.getSimpleName());
        }
        return value;
    }
}
