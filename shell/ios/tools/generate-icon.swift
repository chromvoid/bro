// Generate all Bro application icons from the tracked neutral alpha template.
// Run from shell/ios: swift tools/generate-icon.swift
// Outputs: iOS 1024, PWA 192/512, Apple touch 180, favicon ICO 16/32/48,
// and Android adaptive foreground 432. Square PNG/ICO canvases are opaque sRGB;
// Android keeps transparency. The operating system supplies the outer icon mask.
// Palette values come from tokens.css via web/tooling/generate_palette.py.
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

enum IconTemplate: String, CaseIterable {
    case normal = "symbol.png"
    case small = "symbol-small.png"
}

struct IconOutput {
    let path: String
    let size: Int
    let preferredScale: CGFloat
    let opaque: Bool
    let safeRadius: CGFloat?
    var template: IconTemplate = .normal
}

struct IconPalette: Decodable {
    let icon_background: String
    let icon_foreground: String
}

let repository = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent().deletingLastPathComponent()
    .deletingLastPathComponent().deletingLastPathComponent()
let masters = Dictionary(uniqueKeysWithValues: IconTemplate.allCases.map { template in
    let sourceURL = repository.appendingPathComponent("web/src/assets/bro/\(template.rawValue)")
    guard let source = CGImageSourceCreateWithURL(sourceURL as CFURL, nil),
          let master = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
        fatalError("Could not read the tracked Bro template: \(sourceURL.path)")
    }
    guard master.width == 1024, master.height == 1024 else {
        fatalError("The Bro template must have a 1024×1024 canvas: \(template.rawValue)")
    }
    return (template, master)
})

let colorSpace = CGColorSpace(name: CGColorSpace.sRGB)!
let rgbaBitmap = CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue
let paletteURL = repository.appendingPathComponent("web/src/design/palette.generated.json")
let palette = try JSONDecoder().decode(IconPalette.self, from: Data(contentsOf: paletteURL))

func paletteColor(_ value: String) -> CGColor {
    guard value.range(of: "^#[0-9A-Fa-f]{6}$", options: .regularExpression) != nil,
          let encoded = UInt32(value.dropFirst(), radix: 16) else {
        fatalError("Invalid generated icon color; run python3 web/tooling/generate_palette.py")
    }
    return CGColor(srgbRed: CGFloat((encoded >> 16) & 255) / 255,
                   green: CGFloat((encoded >> 8) & 255) / 255,
                   blue: CGFloat(encoded & 255) / 255, alpha: 1)
}

let iconBackground = paletteColor(palette.icon_background)
let iconForeground = paletteColor(palette.icon_foreground)

func transparentContext(size: Int) -> CGContext {
    guard let context = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8,
                                  bytesPerRow: size * 4, space: colorSpace,
                                  bitmapInfo: rgbaBitmap) else {
        fatalError("Could not create the transparent icon canvas")
    }
    context.clear(CGRect(x: 0, y: 0, width: size, height: size))
    return context
}

// Measure every nontransparent pixel, including the antialiased vector edge.
func visibleRadius(of image: CGImage) -> CGFloat {
    let size = image.width
    let context = transparentContext(size: size)
    context.draw(image, in: CGRect(x: 0, y: 0, width: size, height: size))
    let pixels = context.data!.assumingMemoryBound(to: UInt8.self)
    var radius: CGFloat = 0
    var hasTransparency = false
    for y in 0..<size {
        for x in 0..<size {
            let alpha = pixels[(y * size + x) * 4 + 3]
            if alpha < 255 { hasTransparency = true }
            if alpha > 0 {
                let dx = CGFloat(x) + 0.5 - CGFloat(size) / 2
                let dy = CGFloat(y) + 0.5 - CGFloat(size) / 2
                radius = max(radius, hypot(dx, dy) / CGFloat(size))
            }
        }
    }
    guard hasTransparency, radius > 0 else {
        fatalError("The Bro template must contain transparent artwork")
    }
    return radius
}

let artworkRadii = masters.mapValues { visibleRadius(of: $0) }
func render(_ output: IconOutput) -> CGImage {
    var scale = output.preferredScale
    let artworkRadius = artworkRadii[output.template]!
    let master = masters[output.template]!
    if let safeRadius = output.safeRadius {
        // Reserve two destination pixels for interpolation beyond the source edge.
        scale = min(scale, (safeRadius - 2 / CGFloat(output.size)) / artworkRadius)
    }
    let size = output.size
    let bounds = CGRect(x: 0, y: 0, width: size, height: size)
    let artworkContext = transparentContext(size: size)
    artworkContext.interpolationQuality = .high
    let artworkSize = CGFloat(size) * scale
    let inset = (CGFloat(size) - artworkSize) / 2
    artworkContext.draw(master, in: CGRect(x: inset, y: inset, width: artworkSize, height: artworkSize))
    // Tint after resizing so interpolation affects only the template's alpha,
    // not its palette RGB. This also prevents bright color halos at vector edges.
    artworkContext.setBlendMode(.sourceIn)
    artworkContext.setFillColor(iconForeground)
    artworkContext.fill(bounds)
    artworkContext.setBlendMode(.normal)
    guard let artwork = artworkContext.makeImage() else {
        fatalError("Could not render \(output.path)")
    }
    if let safeRadius = output.safeRadius {
        let renderedRadius = visibleRadius(of: artwork)
        guard renderedRadius <= safeRadius else {
            fatalError("Bro's visible silhouette exceeds the safe circle for \(output.path)")
        }
        print("Safe radius \(output.path): \(String(format: "%.4f", Double(renderedRadius))) / \(String(format: "%.4f", Double(safeRadius))), canvas scale \(String(format: "%.4f", Double(scale))), template \(output.template.rawValue)")
    }
    guard output.opaque else { return artwork }
    let bitmapInfo = CGImageAlphaInfo.noneSkipLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue
    guard let context = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8,
                                  bytesPerRow: size * 4, space: colorSpace,
                                  bitmapInfo: bitmapInfo) else {
        fatalError("Could not create the opaque icon canvas")
    }
    context.setFillColor(iconBackground)
    context.fill(bounds)
    context.draw(artwork, in: bounds)
    guard let image = context.makeImage() else { fatalError("Could not composite \(output.path)") }
    return image
}

func pngData(_ image: CGImage) -> Data {
    let data = NSMutableData()
    guard let destination = CGImageDestinationCreateWithData(data, UTType.png.identifier as CFString, 1, nil) else {
        fatalError("Could not create the PNG encoder")
    }
    CGImageDestinationAddImage(destination, image, nil)
    guard CGImageDestinationFinalize(destination) else { fatalError("Could not encode the PNG") }
    return data as Data
}

func write(_ data: Data, to path: String) throws {
    let target = repository.appendingPathComponent(path)
    try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
    try data.write(to: target)
}

// B) is wider than it is tall: at 0.84 of the canvas the mark spans about 65 % of the tile width.
let tileScale: CGFloat = 0.84
// Favicons fill the canvas: the small template keeps its own side margins.
let faviconScale: CGFloat = 1.0

let outputs = [
    IconOutput(path: "shell/ios/AssistantShell/Assets.xcassets/AppIcon.appiconset/Icon.png",
               size: 1024, preferredScale: tileScale, opaque: true, safeRadius: nil),
    IconOutput(path: "web/public/icon-192.png", size: 192, preferredScale: tileScale, opaque: true, safeRadius: 0.4),
    IconOutput(path: "web/public/icon-512.png", size: 512, preferredScale: tileScale, opaque: true, safeRadius: 0.4),
    IconOutput(path: "web/public/apple-touch-icon.png", size: 180, preferredScale: tileScale, opaque: true, safeRadius: nil),
    // Adaptive icons use a 108dp canvas; the guaranteed central circle is 66dp across.
    IconOutput(path: "mobile/androidApp/src/main/res/drawable-nodpi/ic_bro_foreground.png",
               size: 432, preferredScale: 0.7, opaque: false, safeRadius: 33.0 / 108),
]

for output in outputs {
    try write(pngData(render(output)), to: output.path)
    print("Generated \(output.path) (\(output.size)×\(output.size), \(output.opaque ? "opaque" : "transparent") sRGB, template \(output.template.rawValue))")
}

extension Data {
    mutating func appendLittleEndian<T: FixedWidthInteger>(_ value: T) {
        var encoded = value.littleEndian
        Swift.withUnsafeBytes(of: &encoded) { append(contentsOf: $0) }
    }
}

// ICO directory entries reference independent PNG images, supported by modern
// browsers and desktop icon readers; every image retains the same opaque canvas.
let faviconSizes = [16, 32, 48]
let faviconImages = faviconSizes.map { size in
    pngData(render(IconOutput(path: "web/public/favicon.ico", size: size,
                              preferredScale: faviconScale, opaque: true, safeRadius: nil,
                              template: size <= 32 ? .small : .normal)))
}
var favicon = Data()
favicon.appendLittleEndian(UInt16(0))
favicon.appendLittleEndian(UInt16(1))
favicon.appendLittleEndian(UInt16(faviconSizes.count))
var offset = UInt32(6 + faviconSizes.count * 16)
for (size, image) in zip(faviconSizes, faviconImages) {
    favicon.append(contentsOf: [UInt8(size), UInt8(size), 0, 0])
    favicon.appendLittleEndian(UInt16(1))
    favicon.appendLittleEndian(UInt16(32))
    favicon.appendLittleEndian(UInt32(image.count))
    favicon.appendLittleEndian(offset)
    offset += UInt32(image.count)
}
for image in faviconImages { favicon.append(image) }
try write(favicon, to: "web/public/favicon.ico")
print("Generated web/public/favicon.ico (16/32 from symbol-small.png, 48 from symbol.png; opaque sRGB, canvas scale \(faviconScale))")
