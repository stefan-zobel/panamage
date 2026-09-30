# panamage

JPEG XL for Java, based on [libjxl](https://github.com/libjxl/libjxl) and the
Foreign Function and Memory API ([Project Panama](https://openjdk.org/projects/panama/)).

panamage decodes and encodes JPEG XL images, converts JPEG files losslessly to
JPEG XL and back, reads and writes EXIF and XMP metadata, and plugs into
`javax.imageio`, so existing code can use `ImageIO.read` and
`ImageIO.write(image, "jxl", ...)` without changes. The native libraries are
bundled; nothing needs to be installed.

> **Status:** 0.0.1, an early release for trying things out. The API may change.
> panamage is not yet published on Maven Central; download it from the
> [Releases](../../releases) page.

## Features

- **Decode** JPEG XL (codestream and container) to 8-bit gray, gray+alpha, RGB
  or RGBA, upright according to the image orientation.
- **Encode** 8-bit images lossless or lossy, with a quality (0 to 100) or
  Butteraugli distance and an effort from 1 to 10.
- **Lossless JPEG transcoding:** repack an existing JPEG as JPEG XL, typically
  10 to 20 percent smaller, and restore the original JPEG bit for bit.
- **Metadata:** EXIF and XMP are read and written; EXIF orientation and the
  image orientation are kept consistent.
- **Color:** images that are not sRGB (for example lossless wide-gamut images)
  keep their ICC profile.
- **Image I/O plugin** with reader, writer, write parameters (lossy/lossless,
  quality, effort) and image metadata.
- **Bundled native libraries** for Windows and Linux on x86_64, found through
  a service interface on both the class path and the module path.

## Requirements

- JDK 25 or newer
- Windows 10 or newer on x86_64, or Linux on x86_64 with glibc 2.29 or newer
  (for example Ubuntu 20.04, Debian 11, RHEL 9 or newer). musl-based systems
  such as Alpine Linux are not supported yet.

## Getting started

Download `panamage-0.0.1-windows-x86_64.zip` or
`panamage-0.0.1-linux-x86_64.tar.gz` from the [Releases](../../releases) page
and unpack it. It contains the JARs, a README and a small example program:

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
| `panamage-jxl-natives-windows-x86_64` or `panamage-jxl-natives-linux-x86_64` | libjxl for the platform |

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
JxlImageInfo info = JxlDecoder.readInfo(data);            // size, channels, ICC; no pixels
JxlImage image = JxlDecoder.decode(data, info.channels()); // 8-bit samples
JxlMetadata metadata = JxlDecoder.readMetadata(data);     // EXIF and XMP

byte[] lossless = JxlEncoder.encode(image, JxlEncodeOptions.ofLossless());
byte[] lossy = JxlEncoder.encode(image, JxlEncodeOptions.ofQuality(90).withEffort(9), metadata);
```

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
ships in its `bin` directory.

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
- On Windows, 7-Zip (`7z` on the `PATH`) to unpack the libjxl archive.

Download the external tools (libjxl for Windows and Linux, jextract, a Linux
JDK for tests in WSL) and build:

```sh
python scripts/fetch_tools.py
./mvnw verify            # mvnw.cmd on Windows
```

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

## Limitations

- Only 8 bits per sample; images with more bits are reduced to 8 bits.
- Only the first frame of an animation is decoded.
- Whole images are held in memory; there is no streaming or progressive API yet.
- When writing, images that are not sRGB are converted to sRGB.
- Native libraries for macOS, Linux on aarch64 and musl-based Linux are not
  available yet.

## License

panamage is licensed under the [BSD-3-Clause license](LICENSE).

The native JARs contain libjxl (BSD-3-Clause), Brotli (MIT), Highway
(Apache-2.0 or BSD-3-Clause) and skcms (BSD-3-Clause). Their license texts are
included in `META-INF/licenses` of the native JARs and in the `licenses`
directory of the release archives.
