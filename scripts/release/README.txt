panamage $version for $platform
===============================================================================

JPEG XL for Java, based on libjxl $libjxl_version and the Foreign Function and
Memory API (Project Panama).

This is an early, experimental release for trying things out. The API may
change in later versions.


Requirements
------------
* JDK 25 or newer
* $requirements


Contents
--------
lib/panamage-jxl-$version.jar             JPEG XL decoder, encoder and lossless
                                          JPEG transcoding (API: panamage.jxl)
lib/panamage-jxl-imageio-$version.jar     Image I/O plugin: ImageIO.read and
                                          ImageIO.write(image, "jxl", ...)
lib/panamage-jxl-spi-$version.jar         Service interface for the native libraries
lib/$natives_jar     libjxl native libraries for $platform
Example.java                              Small example program
LICENSE                                   License of panamage (BSD-3-Clause)
licenses/                                 Licenses of the bundled native libraries

The native libraries are extracted automatically to a cache directory
(panamage-jxl-<user> in java.io.tmpdir, or the directory named by the system
property panamage.jxl.cache.dir). Nothing needs to be installed.


Trying it out
-------------
Run the example on any image (JPEG, PNG, JPEG XL, ...). It writes a JPEG XL
copy next to it and, for a JPEG, also a lossless JPEG XL transcoding that
restores the original JPEG bit for bit.

Class path:

  java --enable-native-access=ALL-UNNAMED -cp "lib/*" Example.java photo.jpg

Module path:

  java --enable-native-access=panamage.jxl --module-path lib --add-modules panamage.jxl Example.java photo.jpg

--enable-native-access allows panamage to call libjxl; without it, the JDK
prints a warning.


Using it in your code
---------------------
Put the four JARs on the class path or module path, then:

  BufferedImage image = ImageIO.read(new File("input.jxl"));
  ImageIO.write(image, "jxl", new File("output.jxl"));          // visually lossless

  byte[] jxl = JxlTranscoder.fromJpeg(jpegBytes);                // lossless, smaller
  byte[] jpeg = JxlTranscoder.toJpeg(jxl);                       // the original JPEG

  JxlImage.Uint8 pixels = JxlDecoder.decode(jxlBytes);           // 8-bit RGBA
  byte[] encoded = JxlEncoder.encode(pixels, JxlEncodeOptions.ofQuality(90));

16-bit and floating point samples are decoded with
JxlDecoder.decode(data, channels, JxlSampleType.UINT16 or FLOAT32) as
JxlImage.Uint16 or JxlImage.Float32, and encoded with the same precision.
Every JxlDecoder method also reads from a java.nio.file.Path or a
MemorySegment instead of a byte array. JxlDecodeOptions.withSrgb(true)
converts the pixels to sRGB; the Image I/O reader does so by default
(JxlImageReader.setConvertToSrgb(false) keeps the color space).

Of an animation, JxlDecoder.decode returns the first frame;
JxlDecoder.decodeFrames returns all frames with their durations, and
JxlFrameDecoder decodes one frame at a time. In Image I/O, every frame is an
image: getNumImages(true) counts the frames and read(i) returns frame i.
Animations are written with JxlFrameEncoder, one frame at a time, with
JxlEncoder.encodeAnimation, or in Image I/O as a sequence
(prepareWriteSequence, writeToSequence, endWriteSequence).

EXIF and XMP metadata are kept by JxlTranscoder and when an image is copied
with ImageReader.readAll and ImageWriter.write (IIOImage with metadata); the
convenience methods ImageIO.read and ImageIO.write do not carry metadata. See
the Javadoc of JxlImageMetadata, JxlImageWriter and JxlImageWriteParam.


Untrusted input
---------------
A small JPEG XL file can declare a huge image. panamage therefore rejects
images beyond 256 megapixels, EXIF or XMP boxes beyond 16 MiB and
reconstructed JPEG files beyond 1 GiB with a JxlLimitException. Other limits
can be passed in JxlDecodeOptions (withLimits) to JxlDecoder, JxlFrameDecoder
and JxlTranscoder.toJpeg, to JxlImageReader.setLimits, or set with the
system properties
panamage.jxl.max.pixels, panamage.jxl.max.metadata.bytes and
panamage.jxl.max.jpeg.bytes. For fully untrusted input, decoding in a
separate process with a memory limit is the strongest protection.


Checking the download
---------------------
The release page lists SHA-256 checksums in the file SHA256SUMS:

  Linux:    sha256sum -c SHA256SUMS --ignore-missing
  macOS:    shasum -a 256 -c SHA256SUMS --ignore-missing
  Windows:  Get-FileHash <file> -Algorithm SHA256


License
-------
panamage is licensed under the BSD-3-Clause license (see LICENSE). The bundled
native libraries are libjxl (BSD-3-Clause), Brotli (MIT), Highway (Apache-2.0
or BSD-3-Clause) and skcms (BSD-3-Clause); their license texts are in the
licenses directory.
