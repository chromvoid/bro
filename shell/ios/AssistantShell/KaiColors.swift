import SwiftUI
import UIKit

/// Dynamic sRGB equivalents of the web palette at the native rendering boundary.
enum KaiColors {
    static let background = Color("KaiBackground")
    static let surface = Color("KaiSurface")
    static let accent = Color("KaiAccent")
    static let onAccent = Color("KaiOnAccent")
    static let destructive = Color("KaiDestructive")
    static let foreground = Color("KaiForeground")
    /// The launch screen: Bro's mark on the brand colour (Info.plist UILaunchScreen, SplashMark).
    static let splashBackground = Color("KaiSplashBackground")
    static let splashForeground = Color("KaiSplashForeground")
    static let backgroundUI = UIColor(named: "KaiBackground") ?? .systemBackground
}
