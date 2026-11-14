import BiliMusicCore
import SwiftUI
import WebKit

private struct SavedVideo: Codable, Identifiable {
    let id: String
    let title: String
}

@main
struct BiliMusicApp: App {
    var body: some Scene {
        WindowGroup { LibraryView().preferredColorScheme(.dark) }
    }
}

private struct LibraryView: View {
    @AppStorage("library") private var libraryData = Data()
    @AppStorage("recent") private var recentData = Data()
    @State private var library: [SavedVideo] = []
    @State private var recent: [String] = []
    @State private var input = ""
    @State private var title = ""
    @State private var status = ""
    @State private var playing: SavedVideo?
    @State private var playerID = UUID()
    private let core = VideoLibrary.shared
    private let accent = Color(red: 0.97, green: 0.83, blue: 0.28)

    var body: some View {
        NavigationStack {
            List {
                Section {
                    TextField("BV 号或视频链接", text: $input)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("标题（可选）", text: $title)
                    Button("添加并播放", action: add)
                    Link("在 B 站找歌", destination: URL(string: "https://search.bilibili.com/")!)
                    if !status.isEmpty { Text(status).foregroundStyle(accent) }
                    if let playing, let track = core.track(input: playing.id, title: playing.title),
                       let url = URL(string: track.uri) {
                        VideoPlayerView(url: url)
                            .id(playerID)
                            .frame(minHeight: 210)
                            .accessibilityLabel("B 站视频播放器")
                    }
                }
                Section("我的歌单") {
                    Button("随机播放", action: shuffle).disabled(library.isEmpty)
                    ForEach(library) { video in
                        Button(video.title) { play(video) }
                    }
                    .onDelete { offsets in
                        library.remove(atOffsets: offsets)
                        recent = recent.filter { id in library.contains { $0.id == id } }
                        persist()
                    }
                }
                Section("最近播放") {
                    ForEach(recent.compactMap { id in library.first { $0.id == id } }) { video in
                        Button(video.title) { play(video) }
                    }
                }
            }
            .tint(accent)
            .navigationTitle("B站音乐播放器")
            .onAppear {
                library = (try? JSONDecoder().decode([SavedVideo].self, from: libraryData)) ?? []
                recent = (try? JSONDecoder().decode([String].self, from: recentData)) ?? []
            }
        }
    }

    private func add() {
        guard let track = core.track(input: input, title: title) else {
            status = "请输入 BV 号或 B 站视频链接"
            return
        }
        let video = SavedVideo(id: track.id, title: track.title)
        if let index = library.firstIndex(where: { $0.id == video.id }) {
            library[index] = video
        } else {
            library.append(video)
        }
        input = ""
        title = ""
        play(video)
    }

    private func play(_ video: SavedVideo) {
        playing = video
        playerID = UUID()
        status = video.title
        recent = core.recent(ids: recent, id: video.id)
        persist()
    }

    private func shuffle() {
        let tracks = library.compactMap { core.track(input: $0.id, title: $0.title) }
        guard let track = core.shuffle(tracks: tracks).first,
              let video = library.first(where: { $0.id == track.id }) else { return }
        play(video)
    }

    private func persist() {
        if let data = try? JSONEncoder().encode(library) { libraryData = data }
        if let data = try? JSONEncoder().encode(recent) { recentData = data }
    }
}

private struct VideoPlayerView: UIViewRepresentable {
    let url: URL
    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.allowsInlineMediaPlayback = true
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.load(URLRequest(url: url))
        return view
    }
    func updateUIView(_ view: WKWebView, context: Context) {}
}
