import Foundation

/// Copies the app makes only to hand a file on, and removes once handed on (FBBD-467): a session
/// shared by AirDrop is a second full copy of its video (8.21 GB on 5 Nov), a video with data is a
/// whole rendered MP4, and a session AirDropped to the iPad lands in Documents/Inbox as a copy that
/// stays after its import. None of them is the session itself, which stays in the sessions folder.
enum LeftoverCopies {
    static let tag = "Storage"

    /// The folders in the temporary folder that hold only copies made to hand on: share (.bdsession),
    /// export (VBO, CSV, hard-linked MP4), data-video (video with data) and logs-out (the log's zip).
    static let handOnFolders = ["share", "export", "data-video", "logs-out"]

    /// Removes everything in [handOnFolders] under [root] and returns the bytes freed. Called at
    /// launch, when no share sheet or Files' Save sheet from an earlier run can still be reading them.
    @discardableResult
    static func sweep(root: URL = FileManager.default.temporaryDirectory) -> Int64 {
        let fm = FileManager.default
        var freed: Int64 = 0
        for name in handOnFolders {
            let dir = root.appendingPathComponent(name, isDirectory: true)
            guard let items = try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) else { continue }
            for item in items {
                let bytes = size(of: item)
                if (try? fm.removeItem(at: item)) != nil { freed += bytes }
            }
        }
        if freed > 0 { AppLog.i(tag, "removed \(freed) bytes of copies left from earlier shares and exports") }
        return freed
    }

    /// Removes [urls] once the share sheet or Files' Save sheet is done with them, whether they were sent,
    /// saved or not: a file outside the app's temporary folder is never touched.
    static func discard(_ urls: [URL], root: URL = FileManager.default.temporaryDirectory) {
        for url in urls where isInside(url, root) {
            let bytes = size(of: url)
            if (try? FileManager.default.removeItem(at: url)) != nil {
                AppLog.i(tag, "removed \(url.lastPathComponent) (\(bytes) bytes) once handed on")
            }
        }
    }

    /// The app's Documents/Inbox, where iOS puts a copy of a file AirDropped or opened in the app.
    static var inbox: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Inbox", isDirectory: true)
    }

    /// Removes the received file [url] once its import has ended, when it is iOS's copy in [inbox]: a
    /// file picked in Files is the person's own and stays. Its contents are in the session folder by
    /// then, or the import failed and the copy is of no use.
    static func discardReceived(_ url: URL, inbox: URL = LeftoverCopies.inbox) {
        guard isInside(url, inbox) else { return }
        let bytes = size(of: url)
        if (try? FileManager.default.removeItem(at: url)) != nil {
            AppLog.i(tag, "removed the received copy of \(url.lastPathComponent) (\(bytes) bytes) after its import")
        }
    }

    /// Whether [url] lies under [folder], links resolved (the temporary folder is under /private on a device).
    static func isInside(_ url: URL, _ folder: URL) -> Bool {
        let path = url.standardizedFileURL.resolvingSymlinksInPath().path
        let base = folder.standardizedFileURL.resolvingSymlinksInPath().path
        return path.hasPrefix(base.hasSuffix("/") ? base : base + "/")
    }

    /// The bytes of a file, or of everything in a folder.
    private static func size(of url: URL) -> Int64 {
        let keys: Set<URLResourceKey> = [.fileSizeKey, .isDirectoryKey]
        guard let values = try? url.resourceValues(forKeys: keys) else { return 0 }
        guard values.isDirectory == true else { return Int64(values.fileSize ?? 0) }
        var total: Int64 = 0
        let files = FileManager.default.enumerator(at: url, includingPropertiesForKeys: [.fileSizeKey])
        while let file = files?.nextObject() as? URL {
            total += Int64((try? file.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0)
        }
        return total
    }
}
