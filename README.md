[![Maven Central](https://img.shields.io/maven-central/v/net.sourceforge.streamsupport/panamage-jxl.svg)](https://central.sonatype.com/artifact/net.sourceforge.streamsupport/panamage-jxl)
[![javadoc.io](https://javadoc.io/badge2/net.sourceforge.streamsupport/panamage-jxl/javadoc.svg)](https://javadoc.io/doc/net.sourceforge.streamsupport/panamage-jxl)
[![license](https://img.shields.io/badge/license-BSD--3--Clause-blue.svg)](LICENSE)

# panamage

JPEG XL for Java, based on [libjxl](https://github.com/libjxl/libjxl) and the
Foreign Function and Memory API ([Project Panama](https://openjdk.org/projects/panama/)).

panamage decodes and encodes JPEG XL images, including animations and images
with any number of channels, converts JPEG files losslessly to JPEG XL and
back, reads and writes EXIF, XMP and other metadata, and plugs into
`javax.imageio`, so existing code can use `ImageIO.read` and
`ImageIO.write(image, "jxl", ...)` without changes. The native libraries are
bundled; nothing needs to be installed.

> **Status:** 0.3.0. The API may still change before 1.0.

## Features

- **Decode** JPEG XL (codestream and container) to gray, gray+alpha, RGB or
  RGBA with 8-bit, 16-bit or floating point samples, upright according to the
  image orientation, in the color space of the image or converted to sRGB.
  The input can be a byte array, a `MemorySegment` (for example a mapped file,
  passed to libjxl without a copy) or a file, read into native memory; files
  and segments may be larger than 2 GiB.
- **Separate channels:** images with any number of channels, such as the
  fluorescence channels of a microscope image, are read and written with every
  channel in an array of its own, with its type, name and bit depth.
- **Animations** are read and written: all frames with their duration, name
  and loop count, at once or one frame at a time; in Image I/O, every frame is
  an image, and animations are written as sequences. GIF animations are
  converted with their timing.
- **Encode** 8-bit, 16-bit and floating point images lossless or lossy, with a
  quality (0 to 100) or Butteraugli distance and an effort from 1 to 10.
  Lossless encoding reproduces every sample exactly, including HDR values.
  The output can be written to a stream or channel as it is produced.
- **Lossless JPEG transcoding:** repack an existing JPEG as JPEG XL, typically
  10 to 20 percent smaller, and restore the original JPEG bit for bit.
- **Metadata:** EXIF, XMP and application-specific boxes are read and written;
  EXIF orientation and the image orientation are kept consistent.
- **Color:** images that are not sRGB (for example wide-gamut images) keep
  their ICC profile, when reading and when writing, or are converted to sRGB
  by libjxl's color management when decoding; the Image I/O reader converts
  them by default.
- **Threads:** libjxl's native threads, as many as the image size suggests:
  small images such as thumbnails are processed on the calling thread alone.
  The number of threads can be set per call.
- **Image I/O plugin** with reader, writer, write parameters (lossy/lossless,
  quality, effort) and image metadata. 16-bit and floating point images are
  read and written with full precision. On a JVM that cannot run panamage,
  Image I/O keeps working for all other formats; only the JPEG XL plugin is
  missing.
- **Protection against decompression bombs:** images beyond 256 megapixels and
  metadata beyond 16 MiB are rejected before their memory is allocated; the
  limits are configurable.
- **Bundled native libraries** for Windows on x86_64, Linux on x86_64 and
  aarch64 (glibc and musl), and macOS on Apple silicon, found through a service
  interface on both the class path and the module path.
- **JDK 21:** `panamage-jxl-jdk21` offers the same API on JDK 21, where the
  Foreign Function and Memory API is a preview feature.
- **Tested against the reference images** of selected test cases of the
  official [JPEG XL conformance corpus](https://github.com/libjxl/conformance)
  (gray, float, alpha, orientation, animation, JPEG reconstruction).

## Requirements

- JDK 25 or newer, or JDK 21 with `panamage-jxl-jdk21` (see [JDK 21](#jdk-21))
- Windows 10 or newer on x86_64, Linux on x86_64 with glibc 2.29 or newer
  (for example Ubuntu 20.04, Debian 11, RHEL 9 or newer), Linux on aarch64
  with glibc 2.28 or newer (for example Ubuntu 20.04, Debian 10, RHEL 8,
  Amazon Linux 2023 or newer), Linux on x86_64 or aarch64 with musl 1.2.4 or
  newer (for example Alpine Linux 3.18 or newer), or macOS 11 or newer on
  Apple silicon (arm64). Nothing else needs to be installed.

## Getting started

panamage is on Maven Central. Add the Image I/O plugin (or only
`panamage-jxl` for the decoder and encoder API) and the native libraries for
each platform the application runs on:

```xml
<dependency>
    <groupId>net.sourceforge.streamsupport</groupId>
    <artifactId>panamage-jxl-imageio</artifactId>
    <version>0.3.0</version>
</dependency>
<dependency>
    <groupId>net.sourceforge.streamsupport</groupId>
    <artifactId>panamage-jxl-natives-windows-x86_64</artifactId>
    <version>0.3.0</version>
    <scope>runtime</scope>
</dependency>
```

With Gradle:

```kotlin
implementation("net.sourceforge.streamsupport:panamage-jxl-imageio:0.3.0")
runtimeOnly("net.sourceforge.streamsupport:panamage-jxl-natives-windows-x86_64:0.3.0")
```

The other native artifacts are `panamage-jxl-natives-linux-x86_64`,
`panamage-jxl-natives-linux-aarch64`, `panamage-jxl-natives-linux-musl-x86_64`,
`panamage-jxl-natives-linux-musl-aarch64` and
`panamage-jxl-natives-macos-aarch64`. Linux distributions with the musl C
library, such as Alpine Linux and the container images based on it, need the
`linux-musl` artifacts; if both a glibc and a musl artifact are present, the
one matching the system is used.

To try panamage without a build tool, download
`panamage-0.3.0-windows-x86_64.zip` or `panamage-0.3.0-<platform>.tar.gz` for
`linux-x86_64`, `linux-aarch64`, `linux-musl-x86_64`, `linux-musl-aarch64` or
`macos-aarch64` from the [Releases](../../releases) page and unpack it. It
contains the JARs, a README and a small example program:

```sh
# class path
java --enable-native-access=ALL-UNNAMED -cp "lib/*" Example.java photo.jpg

# module path
java --enable-native-access=panamage.jxl --module-path lib --add-modules panamage.jxl Example.java photo.jpg
```

`--enable-native-access` allows panamage to call libjxl; without it, the JDK
prints a warning.

For your own application, put these JARs on the class path or module path:

| JAR | Contents |
|---|---|
| `panamage-jxl` | Decoder, encoder, JPEG transcoding (`panamage.jxl`) |
| `panamage-jxl-imageio` | Image I/O plugin (`panamage.jxl.imageio`) |
| `panamage-jxl-spi` | Service interface for the native libraries |
| `panamage-jxl-natives-<platform>` (`windows-x86_64`, `linux-x86_64`, `linux-aarch64`, `linux-musl-x86_64`, `linux-musl-aarch64` or `macos-aarch64`) | libjxl for the platform |

A plugin for ImageJ and Fiji, `panamage-jxl-imagej`, is attached to the
[Releases](../../releases) page; it is not on Maven Central. A Fiji update
site will follow.

## Usage

### Image I/O

```java
BufferedImage image = ImageIO.read(new File("input.jxl"));
ImageIO.write(image, "jxl", new File("output.jxl"));   // visually lossless (distance 1.0)
```

With write parameters and metadata, for example to convert a JPEG with its
EXIF and XMP data:

```java
ImageReader jpegReader = ImageIO.getImageReadersByFormatName("jpeg").next();
jpegReader.setInput(ImageIO.createImageInputStream(new File("photo.jpg")));
IIOImage photo = jpegReader.readAll(0, null);           // pixels and metadata

ImageWriter writer = ImageIO.getImageWritersByFormatName("jxl").next();
JxlImageWriteParam param = (JxlImageWriteParam) writer.getDefaultWriteParam();
param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
param.setCompressionType(JxlImageWriteParam.LOSSY);     // or LOSSLESS
param.setCompressionQuality(0.9f);
param.setEffort(7);
try (ImageOutputStream out = ImageIO.createImageOutputStream(new File("photo.jxl"))) {
    writer.setOutput(out);
    writer.write(null, photo, param);                   // keeps EXIF and XMP
}
```

`ImageIO.read` and `ImageIO.write` do not carry metadata; use `readAll` and
`write(IIOMetadata, IIOImage, ImageWriteParam)` as above, or
`JxlImageMetadata` to set EXIF, XMP and application-specific boxes
(`setBoxes`) yourself.

The reader converts images in another color space than sRGB (such as Display
P3 or Adobe RGB) to sRGB, so they look right in code that assumes sRGB; the
image metadata keeps their ICC profile. `setConvertToSrgb(false)` keeps the
color space of the image, with its ICC profile in the color model, and so does
a destination type with that color space from `getImageTypes`:

```java
JxlImageReader reader = (JxlImageReader) ImageIO.getImageReadersByFormatName("jxl").next();
reader.setConvertToSrgb(false);
reader.setThreads(JxlThreads.none());                   // see Threads below
```

An animation has one image per frame: `getNumImages(true)` returns the number
of frames, `read(i)` returns frame `i` as it is displayed, and
`JxlImageMetadata.getFrameInfo()` of `getImageMetadata(i)` gives its duration
and name. `ImageIO.read` returns the first frame.

An animation is written as a sequence:

```java
ImageWriter writer = ImageIO.getImageWritersByFormatName("jxl").next();
try (ImageOutputStream out = ImageIO.createImageOutputStream(new File("animation.jxl"))) {
    writer.setOutput(out);
    writer.prepareWriteSequence(null);
    for (BufferedImage frame : frames) {
        writer.writeToSequence(new IIOImage(frame, null, null), null);  // 100 ms each
    }
    writer.endWriteSequence();
}
```

The duration of each frame comes from `JxlImageMetadata.setFrameInfo`, the
tick rate and the loop count from `setAnimationHeader` of the first frame;
without them, a frame is shown for 100 ms and the animation plays forever.
Metadata read from a JPEG XL animation carries these values, so `readAll` of
every frame followed by `writeToSequence` keeps the timing. The same works for
GIF animations: the writer understands the metadata of the JDK's GIF reader
(frame delays, loop count) and composes partial GIF frames as browsers show
them:

```java
ImageReader gif = ImageIO.getImageReadersByFormatName("gif").next();
gif.setInput(ImageIO.createImageInputStream(new File("animation.gif")));
ImageWriter writer = ImageIO.getImageWritersByFormatName("jxl").next();
try (ImageOutputStream out = ImageIO.createImageOutputStream(new File("animation.jxl"))) {
    writer.setOutput(out);
    writer.prepareWriteSequence(gif.getStreamMetadata());   // the size of the GIF screen
    for (int i = 0; i < gif.getNumImages(true); i++) {
        writer.writeToSequence(gif.readAll(i, null), null);
    }
    writer.endWriteSequence();
}
```

### Lossless JPEG transcoding

```java
byte[] jpeg = Files.readAllBytes(Path.of("photo.jpg"));
byte[] jxl = JxlTranscoder.fromJpeg(jpeg);              // smaller, lossless
byte[] restored = JxlTranscoder.toJpeg(jxl);            // identical to jpeg

byte[] fromFile = JxlTranscoder.fromJpeg(Path.of("photo.jpg"),
        JxlEncodeOptions.ofLossless().withEffort(9));   // only the effort and the threads apply
```

### Decoder and encoder

```java
JxlImageInfo info = JxlDecoder.readInfo(data);            // size, channels, bit depth, ICC; no pixels
JxlImage image = JxlDecoder.decode(data, info.channels(), info.sampleType());
JxlMetadata metadata = JxlDecoder.readMetadata(data);     // EXIF and XMP

switch (image) {
    case JxlImage.Uint8 img -> process(img.pixels());     // byte[]
    case JxlImage.Uint16 img -> process(img.pixels());    // short[], unsigned
    case JxlImage.Float32 img -> process(img.pixels());   // float[], nominally 0.0 to 1.0
}

byte[] lossless = JxlEncoder.encode(image, JxlEncodeOptions.ofLossless());
byte[] lossy = JxlEncoder.encode(image, JxlEncodeOptions.ofQuality(90).withEffort(9), metadata);
```

`JxlDecoder.decode(data)` returns 8-bit RGBA samples (`JxlImage.Uint8`).

Every reading method takes the image as a `byte[]`, a `MemorySegment` or a
`Path`, with the same parameters otherwise. `JxlDecodeOptions` holds the
limits (see below), the color space of the decoded pixels and the threads;
the methods without options use `JxlDecodeOptions.defaults()`, which keeps the
color space of the image:

```java
JxlImage srgb = JxlDecoder.decode(Path.of("photo.jxl"), 4, JxlSampleType.UINT8,
        JxlDecodeOptions.defaults().withSrgb(true));      // converted by libjxl
```

Besides EXIF and XMP, `JxlMetadata` carries application-specific boxes, which
are written Brotli-compressed by default:

```java
JxlMetadata withBox = metadata.withBoxes(List.of(JxlBox.of("abcd", content)));
byte[] encoded = JxlEncoder.encode(image, JxlEncodeOptions.ofLossless(), withBox);
JxlBox box = JxlDecoder.readMetadata(encoded).box("abcd");
```

`JxlEncoder.encode`, `JxlTranscoder.fromJpeg` and `JxlTranscoder.toJpeg` can
also write to an `OutputStream` or a `WritableByteChannel` as the output is
produced, without holding it in memory. They return the number of bytes
written and neither flush nor close the target:

```java
try (OutputStream out = Files.newOutputStream(Path.of("output.jxl"))) {
    JxlEncoder.encode(image, JxlEncodeOptions.ofQuality(90), metadata, out);
}
```

### Separate channels

`JxlChannels` holds every channel in an array of its own: the color channels
(gray or RGB) and any number of extra channels with a type and a name, such
as alpha, a depth map or the fluorescence channels of a microscope image:

```java
JxlChannels image = JxlChannels.builder(width, height)
        .gray(dapi)                                       // short[] each
        .add("GFP", gfp)
        .add("mCherry", mcherry)
        .bitsPerSample(12)
        .build();
byte[] jxl = JxlEncoder.encode(image, JxlEncodeOptions.ofLossless());

List<JxlExtraChannelInfo> extra = JxlDecoder.readExtraChannels(jxl);  // types, names, bit depths
JxlChannels decoded = JxlDecoder.decodeChannels(jxl, JxlSampleType.UINT16);
```

Integer samples keep their values when the requested type can hold them: a
12-bit channel decoded to `UINT16` has values from 0 to 4095. Images with more
than 4 extra channels need codestream level 10, which some decoders may not
support. `JxlFrameEncoder` and `JxlFrameDecoder.openChannels` handle
animations with separate channels.

### Animations

`decode` returns the first frame of an animation. All frames, as they are
displayed and each as large as the image:

```java
JxlAnimationInfo animation = JxlDecoder.readAnimationInfo(data);  // frames, durations, loops; no pixels
List<JxlFrame> frames = JxlDecoder.decodeFrames(data, 4, JxlSampleType.UINT8);

try (JxlFrameDecoder decoder = JxlFrameDecoder.open(data, 4, JxlSampleType.UINT8)) {
    for (JxlFrame frame = decoder.next(); frame != null; frame = decoder.next()) {
        show(frame.image(), frame.info().durationMillis());       // one frame in memory at a time
    }
}
```

`JxlFrameEncoder` writes an animation one frame at a time, and
`JxlEncoder.encodeAnimation` encodes a list of frames, for example the decoded
frames above:

```java
try (OutputStream out = Files.newOutputStream(Path.of("animation.jxl"));
        JxlFrameEncoder encoder = JxlFrameEncoder.open(out, JxlAnimationHeader.millis(0),  // ms, loop forever
                JxlEncodeOptions.ofLossless(), JxlMetadata.NONE)) {
    for (JxlImage image : images) {
        encoder.add(image, 100);                                  // shown for 100 ms
    }
    encoder.finish();                                             // without it, the file is incomplete
}

byte[] copy = JxlEncoder.encodeAnimation(frames, animation.header(),        // the tick rate and loops
        JxlEncodeOptions.ofLossless(), JxlMetadata.NONE);
```

Frame durations are given in ticks of the `JxlAnimationHeader`. Every frame
covers the whole image and must match the first frame in size, channels,
sample type and ICC profile; only the last frame may have a duration of 0.

### Limits for untrusted input

A JPEG XL file of a few hundred bytes can declare an image of a billion
pixels. libjxl allocates the pixels in native memory, which `-Xmx` does not
bound, so panamage checks the size first and throws a `JxlLimitException`
(a `JxlException`; an `IIOException` caused by it in Image I/O):

- **Pixels:** by default at most 2^28 (256 megapixels, for example
  16384 x 16384), for the image and for every layer of the decoded frames (a
  layer may be larger than the image). Images with more than 4 channels,
  including extra channels, count once for every 4 channels started.
  `decodeFrames` holds all frames of an animation at once, so there the limit
  applies to all frames together; `JxlFrameDecoder` applies it to each frame.
- **Metadata:** by default at most 16 MiB for each EXIF or XMP box after
  decompression, and for all application-specific boxes together.
- **JPEG reconstruction:** by default at most 1 GiB for the JPEG file that
  `JxlTranscoder.toJpeg` restores.

```java
JxlLimits limits = JxlLimits.defaults().withMaxPixels(50_000_000);
JxlImage image = JxlDecoder.decode(data, 4, JxlSampleType.UINT8,
        JxlDecodeOptions.defaults().withLimits(limits));
((JxlImageReader) reader).setLimits(limits);                    // Image I/O
```

The defaults can be changed with the system properties
`panamage.jxl.max.pixels`, `panamage.jxl.max.metadata.bytes` and
`panamage.jxl.max.jpeg.bytes`; `JxlLimits.unlimited()` turns the checks off
for trusted input.
`JxlDecoder.readInfo` is not limited and reports the size without allocating
the pixels. While an image is decoded, its pixels are held twice for a short
time, in native memory and in the Java array, so a 256-megapixel RGBA image
needs about 2 GiB with 8-bit samples.

The limits bound what libjxl reports through its API. libjxl does not report
the size of reference frames, which only serve as a source for other frames,
so a hostile file can still make libjxl allocate more than the limit. For
fully untrusted input, decoding in a separate process with a memory limit is
the strongest protection.

### Threads

libjxl processes an image in groups of 256 x 256 pixels on its own native
threads, which never call back into Java. By default (`JxlThreads.auto()`),
the number of threads follows the size of the image: an image of up to one
group, such as a thumbnail, is processed on the calling thread alone, a large
image by up to one thread per processor. For JPEG transcoding, the size is
read from the JPEG header. `JxlThreads.none()` keeps all work on the calling
thread, for example in a server that already processes many images in
parallel, and `JxlThreads.fixed(n)` uses `n` threads:

```java
JxlDecodeOptions single = JxlDecodeOptions.defaults().withThreads(JxlThreads.none());
JxlEncodeOptions four = JxlEncodeOptions.ofQuality(90).withThreads(JxlThreads.fixed(4));
```

In Image I/O, `JxlImageReader.setThreads` and `JxlImageWriteParam.setThreads`
do the same. Headers and metadata are always read on the calling thread.

## Upgrading from 0.2

0.3.0 changes some of the API and behavior of 0.2:

- `JxlLimits`, `JxlDecodeOptions`, `JxlEncodeOptions` and the information
  types (`JxlImageInfo`, `JxlFrameInfo`, `JxlAnimationInfo`,
  `JxlExtraChannelInfo`) are classes instead of records, so that they can
  grow without breaking code. Options are created with `defaults()`,
  `unlimited()` and the `of` methods and changed with the `with` methods; the
  accessors are unchanged, record patterns no longer work.
- The overloads with a `JxlLimits` parameter are gone: pass
  `JxlDecodeOptions.defaults().withLimits(limits)` instead. The same goes for
  `JxlTranscoder.toJpeg`, and `JxlTranscoder.fromJpeg` takes
  `JxlEncodeOptions` instead of an effort.
- `JxlDecoder.decode(data, channels)` is gone: use
  `decode(data, channels, JxlSampleType.UINT8)`.
- The Image I/O reader converts images in another color space than sRGB to
  sRGB; `setConvertToSrgb(false)` restores the behavior of 0.2.
- Small images are decoded and encoded without starting threads (see
  [Threads](#threads)), which makes them two to three times faster.

## JDK 21

On JDK 21, the Foreign Function and Memory API is a preview feature with a
slightly different API. `panamage-jxl-jdk21` contains `panamage-jxl` and
`panamage-jxl-imageio` built for it, with the same API, and is used instead of
them. `panamage-jxl-spi` (a dependency) and the native artifacts are the same
as for JDK 25:

```xml
<dependency>
    <groupId>net.sourceforge.streamsupport</groupId>
    <artifactId>panamage-jxl-jdk21</artifactId>
    <version>0.3.0</version>
</dependency>
<dependency>
    <groupId>net.sourceforge.streamsupport</groupId>
    <artifactId>panamage-jxl-natives-windows-x86_64</artifactId>
    <version>0.3.0</version>
    <scope>runtime</scope>
</dependency>
```

- It runs on JDK 21 only (any update), not on JDK 22 or newer; use
  `panamage-jxl` and `panamage-jxl-imageio` there.
- The JVM needs `--enable-preview`; `--enable-native-access=ALL-UNNAMED`
  avoids the warning about native access:

  ```sh
  java --enable-preview --enable-native-access=ALL-UNNAMED -cp "lib/*" ...
  ```

- It has no module descriptor and is meant for the class path.
- Without `--enable-preview`, or on Java 8 to 24 with `panamage-jxl-imageio`,
  Image I/O keeps working for all other formats; the JPEG XL reader and
  writer are missing, and the logger `panamage.jxl.imageio.JxlFormat` reports
  why at level `FINE`.
- It is not part of the release archives; the JAR is attached to the GitHub
  release.

## Native libraries

The native libraries are loaded on first use from the first of these sources:

1. the directory named by the system property `panamage.jxl.library.path`
   (if set, no other source is tried);
2. the platform JAR (`panamage-jxl-natives-<platform>`) on the class path or
   module path; its libraries are extracted to the directory named by
   `panamage.jxl.cache.dir`, by default `panamage-jxl-<user>` in
   `java.io.tmpdir`;
3. libjxl installed on the system.

On Linux and macOS, the default cache directory must belong to the current
user and must not be writable by others, so that no other user can replace a
library before it is loaded; otherwise a new private temporary directory is
used instead. A directory set with `panamage.jxl.cache.dir` is used as it is
and should be private to the user, too.

The loaded library must have the libjxl version the bindings were generated
for (0.12.x). `JxlNative.librarySource()` tells where the library came from.
The conversion to sRGB needs `libjxl_cms`, which all bundled libraries
include; with a system libjxl without it, decoded pixels keep their color
space.

libjxl publishes no binaries for macOS, Linux on aarch64 and musl-based Linux,
and its DLLs for Windows need a newer Microsoft Visual C++ runtime than some
JDKs ship. The libraries for these platforms are therefore built from the
libjxl sources by this project (see [natives/README.md](natives/README.md));
the DLLs for Windows contain the C and C++ runtime and need nothing else.

## Building from source

Prerequisites:

- JDK 25, registered in `~/.m2/toolchains.xml` (the build uses the Maven
  toolchains plugin, so Maven itself may run on another JDK):

  ```xml
  <toolchains>
    <toolchain>
      <type>jdk</type>
      <provides><version>25</version></provides>
      <configuration><jdkHome>/path/to/jdk-25</jdkHome></configuration>
    </toolchain>
  </toolchains>
  ```

- Python 3.12 or newer for the helper scripts (standard library only;
  `make_test_images.py` also needs Pillow).
- 7-Zip (`7z`, or `7zz` on macOS, on the `PATH`) to unpack the libjxl release
  for Windows; not needed with `fetch_tools.py --build-only`.

Download the external tools (libjxl for Linux x86_64, the libjxl release for
Windows with the headers and `cjxl`, jextract, a Linux JDK for tests in WSL,
and about 45 MB of conformance test cases) and build:

```sh
python scripts/fetch_tools.py
./mvnw verify            # mvnw.cmd on Windows
```

`python scripts/fetch_tools.py --no-conformance` leaves out the conformance
test cases; the conformance tests are then skipped. `--build-only` leaves out
jextract, the libjxl release for Windows and the Linux JDK, which only the
helper scripts need. The libraries for Windows, macOS, Linux aarch64 and
musl-based Linux are checked in under `natives/`.

`panamage-jxl-jdk21` and the ImageJ/Fiji plugin `panamage-jxl-imagej` are
built only with the profile `jdk21` (`./mvnw -Pjdk21 verify`); the plugin
needs the SciJava Maven repository, which its POM names. It needs a JDK 21 in `~/.m2/toolchains.xml` as
well, with `<version>21</version>`, and Python: its sources are generated
from those of `panamage-jxl` and `panamage-jxl-imageio` by
`scripts/make_jdk21_variant.py`, run with `python` from the `PATH` or with
`-Dpython.executable=...`.

With `-Djdk8.home=...` and `-Djdk21.home=...`, the build also checks that the
Image I/O plugin stays out of the way on a JDK 8 and on a JDK 21 without
`--enable-preview`; without them, these tests are skipped.

The tools go to `.tools` in the project. To keep them elsewhere, set the
environment variable `PANAMAGE_TOOLS_DIR` or pass `-Dtools.dir=...` to Maven
and `--tools ...` to the scripts.

Other scripts in `scripts/`:

| Script | Purpose |
|---|---|
| `jextract_jxl.py` | Regenerate the checked-in jextract bindings (`--update-symbols` also refreshes the symbol list) |
| `make_test_images.py` | Regenerate the test images |
| `wsl_verify.py` | Build and run all tests on Linux in WSL (from Windows) |
| `make_release.py` | Build the release files in `dist/<version>` (`--wsl` also tests on Linux, `--central` writes the signed bundle for Maven Central) |
| `build_libjxl_macos.py` | Build the libjxl libraries for macOS arm64 from source (on macOS; used by the workflow below) |
| `build_libjxl_linux_aarch64.py` | Build the libjxl libraries for Linux aarch64 from source (in the manylinux_2_28 container; used by the workflow below) |
| `build_libjxl_linux_musl.py` | Build the libjxl libraries for musl-based Linux on x86_64 and aarch64 from source (in an Alpine 3.18 container; used by the workflow below) |
| `build_libjxl_windows.py` | Build the libjxl DLLs for Windows x86_64 from source with the static runtime (Visual Studio with clang-cl; used by the workflow below) |
| `make_jdk21_variant.py` | Generate the sources of `panamage-jxl-jdk21` (`--generate`, run by Maven) and regenerate its checked-in jextract 21 bindings (`--update-bindings`) |
| `write_toolchains.py` | Write a Maven toolchains file for a JDK 25 and, with `--jdk21`, a JDK 21 |
| `make_update_site.py` | Build the Fiji update site from the release files and upload it (`--site NAME --webdav-user USER`, or `--local-site DIR` for a test) |

Every build also creates sources and Javadoc JARs. The JARs are reproducible:
`project.build.outputTimestamp` fixes the time stamps, so the same sources
give byte-identical JARs.

### Releasing

Releases are built and uploaded by hand:

1. Set the release version in all POMs and `project.build.outputTimestamp` to
   the release date, and update the status line and "Getting started" in this
   README.
2. Build, test and sign:

   ```sh
   python scripts/make_release.py --wsl --central --gpg <path of gpg> --gpg-key <fingerprint>
   ```

   This writes the files for GitHub to `dist/<version>` and the bundle for
   Maven Central to `dist/<version>/central`. The environment variables
   `PANAMAGE_GPG` (the gpg executable) and `PANAMAGE_GPG_KEY` (fingerprint or
   key ID) can replace `--gpg` and `--gpg-key`; without a key, gpg signs with
   its default key. gpg may ask for the passphrase. `--dry-run` tries this out
   with a snapshot version in `target/release-dry-run`. The release includes
   `panamage-jxl-jdk21`, so `~/.m2/toolchains.xml` needs a JDK 21, too.
3. Upload `panamage-<version>-central.zip` in the
   [Central Portal](https://central.sonatype.com/publishing) (Publish
   Component), wait for the validation and publish it.
4. Tag the release commit and create the GitHub release with the other files
   of `dist/<version>`, including `SHA256SUMS`, `panamage-jxl-jdk21` and the
   ImageJ/Fiji plugin `panamage-jxl-imagej`.
5. Update the Fiji update site with `make_update_site.py`.
6. Set the next snapshot version.

## Continuous integration

Five GitHub Actions workflows, all started manually (Actions, Run workflow):

- **Build** builds the project and runs all tests on Linux x86_64, Linux
  aarch64, macOS arm64 and Windows x86_64, each with its own native libraries
  and including `panamage-jxl-jdk21` on JDK 21, and on Alpine Linux on x86_64
  and aarch64 in a container; the platforms can be chosen when starting it.
  The hosted jobs also check the Image I/O plugin on JDK 21 without
  `--enable-preview`, and the Linux x86_64 job on JDK 8.
  The test reports are kept as workflow artifacts.
- **libjxl for macOS arm64** builds the libjxl libraries for macOS from source
  and checks them; its artifact is what `natives/` contains.
- **libjxl for Linux aarch64** does the same for Linux on 64-bit ARM, in the
  manylinux_2_28 container (glibc 2.28).
- **libjxl for Linux musl** does the same for musl-based Linux on x86_64 and
  aarch64, in an Alpine 3.18 container (musl 1.2.4).
- **libjxl for Windows x86_64** does the same for Windows, with the static C
  and C++ runtime.

## Limitations

- Integer samples with more than 16 bits are decoded as floating point; 16-bit
  floating point (half precision) is not supported as a sample type.
- Written animation frames always cover the whole image; layers, blending and
  cropped frames are not written.
- The input and whole decoded images are held in memory; only the output of
  the encoders and the transcoder can be written as it is produced. There is
  no progressive API yet.
- The Image I/O writer converts images without a component color model (for
  example `TYPE_INT_RGB` or indexed images) to 8-bit sRGB.
- Native libraries for macOS on Intel (x86_64) and Windows on ARM (aarch64)
  are not available yet.

## License

panamage is licensed under the [BSD-3-Clause license](LICENSE).

The native JARs contain libjxl (BSD-3-Clause), Brotli (MIT), Highway
(Apache-2.0 or BSD-3-Clause) and skcms (BSD-3-Clause). Their license texts are
included in `META-INF/licenses` of the native JARs and in the `licenses`
directory of the release archives.
