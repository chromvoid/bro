import SwiftUI

/// Where Bro's launch screen is: thinking until the page's first view is ready, then the dots flow
/// into ")", Bro smiles, and the screen fades away.
enum SplashPhase {
    case thinking, merging, smiled, gone
}

/**
 Bro's launch screen above the app (shell/README.md «Сплеш»): the launch image's mark in the same
 place, so the system's launch screen gives way unnoticed. ShellView moves it through its phases.
 */
struct SplashOverlay: View {
    let phase: SplashPhase
    /// The dots are seen at least this long, however fast the page is.
    static let thinking = 0.5
    static let smile = 0.25
    static let fade = 0.3

    @State private var shownAt = Date()

    var body: some View {
        ZStack {
            KaiColors.splashBackground
            ZStack {
                Image("SplashBody")
                TimelineView(.animation(paused: phase != .thinking)) { context in
                    ZStack {
                        ForEach(SplashGeometry.pieces.indices, id: \.self) { index in
                            MergePiece(index: index, progress: phase == .thinking ? 0 : 1)
                                .fill(KaiColors.splashForeground)
                                .opacity(phase == .smiled ? 0 : breath(index, at: context.date))
                        }
                    }
                }
                Image("SplashSmile").opacity(phase == .smiled ? 1 : 0)
            }
            .frame(width: 192, height: 192)
        }
        .ignoresSafeArea()
        .accessibilityHidden(true)
    }

    /// The dots breathe one after another, 200 ms apart (web/src/boot.css), while Bro thinks.
    private func breath(_ index: Int, at date: Date) -> Double {
        guard phase == .thinking else { return 1 }
        let elapsed = max(0, date.timeIntervalSince(shownAt) - Double(index) * 0.2)
        let cycle = elapsed.truncatingRemainder(dividingBy: SplashGeometry.breathSeconds * 2) / SplashGeometry.breathSeconds
        let half = cycle <= 1 ? cycle : 2 - cycle
        return 1 - 0.7 * (0.5 - 0.5 * cos(half * .pi))
    }
}

/// One dot on its way to a third of ")": the same number of points at both ends (moods.json).
private struct MergePiece: Shape {
    let index: Int
    var progress: CGFloat

    var animatableData: CGFloat {
        get { progress }
        set { progress = newValue }
    }

    func path(in rect: CGRect) -> Path {
        let piece = SplashGeometry.pieces[index]
        let scale = rect.width / 256
        var path = Path()
        for (offset, (start, end)) in zip(piece.from, piece.to).enumerated() {
            let point = CGPoint(x: rect.minX + (start.x + (end.x - start.x) * progress) * scale,
                                y: rect.minY + (start.y + (end.y - start.y) * progress) * scale)
            if offset == 0 { path.move(to: point) } else { path.addLine(to: point) }
        }
        path.closeSubpath()
        return path
    }
}
