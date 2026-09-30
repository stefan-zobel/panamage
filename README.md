# panamage

JPEG XL for Java, based on [libjxl](https://github.com/libjxl/libjxl) and the
Foreign Function and Memory API ([Project Panama](https://openjdk.org/projects/panama/)).

panamage decodes and encodes JPEG XL images, converts JPEG files losslessly to
JPEG XL and back, reads and writes EXIF and XMP metadata, and plugs into
`javax.imageio`, so existing code can use `ImageIO.read` and
`ImageIO.write(image, "jxl", ...)` without changes. The native libraries are
bundled; nothing needs to be installed.

> **Status:** 0.0.2, an early release for trying things out. The API may change.
> panamage is not yet published on Maven Central; download it from the
> [Releases](../../releases) page.

## Features

- **Decode** JPEG XL (codestream and container) to gray, gray+alpha, RGB or
  RGBA with 8-bit, 16-bit or floating point samples, upright according to the
  image orientation.
- **Encode** 8-bit, 16-bit and floating point images lossless or lossy, with a
  quality (0 to 100) or Butteraugli distance and an effort from 1 to 10.
  Lossless encoding reproduces every sample exactly, including HDR values.
- **Lossless JPEG transcoding:** repack an existing JPEG as JPEG XL, typically
  10 to 20 percent smaller, and restore the original JPEG bit for bit.
- **Metadata:** EXIF and XMP are read and written; EXIF orientation and the
  image orientation are kept consistent.
- **Color:** images that are not sRGB (for example wide-gamut images) keep
  their ICC profile, when reading and when writing.
- **Image I/O plugin** with reader, writer, write parameters (lossy/lossless,
  quality, effort) and image metadata. 16-bit and floating point images are
  read and written with full precision.
- **Protection against decompression bombs:** images beyond 256 megapixels and
  metadata boxes beyond 16 MiB are rejected before their memory is allocated;
  both limits are configurable.
- **Bundled native libraries** for Windows on x86_64, Linux on x86_64 and
  aarch64, and macOS on Apple silicon, found through a service interface on
  both the class path and the module path.
- **Tested against the reference images** of selected test cases of the
  official [JPEG XL conformance corpus](https://github.com/libjxl/conformance)
  (gray, float, alpha, orientation, animation, JPEG reconstruction).

## Requirements

- JDK 25 or newer
- Windows 10 or newer on x86_64, Linux on x86_64 with glibc 2.29 or newer
  (for example Ubuntu 20.04, Debian 11, RHEL 9 or newer), Linux on aarch64
  with glibc 2.28 or newer (for example Ubuntu 20.04, Debian 10, RHEL 8,
  Amazon Linux 2023 or newer), or macOS 11 or newer on Apple silicon (arm64).
  musl-based systems such as Alpine Linux are not supported yet.

## Getting started

Download `panamage-0.0.2-windows-x86_64.zip`,
`panamage-0.0.2-linux-x86_64.tar.gz` or `panamage-0.0.2-macos-aarch64.tar.gz`
from the [Releases](../../releases) page and unpack it. It contains the JARs, a README and a small example program:

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
| `panamage-jxl-natives-windows-x86_64`, `panamage-jxl-natives-linux-x86_64`, `panamage-jxl-natives-linux-aarch64` or `panamage-jxl-natives-macos-aarch64` | libjxl for the platform |

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
    writer.write(null, photo, param);                     // keeps EXIF and XMP
}
```

`ImageIO.read` and `ImageIO.write` do not carry metadata; use `readAll` and
`write(IIOMetadata, IIOImage, ImageWriteParam)` as above, or
`JxlImageMetadata` to set EXIF and XMP yourself.

### Lossless JPEG transcoding

```java
byte[] jpeg = Files.readAllBytes(Path.of("photo.jpg"));
byte[] jxl = JxlTranscoder.fromJpeg(jpeg);              // smaller, lossless
byte[] restored = JxlTranscoder.toJpeg(jxl);             // identical to jpeg
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

`JxlDecoder.decode(data)` and `decode(data, channels)` return 8-bit samples
(`JxlImage.Uint8`).

### Limits for untrusted input

A JPEG XL file of a few hundred bytes can declare an image of a billion
pixels. libjxl allocates the pixels in native memory, which `-Xmx` does not
bound, so panamage checks the size first and throws a `JxlLimitException`
(a `JxlException`; an `IIOException` caused by it in Image I/O):

- **Pixels:** by default at most 2^28 (256 megapixels, for example
  16384 x 16384), for the image and for every layer of the first frame (a layer
  may be larger than the image). Images with more than 4 channels, including
  extra channels, count once for every 4 channels started.
- **Metadata:** by default at most 16 MiB for each EXIF or XMP box after
  decompression.

```java
JxlLimits limits = JxlLimits.defaults().withMaxPixels(50_000_000);
JxlImage image = JxlDecoder.decode(data, 4, JxlSampleType.UINT8, limits);
((JxlImageReader) reader).setLimits(limits);                    // Image I/O
```

The defaults can be changed with the system properties
`panamage.jxl.max.pixels` and `panamage.jxl.max.metadata.bytes`;
`JxlLimits.unlimited()` turns the checks off for trusted input.
`JxlDecoder.readInfo` is not limited and reports the size without allocating
the pixels.

The limits bound what libjxl reports through its API. libjxl does not report
the size of reference frames, which only serve as a source for other frames,
so a hostile file can still make libjxl allocate more than the limit. For
fully untrusted input, decoding in a separate process with a memory limit is
the strongest protection.

## Native libraries

The native libraries are loaded on first use from the first of these sources:

1. the directory named by the system property `panamage.jxl.library.path`
   (if set, no other source is tried);
2. the platform JAR (`panamage-jxl-natives-<platform>`) on the class path or
   module path; its libraries are extracted to the directory named by
   `panamage.jxl.cache.dir`, by default `panamage-jxl` in `java.io.tmpdir`;
3. libjxl installed on the system.

The loaded library must have the libjxl version the bindings were generated
for (0.12.x). `JxlNative.librarySource()` tells where the library came from.

On Windows, libjxl needs the Microsoft Visual C++ runtime, which every JDK
ships in its `bin` directory. libjxl publishes no binaries for macOS and for
Linux on aarch64; the libraries for these platforms are built from the libjxl
sources by this project (see [natives/README.md](natives/README.md)).

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
- 7-Zip (`7z`, or `7zz` on macOS, on the `PATH`) to unpack the libjxl archive
  for Windows.

Download the external tools (libjxl for Windows and Linux, jextract, a Linux
JDK for tests in WSL, and about 45 MB of conformance test cases) and build:

```sh
python scripts/fetch_tools.py
./mvnw verify            # mvnw.cmd on Windows
```

`python scripts/fetch_tools.py --no-conformance` leaves out the conformance
test cases; the conformance tests are then skipped. `--build-only` leaves out
jextract and the Linux JDK, which only the helper scripts need. The libraries
for macOS and Linux aarch64 are checked in under `natives/`.

The tools go to `.tools` in the project. To keep them elsewhere, set the
environment variable `PANAMAGE_TOOLS_DIR` or pass `-Dtools.dir=...` to Maven
and `--tools ...` to the scripts.

Other scripts in `scripts/`:

| Script | Purpose |
|---|---|
| `jextract_jxl.py` | Regenerate the checked-in jextract bindings (`--update-symbols` also refreshes the symbol list) |
| `make_test_images.py` | Regenerate the test images |
| `wsl_verify.py` | Build and run all tests on Linux in WSL (from Windows) |
| `make_release.py` | Build the release files in `dist/<version>` (`--wsl` also tests on Linux) |
| `build_libjxl_macos.py` | Build the libjxl libraries for macOS arm64 from source (on macOS; used by the workflow below) |
| `build_libjxl_linux_aarch64.py` | Build the libjxl libraries for Linux aarch64 from source (in the manylinux_2_28 container; used by the workflow below) |
| `write_toolchains.py` | Write a Maven toolchains file for a JDK 25 |

## Continuous integration

Three GitHub Actions workflows, all started manually (Actions, Run workflow):

- **Build** builds the project and runs all tests on Linux x86_64, Linux
  aarch64, macOS arm64 and Windows x86_64, each with its own native libraries;
  the platforms can be chosen when starting it. The test reports are kept as workflow artifacts.
- **libjxl for macOS arm64** builds the libjxl libraries for macOS from source
  and checks them; its artifact is what `natives/` contains.
- **libjxl for Linux aarch64** does the same for Linux on 64-bit ARM, in the
  manylinux_2_28 container (glibc 2.28).

## Limitations

- Integer samples with more than 16 bits are decoded as floating point; 16-bit
  floating point (half precision) is not supported as a sample type.
- Only the first frame of an animation is decoded.
- Whole images are held in memory; there is no streaming or progressive API yet.
- The Image I/O writer converts images without a component color model (for
  example `TYPE_INT_RGB` or indexed images) to 8-bit sRGB.
- Native libraries for macOS on Intel (x86_64) and for musl-based Linux are
  not available yet.

## License

panamage is licensed under the [BSD-3-Clause license](LICENSE).

The native JARs contain libjxl (BSD-3-Clause), Brotli (MIT), Highway
(Apache-2.0 or BSD-3-Clause) and skcms (BSD-3-Clause). Their license texts are
included in `META-INF/licenses` of the native JARs and in the `licenses`
directory of the release archives.
